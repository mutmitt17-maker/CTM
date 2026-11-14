/*
# Step 2: authentication data model, RBAC + ABAC, immutable audit, device security, USD

Plain English: adds the database half of step 2. Staff and customer profiles linked to Supabase Auth, the 8 roles with a permission matrix, zone/agency/amount/hour checks, row level security per role, anti-fraud counters (OTP, lockout, SIM swap), dual approval, separation of duties, an append-only audit log, and the USD rule.

1. Roles and profiles
- app_role: client, field_agent, distributor, agency_supervisor, risk_analyst, collections, finance, system_admin
- agency (store / zone), app_user (one named account per person, linked to auth.users)
- Staff accounts cannot be 'active' without MFA (passkey or TOTP only, never SMS)
- Agency-scoped roles must have an agency; clients must be linked to a customer

2. RBAC + ABAC
- role_permission: permission matrix with optional max amount (USD), agency scope and allowed hours
- abac_allows(permission, amount, agency): single check used by backend and policies
- field_agent and distributor can never hold device.unlock or schedule.modify (CHECK)

3. Row level security
- authenticated users get SELECT only, filtered by role, agency or ownership. All writes stay on the server (service_role) behind the API gateway.

4. Anti-fraud
- otp_challenge (max 5 attempts), account lockout after 5 failures, user_device + refresh_session (device-bound), SIM swap / new device detection that locks the account for 24h, security_event (append-only), ip_allowlist

5. Governance
- approval_request: dual validation (approver must differ from requester and hold approval.decide)
- loan_application gets created_by / approved_by / handed_over_by with separation-of-duties checks
- audit_log: append-only, actor, role, IP, device fingerprint, before/after state, attached to sensitive tables

6. Currency
- All money is USD. exchange_rate holds display-only rates (never USD itself) for the front-end.

Notes
- OIDC SSO, WebAuthn enrolment, SMS/WhatsApp delivery, HLR lookups, API gateway rate limiting, TLS/HSTS/CSP and AES-256 at rest live outside the database. This migration provides the tables and rules they rely on.
*/

-- 0. Types
DO $$ BEGIN
  CREATE TYPE app_role AS ENUM (
    'client', 'field_agent', 'distributor', 'agency_supervisor',
    'risk_analyst', 'collections', 'finance', 'system_admin'
  );
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
  CREATE TYPE user_status AS ENUM ('pending', 'active', 'locked', 'suspended');
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
  CREATE TYPE mfa_method AS ENUM ('passkey', 'totp');
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
  CREATE TYPE otp_channel AS ENUM ('sms', 'whatsapp');
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
  CREATE TYPE device_status AS ENUM ('trusted', 'pending', 'revoked');
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
  CREATE TYPE approval_kind AS ENUM (
    'loan_restructure', 'penalty_waiver', 'scoring_exception', 'payment_cancellation'
  );
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
  CREATE TYPE approval_status AS ENUM ('pending', 'approved', 'rejected');
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

-- 1. Agencies and profiles
CREATE TABLE IF NOT EXISTS agency (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  name varchar(150) NOT NULL UNIQUE,
  region varchar(100),
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS app_user (
  id uuid PRIMARY KEY REFERENCES auth.users (id) ON DELETE RESTRICT,
  role app_role NOT NULL,
  status user_status NOT NULL DEFAULT 'pending',
  customer_id uuid UNIQUE REFERENCES customer (id),
  agency_id uuid REFERENCES agency (id),
  zone varchar(100),
  sso_subject varchar(255) UNIQUE,
  mfa_method mfa_method,
  failed_attempts integer NOT NULL DEFAULT 0 CHECK (failed_attempts >= 0),
  locked_until timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT app_user_client_has_customer CHECK (role <> 'client' OR customer_id IS NOT NULL),
  CONSTRAINT app_user_staff_needs_mfa CHECK (role = 'client' OR status <> 'active' OR mfa_method IS NOT NULL),
  CONSTRAINT app_user_scoped_role_has_agency CHECK (
    role NOT IN ('field_agent', 'distributor', 'agency_supervisor') OR agency_id IS NOT NULL
  )
);

CREATE INDEX IF NOT EXISTS app_user_agency_id_idx ON app_user (agency_id);
CREATE INDEX IF NOT EXISTS app_user_role_idx ON app_user (role);

DROP TRIGGER IF EXISTS app_user_set_updated_at ON app_user;
CREATE TRIGGER app_user_set_updated_at BEFORE UPDATE ON app_user
  FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

-- Zone / agency / actor attributes on business tables (ABAC inputs)
ALTER TABLE customer ADD COLUMN IF NOT EXISTS agency_id uuid REFERENCES agency (id);
ALTER TABLE inventory_item ADD COLUMN IF NOT EXISTS agency_id uuid REFERENCES agency (id);
ALTER TABLE loan_application ADD COLUMN IF NOT EXISTS agency_id uuid REFERENCES agency (id);
ALTER TABLE loan_application ADD COLUMN IF NOT EXISTS created_by uuid REFERENCES app_user (id);
ALTER TABLE loan_application ADD COLUMN IF NOT EXISTS approved_by uuid REFERENCES app_user (id);
ALTER TABLE loan_application ADD COLUMN IF NOT EXISTS handed_over_by uuid REFERENCES app_user (id);

DO $$ BEGIN
  ALTER TABLE loan_application ADD CONSTRAINT loan_application_creator_not_approver
    CHECK (approved_by IS NULL OR created_by IS NULL OR approved_by <> created_by);
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
  ALTER TABLE loan_application ADD CONSTRAINT loan_application_handover_not_approver
    CHECK (handed_over_by IS NULL OR approved_by IS NULL OR handed_over_by <> approved_by);
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
  ALTER TABLE loan_application ADD CONSTRAINT loan_application_handover_not_creator
    CHECK (handed_over_by IS NULL OR created_by IS NULL OR handed_over_by <> created_by);
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

CREATE INDEX IF NOT EXISTS customer_agency_id_idx ON customer (agency_id);
CREATE INDEX IF NOT EXISTS inventory_item_agency_id_idx ON inventory_item (agency_id);
CREATE INDEX IF NOT EXISTS loan_application_agency_id_idx ON loan_application (agency_id);

-- 2. Permission matrix (RBAC) with ABAC attributes
CREATE TABLE IF NOT EXISTS role_permission (
  role app_role NOT NULL,
  permission varchar(80) NOT NULL,
  max_amount_usd numeric(14,2) CHECK (max_amount_usd IS NULL OR max_amount_usd > 0),
  agency_scoped boolean NOT NULL DEFAULT false,
  allowed_from time,
  allowed_to time,
  PRIMARY KEY (role, permission),
  CONSTRAINT role_permission_hours_pair CHECK ((allowed_from IS NULL) = (allowed_to IS NULL)),
  CONSTRAINT role_permission_least_privilege CHECK (
    role NOT IN ('field_agent', 'distributor')
    OR permission NOT IN ('device.unlock', 'schedule.modify')
  )
);

INSERT INTO role_permission (role, permission, agency_scoped) VALUES
  ('client', 'customer.read_own', false),
  ('client', 'payment.create_own', false),
  ('client', 'contract.download_own', false),
  ('field_agent', 'prospect.create', true),
  ('field_agent', 'kyc.collect', true),
  ('field_agent', 'credit.simulate', true),
  ('field_agent', 'application.submit', true),
  ('distributor', 'catalogue.read', true),
  ('distributor', 'imei.reserve', true),
  ('distributor', 'deposit.collect', true),
  ('agency_supervisor', 'application.approve', true),
  ('agency_supervisor', 'application.reject', true),
  ('agency_supervisor', 'agent.manage', true),
  ('agency_supervisor', 'loan.restructure.request', true),
  ('risk_analyst', 'application.approve', false),
  ('risk_analyst', 'application.reject', false),
  ('risk_analyst', 'scoring.adjust', false),
  ('risk_analyst', 'exception.review', false),
  ('risk_analyst', 'customer.blacklist', false),
  ('risk_analyst', 'device.unlock', false),
  ('risk_analyst', 'approval.decide', false),
  ('risk_analyst', 'audit.read', false),
  ('collections', 'collection.schedule', false),
  ('collections', 'collection.assign_visit', false),
  ('collections', 'contract.enforce', false),
  ('collections', 'penalty.waive.request', false),
  ('finance', 'payment.reconcile', false),
  ('finance', 'ledger.export', false),
  ('finance', 'payment.cancel.request', false),
  ('finance', 'approval.decide', false),
  ('system_admin', 'user.manage', false),
  ('system_admin', 'role.manage', false),
  ('system_admin', 'secret.manage', false),
  ('system_admin', 'integration.manage', false),
  ('system_admin', 'audit.read', false)
ON CONFLICT (role, permission) DO NOTHING;

-- 3. Device security, OTP, sessions
CREATE TABLE IF NOT EXISTS user_device (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid NOT NULL REFERENCES app_user (id),
  device_fingerprint varchar(128) NOT NULL,
  imei varchar(15),
  sim_serial varchar(32),
  status device_status NOT NULL DEFAULT 'pending',
  first_seen_at timestamptz NOT NULL DEFAULT now(),
  last_seen_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT user_device_unique UNIQUE (user_id, device_fingerprint)
);

CREATE TABLE IF NOT EXISTS refresh_session (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid NOT NULL REFERENCES app_user (id),
  device_id uuid NOT NULL REFERENCES user_device (id),
  token_hash varchar(128) NOT NULL UNIQUE,
  expires_at timestamptz NOT NULL,
  revoked_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS refresh_session_user_id_idx ON refresh_session (user_id);

CREATE TABLE IF NOT EXISTS otp_challenge (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  phone_number varchar(20) NOT NULL,
  channel otp_channel NOT NULL DEFAULT 'sms',
  code_hash varchar(128) NOT NULL,
  attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
  expires_at timestamptz NOT NULL,
  consumed_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS otp_challenge_phone_idx ON otp_challenge (phone_number, created_at DESC);

CREATE TABLE IF NOT EXISTS security_event (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid REFERENCES app_user (id),
  event_type varchar(50) NOT NULL,
  details jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS security_event_user_id_idx ON security_event (user_id, created_at DESC);

CREATE TABLE IF NOT EXISTS ip_allowlist (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  network cidr NOT NULL UNIQUE,
  label varchar(100) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);

-- 4. Dual validation
CREATE TABLE IF NOT EXISTS approval_request (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  kind approval_kind NOT NULL,
  target_table varchar(63) NOT NULL,
  target_id uuid NOT NULL,
  payload jsonb NOT NULL DEFAULT '{}'::jsonb,
  reason text NOT NULL,
  requested_by uuid NOT NULL REFERENCES app_user (id),
  approved_by uuid REFERENCES app_user (id),
  status approval_status NOT NULL DEFAULT 'pending',
  created_at timestamptz NOT NULL DEFAULT now(),
  decided_at timestamptz,
  CONSTRAINT approval_request_two_people CHECK (approved_by IS NULL OR approved_by <> requested_by),
  CONSTRAINT approval_request_decision_complete CHECK (
    (status = 'pending' AND approved_by IS NULL AND decided_at IS NULL)
    OR (status <> 'pending' AND approved_by IS NOT NULL AND decided_at IS NOT NULL)
  )
);

CREATE INDEX IF NOT EXISTS approval_request_status_idx ON approval_request (status, kind);

-- 5. Audit log (append-only)
CREATE TABLE IF NOT EXISTS audit_log (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  actor_id uuid,
  actor_role app_role,
  ip_address inet,
  device_fingerprint varchar(128),
  action varchar(10) NOT NULL,
  table_name varchar(63) NOT NULL,
  record_id uuid,
  state_before jsonb,
  state_after jsonb,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS audit_log_actor_idx ON audit_log (actor_id, created_at DESC);
CREATE INDEX IF NOT EXISTS audit_log_record_idx ON audit_log (table_name, record_id);

-- 6. Currency: USD is the only stored currency; rates are display-only
CREATE TABLE IF NOT EXISTS exchange_rate (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  currency_code char(3) NOT NULL CHECK (currency_code ~ '^[A-Z]{3}$' AND currency_code <> 'USD'),
  rate_per_usd numeric(18,6) NOT NULL CHECK (rate_per_usd > 0),
  effective_date date NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT exchange_rate_unique_day UNIQUE (currency_code, effective_date)
);

DO $$
DECLARE r record;
BEGIN
  FOR r IN
    SELECT table_name, column_name
    FROM information_schema.columns
    WHERE table_schema = 'public'
      AND data_type = 'numeric'
      AND numeric_precision = 14 AND numeric_scale = 2
  LOOP
    EXECUTE format('COMMENT ON COLUMN public.%I.%I IS %L', r.table_name, r.column_name, 'Amount in USD (system currency)');
  END LOOP;
END $$;

-- 7. Helper functions
CREATE OR REPLACE FUNCTION public.current_actor_id()
RETURNS uuid
LANGUAGE sql
STABLE
SET search_path = ''
AS $$
  SELECT coalesce(auth.uid(), nullif(current_setting('app.actor_id', true), '')::uuid);
$$;

CREATE OR REPLACE FUNCTION public.current_app_role()
RETURNS public.app_role
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = ''
AS $$
  SELECT u.role
  FROM public.app_user AS u
  WHERE u.id = public.current_actor_id()
    AND u.status = 'active'
    AND (u.locked_until IS NULL OR u.locked_until < now());
$$;

CREATE OR REPLACE FUNCTION public.current_agency_id()
RETURNS uuid
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = ''
AS $$
  SELECT u.agency_id FROM public.app_user AS u WHERE u.id = public.current_actor_id();
$$;

CREATE OR REPLACE FUNCTION public.current_customer_id()
RETURNS uuid
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = ''
AS $$
  SELECT u.customer_id FROM public.app_user AS u WHERE u.id = public.current_actor_id();
$$;

CREATE OR REPLACE FUNCTION public.abac_allows(
  p_permission text,
  p_amount numeric DEFAULT NULL,
  p_agency uuid DEFAULT NULL
)
RETURNS boolean
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = ''
AS $$
  SELECT EXISTS (
    SELECT 1
    FROM public.app_user AS u
    JOIN public.role_permission AS rp ON rp.role = u.role
    WHERE u.id = public.current_actor_id()
      AND u.status = 'active'
      AND (u.locked_until IS NULL OR u.locked_until < now())
      AND rp.permission = p_permission
      AND (rp.max_amount_usd IS NULL OR p_amount IS NULL OR p_amount <= rp.max_amount_usd)
      AND (NOT rp.agency_scoped OR (u.agency_id IS NOT NULL AND u.agency_id = p_agency))
      AND (
        rp.allowed_from IS NULL
        OR (timezone('Africa/Lubumbashi', now()))::time BETWEEN rp.allowed_from AND rp.allowed_to
      )
  );
$$;

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
    WHERE la.customer_id = p_customer AND rs.status = 'overdue'
  );
$$;

CREATE OR REPLACE FUNCTION public.is_ip_allowed(p_ip inet)
RETURNS boolean
LANGUAGE sql
STABLE
SET search_path = ''
AS $$
  SELECT EXISTS (SELECT 1 FROM public.ip_allowlist AS a WHERE p_ip <<= a.network);
$$;

-- 8. Anti-fraud functions (server side only)
CREATE OR REPLACE FUNCTION public.register_failed_auth(p_user uuid)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  new_count integer;
BEGIN
  UPDATE public.app_user
    SET failed_attempts = failed_attempts + 1
    WHERE id = p_user
    RETURNING failed_attempts INTO new_count;

  IF new_count >= 5 THEN
    UPDATE public.app_user
      SET status = 'locked', locked_until = now() + interval '30 minutes'
      WHERE id = p_user AND status = 'active';
    INSERT INTO public.security_event (user_id, event_type, details)
      VALUES (p_user, 'account_locked', jsonb_build_object('reason', 'too_many_failed_attempts', 'attempts', new_count));
  END IF;
END;
$$;

CREATE OR REPLACE FUNCTION public.register_successful_auth(p_user uuid)
RETURNS void
LANGUAGE sql
SECURITY DEFINER
SET search_path = ''
AS $$
  UPDATE public.app_user
    SET failed_attempts = 0
    WHERE id = p_user AND (locked_until IS NULL OR locked_until < now());
$$;

CREATE OR REPLACE FUNCTION public.verify_otp_challenge(p_challenge uuid, p_code_hash text)
RETURNS boolean
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  c public.otp_challenge%ROWTYPE;
  linked_user uuid;
BEGIN
  SELECT * INTO c FROM public.otp_challenge WHERE id = p_challenge FOR UPDATE;

  IF NOT FOUND OR c.consumed_at IS NOT NULL OR c.expires_at < now() OR c.attempts >= 5 THEN
    RETURN false;
  END IF;

  IF c.code_hash = p_code_hash THEN
    UPDATE public.otp_challenge SET consumed_at = now() WHERE id = c.id;
    RETURN true;
  END IF;

  UPDATE public.otp_challenge SET attempts = attempts + 1 WHERE id = c.id;

  SELECT u.id INTO linked_user
    FROM public.app_user AS u
    JOIN public.customer AS cu ON cu.id = u.customer_id
    WHERE cu.phone_number = c.phone_number;

  IF linked_user IS NOT NULL THEN
    PERFORM public.register_failed_auth(linked_user);
  END IF;

  RETURN false;
END;
$$;

CREATE OR REPLACE FUNCTION public.report_device_change(
  p_user uuid,
  p_fingerprint text,
  p_imei text,
  p_sim_serial text
)
RETURNS text
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  d public.user_device%ROWTYPE;
  reason text;
BEGIN
  SELECT * INTO d
    FROM public.user_device
    WHERE user_id = p_user AND device_fingerprint = p_fingerprint AND status = 'trusted';

  IF FOUND THEN
    IF d.imei IS DISTINCT FROM p_imei OR d.sim_serial IS DISTINCT FROM p_sim_serial THEN
      reason := CASE WHEN d.sim_serial IS DISTINCT FROM p_sim_serial THEN 'sim_swap' ELSE 'imei_change' END;
    ELSE
      UPDATE public.user_device SET last_seen_at = now() WHERE id = d.id;
      RETURN 'ok';
    END IF;
  ELSIF NOT EXISTS (
    SELECT 1 FROM public.user_device WHERE user_id = p_user AND status = 'trusted'
  ) THEN
    INSERT INTO public.user_device (user_id, device_fingerprint, imei, sim_serial, status)
      VALUES (p_user, p_fingerprint, p_imei, p_sim_serial, 'trusted')
      ON CONFLICT (user_id, device_fingerprint) DO UPDATE
        SET imei = EXCLUDED.imei, sim_serial = EXCLUDED.sim_serial, status = 'trusted', last_seen_at = now();
    RETURN 'enrolled';
  ELSE
    reason := 'new_device';
  END IF;

  INSERT INTO public.user_device (user_id, device_fingerprint, imei, sim_serial, status)
    VALUES (p_user, p_fingerprint, p_imei, p_sim_serial, 'pending')
    ON CONFLICT (user_id, device_fingerprint) DO UPDATE SET last_seen_at = now();

  UPDATE public.app_user
    SET status = 'locked', locked_until = now() + interval '24 hours'
    WHERE id = p_user AND status = 'active';

  UPDATE public.refresh_session
    SET revoked_at = now()
    WHERE user_id = p_user AND revoked_at IS NULL;

  INSERT INTO public.security_event (user_id, event_type, details)
    VALUES (p_user, reason, jsonb_build_object('device_fingerprint', p_fingerprint, 'imei', p_imei, 'sim_serial', p_sim_serial));

  RETURN 'locked';
END;
$$;

-- 9. Dual validation guard
CREATE OR REPLACE FUNCTION public.approval_request_guard()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = ''
AS $$
BEGIN
  IF NEW.kind IS DISTINCT FROM OLD.kind
     OR NEW.target_table IS DISTINCT FROM OLD.target_table
     OR NEW.target_id IS DISTINCT FROM OLD.target_id
     OR NEW.payload IS DISTINCT FROM OLD.payload
     OR NEW.reason IS DISTINCT FROM OLD.reason
     OR NEW.requested_by IS DISTINCT FROM OLD.requested_by
     OR NEW.created_at IS DISTINCT FROM OLD.created_at
  THEN
    RAISE EXCEPTION 'approval_request content is immutable once created';
  END IF;

  IF OLD.status <> 'pending' THEN
    RAISE EXCEPTION 'approval_request already decided (%)', OLD.status;
  END IF;

  IF NEW.status <> 'pending' THEN
    IF NEW.approved_by IS NULL OR NEW.approved_by = NEW.requested_by THEN
      RAISE EXCEPTION 'approval requires a second person, different from the requester';
    END IF;
    IF NOT EXISTS (
      SELECT 1
      FROM public.app_user AS u
      JOIN public.role_permission AS rp ON rp.role = u.role
      WHERE u.id = NEW.approved_by AND u.status = 'active' AND rp.permission = 'approval.decide'
    ) THEN
      RAISE EXCEPTION 'approver does not hold approval.decide';
    END IF;
    NEW.decided_at := now();
  END IF;

  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS approval_request_guard_trg ON approval_request;
CREATE TRIGGER approval_request_guard_trg BEFORE UPDATE ON approval_request
  FOR EACH ROW EXECUTE FUNCTION public.approval_request_guard();

DROP TRIGGER IF EXISTS approval_request_block_delete_trg ON approval_request;
CREATE TRIGGER approval_request_block_delete_trg BEFORE DELETE ON approval_request
  FOR EACH ROW EXECUTE FUNCTION public.block_row_or_truncate();

-- 10. Audit trigger and immutability
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

  rec_id := (CASE WHEN TG_OP = 'DELETE' THEN to_jsonb(OLD) ELSE to_jsonb(NEW) END ->> 'id')::uuid;

  INSERT INTO public.audit_log (actor_id, actor_role, ip_address, device_fingerprint, action, table_name, record_id, state_before, state_after)
  VALUES (
    actor, actor_r, ip, left(fp, 128), TG_OP, TG_TABLE_NAME, rec_id,
    CASE WHEN TG_OP IN ('UPDATE', 'DELETE') THEN to_jsonb(OLD) END,
    CASE WHEN TG_OP IN ('INSERT', 'UPDATE') THEN to_jsonb(NEW) END
  );
  RETURN NULL;
END;
$$;

DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY[
    'customer', 'inventory_item', 'loan_application', 'loan_contract',
    'repayment_schedule', 'payment', 'app_user', 'role_permission',
    'approval_request', 'exchange_rate', 'ip_allowlist'
  ]
  LOOP
    EXECUTE format('DROP TRIGGER IF EXISTS %I ON public.%I', t || '_audit_trg', t);
    EXECUTE format(
      'CREATE TRIGGER %I AFTER INSERT OR UPDATE OR DELETE ON public.%I FOR EACH ROW EXECUTE FUNCTION public.audit_row_change()',
      t || '_audit_trg', t
    );
  END LOOP;
END $$;

DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['audit_log', 'security_event']
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

-- 11. Row level security
ALTER TABLE agency ENABLE ROW LEVEL SECURITY;
ALTER TABLE app_user ENABLE ROW LEVEL SECURITY;
ALTER TABLE role_permission ENABLE ROW LEVEL SECURITY;
ALTER TABLE user_device ENABLE ROW LEVEL SECURITY;
ALTER TABLE refresh_session ENABLE ROW LEVEL SECURITY;
ALTER TABLE otp_challenge ENABLE ROW LEVEL SECURITY;
ALTER TABLE security_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE ip_allowlist ENABLE ROW LEVEL SECURITY;
ALTER TABLE approval_request ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE exchange_rate ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS agency_select ON agency;
CREATE POLICY agency_select ON agency FOR SELECT TO authenticated
  USING (public.current_app_role() IS NOT NULL);

DROP POLICY IF EXISTS exchange_rate_select ON exchange_rate;
CREATE POLICY exchange_rate_select ON exchange_rate FOR SELECT TO authenticated
  USING (public.current_app_role() IS NOT NULL);

DROP POLICY IF EXISTS app_user_select ON app_user;
CREATE POLICY app_user_select ON app_user FOR SELECT TO authenticated
  USING (
    id = auth.uid()
    OR public.current_app_role() = 'system_admin'
    OR (public.current_app_role() = 'agency_supervisor' AND agency_id = public.current_agency_id())
  );

DROP POLICY IF EXISTS audit_log_select ON audit_log;
CREATE POLICY audit_log_select ON audit_log FOR SELECT TO authenticated
  USING (public.abac_allows('audit.read'));

DROP POLICY IF EXISTS approval_request_select ON approval_request;
CREATE POLICY approval_request_select ON approval_request FOR SELECT TO authenticated
  USING (
    requested_by = auth.uid()
    OR public.abac_allows('approval.decide')
    OR public.current_app_role() = 'system_admin'
  );

DROP POLICY IF EXISTS customer_select ON customer;
CREATE POLICY customer_select ON customer FOR SELECT TO authenticated
  USING (
    (public.current_app_role() = 'client' AND id = public.current_customer_id())
    OR (public.current_app_role() IN ('field_agent', 'distributor', 'agency_supervisor')
        AND agency_id = public.current_agency_id())
    OR public.current_app_role() = 'risk_analyst'
    OR (public.current_app_role() = 'collections' AND public.customer_has_overdue(id))
  );

DROP POLICY IF EXISTS inventory_item_select ON inventory_item;
CREATE POLICY inventory_item_select ON inventory_item FOR SELECT TO authenticated
  USING (
    (public.current_app_role() IN ('field_agent', 'distributor', 'agency_supervisor')
     AND agency_id = public.current_agency_id())
    OR public.current_app_role() IN ('risk_analyst', 'finance')
  );

DROP POLICY IF EXISTS loan_application_select ON loan_application;
CREATE POLICY loan_application_select ON loan_application FOR SELECT TO authenticated
  USING (
    (public.current_app_role() = 'client' AND customer_id = public.current_customer_id())
    OR (public.current_app_role() IN ('field_agent', 'distributor', 'agency_supervisor')
        AND agency_id = public.current_agency_id())
    OR public.current_app_role() = 'risk_analyst'
    OR (public.current_app_role() = 'collections' AND public.customer_has_overdue(customer_id))
  );

DROP POLICY IF EXISTS loan_contract_select ON loan_contract;
CREATE POLICY loan_contract_select ON loan_contract FOR SELECT TO authenticated
  USING (
    public.current_app_role() = 'finance'
    OR EXISTS (SELECT 1 FROM public.loan_application AS la WHERE la.id = loan_application_id)
  );

DROP POLICY IF EXISTS repayment_schedule_select ON repayment_schedule;
CREATE POLICY repayment_schedule_select ON repayment_schedule FOR SELECT TO authenticated
  USING (
    public.current_app_role() = 'finance'
    OR EXISTS (SELECT 1 FROM public.loan_contract AS lc WHERE lc.id = contract_id)
  );

DROP POLICY IF EXISTS payment_select ON payment;
CREATE POLICY payment_select ON payment FOR SELECT TO authenticated
  USING (
    public.current_app_role() = 'finance'
    OR EXISTS (SELECT 1 FROM public.loan_contract AS lc WHERE lc.id = contract_id)
  );

DROP POLICY IF EXISTS ledger_entry_select ON ledger_entry;
CREATE POLICY ledger_entry_select ON ledger_entry FOR SELECT TO authenticated
  USING (public.abac_allows('ledger.export'));

-- 12. Privileges
REVOKE ALL ON agency, app_user, role_permission, user_device, refresh_session, otp_challenge,
  security_event, ip_allowlist, approval_request, audit_log, exchange_rate FROM anon, authenticated;

GRANT SELECT ON agency, app_user, audit_log, approval_request, exchange_rate TO authenticated;
GRANT SELECT ON customer, inventory_item, loan_application, loan_contract,
  repayment_schedule, payment, ledger_entry TO authenticated;

GRANT SELECT, INSERT, UPDATE, DELETE ON agency, app_user, role_permission, user_device,
  refresh_session, otp_challenge, ip_allowlist, exchange_rate TO service_role;
GRANT SELECT, INSERT, UPDATE ON approval_request TO service_role;
GRANT SELECT, INSERT ON audit_log, security_event TO service_role;

REVOKE EXECUTE ON FUNCTION public.current_actor_id() FROM PUBLIC, anon;
REVOKE EXECUTE ON FUNCTION public.current_app_role() FROM PUBLIC, anon;
REVOKE EXECUTE ON FUNCTION public.current_agency_id() FROM PUBLIC, anon;
REVOKE EXECUTE ON FUNCTION public.current_customer_id() FROM PUBLIC, anon;
REVOKE EXECUTE ON FUNCTION public.abac_allows(text, numeric, uuid) FROM PUBLIC, anon;
REVOKE EXECUTE ON FUNCTION public.customer_has_overdue(uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.current_actor_id() TO authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.current_app_role() TO authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.current_agency_id() TO authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.current_customer_id() TO authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.abac_allows(text, numeric, uuid) TO authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.customer_has_overdue(uuid) TO authenticated, service_role;

REVOKE EXECUTE ON FUNCTION public.is_ip_allowed(inet) FROM PUBLIC, anon, authenticated;
REVOKE EXECUTE ON FUNCTION public.register_failed_auth(uuid) FROM PUBLIC, anon, authenticated;
REVOKE EXECUTE ON FUNCTION public.register_successful_auth(uuid) FROM PUBLIC, anon, authenticated;
REVOKE EXECUTE ON FUNCTION public.verify_otp_challenge(uuid, text) FROM PUBLIC, anon, authenticated;
REVOKE EXECUTE ON FUNCTION public.report_device_change(uuid, text, text, text) FROM PUBLIC, anon, authenticated;
REVOKE EXECUTE ON FUNCTION public.approval_request_guard() FROM PUBLIC, anon, authenticated;
REVOKE EXECUTE ON FUNCTION public.audit_row_change() FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.is_ip_allowed(inet) TO service_role;
GRANT EXECUTE ON FUNCTION public.register_failed_auth(uuid) TO service_role;
GRANT EXECUTE ON FUNCTION public.register_successful_auth(uuid) TO service_role;
GRANT EXECUTE ON FUNCTION public.verify_otp_challenge(uuid, text) TO service_role;
GRANT EXECUTE ON FUNCTION public.report_device_change(uuid, text, text, text) TO service_role;
