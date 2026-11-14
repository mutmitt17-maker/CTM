/*
# Step 5: hardware security, MDM / Android Enterprise, remote lock orchestration

Plain English: the real enforcement (Device Owner, Knox Guard, locked bootloader, Verified Boot, Play Integrity) happens on the phone and at the vendors. The database is the brain: it knows each enrolled phone, what state it must be in, why, and it queues the commands the MDM integration must send. It never trusts the phone app alone.

1. Security policy (editable by the system administrator only)
- device_restriction_policy: what is forbidden (USB debugging, developer mode, factory reset, uninstall, unknown sources, root, unlocked bootloader, failed Verified Boot), attestation rhythm, SIM grace period, silence delay, maximum manual unlock time.
- update_device_policy(): same controls as the credit policy (permission policy.manage, reason required, history kept, direct UPDATE removed).
- Starting values are strict defaults and can be changed by the administrator.

2. Managed devices
- managed_device is created automatically when the server confirms the MDM enrolment (confirm_mdm_enrollment) and a first 'apply_policy' command is queued.
- register_oem_lock(): records the OEM layer (Knox Guard or equivalent) for the device.

3. Device reports and tamper detection
- record_device_report(): bootloader, Verified Boot, root, IMEI, USB debugging, developer mode, unknown sources, MDM compliance, SIM. Hard violations (unlocked bootloader, Verified Boot not green, root, IMEI changed) put the device on security hold = locked. Soft violations re-queue the policy.
- Remote attestation: enqueue_attestation_requests() issues one-time nonces; record_attestation() checks the nonce (no replay) and the integrity verdict. Repeated failures put the device on security hold.
- SIM change: the first SIM is registered at enrolment. A new SIM is 'pending': warning first, lock after the grace period, or at once if rejected. resolve_sim_change() is for supervisors (own agency) and risk analysts.

4. Lock orchestration
- evaluate_device_policy(): the only place that decides the state, from the contract's financial state, security hold, SIM and manual overrides. States: unlocked, warning, locked, released (loan paid off, device freed).
- Every change writes a device_event and queues ONE absolute command (lock, show_warning, unlock, release_device). Older queued state commands are superseded so a stale lock can never be sent after an unlock.
- device_command is an outbox with idempotency keys, retry with growing delay (5 tries), and a worker API (claim_device_commands, complete_device_command).
- Manual controls: collections can lock (contract.enforce); risk analysts can unlock for a limited time and clear a security hold (device.unlock). No other role can. A security hold cannot be bypassed by a manual unlock.

5. Payments to lock, end to end
- process_mobile_money_payment(): idempotent on the operator transaction id, every call logged in payment_webhook_log, oldest installment paid first, ledger entries (money in clearing, receivable reduced), contract closed and loan completed when fully paid, device state re-evaluated at once.
- A payment larger than what is owed is kept 'pending' for Finance (nothing is allocated).
- confirm_cash_payment(): Finance confirms a field cash payment; then same allocation and unlock logic.
- mark_overdue_installments() + evaluate_all_devices(): the periodic job (daily is the minimum, hourly is better).

6. Changes to existing objects
- generate_contract() now books the amount owed in the ledger (DEBIT PRINCIPAL_RECEIVABLE = total due). Existing contracts are backfilled. Income recognition for fee and interest is NOT posted: that is an accounting decision for Finance.
- confirm_mdm_enrollment() now creates the managed device.
- New permission device.sim.approve (agency_supervisor within own agency, risk_analyst).

Not in the database (must exist elsewhere)
- Android Enterprise / Zero-Touch provisioning, Knox Guard calls, Play Integrity token decoding, webhook signature checks, the worker that reads device_command and calls the MDM, and the scheduler that runs evaluate_all_devices().
*/


-- 0. Permissions
INSERT INTO role_permission (role, permission, agency_scoped) VALUES
  ('agency_supervisor', 'device.sim.approve', true),
  ('risk_analyst', 'device.sim.approve', false)
ON CONFLICT (role, permission) DO NOTHING;

-- 1. Policy
CREATE TABLE IF NOT EXISTS device_restriction_policy (
  id boolean PRIMARY KEY DEFAULT true CHECK (id),
  block_usb_debugging boolean NOT NULL DEFAULT true,
  block_developer_mode boolean NOT NULL DEFAULT true,
  block_factory_reset boolean NOT NULL DEFAULT true,
  block_uninstall boolean NOT NULL DEFAULT true,
  block_unknown_sources boolean NOT NULL DEFAULT true,
  block_root boolean NOT NULL DEFAULT true,
  require_locked_bootloader boolean NOT NULL DEFAULT true,
  require_verified_boot boolean NOT NULL DEFAULT true,
  require_oem_lock boolean NOT NULL DEFAULT true,
  attestation_interval_hours integer NOT NULL DEFAULT 24 CHECK (attestation_interval_hours > 0),
  max_attestation_failures integer NOT NULL DEFAULT 3 CHECK (max_attestation_failures > 0),
  heartbeat_max_silence_days integer NOT NULL DEFAULT 7 CHECK (heartbeat_max_silence_days > 0),
  sim_grace_hours integer NOT NULL DEFAULT 24 CHECK (sim_grace_hours >= 0),
  max_manual_unlock_hours integer NOT NULL DEFAULT 72 CHECK (max_manual_unlock_hours > 0),
  updated_at timestamptz NOT NULL DEFAULT now()
);

INSERT INTO device_restriction_policy (id) VALUES (true) ON CONFLICT (id) DO NOTHING;

CREATE TABLE IF NOT EXISTS device_policy_history (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  changed_by uuid NOT NULL REFERENCES app_user (id),
  reason text NOT NULL,
  state_before jsonb NOT NULL,
  state_after jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);

-- 2. Devices and their trail
CREATE TABLE IF NOT EXISTS managed_device (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  delivery_id uuid NOT NULL UNIQUE REFERENCES delivery (id),
  application_id uuid NOT NULL REFERENCES loan_application (id),
  contract_id uuid NOT NULL UNIQUE REFERENCES loan_contract (id),
  imei varchar(15) NOT NULL,
  mdm_provider varchar(30) NOT NULL DEFAULT 'android_enterprise',
  mdm_enrollment_ref varchar(100),
  enrolled_at timestamptz NOT NULL DEFAULT now(),
  lock_state varchar(10) NOT NULL DEFAULT 'unlocked' CHECK (lock_state IN ('unlocked', 'warning', 'locked', 'released')),
  lock_reason text,
  lock_version integer NOT NULL DEFAULT 0,
  lock_state_since timestamptz NOT NULL DEFAULT now(),
  security_hold boolean NOT NULL DEFAULT false,
  security_hold_reason text,
  override_state varchar(10) CHECK (override_state IS NULL OR override_state IN ('locked', 'unlocked')),
  override_until timestamptz,
  override_by uuid REFERENCES app_user (id),
  override_reason text,
  current_sim_hash varchar(128),
  bootloader_locked boolean,
  verified_boot_state varchar(20),
  reported_state jsonb,
  last_seen_at timestamptz,
  last_attestation_at timestamptz,
  last_attestation_ok boolean,
  attestation_failures integer NOT NULL DEFAULT 0 CHECK (attestation_failures >= 0),
  oem_lock_provider varchar(30),
  oem_lock_ref varchar(100),
  oem_lock_registered_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS managed_device_state_idx ON managed_device (lock_state);

CREATE TABLE IF NOT EXISTS device_sim (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  managed_device_id uuid NOT NULL REFERENCES managed_device (id),
  sim_hash varchar(128) NOT NULL,
  status varchar(10) NOT NULL CHECK (status IN ('authorized', 'pending', 'rejected')),
  first_seen_at timestamptz NOT NULL DEFAULT now(),
  decided_by uuid REFERENCES app_user (id),
  decided_at timestamptz,
  CONSTRAINT device_sim_unique UNIQUE (managed_device_id, sim_hash)
);

CREATE TABLE IF NOT EXISTS device_attestation (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  managed_device_id uuid NOT NULL REFERENCES managed_device (id),
  nonce varchar(64) NOT NULL,
  device_integrity boolean NOT NULL,
  basic_integrity boolean NOT NULL,
  app_recognized boolean NOT NULL,
  licensed boolean,
  passed boolean NOT NULL,
  raw_hash varchar(128),
  created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT device_attestation_nonce_unique UNIQUE (managed_device_id, nonce)
);

CREATE TABLE IF NOT EXISTS device_command (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  managed_device_id uuid NOT NULL REFERENCES managed_device (id),
  command_type varchar(25) NOT NULL CHECK (
    command_type IN ('apply_policy', 'lock', 'unlock', 'show_warning', 'release_device', 'request_attestation')
  ),
  payload jsonb NOT NULL DEFAULT '{}'::jsonb,
  idempotency_key varchar(150) NOT NULL UNIQUE,
  status varchar(12) NOT NULL DEFAULT 'queued' CHECK (status IN ('queued', 'sending', 'acked', 'failed', 'superseded')),
  attempts integer NOT NULL DEFAULT 0,
  next_attempt_at timestamptz NOT NULL DEFAULT now(),
  last_error text,
  provider_ref varchar(100),
  created_at timestamptz NOT NULL DEFAULT now(),
  sent_at timestamptz,
  acked_at timestamptz
);

CREATE INDEX IF NOT EXISTS device_command_queue_idx ON device_command (status, next_attempt_at);
CREATE INDEX IF NOT EXISTS device_command_device_idx ON device_command (managed_device_id, created_at DESC);

CREATE TABLE IF NOT EXISTS device_event (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  managed_device_id uuid NOT NULL REFERENCES managed_device (id),
  event_type varchar(40) NOT NULL,
  details jsonb NOT NULL DEFAULT '{}'::jsonb,
  actor_id uuid,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS device_event_device_idx ON device_event (managed_device_id, created_at DESC);

CREATE TABLE IF NOT EXISTS payment_webhook_log (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  provider varchar(30) NOT NULL,
  external_transaction_id varchar(100) NOT NULL,
  raw jsonb NOT NULL DEFAULT '{}'::jsonb,
  outcome varchar(30) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS payment_webhook_log_tx_idx ON payment_webhook_log (external_transaction_id);

DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['device_policy_history', 'device_attestation', 'device_event', 'payment_webhook_log']
  LOOP
    EXECUTE format('DROP TRIGGER IF EXISTS %I ON public.%I', t || '_block_change_trg', t);
    EXECUTE format(
      'CREATE TRIGGER %I BEFORE UPDATE OR DELETE ON public.%I FOR EACH ROW EXECUTE FUNCTION public.block_row_or_truncate()',
      t || '_block_change_trg', t
    );
    EXECUTE format('DROP TRIGGER IF EXISTS %I ON public.%I', t || '_block_truncate_trg', t);
    EXECUTE format(
      'CREATE TRIGGER %I BEFORE TRUNCATE ON public.%I FOR EACH STATEMENT EXECUTE FUNCTION public.block_row_or_truncate()',
      t || '_block_truncate_trg', t
    );
  END LOOP;
END $$;

DROP TRIGGER IF EXISTS managed_device_set_updated_at ON managed_device;
CREATE TRIGGER managed_device_set_updated_at BEFORE UPDATE ON managed_device
  FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

DROP TRIGGER IF EXISTS device_restriction_policy_set_updated_at ON device_restriction_policy;
CREATE TRIGGER device_restriction_policy_set_updated_at BEFORE UPDATE ON device_restriction_policy
  FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

DROP TRIGGER IF EXISTS device_restriction_policy_audit_trg ON device_restriction_policy;
CREATE TRIGGER device_restriction_policy_audit_trg AFTER INSERT OR UPDATE OR DELETE ON device_restriction_policy
  FOR EACH ROW EXECUTE FUNCTION public.audit_row_change();

DROP TRIGGER IF EXISTS managed_device_block_delete_trg ON managed_device;
CREATE TRIGGER managed_device_block_delete_trg BEFORE DELETE ON managed_device
  FOR EACH ROW EXECUTE FUNCTION public.block_row_or_truncate();

-- 3. Small helpers
CREATE OR REPLACE FUNCTION public.device_overdue_days(p_contract uuid)
RETURNS integer
LANGUAGE sql
STABLE
SET search_path = ''
AS $$
  SELECT coalesce(max(current_date - rs.due_date), 0)
  FROM public.repayment_schedule AS rs
  WHERE rs.contract_id = p_contract
    AND rs.amount_paid < rs.amount_due
    AND rs.due_date < current_date;
$$;

CREATE OR REPLACE FUNCTION public.contract_outstanding(p_contract uuid)
RETURNS numeric
LANGUAGE sql
STABLE
SET search_path = ''
AS $$
  SELECT coalesce(sum(rs.amount_due - rs.amount_paid), 0)
  FROM public.repayment_schedule AS rs
  WHERE rs.contract_id = p_contract;
$$;

CREATE OR REPLACE FUNCTION public.emit_device_event(p_device uuid, p_type text, p_details jsonb, p_actor uuid)
RETURNS void
LANGUAGE sql
SECURITY DEFINER
SET search_path = ''
AS $$
  INSERT INTO public.device_event (managed_device_id, event_type, details, actor_id)
  VALUES (p_device, p_type, coalesce(p_details, '{}'::jsonb), p_actor);
$$;

CREATE OR REPLACE FUNCTION public.enqueue_device_command(p_device uuid, p_type text, p_payload jsonb, p_key text)
RETURNS uuid
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  v_id uuid;
BEGIN
  IF p_type IN ('lock', 'unlock', 'show_warning', 'release_device') THEN
    UPDATE public.device_command
      SET status = 'superseded'
      WHERE managed_device_id = p_device
        AND status = 'queued'
        AND command_type IN ('lock', 'unlock', 'show_warning', 'release_device');
  END IF;

  INSERT INTO public.device_command (managed_device_id, command_type, payload, idempotency_key)
  VALUES (p_device, p_type, coalesce(p_payload, '{}'::jsonb), p_key)
  ON CONFLICT (idempotency_key) DO NOTHING
  RETURNING id INTO v_id;

  RETURN v_id;
END;
$$;

-- 4. The one place that decides the device state
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

  v_command := CASE v_desired
    WHEN 'locked' THEN 'lock'
    WHEN 'warning' THEN 'show_warning'
    WHEN 'released' THEN 'release_device'
    ELSE 'unlock' END;

  PERFORM public.enqueue_device_command(
    d.id, v_command,
    jsonb_build_object('reason', v_reason, 'overdue_days', v_overdue, 'version', d.lock_version + 1),
    'state:' || d.id::text || ':' || (d.lock_version + 1)::text
  );

  RETURN v_desired;
END;
$$;

-- 5. Enrolment hook, OEM lock
CREATE OR REPLACE FUNCTION public.register_managed_device(p_delivery uuid, p_reference text)
RETURNS uuid
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  d public.delivery%ROWTYPE;
  v_imei text;
  v_id uuid;
  pol public.device_restriction_policy%ROWTYPE;
BEGIN
  SELECT * INTO d FROM public.delivery WHERE id = p_delivery;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'unknown delivery';
  END IF;

  SELECT it.imei INTO v_imei
    FROM public.loan_application AS la
    JOIN public.inventory_item AS it ON it.id = la.inventory_item_id
    WHERE la.id = d.application_id;

  INSERT INTO public.managed_device (delivery_id, application_id, contract_id, imei, mdm_enrollment_ref)
  VALUES (d.id, d.application_id, d.contract_id, v_imei, p_reference)
  ON CONFLICT (delivery_id) DO NOTHING
  RETURNING id INTO v_id;

  IF v_id IS NULL THEN
    SELECT md.id INTO v_id FROM public.managed_device AS md WHERE md.delivery_id = d.id;
    RETURN v_id;
  END IF;

  SELECT * INTO pol FROM public.device_restriction_policy;
  PERFORM public.emit_device_event(v_id, 'enrolled', jsonb_build_object('reference', p_reference), NULL);
  PERFORM public.enqueue_device_command(v_id, 'apply_policy', to_jsonb(pol) - 'id' - 'updated_at', 'policy:' || v_id::text || ':initial');
  RETURN v_id;
END;
$$;

CREATE OR REPLACE FUNCTION public.register_oem_lock(p_device uuid, p_provider text, p_reference text)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
BEGIN
  UPDATE public.managed_device
    SET oem_lock_provider = p_provider, oem_lock_ref = p_reference, oem_lock_registered_at = now()
    WHERE id = p_device;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'unknown managed device';
  END IF;
  PERFORM public.emit_device_event(p_device, 'oem_lock_registered', jsonb_build_object('provider', p_provider), NULL);
END;
$$;

-- 6. Reports from the device (tamper, SIM)
CREATE OR REPLACE FUNCTION public.record_device_report(p_device uuid, p_report jsonb)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  d public.managed_device%ROWTYPE;
  pol public.device_restriction_policy%ROWTYPE;
  v_hard text[] := ARRAY[]::text[];
  v_soft text[] := ARRAY[]::text[];
  v_sim text;
  v_inserted integer;
  v_state text;
BEGIN
  SELECT * INTO d FROM public.managed_device WHERE id = p_device FOR UPDATE;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'unknown managed device';
  END IF;
  IF d.lock_state = 'released' THEN
    RETURN jsonb_build_object('state', 'released');
  END IF;
  SELECT * INTO pol FROM public.device_restriction_policy;

  IF NOT (p_report ? 'bootloader_locked' AND p_report ? 'verified_boot_state' AND p_report ? 'rooted') THEN
    v_soft := array_append(v_soft, 'report_incomplete');
  END IF;

  IF pol.require_locked_bootloader AND p_report ? 'bootloader_locked'
     AND (p_report ->> 'bootloader_locked')::boolean IS DISTINCT FROM true THEN
    v_hard := array_append(v_hard, 'bootloader_unlocked');
  END IF;
  IF pol.require_verified_boot AND p_report ? 'verified_boot_state'
     AND (p_report ->> 'verified_boot_state') IS DISTINCT FROM 'green' THEN
    v_hard := array_append(v_hard, 'verified_boot_failed');
  END IF;
  IF pol.block_root AND (p_report ->> 'rooted')::boolean IS TRUE THEN
    v_hard := array_append(v_hard, 'root_detected');
  END IF;
  IF p_report ? 'imei' AND (p_report ->> 'imei') IS DISTINCT FROM d.imei THEN
    v_hard := array_append(v_hard, 'imei_changed');
  END IF;

  IF pol.block_usb_debugging AND (p_report ->> 'usb_debugging')::boolean IS TRUE THEN
    v_soft := array_append(v_soft, 'usb_debugging_enabled');
  END IF;
  IF pol.block_developer_mode AND (p_report ->> 'developer_options')::boolean IS TRUE THEN
    v_soft := array_append(v_soft, 'developer_options_enabled');
  END IF;
  IF pol.block_unknown_sources AND (p_report ->> 'unknown_sources')::boolean IS TRUE THEN
    v_soft := array_append(v_soft, 'unknown_sources_enabled');
  END IF;
  IF (p_report ->> 'mdm_compliant')::boolean IS FALSE THEN
    v_soft := array_append(v_soft, 'mdm_noncompliant');
  END IF;

  UPDATE public.managed_device
    SET last_seen_at = now(),
        reported_state = p_report,
        bootloader_locked = coalesce((p_report ->> 'bootloader_locked')::boolean, bootloader_locked),
        verified_boot_state = coalesce(p_report ->> 'verified_boot_state', verified_boot_state)
    WHERE id = d.id;

  IF array_length(v_hard, 1) > 0 THEN
    UPDATE public.managed_device
      SET security_hold = true,
          security_hold_reason = array_to_string(v_hard, ', ')
      WHERE id = d.id;
    PERFORM public.emit_device_event(d.id, 'tamper_detected', jsonb_build_object('violations', to_jsonb(v_hard)), NULL);
  END IF;

  IF array_length(v_soft, 1) > 0 THEN
    PERFORM public.emit_device_event(d.id, 'policy_violation', jsonb_build_object('violations', to_jsonb(v_soft)), NULL);
    PERFORM public.enqueue_device_command(
      d.id, 'apply_policy', to_jsonb(pol) - 'id' - 'updated_at',
      'policy:' || d.id::text || ':' || to_char(now(), 'YYYYMMDDHH24'));
  END IF;

  v_sim := nullif(p_report ->> 'sim_hash', '');
  IF v_sim IS NOT NULL THEN
    IF NOT EXISTS (SELECT 1 FROM public.device_sim AS s WHERE s.managed_device_id = d.id) THEN
      INSERT INTO public.device_sim (managed_device_id, sim_hash, status, decided_at)
      VALUES (d.id, v_sim, 'authorized', now());
      PERFORM public.emit_device_event(d.id, 'sim_registered', '{}'::jsonb, NULL);
    ELSE
      INSERT INTO public.device_sim (managed_device_id, sim_hash, status)
      VALUES (d.id, v_sim, 'pending')
      ON CONFLICT (managed_device_id, sim_hash) DO NOTHING;
      GET DIAGNOSTICS v_inserted = ROW_COUNT;
      IF v_inserted > 0 THEN
        PERFORM public.emit_device_event(d.id, 'sim_changed', '{}'::jsonb, NULL);
      END IF;
    END IF;
    UPDATE public.managed_device SET current_sim_hash = v_sim WHERE id = d.id;
  END IF;

  v_state := public.evaluate_device_policy(d.id, 'device_report');

  RETURN jsonb_build_object(
    'state', v_state,
    'hard_violations', to_jsonb(v_hard),
    'soft_violations', to_jsonb(v_soft)
  );
END;
$$;

-- 7. Remote attestation
CREATE OR REPLACE FUNCTION public.enqueue_attestation_requests()
RETURNS integer
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  pol public.device_restriction_policy%ROWTYPE;
  r record;
  v_count integer := 0;
  v_id uuid;
BEGIN
  SELECT * INTO pol FROM public.device_restriction_policy;
  FOR r IN
    SELECT md.id
    FROM public.managed_device AS md
    WHERE md.lock_state <> 'released'
      AND (md.last_attestation_at IS NULL OR md.last_attestation_at < now() - make_interval(hours => pol.attestation_interval_hours))
  LOOP
    v_id := public.enqueue_device_command(
      r.id, 'request_attestation',
      jsonb_build_object('nonce', gen_random_uuid()::text),
      'att:' || r.id::text || ':' || floor(extract(epoch FROM now()) / (pol.attestation_interval_hours * 3600))::bigint::text
    );
    IF v_id IS NOT NULL THEN
      v_count := v_count + 1;
    END IF;
  END LOOP;
  RETURN v_count;
END;
$$;

CREATE OR REPLACE FUNCTION public.record_attestation(
  p_device uuid,
  p_nonce text,
  p_device_integrity boolean,
  p_basic_integrity boolean,
  p_app_recognized boolean,
  p_licensed boolean,
  p_raw_hash text
)
RETURNS boolean
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  d public.managed_device%ROWTYPE;
  pol public.device_restriction_policy%ROWTYPE;
  v_passed boolean;
  v_failures integer;
BEGIN
  SELECT * INTO d FROM public.managed_device WHERE id = p_device FOR UPDATE;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'unknown managed device';
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM public.device_command AS c
    WHERE c.managed_device_id = d.id AND c.command_type = 'request_attestation' AND c.payload ->> 'nonce' = p_nonce
  ) THEN
    RAISE EXCEPTION 'unknown attestation nonce';
  END IF;

  SELECT * INTO pol FROM public.device_restriction_policy;
  v_passed := p_device_integrity AND p_basic_integrity AND p_app_recognized;

  INSERT INTO public.device_attestation (
    managed_device_id, nonce, device_integrity, basic_integrity, app_recognized, licensed, passed, raw_hash
  ) VALUES (d.id, p_nonce, p_device_integrity, p_basic_integrity, p_app_recognized, p_licensed, v_passed, p_raw_hash);

  v_failures := CASE WHEN v_passed THEN 0 ELSE d.attestation_failures + 1 END;
  UPDATE public.managed_device
    SET last_attestation_at = now(), last_attestation_ok = v_passed, attestation_failures = v_failures
    WHERE id = d.id;

  PERFORM public.emit_device_event(d.id, 'attestation_result', jsonb_build_object('passed', v_passed, 'failures', v_failures), NULL);

  IF NOT v_passed AND v_failures >= pol.max_attestation_failures THEN
    UPDATE public.managed_device
      SET security_hold = true, security_hold_reason = 'attestation_failed'
      WHERE id = d.id;
    PERFORM public.emit_device_event(d.id, 'tamper_detected', jsonb_build_object('violations', jsonb_build_array('attestation_failed')), NULL);
  END IF;

  PERFORM public.evaluate_device_policy(d.id, 'attestation');
  RETURN v_passed;
END;
$$;

-- 8. Periodic job
CREATE OR REPLACE FUNCTION public.mark_overdue_installments()
RETURNS integer
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  v_count integer;
BEGIN
  UPDATE public.repayment_schedule AS rs
    SET status = 'overdue'
    FROM public.loan_contract AS lc
    WHERE lc.id = rs.contract_id AND lc.status = 'active'
      AND rs.due_date < current_date
      AND rs.amount_paid < rs.amount_due
      AND rs.status IN ('pending', 'partial');
  GET DIAGNOSTICS v_count = ROW_COUNT;
  RETURN v_count;
END;
$$;

CREATE OR REPLACE FUNCTION public.evaluate_all_devices()
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  r record;
  v_before text;
  v_after text;
  v_evaluated integer := 0;
  v_changed integer := 0;
  v_marked integer;
  v_att integer;
BEGIN
  v_marked := public.mark_overdue_installments();

  FOR r IN SELECT md.id, md.lock_state FROM public.managed_device AS md WHERE md.lock_state <> 'released' ORDER BY md.id
  LOOP
    v_before := r.lock_state;
    v_after := public.evaluate_device_policy(r.id, 'periodic');
    v_evaluated := v_evaluated + 1;
    IF v_after IS DISTINCT FROM v_before THEN
      v_changed := v_changed + 1;
    END IF;
  END LOOP;

  v_att := public.enqueue_attestation_requests();

  RETURN jsonb_build_object(
    'overdue_marked', v_marked, 'devices_evaluated', v_evaluated,
    'state_changes', v_changed, 'attestation_requests', v_att);
END;
$$;

-- 9. Command outbox worker API
CREATE OR REPLACE FUNCTION public.claim_device_commands(p_limit integer, p_worker text)
RETURNS SETOF public.device_command
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
BEGIN
  UPDATE public.device_command
    SET status = 'queued'
    WHERE status = 'sending' AND sent_at < now() - interval '10 minutes';

  RETURN QUERY
  WITH picked AS (
    SELECT c.id
    FROM public.device_command AS c
    WHERE c.status = 'queued' AND c.next_attempt_at <= now()
    ORDER BY c.created_at
    LIMIT greatest(p_limit, 1)
    FOR UPDATE SKIP LOCKED
  ),
  updated AS (
    UPDATE public.device_command AS c
      SET status = 'sending', attempts = c.attempts + 1, sent_at = now()
      FROM picked
      WHERE c.id = picked.id
      RETURNING c.*
  )
  SELECT * FROM updated;
END;
$$;

CREATE OR REPLACE FUNCTION public.complete_device_command(p_id uuid, p_ok boolean, p_error text, p_reference text)
RETURNS text
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  c public.device_command%ROWTYPE;
  v_status text;
BEGIN
  SELECT * INTO c FROM public.device_command WHERE id = p_id FOR UPDATE;
  IF NOT FOUND OR c.status <> 'sending' THEN
    RETURN coalesce(c.status, 'unknown');
  END IF;

  IF p_ok THEN
    v_status := 'acked';
    UPDATE public.device_command
      SET status = 'acked', acked_at = now(), provider_ref = p_reference, last_error = NULL
      WHERE id = c.id;
    PERFORM public.emit_device_event(c.managed_device_id, 'command_acked',
      jsonb_build_object('command', c.command_type, 'command_id', c.id), NULL);
  ELSIF c.attempts >= 5 THEN
    v_status := 'failed';
    UPDATE public.device_command SET status = 'failed', last_error = p_error WHERE id = c.id;
    PERFORM public.emit_device_event(c.managed_device_id, 'command_failed',
      jsonb_build_object('command', c.command_type, 'command_id', c.id, 'error', p_error), NULL);
  ELSE
    v_status := 'queued';
    UPDATE public.device_command
      SET status = 'queued', last_error = p_error,
          next_attempt_at = now() + (power(2, c.attempts)::integer * interval '1 minute')
      WHERE id = c.id;
  END IF;
  RETURN v_status;
END;
$$;

-- 10. Payments: webhook to ledger to lock decision
CREATE OR REPLACE FUNCTION public.apply_payment(p_payment uuid)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  pay public.payment%ROWTYPE;
  v_contract public.loan_contract%ROWTYPE;
  r public.repayment_schedule%ROWTYPE;
  v_left numeric;
  v_apply numeric;
  v_outstanding numeric;
  v_cat public.ledger_account_category;
  v_dev uuid;
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

  FOR r IN
    SELECT * FROM public.repayment_schedule AS rs
    WHERE rs.contract_id = pay.contract_id AND rs.amount_paid < rs.amount_due
    ORDER BY rs.installment_number
    FOR UPDATE
  LOOP
    EXIT WHEN v_left <= 0;
    v_apply := least(v_left, r.amount_due - r.amount_paid);
    UPDATE public.repayment_schedule
      SET amount_paid = r.amount_paid + v_apply,
          status = (CASE
            WHEN r.amount_paid + v_apply >= r.amount_due THEN 'paid'
            WHEN r.due_date < current_date THEN 'overdue'
            ELSE 'partial' END)::public.repayment_status
      WHERE id = r.id;
    v_left := v_left - v_apply;
  END LOOP;

  IF v_left > 0 THEN
    RAISE EXCEPTION 'payment exceeds the outstanding balance';
  END IF;

  v_cat := (CASE WHEN pay.payment_method = 'cash' THEN 'CUSTOMER_CASH' ELSE 'MOBILE_MONEY_CLEARING' END)::public.ledger_account_category;

  INSERT INTO public.ledger_entry (contract_id, payment_id, entry_type, account_category, amount, description)
  VALUES
    (pay.contract_id, pay.id, 'DEBIT', v_cat, pay.amount, 'Payment received ' || pay.external_transaction_id),
    (pay.contract_id, pay.id, 'CREDIT', 'PRINCIPAL_RECEIVABLE', pay.amount, 'Receivable reduced by payment ' || pay.external_transaction_id);

  v_outstanding := public.contract_outstanding(pay.contract_id);

  IF v_outstanding = 0 AND v_contract.status = 'active' THEN
    UPDATE public.loan_contract SET status = 'closed' WHERE id = v_contract.id;
    UPDATE public.loan_application
      SET status = 'completed'
      WHERE id = v_contract.loan_application_id AND status::text = 'active';
  END IF;

  FOR v_dev IN SELECT md.id FROM public.managed_device AS md WHERE md.contract_id = pay.contract_id
  LOOP
    PERFORM public.emit_device_event(v_dev, 'payment_received',
      jsonb_build_object('payment_id', pay.id, 'amount', pay.amount, 'outstanding', v_outstanding), NULL);
    PERFORM public.evaluate_device_policy(v_dev, 'payment_received');
  END LOOP;

  RETURN jsonb_build_object('applied', true, 'outstanding', v_outstanding);
END;
$$;

CREATE OR REPLACE FUNCTION public.process_mobile_money_payment(
  p_provider text,
  p_external_id text,
  p_contract uuid,
  p_amount numeric,
  p_method text,
  p_raw jsonb
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
BEGIN
  IF p_external_id IS NULL OR btrim(p_external_id) = '' THEN
    RAISE EXCEPTION 'external transaction id is required';
  END IF;
  v_method := p_method::public.payment_method;
  IF v_method = 'cash' THEN
    RAISE EXCEPTION 'cash payments go through the field cash flow';
  END IF;

  PERFORM pg_advisory_xact_lock(hashtextextended('pay:' || p_external_id, 0));

  SELECT * INTO pay FROM public.payment WHERE external_transaction_id = p_external_id;
  IF FOUND THEN
    INSERT INTO public.payment_webhook_log (provider, external_transaction_id, raw, outcome)
    VALUES (p_provider, p_external_id, coalesce(p_raw, '{}'::jsonb), 'duplicate');
    RETURN jsonb_build_object('status', 'duplicate', 'payment_id', pay.id, 'payment_status', pay.status);
  END IF;

  SELECT lc.status INTO v_contract_status FROM public.loan_contract AS lc WHERE lc.id = p_contract;
  IF NOT FOUND THEN
    INSERT INTO public.payment_webhook_log (provider, external_transaction_id, raw, outcome)
    VALUES (p_provider, p_external_id, coalesce(p_raw, '{}'::jsonb), 'unknown_contract');
    RETURN jsonb_build_object('status', 'unknown_contract');
  END IF;
  IF v_contract_status <> 'active' THEN
    INSERT INTO public.payment_webhook_log (provider, external_transaction_id, raw, outcome)
    VALUES (p_provider, p_external_id, coalesce(p_raw, '{}'::jsonb), 'contract_not_active');
    RETURN jsonb_build_object('status', 'contract_not_active');
  END IF;

  v_outstanding := public.contract_outstanding(p_contract);
  IF p_amount > v_outstanding THEN
    INSERT INTO public.payment (contract_id, external_transaction_id, amount, payment_method, status)
    VALUES (p_contract, p_external_id, p_amount, v_method, 'pending')
    RETURNING id INTO v_id;
    INSERT INTO public.payment_webhook_log (provider, external_transaction_id, raw, outcome)
    VALUES (p_provider, p_external_id, coalesce(p_raw, '{}'::jsonb), 'needs_review');
    RETURN jsonb_build_object('status', 'needs_review', 'reason', 'amount_exceeds_outstanding',
                              'payment_id', v_id, 'outstanding', v_outstanding);
  END IF;

  INSERT INTO public.payment (contract_id, external_transaction_id, amount, payment_method, status)
  VALUES (p_contract, p_external_id, p_amount, v_method, 'success')
  RETURNING id INTO v_id;

  v_apply := public.apply_payment(v_id);

  INSERT INTO public.payment_webhook_log (provider, external_transaction_id, raw, outcome)
  VALUES (p_provider, p_external_id, coalesce(p_raw, '{}'::jsonb), 'applied');

  RETURN jsonb_build_object('status', 'applied', 'payment_id', v_id) || v_apply;
END;
$$;

CREATE OR REPLACE FUNCTION public.confirm_cash_payment(p_actor uuid, p_payment uuid)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  pay public.payment%ROWTYPE;
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM public.app_user AS u
    JOIN public.role_permission AS rp ON rp.role = u.role
    WHERE u.id = p_actor AND u.status = 'active' AND rp.permission = 'payment.reconcile'
  ) THEN
    RAISE EXCEPTION 'only Finance can confirm a cash payment' USING ERRCODE = '42501';
  END IF;

  SELECT * INTO pay FROM public.payment WHERE id = p_payment FOR UPDATE;
  IF NOT FOUND OR pay.payment_method <> 'cash' OR pay.status <> 'pending' THEN
    RAISE EXCEPTION 'not a pending cash payment';
  END IF;
  IF pay.collected_by IS NOT DISTINCT FROM p_actor THEN
    RAISE EXCEPTION 'the collector cannot confirm their own cash payment' USING ERRCODE = '42501';
  END IF;
  IF pay.amount > public.contract_outstanding(pay.contract_id) THEN
    RAISE EXCEPTION 'amount exceeds the outstanding balance';
  END IF;

  PERFORM set_config('app.actor_id', p_actor::text, true);
  UPDATE public.payment SET status = 'success' WHERE id = pay.id;
  RETURN public.apply_payment(pay.id);
END;
$$;

-- 11. Human controls
CREATE OR REPLACE FUNCTION public.manual_lock_device(p_actor uuid, p_device uuid, p_reason text)
RETURNS text
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
BEGIN
  PERFORM set_config('app.actor_id', p_actor::text, true);
  IF coalesce(btrim(p_reason), '') = '' THEN
    RAISE EXCEPTION 'a reason is required';
  END IF;
  IF NOT public.abac_allows('contract.enforce') THEN
    RAISE EXCEPTION 'not allowed to lock a device' USING ERRCODE = '42501';
  END IF;

  UPDATE public.managed_device
    SET override_state = 'locked', override_until = NULL, override_by = p_actor, override_reason = btrim(p_reason)
    WHERE id = p_device AND lock_state <> 'released';
  IF NOT FOUND THEN
    RAISE EXCEPTION 'unknown or released device';
  END IF;

  PERFORM public.emit_device_event(p_device, 'manual_lock', jsonb_build_object('reason', btrim(p_reason)), p_actor);
  RETURN public.evaluate_device_policy(p_device, 'manual_lock');
END;
$$;

CREATE OR REPLACE FUNCTION public.manual_unlock_device(p_actor uuid, p_device uuid, p_hours integer, p_reason text)
RETURNS text
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  d public.managed_device%ROWTYPE;
  pol public.device_restriction_policy%ROWTYPE;
BEGIN
  PERFORM set_config('app.actor_id', p_actor::text, true);
  IF coalesce(btrim(p_reason), '') = '' THEN
    RAISE EXCEPTION 'a reason is required';
  END IF;
  IF NOT public.abac_allows('device.unlock') THEN
    RAISE EXCEPTION 'not allowed to unlock a device' USING ERRCODE = '42501';
  END IF;

  SELECT * INTO pol FROM public.device_restriction_policy;
  IF p_hours IS NULL OR p_hours < 1 OR p_hours > pol.max_manual_unlock_hours THEN
    RAISE EXCEPTION 'unlock duration must be between 1 and % hours', pol.max_manual_unlock_hours;
  END IF;

  SELECT * INTO d FROM public.managed_device WHERE id = p_device FOR UPDATE;
  IF NOT FOUND OR d.lock_state = 'released' THEN
    RAISE EXCEPTION 'unknown or released device';
  END IF;
  IF d.security_hold THEN
    RAISE EXCEPTION 'this device is on security hold: clear the hold first';
  END IF;

  UPDATE public.managed_device
    SET override_state = 'unlocked', override_until = now() + make_interval(hours => p_hours),
        override_by = p_actor, override_reason = btrim(p_reason)
    WHERE id = d.id;

  PERFORM public.emit_device_event(d.id, 'manual_unlock',
    jsonb_build_object('reason', btrim(p_reason), 'hours', p_hours), p_actor);
  RETURN public.evaluate_device_policy(d.id, 'manual_unlock');
END;
$$;

CREATE OR REPLACE FUNCTION public.clear_security_hold(p_actor uuid, p_device uuid, p_reason text)
RETURNS text
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
BEGIN
  PERFORM set_config('app.actor_id', p_actor::text, true);
  IF coalesce(btrim(p_reason), '') = '' THEN
    RAISE EXCEPTION 'a reason is required';
  END IF;
  IF NOT public.abac_allows('device.unlock') THEN
    RAISE EXCEPTION 'not allowed to clear a security hold' USING ERRCODE = '42501';
  END IF;

  UPDATE public.managed_device
    SET security_hold = false, security_hold_reason = NULL, attestation_failures = 0
    WHERE id = p_device AND lock_state <> 'released';
  IF NOT FOUND THEN
    RAISE EXCEPTION 'unknown or released device';
  END IF;

  PERFORM public.emit_device_event(p_device, 'security_hold_cleared', jsonb_build_object('reason', btrim(p_reason)), p_actor);
  RETURN public.evaluate_device_policy(p_device, 'security_hold_cleared');
END;
$$;

CREATE OR REPLACE FUNCTION public.resolve_sim_change(
  p_actor uuid,
  p_device uuid,
  p_sim_hash text,
  p_approve boolean,
  p_reason text
)
RETURNS text
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  v_agency uuid;
BEGIN
  PERFORM set_config('app.actor_id', p_actor::text, true);
  IF coalesce(btrim(p_reason), '') = '' THEN
    RAISE EXCEPTION 'a reason is required';
  END IF;

  SELECT la.agency_id INTO v_agency
    FROM public.managed_device AS md
    JOIN public.loan_application AS la ON la.id = md.application_id
    WHERE md.id = p_device;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'unknown managed device';
  END IF;
  IF NOT public.abac_allows('device.sim.approve', NULL, v_agency) THEN
    RAISE EXCEPTION 'not allowed to decide on this SIM' USING ERRCODE = '42501';
  END IF;

  UPDATE public.device_sim
    SET status = CASE WHEN p_approve THEN 'authorized' ELSE 'rejected' END,
        decided_by = p_actor, decided_at = now()
    WHERE managed_device_id = p_device AND sim_hash = p_sim_hash AND status = 'pending';
  IF NOT FOUND THEN
    RAISE EXCEPTION 'no pending SIM with this hash';
  END IF;

  PERFORM public.emit_device_event(p_device, CASE WHEN p_approve THEN 'sim_approved' ELSE 'sim_rejected' END,
    jsonb_build_object('reason', btrim(p_reason)), p_actor);
  RETURN public.evaluate_device_policy(p_device, 'sim_decision');
END;
$$;

-- 12. Policy change by the administrator only
CREATE OR REPLACE FUNCTION public.update_device_policy(p_admin uuid, p_changes jsonb, p_reason text)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  editable text[] := ARRAY[
    'block_usb_debugging', 'block_developer_mode', 'block_factory_reset', 'block_uninstall',
    'block_unknown_sources', 'block_root', 'require_locked_bootloader', 'require_verified_boot',
    'require_oem_lock', 'attestation_interval_hours', 'max_attestation_failures',
    'heartbeat_max_silence_days', 'sim_grace_hours', 'max_manual_unlock_hours'
  ];
  bad text;
  v_old public.device_restriction_policy%ROWTYPE;
  v_new public.device_restriction_policy%ROWTYPE;
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM public.app_user AS u
    JOIN public.role_permission AS rp ON rp.role = u.role
    WHERE u.id = p_admin AND u.status = 'active' AND rp.permission = 'policy.manage'
  ) THEN
    RAISE EXCEPTION 'only the system administrator can change the device policy' USING ERRCODE = '42501';
  END IF;
  IF p_changes IS NULL OR jsonb_typeof(p_changes) <> 'object' OR p_changes = '{}'::jsonb THEN
    RAISE EXCEPTION 'no changes provided';
  END IF;
  IF coalesce(btrim(p_reason), '') = '' THEN
    RAISE EXCEPTION 'a reason is required';
  END IF;

  SELECT k INTO bad FROM jsonb_object_keys(p_changes) AS k WHERE k <> ALL (editable) LIMIT 1;
  IF bad IS NOT NULL THEN
    RAISE EXCEPTION 'unknown or read-only setting: %', bad;
  END IF;

  PERFORM set_config('app.actor_id', p_admin::text, true);
  SELECT * INTO v_old FROM public.device_restriction_policy FOR UPDATE;
  v_new := jsonb_populate_record(v_old, p_changes);

  UPDATE public.device_restriction_policy SET
    block_usb_debugging = v_new.block_usb_debugging,
    block_developer_mode = v_new.block_developer_mode,
    block_factory_reset = v_new.block_factory_reset,
    block_uninstall = v_new.block_uninstall,
    block_unknown_sources = v_new.block_unknown_sources,
    block_root = v_new.block_root,
    require_locked_bootloader = v_new.require_locked_bootloader,
    require_verified_boot = v_new.require_verified_boot,
    require_oem_lock = v_new.require_oem_lock,
    attestation_interval_hours = v_new.attestation_interval_hours,
    max_attestation_failures = v_new.max_attestation_failures,
    heartbeat_max_silence_days = v_new.heartbeat_max_silence_days,
    sim_grace_hours = v_new.sim_grace_hours,
    max_manual_unlock_hours = v_new.max_manual_unlock_hours
  WHERE id = true;

  SELECT * INTO v_new FROM public.device_restriction_policy;
  INSERT INTO public.device_policy_history (changed_by, reason, state_before, state_after)
  VALUES (p_admin, btrim(p_reason), to_jsonb(v_old), to_jsonb(v_new));

  RETURN to_jsonb(v_new);
END;
$$;

CREATE OR REPLACE VIEW device_compliance_gaps
WITH (security_invoker = true) AS
  SELECT
    md.id AS managed_device_id,
    md.contract_id,
    md.lock_state,
    md.security_hold,
    (md.oem_lock_registered_at IS NULL) AS oem_lock_missing,
    (md.last_attestation_at IS NULL OR md.last_attestation_ok IS NOT TRUE) AS attestation_not_ok,
    (md.last_seen_at IS NULL) AS never_reported,
    md.last_seen_at
  FROM managed_device AS md
  WHERE md.lock_state <> 'released'
    AND (md.oem_lock_registered_at IS NULL
         OR md.last_attestation_at IS NULL OR md.last_attestation_ok IS NOT TRUE
         OR md.last_seen_at IS NULL OR md.security_hold);

-- Redefined: contract generation now books the receivable
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

  INSERT INTO public.repayment_schedule (contract_id, installment_number, due_date, amount_due)
    SELECT v_id, p.installment_number, p.due_date, p.amount_due
    FROM public.installment_plan(current_date, a.installment_frequency, a.duration_days, v_total) AS p;

  -- Ledger booking: what the customer owes (principal + fee + interest)
  INSERT INTO public.ledger_entry (contract_id, entry_type, account_category, amount, description)
  VALUES (v_id, 'DEBIT', 'PRINCIPAL_RECEIVABLE', v_total, 'Contract booked: total due');

  RETURN v_id;
END;
$$;

-- Redefined: enrolment confirmation now creates the managed device
CREATE OR REPLACE FUNCTION public.confirm_mdm_enrollment(p_delivery uuid, p_enrolled boolean, p_reference text)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  d public.delivery%ROWTYPE;
BEGIN
  SELECT * INTO d FROM public.delivery WHERE id = p_delivery FOR UPDATE;
  IF NOT FOUND OR d.status <> 'pending' OR d.mdm_status <> 'token_requested' THEN
    RAISE EXCEPTION 'no pending enrolment for this delivery';
  END IF;

  UPDATE public.delivery
    SET mdm_status = CASE WHEN p_enrolled THEN 'enrolled' ELSE 'failed' END,
        mdm_enrollment_ref = p_reference,
        mdm_updated_at = now()
    WHERE id = d.id;

  IF p_enrolled THEN
    PERFORM public.register_managed_device(d.id, p_reference);
  END IF;
END;
$$;


-- 13. Backfills for contracts and deliveries that already exist
INSERT INTO ledger_entry (contract_id, entry_type, account_category, amount, description)
SELECT lc.id, 'DEBIT', 'PRINCIPAL_RECEIVABLE', lc.total_payable, 'Contract booked: total due (backfill)'
FROM loan_contract AS lc
WHERE lc.total_payable > 0
  AND NOT EXISTS (
    SELECT 1 FROM ledger_entry AS le
    WHERE le.contract_id = lc.id AND le.account_category = 'PRINCIPAL_RECEIVABLE' AND le.entry_type = 'DEBIT'
  );

SELECT public.register_managed_device(d.id, d.mdm_enrollment_ref)
FROM delivery AS d
WHERE d.mdm_status = 'enrolled';

-- 14. Row level security and privileges
ALTER TABLE device_restriction_policy ENABLE ROW LEVEL SECURITY;
ALTER TABLE device_policy_history ENABLE ROW LEVEL SECURITY;
ALTER TABLE managed_device ENABLE ROW LEVEL SECURITY;
ALTER TABLE device_sim ENABLE ROW LEVEL SECURITY;
ALTER TABLE device_attestation ENABLE ROW LEVEL SECURITY;
ALTER TABLE device_command ENABLE ROW LEVEL SECURITY;
ALTER TABLE device_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE payment_webhook_log ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS device_restriction_policy_select ON device_restriction_policy;
CREATE POLICY device_restriction_policy_select ON device_restriction_policy FOR SELECT TO authenticated
  USING (public.current_app_role() IN ('risk_analyst', 'system_admin', 'agency_supervisor'));

DROP POLICY IF EXISTS device_policy_history_select ON device_policy_history;
CREATE POLICY device_policy_history_select ON device_policy_history FOR SELECT TO authenticated
  USING (public.abac_allows('audit.read'));

DROP POLICY IF EXISTS managed_device_select ON managed_device;
CREATE POLICY managed_device_select ON managed_device FOR SELECT TO authenticated
  USING (
    public.current_app_role() <> 'client'
    AND EXISTS (SELECT 1 FROM public.loan_application AS la WHERE la.id = application_id)
  );

DROP POLICY IF EXISTS device_sim_select ON device_sim;
CREATE POLICY device_sim_select ON device_sim FOR SELECT TO authenticated
  USING (EXISTS (SELECT 1 FROM public.managed_device AS md WHERE md.id = managed_device_id));

DROP POLICY IF EXISTS device_event_select ON device_event;
CREATE POLICY device_event_select ON device_event FOR SELECT TO authenticated
  USING (EXISTS (SELECT 1 FROM public.managed_device AS md WHERE md.id = managed_device_id));

DROP POLICY IF EXISTS device_command_select ON device_command;
CREATE POLICY device_command_select ON device_command FOR SELECT TO authenticated
  USING (public.abac_allows('audit.read'));

DROP POLICY IF EXISTS device_attestation_select ON device_attestation;
CREATE POLICY device_attestation_select ON device_attestation FOR SELECT TO authenticated
  USING (public.abac_allows('audit.read'));

DROP POLICY IF EXISTS payment_webhook_log_select ON payment_webhook_log;
CREATE POLICY payment_webhook_log_select ON payment_webhook_log FOR SELECT TO authenticated
  USING (public.abac_allows('payment.reconcile') OR public.abac_allows('audit.read'));

REVOKE ALL ON device_restriction_policy, device_policy_history, managed_device, device_sim,
  device_attestation, device_command, device_event, payment_webhook_log, device_compliance_gaps
  FROM anon, authenticated;

GRANT SELECT ON device_restriction_policy, device_policy_history, managed_device, device_sim,
  device_attestation, device_command, device_event, payment_webhook_log TO authenticated;

GRANT SELECT ON device_restriction_policy TO service_role;
GRANT SELECT, INSERT ON device_policy_history, device_attestation, device_event, payment_webhook_log TO service_role;
GRANT SELECT, INSERT, UPDATE ON managed_device, device_sim, device_command TO service_role;
GRANT SELECT ON device_compliance_gaps TO service_role;

-- 15. Server-only functions
REVOKE EXECUTE ON FUNCTION public.device_overdue_days(uuid) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.device_overdue_days(uuid) TO service_role;
REVOKE EXECUTE ON FUNCTION public.contract_outstanding(uuid) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.contract_outstanding(uuid) TO service_role;
REVOKE EXECUTE ON FUNCTION public.emit_device_event(uuid, text, jsonb, uuid) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.emit_device_event(uuid, text, jsonb, uuid) TO service_role;
REVOKE EXECUTE ON FUNCTION public.enqueue_device_command(uuid, text, jsonb, text) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.enqueue_device_command(uuid, text, jsonb, text) TO service_role;
REVOKE EXECUTE ON FUNCTION public.evaluate_device_policy(uuid, text) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.evaluate_device_policy(uuid, text) TO service_role;
REVOKE EXECUTE ON FUNCTION public.register_managed_device(uuid, text) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.register_managed_device(uuid, text) TO service_role;
REVOKE EXECUTE ON FUNCTION public.register_oem_lock(uuid, text, text) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.register_oem_lock(uuid, text, text) TO service_role;
REVOKE EXECUTE ON FUNCTION public.record_device_report(uuid, jsonb) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.record_device_report(uuid, jsonb) TO service_role;
REVOKE EXECUTE ON FUNCTION public.enqueue_attestation_requests() FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.enqueue_attestation_requests() TO service_role;
REVOKE EXECUTE ON FUNCTION public.record_attestation(uuid, text, boolean, boolean, boolean, boolean, text) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.record_attestation(uuid, text, boolean, boolean, boolean, boolean, text) TO service_role;
REVOKE EXECUTE ON FUNCTION public.mark_overdue_installments() FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.mark_overdue_installments() TO service_role;
REVOKE EXECUTE ON FUNCTION public.evaluate_all_devices() FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.evaluate_all_devices() TO service_role;
REVOKE EXECUTE ON FUNCTION public.claim_device_commands(integer, text) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.claim_device_commands(integer, text) TO service_role;
REVOKE EXECUTE ON FUNCTION public.complete_device_command(uuid, boolean, text, text) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.complete_device_command(uuid, boolean, text, text) TO service_role;
REVOKE EXECUTE ON FUNCTION public.apply_payment(uuid) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.apply_payment(uuid) TO service_role;
REVOKE EXECUTE ON FUNCTION public.process_mobile_money_payment(text, text, uuid, numeric, text, jsonb) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.process_mobile_money_payment(text, text, uuid, numeric, text, jsonb) TO service_role;
REVOKE EXECUTE ON FUNCTION public.confirm_cash_payment(uuid, uuid) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.confirm_cash_payment(uuid, uuid) TO service_role;
REVOKE EXECUTE ON FUNCTION public.manual_lock_device(uuid, uuid, text) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.manual_lock_device(uuid, uuid, text) TO service_role;
REVOKE EXECUTE ON FUNCTION public.manual_unlock_device(uuid, uuid, integer, text) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.manual_unlock_device(uuid, uuid, integer, text) TO service_role;
REVOKE EXECUTE ON FUNCTION public.clear_security_hold(uuid, uuid, text) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.clear_security_hold(uuid, uuid, text) TO service_role;
REVOKE EXECUTE ON FUNCTION public.resolve_sim_change(uuid, uuid, text, boolean, text) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.resolve_sim_change(uuid, uuid, text, boolean, text) TO service_role;
REVOKE EXECUTE ON FUNCTION public.update_device_policy(uuid, jsonb, text) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.update_device_policy(uuid, jsonb, text) TO service_role;
REVOKE INSERT, UPDATE, DELETE ON device_restriction_policy FROM service_role;
