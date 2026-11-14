/*
# Credit policy: starting values, editable only by the system administrator

Plain English: puts the pilot values (fees, interest, penalties, lock delay, score thresholds) in the credit_policy table instead of the code, and gives the system administrator, and only that role, a controlled way to change them. Every change is justified, validated, and kept in a history.

1. Starting values (only if the policy is still untouched)
- Processing fee 3% (0.03), interest 6% per 30 days (0.06), penalty 0.5% per day (0.005), grace 3 days, lock after 7 days overdue
- Auto-approve score 80, manual review from score 50
- Rates are fractions (0.06 = 6%); down payment limits stay in percent (20 = 20%)

2. Who can change it
- New permission policy.manage, granted to system_admin only
- update_credit_policy(admin, changes, reason): checks the permission, accepts only known settings, requires a reason, and lets the table checks reject nonsense
- Direct UPDATE on credit_policy is removed for service_role, so this function is the only way in

3. Safety nets
- Sanity ceilings against typos: fee <= 50%, interest per 30 days <= 100%, penalty per day <= 10%
- credit_policy_history: append-only record of who changed what, when, why (before and after)
- Existing contracts are not affected: each contract keeps a frozen copy of its terms. Changes apply to new applications only.
*/

-- 0. Audit trigger: tolerate tables whose id is not a uuid (credit_policy.id is boolean)
CREATE OR REPLACE FUNCTION public.audit_row_change()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  hdrs json;
  actor uuid;
  actor_r public.app_role;
  ip inet;
  fp text;
  rec_txt text;
  rec_id uuid;
BEGIN
  BEGIN
    hdrs := nullif(current_setting('request.headers', true), '')::json;
  EXCEPTION WHEN others THEN
    hdrs := NULL;
  END;

  BEGIN
    ip := nullif(coalesce(
      nullif(current_setting('app.client_ip', true), ''),
      split_part(coalesce(hdrs ->> 'x-forwarded-for', ''), ',', 1)
    ), '')::inet;
  EXCEPTION WHEN others THEN
    ip := NULL;
  END;

  fp := coalesce(nullif(current_setting('app.device_fingerprint', true), ''), hdrs ->> 'x-device-fingerprint');
  actor := public.current_actor_id();
  SELECT u.role INTO actor_r FROM public.app_user AS u WHERE u.id = actor;

  rec_txt := (CASE WHEN TG_OP = 'DELETE' THEN to_jsonb(OLD) ELSE to_jsonb(NEW) END ->> 'id');
  IF rec_txt ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' THEN
    rec_id := rec_txt::uuid;
  ELSE
    rec_id := NULL; -- non-uuid key (e.g. singleton credit_policy); full row is in state_before/after
  END IF;

  INSERT INTO public.audit_log (actor_id, actor_role, ip_address, device_fingerprint, action, table_name, record_id, state_before, state_after)
  VALUES (
    actor, actor_r, ip, left(fp, 128), TG_OP, TG_TABLE_NAME, rec_id,
    CASE WHEN TG_OP IN ('UPDATE', 'DELETE') THEN to_jsonb(OLD) END,
    CASE WHEN TG_OP IN ('INSERT', 'UPDATE') THEN to_jsonb(NEW) END
  );
  RETURN NULL;
END;
$$;

-- 1. Starting values (only if still at the initial zero values)
UPDATE credit_policy
SET processing_fee_rate = 0.03,
    interest_rate_per_30_days = 0.06,
    penalty_rate_per_day = 0.005,
    grace_days = 3,
    lock_after_days_overdue = 7,
    auto_approve_min_score = 80,
    review_min_score = 50
WHERE id = true
  AND processing_fee_rate = 0
  AND interest_rate_per_30_days = 0
  AND penalty_rate_per_day = 0;

-- 2. Permission
INSERT INTO role_permission (role, permission, agency_scoped)
VALUES ('system_admin', 'policy.manage', false)
ON CONFLICT (role, permission) DO NOTHING;

-- 3. Sanity ceilings
DO $$ BEGIN
  ALTER TABLE credit_policy ADD CONSTRAINT credit_policy_fee_ceiling CHECK (processing_fee_rate <= 0.5);
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
  ALTER TABLE credit_policy ADD CONSTRAINT credit_policy_interest_ceiling CHECK (interest_rate_per_30_days <= 1);
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
  ALTER TABLE credit_policy ADD CONSTRAINT credit_policy_penalty_ceiling CHECK (penalty_rate_per_day <= 0.1);
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

-- 4. History
CREATE TABLE IF NOT EXISTS credit_policy_history (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  changed_by uuid NOT NULL REFERENCES app_user (id),
  reason text NOT NULL,
  state_before jsonb NOT NULL,
  state_after jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);

DROP TRIGGER IF EXISTS credit_policy_history_block_change_trg ON credit_policy_history;
CREATE TRIGGER credit_policy_history_block_change_trg BEFORE UPDATE OR DELETE ON credit_policy_history
  FOR EACH ROW EXECUTE FUNCTION public.block_row_or_truncate();

DROP TRIGGER IF EXISTS credit_policy_history_block_truncate_trg ON credit_policy_history;
CREATE TRIGGER credit_policy_history_block_truncate_trg BEFORE TRUNCATE ON credit_policy_history
  FOR EACH STATEMENT EXECUTE FUNCTION public.block_row_or_truncate();

ALTER TABLE credit_policy_history ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS credit_policy_history_select ON credit_policy_history;
CREATE POLICY credit_policy_history_select ON credit_policy_history FOR SELECT TO authenticated
  USING (public.abac_allows('audit.read'));

-- 5. The only way to change the policy
CREATE OR REPLACE FUNCTION public.update_credit_policy(
  p_admin uuid,
  p_changes jsonb,
  p_reason text
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  editable text[] := ARRAY[
    'down_payment_min_pct', 'down_payment_max_pct', 'allowed_durations',
    'processing_fee_rate', 'interest_rate_per_30_days', 'penalty_rate_per_day',
    'grace_days', 'lock_after_days_overdue',
    'auto_approve_min_score', 'review_min_score',
    'face_match_min', 'ocr_confidence_min',
    'burden_good', 'burden_ok', 'burden_max'
  ];
  bad text;
  v_old public.credit_policy%ROWTYPE;
  v_new public.credit_policy%ROWTYPE;
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM public.app_user AS u
    JOIN public.role_permission AS rp ON rp.role = u.role
    WHERE u.id = p_admin
      AND u.status = 'active'
      AND (u.locked_until IS NULL OR u.locked_until < now())
      AND rp.permission = 'policy.manage'
  ) THEN
    RAISE EXCEPTION 'only the system administrator can change the credit policy' USING ERRCODE = '42501';
  END IF;

  IF p_changes IS NULL OR jsonb_typeof(p_changes) <> 'object' OR p_changes = '{}'::jsonb THEN
    RAISE EXCEPTION 'no changes provided';
  END IF;

  IF coalesce(btrim(p_reason), '') = '' THEN
    RAISE EXCEPTION 'a reason is required';
  END IF;

  SELECT k INTO bad
    FROM jsonb_object_keys(p_changes) AS k
    WHERE k <> ALL (editable)
    LIMIT 1;
  IF bad IS NOT NULL THEN
    RAISE EXCEPTION 'unknown or read-only setting: %', bad;
  END IF;

  SELECT * INTO v_old FROM public.credit_policy FOR UPDATE;
  v_new := jsonb_populate_record(v_old, p_changes);

  UPDATE public.credit_policy SET
    down_payment_min_pct = v_new.down_payment_min_pct,
    down_payment_max_pct = v_new.down_payment_max_pct,
    allowed_durations = v_new.allowed_durations,
    processing_fee_rate = v_new.processing_fee_rate,
    interest_rate_per_30_days = v_new.interest_rate_per_30_days,
    penalty_rate_per_day = v_new.penalty_rate_per_day,
    grace_days = v_new.grace_days,
    lock_after_days_overdue = v_new.lock_after_days_overdue,
    auto_approve_min_score = v_new.auto_approve_min_score,
    review_min_score = v_new.review_min_score,
    face_match_min = v_new.face_match_min,
    ocr_confidence_min = v_new.ocr_confidence_min,
    burden_good = v_new.burden_good,
    burden_ok = v_new.burden_ok,
    burden_max = v_new.burden_max
  WHERE id = true;

  SELECT * INTO v_new FROM public.credit_policy;

  INSERT INTO public.credit_policy_history (changed_by, reason, state_before, state_after)
  VALUES (p_admin, btrim(p_reason), to_jsonb(v_old), to_jsonb(v_new));

  RETURN to_jsonb(v_new);
END;
$$;

-- 6. Privileges
REVOKE ALL ON credit_policy_history FROM anon, authenticated;
GRANT SELECT ON credit_policy_history TO authenticated;
GRANT SELECT, INSERT ON credit_policy_history TO service_role;

REVOKE INSERT, UPDATE ON credit_policy FROM service_role;
GRANT SELECT ON credit_policy TO service_role;

REVOKE EXECUTE ON FUNCTION public.update_credit_policy(uuid, jsonb, text) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.update_credit_policy(uuid, jsonb, text) TO service_role;
