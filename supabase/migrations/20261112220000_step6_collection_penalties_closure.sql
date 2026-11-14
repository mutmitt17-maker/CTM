/*
# Step 6: automated collection, penalties, escalation, payment reconciliation, closure

Plain English: the post-sale cycle. Reminders, penalties, the escalation tiers, how a payment is split, manual Mobile Money entry for the MVP and webhooks for later, restructuring with two people, and the final closure with the ownership certificate.

1. Reminders (WhatsApp first, SMS as fallback)
- notification_outbox: queue with idempotency keys and retries. If WhatsApp fails 3 times, an SMS copy is queued.
- schedule_payment_reminders(): one message per contract per day: D-3 (not for daily-installment contracts, to avoid spam), due day, and each day of the grace period (with days left before lock).
- merchant_account: the merchant numbers shown in messages (M-Pesa, Airtel Money, Orange Money). Empty until the administrator fills it.

2. Penalties
- penalty: one row per late installment; accrues daily after the grace period at credit_policy.penalty_rate_per_day, capped by the new credit_policy.penalty_cap_rate (share of the installment, default 100%, editable by the administrator).
- Ledger: DEBIT receivable and CREDIT PENALTY_INCOME when accrued; reversed when waived.

3. Allocation of every payment (both phases)
- Order: penalties, then interest and fees of due installments, then principal of due installments, then the next installments (interest and fees before principal).
- repayment_schedule now splits each installment into interest_fee and principal (existing rows are backfilled in proportion).
- payment_allocation keeps the detail of where each cent went.
- contract_outstanding() now includes penalties: a contract closes only at 0.00 USD.

4. Escalation
- Tier 1 (warning, #F39C12): after the grace period. Persistent warning screen, and a collection_task is opened for the field agent who created the file.
- Tier 2 (locked, #C0392B): from lock_after_days_overdue. The command carries the provider chosen by brand: Samsung to Knox Guard, Apple to Apple Business Manager, Tecno / Infinix / Itel and others to Android Enterprise (brand_lock_provider, editable by the administrator).
- Colors: #27AE60 up to date, #2980B9 closed.
- Tier 3: penalty waiver or restructuring requested by a supervisor or risk analyst and decided by a different approver (approval_request). Restructuring closes the old contract version and opens a new one with a new schedule; the device follows the new contract without being released.

5. Manual Mobile Money (phase 1) and webhooks (phase 2)
- record_manual_mobile_money_payment(): Finance or supervisor (permission payment.record), operator reference, USD amount, contract number. References are unique per operator (case and spaces ignored). Same engine as the webhook, so unlock and ledger happen immediately.
- process_mobile_money_payment() is the webhook entry point (signature checks happen in the Edge Function before calling it).

6. Closure
- When the balance is 0.00 USD: contract closed, loan completed, device released (de-enrolment command), and two documents prepared (paid-in-full receipt and ownership certificate) in closure_document with a WhatsApp notification. The PDF is rendered by a worker, which then calls attach_closure_document().

7. Scheduling
- run_daily_collection_cycle() (penalties, overdue marking, device evaluation) and run_reminder_cycle() are scheduled with pg_cron when the extension is available (00:30 and 07:00 UTC, that is 02:30 and 09:00 in Lubumbashi). Otherwise call them from any scheduler.
- evaluate_all_devices() hourly.
- device_unlock_sla: seconds between an unlock command being queued and acknowledged, to watch the 60-second target.

Not in the database
- Cloudflare (DNS, WAF, Pages), the Edge Functions that read the queues and call WhatsApp, SMS, Knox, ABM and the MDM, PDF rendering, webhook signature checks, and the back-office screens.
*/


-- 0. Permissions
INSERT INTO role_permission (role, permission, agency_scoped) VALUES
  ('finance', 'payment.record', false),
  ('agency_supervisor', 'payment.record', true),
  ('agency_supervisor', 'penalty.waive.request', true),
  ('risk_analyst', 'penalty.waive.request', false),
  ('risk_analyst', 'loan.restructure.request', false)
ON CONFLICT (role, permission) DO NOTHING;

-- 1. Existing tables
ALTER TABLE credit_policy ADD COLUMN IF NOT EXISTS penalty_cap_rate numeric(5,4) NOT NULL DEFAULT 1.0000
  CHECK (penalty_cap_rate >= 0 AND penalty_cap_rate <= 1);

ALTER TABLE approval_request ADD COLUMN IF NOT EXISTS decision_reason text;

ALTER TABLE managed_device ADD COLUMN IF NOT EXISTS lock_provider varchar(30) NOT NULL DEFAULT 'android_enterprise'
  CHECK (lock_provider IN ('knox_guard', 'apple_abm', 'android_enterprise'));

ALTER TABLE repayment_schedule ADD COLUMN IF NOT EXISTS interest_fee_due numeric(14,2) NOT NULL DEFAULT 0 CHECK (interest_fee_due >= 0);
ALTER TABLE repayment_schedule ADD COLUMN IF NOT EXISTS principal_due numeric(14,2) NOT NULL DEFAULT 0 CHECK (principal_due >= 0);
ALTER TABLE repayment_schedule ADD COLUMN IF NOT EXISTS interest_fee_paid numeric(14,2) NOT NULL DEFAULT 0 CHECK (interest_fee_paid >= 0);
ALTER TABLE repayment_schedule ADD COLUMN IF NOT EXISTS principal_paid numeric(14,2) NOT NULL DEFAULT 0 CHECK (principal_paid >= 0);

COMMENT ON COLUMN repayment_schedule.interest_fee_due IS 'Amount in USD (system currency)';
COMMENT ON COLUMN repayment_schedule.principal_due IS 'Amount in USD (system currency)';

-- Backfill the split of existing installments (before the guard below freezes these columns)
UPDATE repayment_schedule AS rs
SET interest_fee_due = round(rs.amount_due * x.ratio, 2),
    principal_due = rs.amount_due - round(rs.amount_due * x.ratio, 2),
    interest_fee_paid = least(rs.amount_paid, round(rs.amount_due * x.ratio, 2)),
    principal_paid = rs.amount_paid - least(rs.amount_paid, round(rs.amount_due * x.ratio, 2))
FROM (
  SELECT lc.id AS contract_id,
         CASE WHEN lc.total_payable > 0 THEN (la.processing_fee + la.interest_amount) / lc.total_payable ELSE 0 END AS ratio
  FROM loan_contract AS lc
  JOIN loan_application AS la ON la.id = lc.loan_application_id
) AS x
WHERE x.contract_id = rs.contract_id
  AND rs.interest_fee_due + rs.principal_due = 0
  AND rs.amount_due > 0;

DO $$ BEGIN
  ALTER TABLE repayment_schedule ADD CONSTRAINT repayment_schedule_components_add_up
    CHECK (interest_fee_due + principal_due = amount_due);
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
  ALTER TABLE repayment_schedule ADD CONSTRAINT repayment_schedule_paid_within_due
    CHECK (interest_fee_paid <= interest_fee_due AND principal_paid <= principal_due);
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

CREATE OR REPLACE FUNCTION public.repayment_schedule_guard_update()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = ''
AS $$
BEGIN
  IF NEW.contract_id IS DISTINCT FROM OLD.contract_id
     OR NEW.installment_number IS DISTINCT FROM OLD.installment_number
     OR NEW.due_date IS DISTINCT FROM OLD.due_date
     OR NEW.amount_due IS DISTINCT FROM OLD.amount_due
     OR NEW.interest_fee_due IS DISTINCT FROM OLD.interest_fee_due
     OR NEW.principal_due IS DISTINCT FROM OLD.principal_due
     OR NEW.created_at IS DISTINCT FROM OLD.created_at
  THEN
    RAISE EXCEPTION 'repayment_schedule terms are immutable: create a new contract version with a new schedule instead';
  END IF;
  RETURN NEW;
END;
$$;

-- 2. New tables
CREATE SEQUENCE IF NOT EXISTS closure_document_number_seq;

CREATE TABLE IF NOT EXISTS merchant_account (
  provider varchar(20) PRIMARY KEY CHECK (provider IN ('mpesa', 'airtel_money', 'orange_money')),
  merchant_number varchar(30) NOT NULL,
  display_name varchar(100) NOT NULL,
  active boolean NOT NULL DEFAULT true,
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS brand_lock_provider (
  brand varchar(50) PRIMARY KEY,
  provider varchar(30) NOT NULL CHECK (provider IN ('knox_guard', 'apple_abm', 'android_enterprise'))
);

INSERT INTO brand_lock_provider (brand, provider) VALUES
  ('samsung', 'knox_guard'),
  ('apple', 'apple_abm'),
  ('tecno', 'android_enterprise'),
  ('infinix', 'android_enterprise'),
  ('itel', 'android_enterprise')
ON CONFLICT (brand) DO NOTHING;

CREATE TABLE IF NOT EXISTS notification_outbox (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  customer_id uuid NOT NULL REFERENCES customer (id),
  contract_id uuid REFERENCES loan_contract (id),
  recipient_phone varchar(20) NOT NULL,
  template_code varchar(40) NOT NULL,
  channel varchar(10) NOT NULL CHECK (channel IN ('whatsapp', 'sms')),
  payload jsonb NOT NULL DEFAULT '{}'::jsonb,
  idempotency_key varchar(150) NOT NULL UNIQUE,
  status varchar(10) NOT NULL DEFAULT 'queued' CHECK (status IN ('queued', 'sending', 'sent', 'failed')),
  attempts integer NOT NULL DEFAULT 0,
  next_attempt_at timestamptz NOT NULL DEFAULT now(),
  last_error text,
  provider_ref varchar(100),
  fallback_for uuid REFERENCES notification_outbox (id),
  created_at timestamptz NOT NULL DEFAULT now(),
  sent_at timestamptz
);

CREATE INDEX IF NOT EXISTS notification_outbox_queue_idx ON notification_outbox (status, next_attempt_at);

CREATE TABLE IF NOT EXISTS penalty (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  contract_id uuid NOT NULL REFERENCES loan_contract (id),
  installment_id uuid NOT NULL UNIQUE REFERENCES repayment_schedule (id),
  accrued_amount numeric(14,2) NOT NULL DEFAULT 0 CHECK (accrued_amount >= 0),
  paid_amount numeric(14,2) NOT NULL DEFAULT 0 CHECK (paid_amount >= 0),
  waived_amount numeric(14,2) NOT NULL DEFAULT 0 CHECK (waived_amount >= 0),
  last_accrued_on date NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT penalty_settled_within_accrued CHECK (paid_amount + waived_amount <= accrued_amount)
);

COMMENT ON COLUMN penalty.accrued_amount IS 'Amount in USD (system currency)';
CREATE INDEX IF NOT EXISTS penalty_contract_idx ON penalty (contract_id);

CREATE TABLE IF NOT EXISTS payment_allocation (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  payment_id uuid NOT NULL REFERENCES payment (id),
  installment_id uuid REFERENCES repayment_schedule (id),
  component varchar(15) NOT NULL CHECK (component IN ('penalty', 'interest_fee', 'principal')),
  amount numeric(14,2) NOT NULL CHECK (amount > 0),
  created_at timestamptz NOT NULL DEFAULT now()
);

COMMENT ON COLUMN payment_allocation.amount IS 'Amount in USD (system currency)';
CREATE INDEX IF NOT EXISTS payment_allocation_payment_idx ON payment_allocation (payment_id);

CREATE TABLE IF NOT EXISTS collection_task (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  contract_id uuid NOT NULL REFERENCES loan_contract (id),
  customer_id uuid NOT NULL REFERENCES customer (id),
  assigned_to uuid REFERENCES app_user (id),
  task_type varchar(15) NOT NULL DEFAULT 'followup' CHECK (task_type IN ('followup', 'field_visit')),
  priority varchar(10) NOT NULL DEFAULT 'normal' CHECK (priority IN ('normal', 'high')),
  reason text NOT NULL,
  status varchar(12) NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'in_progress', 'done', 'cancelled')),
  due_date date NOT NULL DEFAULT current_date,
  outcome text,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  closed_at timestamptz
);

CREATE UNIQUE INDEX IF NOT EXISTS collection_task_one_open_per_contract_idx
  ON collection_task (contract_id) WHERE status IN ('open', 'in_progress');

CREATE TABLE IF NOT EXISTS closure_document (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  contract_id uuid NOT NULL REFERENCES loan_contract (id),
  doc_type varchar(25) NOT NULL CHECK (doc_type IN ('paid_in_full_receipt', 'ownership_certificate')),
  document_number varchar(30) NOT NULL UNIQUE,
  payload jsonb NOT NULL,
  document_path text,
  document_hash varchar(128),
  status varchar(15) NOT NULL DEFAULT 'pending_render' CHECK (status IN ('pending_render', 'ready')),
  created_at timestamptz NOT NULL DEFAULT now(),
  rendered_at timestamptz,
  CONSTRAINT closure_document_unique UNIQUE (contract_id, doc_type)
);

CREATE OR REPLACE FUNCTION public.closure_document_guard_update()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = ''
AS $$
BEGIN
  IF NEW.contract_id IS DISTINCT FROM OLD.contract_id
     OR NEW.doc_type IS DISTINCT FROM OLD.doc_type
     OR NEW.document_number IS DISTINCT FROM OLD.document_number
     OR NEW.payload IS DISTINCT FROM OLD.payload
     OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
    RAISE EXCEPTION 'closure documents are immutable';
  END IF;
  IF OLD.document_path IS NOT NULL AND NEW.document_path IS DISTINCT FROM OLD.document_path THEN
    RAISE EXCEPTION 'the rendered document is already attached';
  END IF;
  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS closure_document_guard_update_trg ON closure_document;
CREATE TRIGGER closure_document_guard_update_trg BEFORE UPDATE ON closure_document
  FOR EACH ROW EXECUTE FUNCTION public.closure_document_guard_update();

DROP TRIGGER IF EXISTS closure_document_block_delete_trg ON closure_document;
CREATE TRIGGER closure_document_block_delete_trg BEFORE DELETE ON closure_document
  FOR EACH ROW EXECUTE FUNCTION public.block_row_or_truncate();

DROP TRIGGER IF EXISTS payment_allocation_block_change_trg ON payment_allocation;
CREATE TRIGGER payment_allocation_block_change_trg BEFORE UPDATE OR DELETE ON payment_allocation
  FOR EACH ROW EXECUTE FUNCTION public.block_row_or_truncate();

DROP TRIGGER IF EXISTS payment_allocation_block_truncate_trg ON payment_allocation;
CREATE TRIGGER payment_allocation_block_truncate_trg BEFORE TRUNCATE ON payment_allocation
  FOR EACH STATEMENT EXECUTE FUNCTION public.block_row_or_truncate();

DROP TRIGGER IF EXISTS penalty_block_delete_trg ON penalty;
CREATE TRIGGER penalty_block_delete_trg BEFORE DELETE ON penalty
  FOR EACH ROW EXECUTE FUNCTION public.block_row_or_truncate();

DROP TRIGGER IF EXISTS penalty_set_updated_at ON penalty;
CREATE TRIGGER penalty_set_updated_at BEFORE UPDATE ON penalty
  FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

DROP TRIGGER IF EXISTS collection_task_set_updated_at ON collection_task;
CREATE TRIGGER collection_task_set_updated_at BEFORE UPDATE ON collection_task
  FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

DROP TRIGGER IF EXISTS penalty_audit_trg ON penalty;
CREATE TRIGGER penalty_audit_trg AFTER INSERT OR UPDATE OR DELETE ON penalty
  FOR EACH ROW EXECUTE FUNCTION public.audit_row_change();

DROP TRIGGER IF EXISTS collection_task_audit_trg ON collection_task;
CREATE TRIGGER collection_task_audit_trg AFTER INSERT OR UPDATE OR DELETE ON collection_task
  FOR EACH ROW EXECUTE FUNCTION public.audit_row_change();

DROP TRIGGER IF EXISTS merchant_account_audit_trg ON merchant_account;
CREATE TRIGGER merchant_account_audit_trg AFTER INSERT OR UPDATE OR DELETE ON merchant_account
  FOR EACH ROW EXECUTE FUNCTION public.audit_row_change();

-- 3. Small helpers
CREATE OR REPLACE FUNCTION public.device_status_color(p_state text)
RETURNS text
LANGUAGE sql
IMMUTABLE
SET search_path = ''
AS $$
  SELECT CASE p_state
    WHEN 'unlocked' THEN '#27AE60'
    WHEN 'warning' THEN '#F39C12'
    WHEN 'locked' THEN '#C0392B'
    WHEN 'released' THEN '#2980B9'
    ELSE NULL END;
$$;

CREATE OR REPLACE FUNCTION public.lock_provider_for_brand(p_brand text)
RETURNS text
LANGUAGE sql
STABLE
SET search_path = ''
AS $$
  SELECT coalesce(
    (SELECT b.provider FROM public.brand_lock_provider AS b WHERE b.brand = lower(btrim(p_brand))),
    'android_enterprise');
$$;

UPDATE managed_device AS md
SET lock_provider = public.lock_provider_for_brand(it.brand),
    mdm_provider = public.lock_provider_for_brand(it.brand)
FROM loan_application AS la
JOIN inventory_item AS it ON it.id = la.inventory_item_id
WHERE la.id = md.application_id;

CREATE OR REPLACE FUNCTION public.contract_outstanding(p_contract uuid)
RETURNS numeric
LANGUAGE sql
STABLE
SET search_path = ''
AS $$
  SELECT
    coalesce((SELECT sum(rs.amount_due - rs.amount_paid) FROM public.repayment_schedule AS rs WHERE rs.contract_id = p_contract), 0)
    + coalesce((SELECT sum(pn.accrued_amount - pn.paid_amount - pn.waived_amount) FROM public.penalty AS pn WHERE pn.contract_id = p_contract), 0);
$$;

CREATE OR REPLACE FUNCTION public.penalty_outstanding(p_contract uuid)
RETURNS numeric
LANGUAGE sql
STABLE
SET search_path = ''
AS $$
  SELECT coalesce(sum(pn.accrued_amount - pn.paid_amount - pn.waived_amount), 0)
  FROM public.penalty AS pn WHERE pn.contract_id = p_contract;
$$;

CREATE OR REPLACE FUNCTION public.enqueue_notification(
  p_customer uuid, p_contract uuid, p_template text, p_channel text, p_payload jsonb, p_key text
)
RETURNS uuid
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  v_phone text;
  v_id uuid;
BEGIN
  SELECT c.phone_number INTO v_phone FROM public.customer AS c WHERE c.id = p_customer;
  IF v_phone IS NULL THEN
    RETURN NULL;
  END IF;
  INSERT INTO public.notification_outbox (customer_id, contract_id, recipient_phone, template_code, channel, payload, idempotency_key)
  VALUES (p_customer, p_contract, v_phone, p_template, p_channel, coalesce(p_payload, '{}'::jsonb), p_key)
  ON CONFLICT (idempotency_key) DO NOTHING
  RETURNING id INTO v_id;
  RETURN v_id;
END;
$$;

CREATE OR REPLACE FUNCTION public.evaluate_contract_devices(
  p_contract uuid, p_trigger text, p_event text DEFAULT NULL, p_details jsonb DEFAULT '{}'::jsonb
)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  v_dev uuid;
BEGIN
  FOR v_dev IN SELECT md.id FROM public.managed_device AS md WHERE md.contract_id = p_contract
  LOOP
    IF p_event IS NOT NULL THEN
      PERFORM public.emit_device_event(v_dev, p_event, p_details, NULL);
    END IF;
    PERFORM public.evaluate_device_policy(v_dev, p_trigger);
  END LOOP;
END;
$$;

-- 4. Collection tasks
CREATE OR REPLACE FUNCTION public.ensure_collection_task(p_contract uuid, p_reason text)
RETURNS uuid
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  v_customer uuid;
  v_agent uuid;
  v_id uuid;
BEGIN
  SELECT la.customer_id, la.created_by INTO v_customer, v_agent
    FROM public.loan_contract AS lc
    JOIN public.loan_application AS la ON la.id = lc.loan_application_id
    WHERE lc.id = p_contract;
  IF v_customer IS NULL THEN
    RETURN NULL;
  END IF;

  UPDATE public.collection_task
    SET reason = p_reason,
        priority = CASE WHEN p_reason = 'payment_overdue' THEN 'high' ELSE priority END
    WHERE contract_id = p_contract AND status IN ('open', 'in_progress')
    RETURNING id INTO v_id;
  IF v_id IS NOT NULL THEN
    RETURN v_id;
  END IF;

  INSERT INTO public.collection_task (contract_id, customer_id, assigned_to, reason, priority)
  VALUES (p_contract, v_customer, v_agent, p_reason, CASE WHEN p_reason = 'payment_overdue' THEN 'high' ELSE 'normal' END)
  RETURNING id INTO v_id;
  RETURN v_id;
END;
$$;

CREATE OR REPLACE FUNCTION public.close_collection_tasks(p_contract uuid, p_outcome text)
RETURNS integer
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  v_count integer;
BEGIN
  UPDATE public.collection_task
    SET status = 'done', outcome = p_outcome, closed_at = now()
    WHERE contract_id = p_contract AND status IN ('open', 'in_progress');
  GET DIAGNOSTICS v_count = ROW_COUNT;
  RETURN v_count;
END;
$$;

CREATE OR REPLACE FUNCTION public.assign_collection_task(p_actor uuid, p_task uuid, p_assignee uuid, p_task_type text)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
BEGIN
  PERFORM set_config('app.actor_id', p_actor::text, true);
  IF NOT public.abac_allows('collection.assign_visit') THEN
    RAISE EXCEPTION 'not allowed to assign collection tasks' USING ERRCODE = '42501';
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM public.app_user AS u
    WHERE u.id = p_assignee AND u.status = 'active' AND u.role IN ('field_agent', 'collections')
  ) THEN
    RAISE EXCEPTION 'the assignee must be an active field agent or collections user';
  END IF;
  UPDATE public.collection_task
    SET assigned_to = p_assignee, task_type = coalesce(p_task_type, task_type), status = 'in_progress'
    WHERE id = p_task AND status IN ('open', 'in_progress');
  IF NOT FOUND THEN
    RAISE EXCEPTION 'no open task with this id';
  END IF;
END;
$$;

CREATE OR REPLACE FUNCTION public.complete_collection_task(p_actor uuid, p_task uuid, p_outcome text)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  t public.collection_task%ROWTYPE;
BEGIN
  PERFORM set_config('app.actor_id', p_actor::text, true);
  IF coalesce(btrim(p_outcome), '') = '' THEN
    RAISE EXCEPTION 'an outcome is required';
  END IF;
  SELECT * INTO t FROM public.collection_task WHERE id = p_task FOR UPDATE;
  IF NOT FOUND OR t.status NOT IN ('open', 'in_progress') THEN
    RAISE EXCEPTION 'no open task with this id';
  END IF;
  IF t.assigned_to IS DISTINCT FROM p_actor AND NOT public.abac_allows('collection.schedule') THEN
    RAISE EXCEPTION 'not allowed to close this task' USING ERRCODE = '42501';
  END IF;
  UPDATE public.collection_task
    SET status = 'done', outcome = btrim(p_outcome), closed_at = now()
    WHERE id = t.id;
END;
$$;

-- 5. Reminders and notification worker API
CREATE OR REPLACE FUNCTION public.schedule_payment_reminders()
RETURNS integer
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  cp public.credit_policy%ROWTYPE;
  r record;
  v_merchants jsonb;
  v_delta integer;
  v_template text;
  v_late integer;
  v_id uuid;
  v_count integer := 0;
BEGIN
  SELECT * INTO cp FROM public.credit_policy;
  SELECT coalesce(jsonb_agg(jsonb_build_object(
           'provider', m.provider, 'merchant_number', m.merchant_number, 'display_name', m.display_name)), '[]'::jsonb)
    INTO v_merchants
    FROM public.merchant_account AS m WHERE m.active;

  FOR r IN
    SELECT lc.id AS contract_id, lc.contract_number, la.customer_id, la.installment_frequency,
           u.due_date, (u.amount_due - u.amount_paid) AS open_amount,
           public.contract_outstanding(lc.id) AS total_outstanding,
           public.penalty_outstanding(lc.id) AS penalty_open
    FROM public.loan_contract AS lc
    JOIN public.loan_application AS la ON la.id = lc.loan_application_id
    JOIN LATERAL (
      SELECT rs.due_date, rs.amount_due, rs.amount_paid
      FROM public.repayment_schedule AS rs
      WHERE rs.contract_id = lc.id AND rs.amount_paid < rs.amount_due
      ORDER BY rs.installment_number LIMIT 1
    ) AS u ON true
    WHERE lc.status = 'active' AND la.status::text = 'active'
  LOOP
    v_delta := r.due_date - current_date;
    v_template := NULL;
    v_late := greatest(-v_delta, 0);

    IF v_delta = 3 AND r.installment_frequency <> 'daily' THEN
      v_template := 'reminder_pre_due';
    ELSIF v_delta = 0 THEN
      v_template := 'reminder_due_today';
    ELSIF v_delta < 0 AND v_late <= cp.grace_days THEN
      v_template := 'reminder_grace';
    END IF;

    IF v_template IS NOT NULL THEN
      v_id := public.enqueue_notification(
        r.customer_id, r.contract_id, v_template, 'whatsapp',
        jsonb_build_object(
          'currency', 'USD',
          'contract_number', r.contract_number,
          'due_date', r.due_date,
          'installment_due_usd', r.open_amount,
          'penalty_due_usd', r.penalty_open,
          'total_outstanding_usd', r.total_outstanding,
          'days_late', v_late,
          'days_before_lock', CASE WHEN cp.lock_after_days_overdue IS NULL THEN NULL
                                   ELSE greatest(cp.lock_after_days_overdue - v_late, 0) END,
          'merchant_accounts', v_merchants
        ),
        'rem:' || r.contract_id::text || ':' || to_char(current_date, 'YYYYMMDD')
      );
      IF v_id IS NOT NULL THEN
        v_count := v_count + 1;
      END IF;
    END IF;
  END LOOP;

  RETURN v_count;
END;
$$;

CREATE OR REPLACE FUNCTION public.claim_notifications(p_limit integer)
RETURNS SETOF public.notification_outbox
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
BEGIN
  UPDATE public.notification_outbox
    SET status = 'queued'
    WHERE status = 'sending' AND sent_at < now() - interval '10 minutes';

  RETURN QUERY
  WITH picked AS (
    SELECT n.id
    FROM public.notification_outbox AS n
    WHERE n.status = 'queued' AND n.next_attempt_at <= now()
    ORDER BY n.created_at
    LIMIT greatest(p_limit, 1)
    FOR UPDATE SKIP LOCKED
  ),
  updated AS (
    UPDATE public.notification_outbox AS n
      SET status = 'sending', attempts = n.attempts + 1, sent_at = now()
      FROM picked
      WHERE n.id = picked.id
      RETURNING n.*
  )
  SELECT * FROM updated;
END;
$$;

CREATE OR REPLACE FUNCTION public.complete_notification(p_id uuid, p_ok boolean, p_error text, p_reference text)
RETURNS text
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  n public.notification_outbox%ROWTYPE;
BEGIN
  SELECT * INTO n FROM public.notification_outbox WHERE id = p_id FOR UPDATE;
  IF NOT FOUND OR n.status <> 'sending' THEN
    RETURN coalesce(n.status, 'unknown');
  END IF;

  IF p_ok THEN
    UPDATE public.notification_outbox SET status = 'sent', provider_ref = p_reference, last_error = NULL WHERE id = n.id;
    RETURN 'sent';
  END IF;

  IF n.channel = 'whatsapp' AND n.attempts >= 3 THEN
    UPDATE public.notification_outbox SET status = 'failed', last_error = p_error WHERE id = n.id;
    INSERT INTO public.notification_outbox (
      customer_id, contract_id, recipient_phone, template_code, channel, payload, idempotency_key, fallback_for
    ) VALUES (
      n.customer_id, n.contract_id, n.recipient_phone, n.template_code, 'sms', n.payload,
      'sms:' || n.idempotency_key, n.id
    ) ON CONFLICT (idempotency_key) DO NOTHING;
    RETURN 'failed_sms_queued';
  ELSIF n.channel = 'sms' AND n.attempts >= 5 THEN
    UPDATE public.notification_outbox SET status = 'failed', last_error = p_error WHERE id = n.id;
    RETURN 'failed';
  END IF;

  UPDATE public.notification_outbox
    SET status = 'queued', last_error = p_error,
        next_attempt_at = now() + (power(2, n.attempts)::integer * interval '1 minute')
    WHERE id = n.id;
  RETURN 'queued';
END;
$$;

-- 6. Penalties
CREATE OR REPLACE FUNCTION public.accrue_penalties(p_as_of date DEFAULT current_date)
RETURNS integer
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  cp public.credit_policy%ROWTYPE;
  r record;
  pn public.penalty%ROWTYPE;
  v_days integer;
  v_amount numeric;
  v_cap numeric;
  v_count integer := 0;
BEGIN
  SELECT * INTO cp FROM public.credit_policy;
  IF cp.penalty_rate_per_day <= 0 THEN
    RETURN 0;
  END IF;

  FOR r IN
    SELECT rs.id, rs.contract_id, rs.installment_number, rs.due_date, rs.amount_due, rs.amount_paid
    FROM public.repayment_schedule AS rs
    JOIN public.loan_contract AS lc ON lc.id = rs.contract_id
    WHERE lc.status = 'active'
      AND rs.amount_paid < rs.amount_due
      AND rs.due_date + cp.grace_days < p_as_of
    ORDER BY rs.id
  LOOP
    INSERT INTO public.penalty (contract_id, installment_id, last_accrued_on)
    VALUES (r.contract_id, r.id, r.due_date + cp.grace_days)
    ON CONFLICT (installment_id) DO NOTHING;

    SELECT * INTO pn FROM public.penalty WHERE installment_id = r.id FOR UPDATE;
    v_days := p_as_of - pn.last_accrued_on;
    IF v_days <= 0 THEN
      CONTINUE;
    END IF;

    v_amount := round((r.amount_due - r.amount_paid) * cp.penalty_rate_per_day, 2) * v_days;
    v_cap := round(r.amount_due * cp.penalty_cap_rate, 2) - pn.accrued_amount;
    v_amount := least(v_amount, greatest(v_cap, 0));

    UPDATE public.penalty
      SET accrued_amount = accrued_amount + v_amount, last_accrued_on = p_as_of
      WHERE id = pn.id;

    IF v_amount > 0 THEN
      INSERT INTO public.ledger_entry (contract_id, entry_type, account_category, amount, description)
      VALUES
        (r.contract_id, 'DEBIT', 'PRINCIPAL_RECEIVABLE', v_amount, 'Penalty accrued, installment ' || r.installment_number),
        (r.contract_id, 'CREDIT', 'PENALTY_INCOME', v_amount, 'Penalty accrued, installment ' || r.installment_number);
      v_count := v_count + 1;
    END IF;
  END LOOP;

  RETURN v_count;
END;
$$;

-- 7. Closure
CREATE OR REPLACE FUNCTION public.issue_closure_documents(p_contract uuid)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  r record;
  v_paid numeric;
  v_base jsonb;
  v_year text := to_char(now(), 'YYYY');
  v_ids uuid[];
BEGIN
  SELECT lc.contract_number, lc.total_payable, la.customer_id,
         cu.first_name, cu.last_name, cu.phone_number,
         it.brand, it.model, it.imei
    INTO r
    FROM public.loan_contract AS lc
    JOIN public.loan_application AS la ON la.id = lc.loan_application_id
    JOIN public.customer AS cu ON cu.id = la.customer_id
    JOIN public.inventory_item AS it ON it.id = la.inventory_item_id
    WHERE lc.id = p_contract;
  IF NOT FOUND THEN
    RETURN;
  END IF;

  SELECT coalesce(sum(p.amount), 0) INTO v_paid
    FROM public.payment AS p WHERE p.contract_id = p_contract AND p.status = 'success';

  v_base := jsonb_build_object(
    'currency', 'USD',
    'contract_number', r.contract_number,
    'customer_name', r.first_name || ' ' || r.last_name,
    'customer_phone', r.phone_number,
    'device', r.brand || ' ' || r.model,
    'imei', r.imei,
    'total_payable_usd', r.total_payable,
    'total_paid_usd', v_paid,
    'closed_on', current_date
  );

  INSERT INTO public.closure_document (contract_id, doc_type, document_number, payload)
  VALUES
    (p_contract, 'paid_in_full_receipt', 'SR-' || v_year || '-' || lpad(nextval('public.closure_document_number_seq')::text, 6, '0'), v_base),
    (p_contract, 'ownership_certificate', 'OC-' || v_year || '-' || lpad(nextval('public.closure_document_number_seq')::text, 6, '0'),
       v_base || jsonb_build_object('ownership_transferred_to', r.first_name || ' ' || r.last_name))
  ON CONFLICT (contract_id, doc_type) DO NOTHING;

  SELECT array_agg(cd.id) INTO v_ids FROM public.closure_document AS cd WHERE cd.contract_id = p_contract;

  PERFORM public.enqueue_notification(
    r.customer_id, p_contract, 'closure_documents', 'whatsapp',
    jsonb_build_object('contract_number', r.contract_number, 'document_ids', to_jsonb(v_ids)),
    'closure:' || p_contract::text);
END;
$$;

CREATE OR REPLACE FUNCTION public.attach_closure_document(p_id uuid, p_path text, p_hash text)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
BEGIN
  IF coalesce(btrim(p_path), '') = '' OR coalesce(btrim(p_hash), '') = '' THEN
    RAISE EXCEPTION 'path and hash are required';
  END IF;
  UPDATE public.closure_document
    SET document_path = p_path, document_hash = p_hash, status = 'ready', rendered_at = now()
    WHERE id = p_id AND document_path IS NULL;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'unknown document or already rendered';
  END IF;
END;
$$;

CREATE OR REPLACE FUNCTION public.settle_contract_if_paid(p_contract uuid)
RETURNS boolean
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  v public.loan_contract%ROWTYPE;
BEGIN
  IF public.contract_outstanding(p_contract) <> 0 THEN
    RETURN false;
  END IF;
  SELECT * INTO v FROM public.loan_contract WHERE id = p_contract FOR UPDATE;
  IF NOT FOUND OR v.status <> 'active' THEN
    RETURN false;
  END IF;

  UPDATE public.loan_contract SET status = 'closed' WHERE id = v.id;
  UPDATE public.loan_application
    SET status = 'completed'
    WHERE id = v.loan_application_id AND status::text = 'active';
  PERFORM public.issue_closure_documents(v.id);
  RETURN true;
END;
$$;

-- 8. Payment engine (allocation order: penalties, interest and fees, principal)
CREATE OR REPLACE FUNCTION public.apply_payment(p_payment uuid)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  pay public.payment%ROWTYPE;
  v_contract public.loan_contract%ROWTYPE;
  r record;
  v_left numeric;
  v_apply numeric;
  v_cat public.ledger_account_category;
  v_outstanding numeric;
  v_closed boolean;
  v_customer uuid;
BEGIN
  SELECT * INTO pay FROM public.payment WHERE id = p_payment FOR UPDATE;
  IF NOT FOUND OR pay.status <> 'success' THEN
    RAISE EXCEPTION 'only a successful payment can be applied';
  END IF;
  IF EXISTS (SELECT 1 FROM public.ledger_entry AS le WHERE le.payment_id = pay.id) THEN
    RETURN jsonb_build_object('already_applied', true);
  END IF;

  SELECT * INTO v_contract FROM public.loan_contract WHERE id = pay.contract_id FOR UPDATE;
  v_left := pay.amount;

  -- 1. late penalties
  FOR r IN
    SELECT pn.id, pn.installment_id, (pn.accrued_amount - pn.paid_amount - pn.waived_amount) AS open_amt
    FROM public.penalty AS pn
    JOIN public.repayment_schedule AS rs ON rs.id = pn.installment_id
    WHERE pn.contract_id = pay.contract_id AND pn.accrued_amount - pn.paid_amount - pn.waived_amount > 0
    ORDER BY rs.installment_number
    FOR UPDATE OF pn
  LOOP
    EXIT WHEN v_left <= 0;
    v_apply := least(v_left, r.open_amt);
    UPDATE public.penalty SET paid_amount = paid_amount + v_apply WHERE id = r.id;
    INSERT INTO public.payment_allocation (payment_id, installment_id, component, amount)
    VALUES (pay.id, r.installment_id, 'penalty', v_apply);
    v_left := v_left - v_apply;
  END LOOP;

  -- 2. interest and fees of due installments
  FOR r IN
    SELECT rs.id, (rs.interest_fee_due - rs.interest_fee_paid) AS open_amt
    FROM public.repayment_schedule AS rs
    WHERE rs.contract_id = pay.contract_id AND rs.due_date <= current_date AND rs.interest_fee_paid < rs.interest_fee_due
    ORDER BY rs.installment_number
    FOR UPDATE
  LOOP
    EXIT WHEN v_left <= 0;
    v_apply := least(v_left, r.open_amt);
    UPDATE public.repayment_schedule SET interest_fee_paid = interest_fee_paid + v_apply WHERE id = r.id;
    INSERT INTO public.payment_allocation (payment_id, installment_id, component, amount)
    VALUES (pay.id, r.id, 'interest_fee', v_apply);
    v_left := v_left - v_apply;
  END LOOP;

  -- 3. principal of due installments
  FOR r IN
    SELECT rs.id, (rs.principal_due - rs.principal_paid) AS open_amt
    FROM public.repayment_schedule AS rs
    WHERE rs.contract_id = pay.contract_id AND rs.due_date <= current_date AND rs.principal_paid < rs.principal_due
    ORDER BY rs.installment_number
    FOR UPDATE
  LOOP
    EXIT WHEN v_left <= 0;
    v_apply := least(v_left, r.open_amt);
    UPDATE public.repayment_schedule SET principal_paid = principal_paid + v_apply WHERE id = r.id;
    INSERT INTO public.payment_allocation (payment_id, installment_id, component, amount)
    VALUES (pay.id, r.id, 'principal', v_apply);
    v_left := v_left - v_apply;
  END LOOP;

  -- 4. next installments (paid in advance): interest and fees first, then principal
  FOR r IN
    SELECT rs.id,
           (rs.interest_fee_due - rs.interest_fee_paid) AS if_open,
           (rs.principal_due - rs.principal_paid) AS pr_open
    FROM public.repayment_schedule AS rs
    WHERE rs.contract_id = pay.contract_id AND rs.due_date > current_date
      AND (rs.interest_fee_due - rs.interest_fee_paid) + (rs.principal_due - rs.principal_paid) > 0
    ORDER BY rs.installment_number
    FOR UPDATE
  LOOP
    EXIT WHEN v_left <= 0;
    v_apply := least(v_left, r.if_open);
    IF v_apply > 0 THEN
      UPDATE public.repayment_schedule SET interest_fee_paid = interest_fee_paid + v_apply WHERE id = r.id;
      INSERT INTO public.payment_allocation (payment_id, installment_id, component, amount)
      VALUES (pay.id, r.id, 'interest_fee', v_apply);
      v_left := v_left - v_apply;
    END IF;
    v_apply := least(v_left, r.pr_open);
    IF v_apply > 0 THEN
      UPDATE public.repayment_schedule SET principal_paid = principal_paid + v_apply WHERE id = r.id;
      INSERT INTO public.payment_allocation (payment_id, installment_id, component, amount)
      VALUES (pay.id, r.id, 'principal', v_apply);
      v_left := v_left - v_apply;
    END IF;
  END LOOP;

  IF v_left > 0 THEN
    RAISE EXCEPTION 'payment exceeds the outstanding balance';
  END IF;

  UPDATE public.repayment_schedule AS rs
    SET amount_paid = rs.interest_fee_paid + rs.principal_paid,
        status = (CASE
          WHEN rs.interest_fee_paid + rs.principal_paid >= rs.amount_due THEN 'paid'
          WHEN rs.due_date < current_date THEN 'overdue'
          WHEN rs.interest_fee_paid + rs.principal_paid > 0 THEN 'partial'
          ELSE rs.status::text END)::public.repayment_status
    WHERE rs.contract_id = pay.contract_id
      AND rs.amount_paid IS DISTINCT FROM rs.interest_fee_paid + rs.principal_paid;

  v_cat := (CASE WHEN pay.payment_method = 'cash' THEN 'CUSTOMER_CASH' ELSE 'MOBILE_MONEY_CLEARING' END)::public.ledger_account_category;
  INSERT INTO public.ledger_entry (contract_id, payment_id, entry_type, account_category, amount, description)
  VALUES
    (pay.contract_id, pay.id, 'DEBIT', v_cat, pay.amount, 'Payment received ' || pay.external_transaction_id),
    (pay.contract_id, pay.id, 'CREDIT', 'PRINCIPAL_RECEIVABLE', pay.amount, 'Receivable reduced by payment ' || pay.external_transaction_id);

  v_closed := public.settle_contract_if_paid(pay.contract_id);
  v_outstanding := public.contract_outstanding(pay.contract_id);

  SELECT la.customer_id INTO v_customer
    FROM public.loan_contract AS lc JOIN public.loan_application AS la ON la.id = lc.loan_application_id
    WHERE lc.id = pay.contract_id;

  PERFORM public.enqueue_notification(
    v_customer, pay.contract_id, 'payment_receipt', 'whatsapp',
    jsonb_build_object(
      'currency', 'USD',
      'payment_id', pay.id,
      'amount_usd', pay.amount,
      'contract_number', v_contract.contract_number,
      'outstanding_usd', v_outstanding,
      'closed', v_closed,
      'allocation', (
        SELECT coalesce(jsonb_object_agg(x.component, x.total), '{}'::jsonb)
        FROM (
          SELECT pa.component, sum(pa.amount) AS total
          FROM public.payment_allocation AS pa WHERE pa.payment_id = pay.id GROUP BY pa.component
        ) AS x
      )
    ),
    'receipt:' || pay.id::text);

  PERFORM public.evaluate_contract_devices(
    pay.contract_id, 'payment_received', 'payment_received',
    jsonb_build_object('payment_id', pay.id, 'amount', pay.amount, 'outstanding', v_outstanding));

  RETURN jsonb_build_object('applied', true, 'outstanding', v_outstanding, 'contract_closed', v_closed);
END;
$$;

DROP FUNCTION IF EXISTS public.process_mobile_money_payment(text, text, uuid, numeric, text, jsonb);

CREATE OR REPLACE FUNCTION public.process_mobile_money_payment(
  p_provider text,
  p_external_id text,
  p_contract uuid,
  p_amount numeric,
  p_method text,
  p_raw jsonb,
  p_recorded_by uuid DEFAULT NULL
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  pay public.payment%ROWTYPE;
  v_contract_status public.contract_status;
  v_outstanding numeric;
  v_id uuid;
  v_apply jsonb;
  v_method public.payment_method;
  v_ext text;
BEGIN
  IF p_external_id IS NULL OR btrim(p_external_id) = '' THEN
    RAISE EXCEPTION 'the operator transaction reference is required';
  END IF;
  v_method := p_method::public.payment_method;
  IF v_method = 'cash' THEN
    RAISE EXCEPTION 'cash payments go through the field cash flow';
  END IF;

  v_ext := upper(p_method) || ':' || upper(btrim(p_external_id));
  PERFORM pg_advisory_xact_lock(hashtextextended('pay:' || v_ext, 0));

  SELECT * INTO pay FROM public.payment WHERE external_transaction_id = v_ext;
  IF FOUND THEN
    INSERT INTO public.payment_webhook_log (provider, external_transaction_id, raw, outcome)
    VALUES (p_provider, v_ext, coalesce(p_raw, '{}'::jsonb), 'duplicate');
    RETURN jsonb_build_object('status', 'duplicate', 'payment_id', pay.id, 'payment_status', pay.status);
  END IF;

  SELECT lc.status INTO v_contract_status FROM public.loan_contract AS lc WHERE lc.id = p_contract;
  IF NOT FOUND THEN
    INSERT INTO public.payment_webhook_log (provider, external_transaction_id, raw, outcome)
    VALUES (p_provider, v_ext, coalesce(p_raw, '{}'::jsonb), 'unknown_contract');
    RETURN jsonb_build_object('status', 'unknown_contract');
  END IF;
  IF v_contract_status <> 'active' THEN
    INSERT INTO public.payment_webhook_log (provider, external_transaction_id, raw, outcome)
    VALUES (p_provider, v_ext, coalesce(p_raw, '{}'::jsonb), 'contract_not_active');
    RETURN jsonb_build_object('status', 'contract_not_active');
  END IF;

  v_outstanding := public.contract_outstanding(p_contract);
  IF p_amount > v_outstanding THEN
    INSERT INTO public.payment (contract_id, external_transaction_id, amount, payment_method, status, collected_by)
    VALUES (p_contract, v_ext, p_amount, v_method, 'pending', p_recorded_by)
    RETURNING id INTO v_id;
    INSERT INTO public.payment_webhook_log (provider, external_transaction_id, raw, outcome)
    VALUES (p_provider, v_ext, coalesce(p_raw, '{}'::jsonb), 'needs_review');
    RETURN jsonb_build_object('status', 'needs_review', 'reason', 'amount_exceeds_outstanding',
                              'payment_id', v_id, 'outstanding', v_outstanding);
  END IF;

  INSERT INTO public.payment (contract_id, external_transaction_id, amount, payment_method, status, collected_by)
  VALUES (p_contract, v_ext, p_amount, v_method, 'success', p_recorded_by)
  RETURNING id INTO v_id;

  v_apply := public.apply_payment(v_id);

  INSERT INTO public.payment_webhook_log (provider, external_transaction_id, raw, outcome)
  VALUES (p_provider, v_ext, coalesce(p_raw, '{}'::jsonb), 'applied');

  RETURN jsonb_build_object('status', 'applied', 'payment_id', v_id) || v_apply;
END;
$$;

CREATE OR REPLACE FUNCTION public.record_manual_mobile_money_payment(
  p_actor uuid,
  p_external_id text,
  p_contract_number text,
  p_amount numeric,
  p_method text,
  p_note text
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  v_contract uuid;
  v_agency uuid;
BEGIN
  PERFORM set_config('app.actor_id', p_actor::text, true);

  IF p_method NOT IN ('mpesa', 'airtel_money', 'orange_money') THEN
    RAISE EXCEPTION 'unknown mobile money operator';
  END IF;
  IF p_amount IS NULL OR p_amount <= 0 THEN
    RAISE EXCEPTION 'the amount in USD must be positive';
  END IF;

  SELECT lc.id, la.agency_id INTO v_contract, v_agency
    FROM public.loan_contract AS lc
    JOIN public.loan_application AS la ON la.id = lc.loan_application_id
    WHERE lc.contract_number = btrim(p_contract_number);
  IF NOT FOUND THEN
    RAISE EXCEPTION 'unknown contract number';
  END IF;

  IF NOT public.abac_allows('payment.record', p_amount, v_agency) THEN
    RAISE EXCEPTION 'not allowed to record this payment' USING ERRCODE = '42501';
  END IF;

  RETURN public.process_mobile_money_payment(
    'manual', p_external_id, v_contract, p_amount, p_method,
    jsonb_build_object('entered_by', p_actor, 'note', p_note), p_actor);
END;
$$;

-- 9. Tier 3: waiver and restructuring (two people)
CREATE OR REPLACE FUNCTION public.request_penalty_waiver(p_actor uuid, p_contract uuid, p_amount numeric, p_reason text)
RETURNS uuid
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  v_agency uuid;
  v_id uuid;
BEGIN
  PERFORM set_config('app.actor_id', p_actor::text, true);
  IF coalesce(btrim(p_reason), '') = '' THEN
    RAISE EXCEPTION 'a reason is required';
  END IF;

  SELECT la.agency_id INTO v_agency
    FROM public.loan_contract AS lc JOIN public.loan_application AS la ON la.id = lc.loan_application_id
    WHERE lc.id = p_contract AND lc.status = 'active';
  IF NOT FOUND THEN
    RAISE EXCEPTION 'unknown or inactive contract';
  END IF;
  IF NOT public.abac_allows('penalty.waive.request', p_amount, v_agency) THEN
    RAISE EXCEPTION 'not allowed to request a penalty waiver' USING ERRCODE = '42501';
  END IF;
  IF p_amount IS NULL OR p_amount <= 0 OR p_amount > public.penalty_outstanding(p_contract) THEN
    RAISE EXCEPTION 'the amount must be between 0 and the penalties still owed';
  END IF;

  INSERT INTO public.approval_request (kind, target_table, target_id, payload, reason, requested_by)
  VALUES ('penalty_waiver', 'loan_contract', p_contract, jsonb_build_object('amount', p_amount), btrim(p_reason), p_actor)
  RETURNING id INTO v_id;
  RETURN v_id;
END;
$$;

CREATE OR REPLACE FUNCTION public.request_restructuring(
  p_actor uuid, p_contract uuid, p_duration_days integer,
  p_frequency public.installment_frequency, p_waive_penalties boolean, p_reason text
)
RETURNS uuid
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  v_agency uuid;
  v_id uuid;
BEGIN
  PERFORM set_config('app.actor_id', p_actor::text, true);
  IF coalesce(btrim(p_reason), '') = '' THEN
    RAISE EXCEPTION 'a reason is required';
  END IF;
  IF p_duration_days IS NULL OR p_duration_days < 1 OR p_duration_days > 365 THEN
    RAISE EXCEPTION 'the new duration must be between 1 and 365 days';
  END IF;

  SELECT la.agency_id INTO v_agency
    FROM public.loan_contract AS lc JOIN public.loan_application AS la ON la.id = lc.loan_application_id
    WHERE lc.id = p_contract AND lc.status = 'active';
  IF NOT FOUND THEN
    RAISE EXCEPTION 'unknown or inactive contract';
  END IF;
  IF NOT public.abac_allows('loan.restructure.request', public.contract_outstanding(p_contract), v_agency) THEN
    RAISE EXCEPTION 'not allowed to request a restructuring' USING ERRCODE = '42501';
  END IF;

  INSERT INTO public.approval_request (kind, target_table, target_id, payload, reason, requested_by)
  VALUES (
    'loan_restructure', 'loan_contract', p_contract,
    jsonb_build_object('duration_days', p_duration_days, 'frequency', p_frequency, 'waive_penalties', coalesce(p_waive_penalties, false)),
    btrim(p_reason), p_actor)
  RETURNING id INTO v_id;
  RETURN v_id;
END;
$$;

CREATE OR REPLACE FUNCTION public.execute_penalty_waiver(p_contract uuid, p_amount numeric, p_request uuid, p_actor uuid)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  r record;
  v_left numeric := p_amount;
  v_apply numeric;
  v_closed boolean;
BEGIN
  IF p_amount <= 0 OR p_amount > public.penalty_outstanding(p_contract) THEN
    RAISE EXCEPTION 'the amount exceeds the penalties still owed';
  END IF;

  FOR r IN
    SELECT pn.id, (pn.accrued_amount - pn.paid_amount - pn.waived_amount) AS open_amt
    FROM public.penalty AS pn
    JOIN public.repayment_schedule AS rs ON rs.id = pn.installment_id
    WHERE pn.contract_id = p_contract AND pn.accrued_amount - pn.paid_amount - pn.waived_amount > 0
    ORDER BY rs.installment_number
    FOR UPDATE OF pn
  LOOP
    EXIT WHEN v_left <= 0;
    v_apply := least(v_left, r.open_amt);
    UPDATE public.penalty SET waived_amount = waived_amount + v_apply WHERE id = r.id;
    v_left := v_left - v_apply;
  END LOOP;

  INSERT INTO public.ledger_entry (contract_id, entry_type, account_category, amount, description)
  VALUES
    (p_contract, 'CREDIT', 'PRINCIPAL_RECEIVABLE', p_amount, 'Penalty waived (approval ' || p_request::text || ')'),
    (p_contract, 'DEBIT', 'PENALTY_INCOME', p_amount, 'Penalty waived (approval ' || p_request::text || ')');

  v_closed := public.settle_contract_if_paid(p_contract);
  PERFORM public.evaluate_contract_devices(p_contract, 'penalty_waived', 'penalty_waived',
    jsonb_build_object('amount', p_amount, 'approval_request_id', p_request, 'approved_by', p_actor));

  RETURN jsonb_build_object('waived', p_amount, 'contract_closed', v_closed);
END;
$$;

CREATE OR REPLACE FUNCTION public.execute_restructuring(
  p_contract uuid, p_duration_days integer, p_frequency public.installment_frequency,
  p_waive_penalties boolean, p_request uuid, p_actor uuid
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  v_old public.loan_contract%ROWTYPE;
  v_out numeric;
  v_if_out numeric;
  v_ratio numeric;
  v_pen numeric;
  v_new uuid;
  v_end date;
  v_number text;
BEGIN
  SELECT * INTO v_old FROM public.loan_contract WHERE id = p_contract FOR UPDATE;
  IF NOT FOUND OR v_old.status <> 'active' THEN
    RAISE EXCEPTION 'only an active contract can be restructured';
  END IF;

  SELECT coalesce(sum(rs.amount_due - rs.amount_paid), 0),
         coalesce(sum(rs.interest_fee_due - rs.interest_fee_paid), 0)
    INTO v_out, v_if_out
    FROM public.repayment_schedule AS rs WHERE rs.contract_id = p_contract;
  IF v_out <= 0 THEN
    RAISE EXCEPTION 'nothing left to restructure';
  END IF;

  v_pen := public.penalty_outstanding(p_contract);
  IF v_pen > 0 THEN
    IF NOT p_waive_penalties THEN
      RAISE EXCEPTION 'penalties are still owed: pay them or include their waiver in the request';
    END IF;
    PERFORM public.execute_penalty_waiver(p_contract, v_pen, p_request, p_actor);
  END IF;

  v_ratio := v_if_out / v_out;
  v_number := 'CT-' || to_char(now(), 'YYYY') || '-' || lpad(nextval('public.contract_number_seq')::text, 6, '0')
              || '-V' || (v_old.version + 1)::text;

  SELECT max(p.due_date) INTO v_end
    FROM public.installment_plan(current_date, p_frequency, p_duration_days, v_out) AS p;

  UPDATE public.loan_contract SET status = 'closed' WHERE id = v_old.id;

  INSERT INTO public.loan_contract (
    loan_application_id, version, contract_number, start_date, end_date,
    interest_rate, total_payable, status, terms_snapshot
  ) VALUES (
    v_old.loan_application_id, v_old.version + 1, v_number, current_date, v_end,
    v_old.interest_rate, v_out, 'active',
    v_old.terms_snapshot || jsonb_build_object(
      'restructured_from', v_old.contract_number, 'approval_request_id', p_request)
  )
  RETURNING id INTO v_new;

  INSERT INTO public.repayment_schedule (
    contract_id, installment_number, due_date, amount_due, interest_fee_due, principal_due
  )
  SELECT v_new, p.installment_number, p.due_date, p.amount_due,
         round(p.amount_due * v_ratio, 2), p.amount_due - round(p.amount_due * v_ratio, 2)
  FROM public.installment_plan(current_date, p_frequency, p_duration_days, v_out) AS p;

  INSERT INTO public.ledger_entry (contract_id, entry_type, account_category, amount, description)
  VALUES
    (v_old.id, 'CREDIT', 'PRINCIPAL_RECEIVABLE', v_out, 'Restructuring: balance moved to ' || v_number),
    (v_new, 'DEBIT', 'PRINCIPAL_RECEIVABLE', v_out, 'Restructuring: balance taken from ' || v_old.contract_number);

  UPDATE public.managed_device SET contract_id = v_new WHERE contract_id = v_old.id;

  PERFORM public.evaluate_contract_devices(v_new, 'restructured', 'contract_restructured',
    jsonb_build_object('from', v_old.contract_number, 'to', v_number, 'approval_request_id', p_request));

  RETURN jsonb_build_object('new_contract_id', v_new, 'contract_number', v_number, 'balance_usd', v_out);
END;
$$;

CREATE OR REPLACE FUNCTION public.decide_approval(p_actor uuid, p_request uuid, p_approve boolean, p_reason text)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  req public.approval_request%ROWTYPE;
  v_result jsonb := '{}'::jsonb;
BEGIN
  PERFORM set_config('app.actor_id', p_actor::text, true);
  IF coalesce(btrim(p_reason), '') = '' THEN
    RAISE EXCEPTION 'a reason is required';
  END IF;

  SELECT * INTO req FROM public.approval_request WHERE id = p_request FOR UPDATE;
  IF NOT FOUND OR req.status <> 'pending' THEN
    RAISE EXCEPTION 'no pending request with this id';
  END IF;

  UPDATE public.approval_request
    SET status = (CASE WHEN p_approve THEN 'approved' ELSE 'rejected' END)::public.approval_status,
        approved_by = p_actor,
        decision_reason = btrim(p_reason)
    WHERE id = req.id;

  IF p_approve THEN
    IF req.kind = 'penalty_waiver' THEN
      v_result := public.execute_penalty_waiver(req.target_id, (req.payload ->> 'amount')::numeric, req.id, p_actor);
    ELSIF req.kind = 'loan_restructure' THEN
      v_result := public.execute_restructuring(
        req.target_id,
        (req.payload ->> 'duration_days')::integer,
        (req.payload ->> 'frequency')::public.installment_frequency,
        coalesce((req.payload ->> 'waive_penalties')::boolean, false),
        req.id, p_actor);
    ELSE
      RAISE EXCEPTION 'this approval type is not executed by this function';
    END IF;
  END IF;

  RETURN jsonb_build_object('decision', CASE WHEN p_approve THEN 'approved' ELSE 'rejected' END) || v_result;
END;
$$;

-- 10. Administrator-only settings
CREATE OR REPLACE FUNCTION public.set_merchant_account(
  p_admin uuid, p_provider text, p_number text, p_name text, p_active boolean
)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
BEGIN
  PERFORM set_config('app.actor_id', p_admin::text, true);
  IF NOT public.abac_allows('policy.manage') THEN
    RAISE EXCEPTION 'only the system administrator can change merchant accounts' USING ERRCODE = '42501';
  END IF;
  INSERT INTO public.merchant_account (provider, merchant_number, display_name, active)
  VALUES (p_provider, p_number, p_name, coalesce(p_active, true))
  ON CONFLICT (provider) DO UPDATE
    SET merchant_number = EXCLUDED.merchant_number, display_name = EXCLUDED.display_name,
        active = EXCLUDED.active, updated_at = now();
END;
$$;

CREATE OR REPLACE FUNCTION public.set_brand_lock_provider(p_admin uuid, p_brand text, p_provider text)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
BEGIN
  PERFORM set_config('app.actor_id', p_admin::text, true);
  IF NOT public.abac_allows('policy.manage') THEN
    RAISE EXCEPTION 'only the system administrator can change lock providers' USING ERRCODE = '42501';
  END IF;
  INSERT INTO public.brand_lock_provider (brand, provider)
  VALUES (lower(btrim(p_brand)), p_provider)
  ON CONFLICT (brand) DO UPDATE SET provider = EXCLUDED.provider;
END;
$$;

-- 11. Status for screens, daily cycles, SLA view
CREATE OR REPLACE FUNCTION public.contract_collection_status(p_contract uuid)
RETURNS jsonb
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  r record;
BEGIN
  SELECT lc.status AS contract_status, md.lock_state, md.lock_reason, md.lock_provider
    INTO r
    FROM public.loan_contract AS lc
    LEFT JOIN public.managed_device AS md ON md.contract_id = lc.id
    WHERE lc.id = p_contract;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'unknown contract';
  END IF;

  RETURN jsonb_build_object(
    'currency', 'USD',
    'contract_status', r.contract_status,
    'device_state', r.lock_state,
    'status_color', CASE WHEN r.contract_status = 'closed' THEN public.device_status_color('released')
                         ELSE public.device_status_color(r.lock_state) END,
    'lock_reason', r.lock_reason,
    'lock_provider', r.lock_provider,
    'overdue_days', public.device_overdue_days(p_contract),
    'penalty_outstanding_usd', public.penalty_outstanding(p_contract),
    'total_outstanding_usd', public.contract_outstanding(p_contract),
    'open_task', (
      SELECT jsonb_build_object('id', t.id, 'priority', t.priority, 'assigned_to', t.assigned_to, 'reason', t.reason)
      FROM public.collection_task AS t
      WHERE t.contract_id = p_contract AND t.status IN ('open', 'in_progress') LIMIT 1
    )
  );
END;
$$;

CREATE OR REPLACE FUNCTION public.run_daily_collection_cycle()
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  v_pen integer;
  v_dev jsonb;
BEGIN
  v_pen := public.accrue_penalties();
  v_dev := public.evaluate_all_devices();
  RETURN jsonb_build_object('penalties_accrued', v_pen) || v_dev;
END;
$$;

CREATE OR REPLACE FUNCTION public.run_reminder_cycle()
RETURNS integer
LANGUAGE sql
SECURITY DEFINER
SET search_path = ''
AS $$
  SELECT public.schedule_payment_reminders();
$$;

CREATE OR REPLACE VIEW device_unlock_sla
WITH (security_invoker = true) AS
  SELECT c.id AS command_id, c.managed_device_id, c.command_type, c.created_at, c.acked_at,
         extract(epoch FROM (c.acked_at - c.created_at)) AS seconds_to_ack,
         (c.acked_at - c.created_at) > interval '60 seconds' AS over_60_seconds
  FROM device_command AS c
  WHERE c.command_type IN ('unlock', 'release_device') AND c.acked_at IS NOT NULL;

-- Redefined: device state machine now opens/closes collection tasks and tells the provider and tier
CREATE OR REPLACE FUNCTION public.evaluate_device_policy(p_device uuid, p_trigger text)
RETURNS text
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  d public.managed_device%ROWTYPE;
  pol public.device_restriction_policy%ROWTYPE;
  cp public.credit_policy%ROWTYPE;
  v_contract_status public.contract_status;
  v_overdue integer;
  v_sim_status text;
  v_sim_since timestamptz;
  v_desired text;
  v_reason text;
  v_command text;
BEGIN
  SELECT * INTO d FROM public.managed_device WHERE id = p_device FOR UPDATE;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'unknown managed device';
  END IF;
  IF d.lock_state = 'released' THEN
    RETURN 'released';
  END IF;

  SELECT * INTO pol FROM public.device_restriction_policy;
  SELECT * INTO cp FROM public.credit_policy;
  SELECT lc.status INTO v_contract_status FROM public.loan_contract AS lc WHERE lc.id = d.contract_id;
  v_overdue := public.device_overdue_days(d.contract_id);

  IF d.override_state = 'unlocked' AND d.override_until IS NOT NULL AND d.override_until <= now() THEN
    UPDATE public.managed_device SET override_state = NULL, override_until = NULL WHERE id = d.id;
    d.override_state := NULL;
    d.override_until := NULL;
    PERFORM public.emit_device_event(d.id, 'override_expired', '{}'::jsonb, NULL);
  END IF;

  IF d.current_sim_hash IS NOT NULL THEN
    SELECT s.status, s.first_seen_at INTO v_sim_status, v_sim_since
      FROM public.device_sim AS s
      WHERE s.managed_device_id = d.id AND s.sim_hash = d.current_sim_hash;
  END IF;

  IF v_contract_status = 'closed' THEN
    v_desired := 'released'; v_reason := 'contract_paid_off';
  ELSIF d.security_hold THEN
    v_desired := 'locked'; v_reason := 'security_hold: ' || coalesce(d.security_hold_reason, 'unspecified');
  ELSIF d.override_state = 'locked' THEN
    v_desired := 'locked'; v_reason := 'manual_lock';
  ELSIF v_sim_status = 'rejected' THEN
    v_desired := 'locked'; v_reason := 'sim_rejected';
  ELSIF v_sim_status = 'pending' AND now() >= v_sim_since + make_interval(hours => pol.sim_grace_hours) THEN
    v_desired := 'locked'; v_reason := 'sim_unverified';
  ELSIF d.override_state = 'unlocked' AND d.override_until > now() THEN
    v_desired := 'unlocked'; v_reason := 'manual_unlock';
  ELSIF cp.lock_after_days_overdue IS NOT NULL AND v_overdue > 0 AND v_overdue >= cp.lock_after_days_overdue THEN
    v_desired := 'locked'; v_reason := 'payment_overdue';
  ELSIF v_overdue > cp.grace_days THEN
    v_desired := 'warning'; v_reason := 'payment_late';
  ELSIF v_sim_status = 'pending' THEN
    v_desired := 'warning'; v_reason := 'sim_verification_pending';
  ELSIF d.last_seen_at IS NOT NULL AND d.last_seen_at < now() - make_interval(days => pol.heartbeat_max_silence_days) THEN
    v_desired := 'warning'; v_reason := 'device_silent';
  ELSE
    v_desired := 'unlocked'; v_reason := 'in_good_standing';
  END IF;

  IF v_desired = d.lock_state THEN
    IF v_reason IS DISTINCT FROM d.lock_reason THEN
      UPDATE public.managed_device SET lock_reason = v_reason WHERE id = d.id;
    END IF;
    RETURN v_desired;
  END IF;

  UPDATE public.managed_device
    SET lock_state = v_desired,
        lock_reason = v_reason,
        lock_version = d.lock_version + 1,
        lock_state_since = now()
    WHERE id = d.id;

  PERFORM public.emit_device_event(d.id, 'state_changed', jsonb_build_object(
    'from', d.lock_state, 'to', v_desired, 'reason', v_reason,
    'trigger', p_trigger, 'overdue_days', v_overdue), NULL);

  IF v_desired IN ('warning', 'locked') AND v_reason IN ('payment_late', 'payment_overdue') THEN
    PERFORM public.ensure_collection_task(d.contract_id, v_reason);
  ELSIF v_desired IN ('unlocked', 'released') THEN
    PERFORM public.close_collection_tasks(d.contract_id, 'auto_closed: ' || v_reason);
  END IF;

  v_command := CASE v_desired
    WHEN 'locked' THEN 'lock'
    WHEN 'warning' THEN 'show_warning'
    WHEN 'released' THEN 'release_device'
    ELSE 'unlock' END;

  PERFORM public.enqueue_device_command(
    d.id, v_command,
    jsonb_build_object(
      'reason', v_reason, 'overdue_days', v_overdue, 'version', d.lock_version + 1,
      'provider', d.lock_provider,
      'tier', CASE v_desired WHEN 'warning' THEN 1 WHEN 'locked' THEN 2 ELSE NULL END,
      'status_color', public.device_status_color(v_desired)),
    'state:' || d.id::text || ':' || (d.lock_version + 1)::text
  );

  RETURN v_desired;
END;
$$;

-- Redefined: enrolment picks the lock provider by brand
CREATE OR REPLACE FUNCTION public.register_managed_device(p_delivery uuid, p_reference text)
RETURNS uuid
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  d public.delivery%ROWTYPE;
  v_imei text;
  v_brand text;
  v_id uuid;
  pol public.device_restriction_policy%ROWTYPE;
BEGIN
  SELECT * INTO d FROM public.delivery WHERE id = p_delivery;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'unknown delivery';
  END IF;

  SELECT it.imei, it.brand INTO v_imei, v_brand
    FROM public.loan_application AS la
    JOIN public.inventory_item AS it ON it.id = la.inventory_item_id
    WHERE la.id = d.application_id;

  INSERT INTO public.managed_device (delivery_id, application_id, contract_id, imei, mdm_enrollment_ref, lock_provider, mdm_provider)
  VALUES (d.id, d.application_id, d.contract_id, v_imei, p_reference,
          public.lock_provider_for_brand(v_brand), public.lock_provider_for_brand(v_brand))
  ON CONFLICT (delivery_id) DO NOTHING
  RETURNING id INTO v_id;

  IF v_id IS NULL THEN
    SELECT md.id INTO v_id FROM public.managed_device AS md WHERE md.delivery_id = d.id;
    RETURN v_id;
  END IF;

  SELECT * INTO pol FROM public.device_restriction_policy;
  PERFORM public.emit_device_event(v_id, 'enrolled', jsonb_build_object('reference', p_reference), NULL);
  PERFORM public.enqueue_device_command(v_id, 'apply_policy', to_jsonb(pol) - 'id' - 'updated_at' || jsonb_build_object('provider', public.lock_provider_for_brand(v_brand)), 'policy:' || v_id::text || ':initial');
  RETURN v_id;
END;
$$;

-- Redefined: contract generation writes the interest/principal split
CREATE OR REPLACE FUNCTION public.generate_contract(p_application uuid)
RETURNS uuid
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  a public.loan_application%ROWTYPE;
  it public.inventory_item%ROWTYPE;
  pol public.credit_policy%ROWTYPE;
  v_total numeric;
  v_end date;
  v_id uuid;
  v_number text;
  v_ratio numeric;
BEGIN
  SELECT * INTO a FROM public.loan_application WHERE id = p_application FOR UPDATE;
  IF NOT FOUND OR a.status::text <> 'approved' THEN
    RAISE EXCEPTION 'a contract can only be generated for an approved application';
  END IF;

  IF EXISTS (
    SELECT 1 FROM public.loan_contract AS lc
    WHERE lc.loan_application_id = a.id AND lc.status = 'active'
  ) THEN
    RAISE EXCEPTION 'this application already has an active contract';
  END IF;

  SELECT * INTO it FROM public.inventory_item WHERE id = a.inventory_item_id;
  SELECT * INTO pol FROM public.credit_policy;

  v_total := a.financed_amount + a.interest_amount;
  v_number := 'CT-' || to_char(now(), 'YYYY') || '-' || lpad(nextval('public.contract_number_seq')::text, 6, '0');

  SELECT max(p.due_date) INTO v_end
    FROM public.installment_plan(current_date, a.installment_frequency, a.duration_days, v_total) AS p;

  INSERT INTO public.loan_contract (
    loan_application_id, version, contract_number, start_date, end_date,
    interest_rate, total_payable, status, terms_snapshot
  ) VALUES (
    a.id, 1, v_number, current_date, v_end,
    pol.interest_rate_per_30_days, v_total, 'active',
    jsonb_build_object(
      'currency', 'USD',
      'imei', it.imei,
      'device', it.brand || ' ' || it.model,
      'penalty_rate_per_day', pol.penalty_rate_per_day,
      'grace_days', pol.grace_days,
      'lock_after_days_overdue', pol.lock_after_days_overdue,
      'remote_lock_accepted', public.has_required_consents(a.customer_id),
      'engine_policy_version', 'risk-engine-v1'
    )
  )
  RETURNING id INTO v_id;

  v_ratio := (a.processing_fee + a.interest_amount) / v_total;

  INSERT INTO public.repayment_schedule (
    contract_id, installment_number, due_date, amount_due, interest_fee_due, principal_due
  )
    SELECT v_id, p.installment_number, p.due_date, p.amount_due,
           round(p.amount_due * v_ratio, 2), p.amount_due - round(p.amount_due * v_ratio, 2)
    FROM public.installment_plan(current_date, a.installment_frequency, a.duration_days, v_total) AS p;

  -- Ledger booking: what the customer owes (principal + fee + interest)
  INSERT INTO public.ledger_entry (contract_id, entry_type, account_category, amount, description)
  VALUES (v_id, 'DEBIT', 'PRINCIPAL_RECEIVABLE', v_total, 'Contract booked: total due');

  RETURN v_id;
END;
$$;

-- Redefined: overdue checks ignore closed contract versions
CREATE OR REPLACE FUNCTION public.customer_has_overdue(p_customer uuid)
RETURNS boolean
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = ''
AS $$
  SELECT EXISTS (
    SELECT 1
    FROM public.loan_application AS la
    JOIN public.loan_contract AS lc ON lc.loan_application_id = la.id
    JOIN public.repayment_schedule AS rs ON rs.contract_id = lc.id
    WHERE la.customer_id = p_customer AND rs.status = 'overdue' AND lc.status = 'active'
  );
$$;

CREATE OR REPLACE FUNCTION public.agent_dashboard(p_agent uuid)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
STABLE
SET search_path = ''
AS $$
DECLARE
  t public.agent_target%ROWTYPE;
  v_apps integer;
  v_collected numeric;
BEGIN
  IF NOT EXISTS (SELECT 1 FROM public.app_user AS u WHERE u.id = p_agent AND u.status = 'active') THEN
    RAISE EXCEPTION 'agent not allowed' USING ERRCODE = '42501';
  END IF;

  SELECT * INTO t
    FROM public.agent_target
    WHERE agent_id = p_agent AND current_date BETWEEN period_start AND period_end
    ORDER BY period_start DESC LIMIT 1;

  IF FOUND THEN
    SELECT count(*) INTO v_apps
      FROM public.loan_application AS la
      WHERE la.created_by = p_agent
        AND la.created_at >= t.period_start AND la.created_at < t.period_end + 1;

    SELECT coalesce(sum(p.amount), 0) INTO v_collected
      FROM public.payment AS p
      WHERE p.collected_by = p_agent AND p.status IN ('pending', 'success')
        AND p.created_at >= t.period_start AND p.created_at < t.period_end + 1;
  END IF;

  RETURN jsonb_build_object(
    'currency', 'USD',
    'target', CASE WHEN t.id IS NULL THEN NULL ELSE jsonb_build_object(
      'period_start', t.period_start,
      'period_end', t.period_end,
      'target_applications', t.target_applications,
      'applications_done', v_apps,
      'target_collections_usd', t.target_collections_usd,
      'collections_done_usd', v_collected
    ) END,
    'applications_by_status', coalesce((
      SELECT jsonb_object_agg(s.status, s.n)
      FROM (
        SELECT la.status::text AS status, count(*) AS n
        FROM public.loan_application AS la
        WHERE la.created_by = p_agent
        GROUP BY la.status
      ) AS s
    ), '{}'::jsonb),
    'overdue_alerts', coalesce((
      SELECT jsonb_agg(x ORDER BY x.days_overdue DESC)
      FROM (
        SELECT
          cu.first_name || ' ' || cu.last_name AS customer,
          cu.phone_number,
          lc.contract_number,
          rs.installment_number,
          rs.due_date,
          (current_date - rs.due_date) AS days_overdue,
          (rs.amount_due - rs.amount_paid) AS outstanding_usd
        FROM public.repayment_schedule AS rs
        JOIN public.loan_contract AS lc ON lc.id = rs.contract_id
        JOIN public.loan_application AS la ON la.id = lc.loan_application_id
        JOIN public.customer AS cu ON cu.id = la.customer_id
        WHERE la.created_by = p_agent AND rs.status = 'overdue' AND lc.status = 'active'
        ORDER BY rs.due_date
        LIMIT 50
      ) AS x
    ), '[]'::jsonb)
  );
END;
$$;

-- Redefined: credit policy update knows penalty_cap_rate
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
    'burden_good', 'burden_ok', 'burden_max', 'penalty_cap_rate'
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
    burden_max = v_new.burden_max,
    penalty_cap_rate = v_new.penalty_cap_rate
  WHERE id = true;

  SELECT * INTO v_new FROM public.credit_policy;

  INSERT INTO public.credit_policy_history (changed_by, reason, state_before, state_after)
  VALUES (p_admin, btrim(p_reason), to_jsonb(v_old), to_jsonb(v_new));

  RETURN to_jsonb(v_new);
END;
$$;

-- 12. Row level security and privileges
ALTER TABLE merchant_account ENABLE ROW LEVEL SECURITY;
ALTER TABLE brand_lock_provider ENABLE ROW LEVEL SECURITY;
ALTER TABLE notification_outbox ENABLE ROW LEVEL SECURITY;
ALTER TABLE penalty ENABLE ROW LEVEL SECURITY;
ALTER TABLE payment_allocation ENABLE ROW LEVEL SECURITY;
ALTER TABLE collection_task ENABLE ROW LEVEL SECURITY;
ALTER TABLE closure_document ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS merchant_account_select ON merchant_account;
CREATE POLICY merchant_account_select ON merchant_account FOR SELECT TO authenticated
  USING (public.current_app_role() IS NOT NULL);

DROP POLICY IF EXISTS brand_lock_provider_select ON brand_lock_provider;
CREATE POLICY brand_lock_provider_select ON brand_lock_provider FOR SELECT TO authenticated
  USING (public.current_app_role() IN ('risk_analyst', 'system_admin'));

DROP POLICY IF EXISTS notification_outbox_select ON notification_outbox;
CREATE POLICY notification_outbox_select ON notification_outbox FOR SELECT TO authenticated
  USING (public.current_app_role() IN ('collections', 'risk_analyst', 'system_admin'));

DROP POLICY IF EXISTS penalty_select ON penalty;
CREATE POLICY penalty_select ON penalty FOR SELECT TO authenticated
  USING (EXISTS (SELECT 1 FROM public.loan_contract AS lc WHERE lc.id = contract_id));

DROP POLICY IF EXISTS payment_allocation_select ON payment_allocation;
CREATE POLICY payment_allocation_select ON payment_allocation FOR SELECT TO authenticated
  USING (EXISTS (SELECT 1 FROM public.payment AS p WHERE p.id = payment_id));

DROP POLICY IF EXISTS collection_task_select ON collection_task;
CREATE POLICY collection_task_select ON collection_task FOR SELECT TO authenticated
  USING (
    assigned_to = auth.uid()
    OR (public.current_app_role() <> 'client'
        AND EXISTS (SELECT 1 FROM public.loan_contract AS lc WHERE lc.id = contract_id))
  );

DROP POLICY IF EXISTS closure_document_select ON closure_document;
CREATE POLICY closure_document_select ON closure_document FOR SELECT TO authenticated
  USING (EXISTS (SELECT 1 FROM public.loan_contract AS lc WHERE lc.id = contract_id));

REVOKE ALL ON merchant_account, brand_lock_provider, notification_outbox, penalty, payment_allocation,
  collection_task, closure_document, device_unlock_sla FROM anon, authenticated;

GRANT SELECT ON merchant_account, brand_lock_provider, notification_outbox, penalty, payment_allocation,
  collection_task, closure_document TO authenticated;

GRANT SELECT ON merchant_account, brand_lock_provider TO service_role;
GRANT SELECT, INSERT, UPDATE ON notification_outbox, penalty, collection_task, closure_document TO service_role;
GRANT SELECT, INSERT ON payment_allocation TO service_role;
GRANT SELECT ON device_unlock_sla TO service_role;
GRANT USAGE ON SEQUENCE closure_document_number_seq TO service_role;
REVOKE INSERT, UPDATE, DELETE ON merchant_account, brand_lock_provider FROM service_role;

-- 13. Scheduling (pg_cron when available)
DO $$
BEGIN
  CREATE EXTENSION IF NOT EXISTS pg_cron;
  PERFORM cron.schedule('daily-collection-cycle', '30 0 * * *', 'SELECT public.run_daily_collection_cycle()');
  PERFORM cron.schedule('payment-reminders', '0 7 * * *', 'SELECT public.run_reminder_cycle()');
  PERFORM cron.schedule('hourly-device-evaluation', '5 * * * *', 'SELECT public.evaluate_all_devices()');
EXCEPTION WHEN OTHERS THEN
  RAISE NOTICE 'pg_cron is not available (%). Schedule run_daily_collection_cycle() at 00:30 UTC, run_reminder_cycle() at 07:00 UTC and evaluate_all_devices() hourly from your own scheduler.', SQLERRM;
END $$;

-- 14. Server-only functions
REVOKE EXECUTE ON FUNCTION public.device_status_color(text) FROM PUBLIC, anon, authenticated;
REVOKE EXECUTE ON FUNCTION public.lock_provider_for_brand(text) FROM PUBLIC, anon, authenticated;
REVOKE EXECUTE ON FUNCTION public.penalty_outstanding(uuid) FROM PUBLIC, anon, authenticated;
REVOKE EXECUTE ON FUNCTION public.enqueue_notification(uuid, uuid, text, text, jsonb, text) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.enqueue_notification(uuid, uuid, text, text, jsonb, text) TO service_role;
REVOKE EXECUTE ON FUNCTION public.evaluate_contract_devices(uuid, text, text, jsonb) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.evaluate_contract_devices(uuid, text, text, jsonb) TO service_role;
REVOKE EXECUTE ON FUNCTION public.ensure_collection_task(uuid, text) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.ensure_collection_task(uuid, text) TO service_role;
REVOKE EXECUTE ON FUNCTION public.close_collection_tasks(uuid, text) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.close_collection_tasks(uuid, text) TO service_role;
REVOKE EXECUTE ON FUNCTION public.assign_collection_task(uuid, uuid, uuid, text) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.assign_collection_task(uuid, uuid, uuid, text) TO service_role;
REVOKE EXECUTE ON FUNCTION public.complete_collection_task(uuid, uuid, text) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.complete_collection_task(uuid, uuid, text) TO service_role;
REVOKE EXECUTE ON FUNCTION public.schedule_payment_reminders() FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.schedule_payment_reminders() TO service_role;
REVOKE EXECUTE ON FUNCTION public.claim_notifications(integer) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.claim_notifications(integer) TO service_role;
REVOKE EXECUTE ON FUNCTION public.complete_notification(uuid, boolean, text, text) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.complete_notification(uuid, boolean, text, text) TO service_role;
REVOKE EXECUTE ON FUNCTION public.accrue_penalties(date) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.accrue_penalties(date) TO service_role;
REVOKE EXECUTE ON FUNCTION public.issue_closure_documents(uuid) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.issue_closure_documents(uuid) TO service_role;
REVOKE EXECUTE ON FUNCTION public.attach_closure_document(uuid, text, text) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.attach_closure_document(uuid, text, text) TO service_role;
REVOKE EXECUTE ON FUNCTION public.settle_contract_if_paid(uuid) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.settle_contract_if_paid(uuid) TO service_role;
REVOKE EXECUTE ON FUNCTION public.apply_payment(uuid) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.apply_payment(uuid) TO service_role;
REVOKE EXECUTE ON FUNCTION public.process_mobile_money_payment(text, text, uuid, numeric, text, jsonb, uuid) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.process_mobile_money_payment(text, text, uuid, numeric, text, jsonb, uuid) TO service_role;
REVOKE EXECUTE ON FUNCTION public.record_manual_mobile_money_payment(uuid, text, text, numeric, text, text) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.record_manual_mobile_money_payment(uuid, text, text, numeric, text, text) TO service_role;
REVOKE EXECUTE ON FUNCTION public.request_penalty_waiver(uuid, uuid, numeric, text) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.request_penalty_waiver(uuid, uuid, numeric, text) TO service_role;
REVOKE EXECUTE ON FUNCTION public.request_restructuring(uuid, uuid, integer, public.installment_frequency, boolean, text) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.request_restructuring(uuid, uuid, integer, public.installment_frequency, boolean, text) TO service_role;
REVOKE EXECUTE ON FUNCTION public.execute_penalty_waiver(uuid, numeric, uuid, uuid) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.execute_penalty_waiver(uuid, numeric, uuid, uuid) TO service_role;
REVOKE EXECUTE ON FUNCTION public.execute_restructuring(uuid, integer, public.installment_frequency, boolean, uuid, uuid) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.execute_restructuring(uuid, integer, public.installment_frequency, boolean, uuid, uuid) TO service_role;
REVOKE EXECUTE ON FUNCTION public.decide_approval(uuid, uuid, boolean, text) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.decide_approval(uuid, uuid, boolean, text) TO service_role;
REVOKE EXECUTE ON FUNCTION public.set_merchant_account(uuid, text, text, text, boolean) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.set_merchant_account(uuid, text, text, text, boolean) TO service_role;
REVOKE EXECUTE ON FUNCTION public.set_brand_lock_provider(uuid, text, text) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.set_brand_lock_provider(uuid, text, text) TO service_role;
REVOKE EXECUTE ON FUNCTION public.contract_collection_status(uuid) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.contract_collection_status(uuid) TO service_role;
REVOKE EXECUTE ON FUNCTION public.run_daily_collection_cycle() FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.run_daily_collection_cycle() TO service_role;
REVOKE EXECUTE ON FUNCTION public.run_reminder_cycle() FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.run_reminder_cycle() TO service_role;
REVOKE EXECUTE ON FUNCTION public.update_credit_policy(uuid, jsonb, text) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.update_credit_policy(uuid, jsonb, text) TO service_role;
REVOKE EXECUTE ON FUNCTION public.closure_document_guard_update() FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.device_status_color(text), public.lock_provider_for_brand(text), public.penalty_outstanding(uuid) TO service_role;
