/*
# Step 3: KYC onboarding, credit calculator, scoring engine, contract generation

Plain English: adds the data and the database functions for the full admission journey. KYC profile, emergency contact and legal consents, a USD credit calculator, a risk engine that decides approved / pending_review / rejected with a recorded reason, manual review, and contract + repayment schedule generation.

1. Onboarding data
- kyc_profile: personal and professional data, ID document type, OCR result, liveness and face-match results, paths of the stored photos (files themselves live in encrypted storage)
- emergency_contact: next of kin or guarantor, phone in international format
- customer_consent: append-only; four required consents (data processing, risk profile check, IMEI registration, remote lock). A withdrawal is a new row.

2. Credit policy (credit_policy, one row)
- Down payment range, allowed durations, fee and interest rates, penalty terms, lock rule, score thresholds, KYC and affordability thresholds.
- IMPORTANT: fee, interest, penalty and lock values are seeded at 0 / NULL on purpose. The business must set them before going live. Score thresholds (75 auto-approve, 50 review) are starting values to adjust.

3. Calculator
- installment_plan(): one source of truth for dates and amounts (the last installment absorbs rounding so the schedule always adds up to the exact total)
- simulate_loan(): returns the full offer in USD (down payment, fee, financed amount, interest, total due, installments, first due date, end date, penalty and lock terms)
- Formulas: financed = price + fee - down payment; total due = financed + interest; installment = total due / count
- Interest model used: flat rate per 30 days x duration / 30 (the brief gives no interest formula, so this is an assumption)

4. Decision engine
- submit_application(): creates the application with the offer figures
- evaluate_application(): hard rules (-> rejected), review flags (-> pending_review), score 0-100 (-> approved / pending_review / rejected). Every outcome is stored in credit_decision (append-only) with reasons and a policy snapshot.
- resolve_review(): manual decision by a user holding application.approve / application.reject (agency scope and amount limit enforced), never the person who created the file

5. Contract
- generate_contract(): unique contract number, repayment schedule inserted, terms snapshot (penalties, lock) frozen on the contract
- contract_summary(): everything needed to print the contract (IMEI, amounts, schedule, lock acceptance)
- contract_signature: append-only record of electronic signature or handover validation

6. Changes to existing objects
- loan_status gets 'pending_review'
- loan_application: processing_fee, interest_amount, installment_count; the "financed = total - down" check becomes "financed = total + fee - down"
- inventory_item.min_down_payment_pct (per-product minimum, falls back to the policy)
- loan_contract.terms_snapshot (frozen by the immutability guard)
- customer phone numbers must be international format for new rows

Security
- RLS on every new table, SELECT only for authenticated users, all writes through server-side functions (service_role). Credit decisions and scores are hidden from clients.
*/

-- 0. Enum and existing table changes
ALTER TYPE loan_status ADD VALUE IF NOT EXISTS 'pending_review' BEFORE 'approved';

ALTER TABLE loan_application DROP CONSTRAINT IF EXISTS loan_application_financed_amount_matches;
ALTER TABLE loan_application ADD COLUMN IF NOT EXISTS processing_fee numeric(14,2) NOT NULL DEFAULT 0 CHECK (processing_fee >= 0);
ALTER TABLE loan_application ADD COLUMN IF NOT EXISTS interest_amount numeric(14,2) NOT NULL DEFAULT 0 CHECK (interest_amount >= 0);
ALTER TABLE loan_application ADD COLUMN IF NOT EXISTS installment_count integer CHECK (installment_count IS NULL OR installment_count > 0);

DO $$ BEGIN
  ALTER TABLE loan_application ADD CONSTRAINT loan_application_financed_amount_matches
    CHECK (financed_amount = total_price + processing_fee - down_payment);
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

ALTER TABLE inventory_item ADD COLUMN IF NOT EXISTS min_down_payment_pct numeric(5,2)
  CHECK (min_down_payment_pct IS NULL OR (min_down_payment_pct >= 0 AND min_down_payment_pct <= 100));

ALTER TABLE loan_contract ADD COLUMN IF NOT EXISTS terms_snapshot jsonb NOT NULL DEFAULT '{}'::jsonb;

DO $$ BEGIN
  ALTER TABLE customer ADD CONSTRAINT customer_phone_international_format
    CHECK (phone_number ~ '^\+[1-9][0-9]{7,14}$') NOT VALID;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

CREATE SEQUENCE IF NOT EXISTS contract_number_seq;

-- Contract guard now also freezes the terms snapshot
CREATE OR REPLACE FUNCTION public.loan_contract_guard_update()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = ''
AS $$
BEGIN
  IF NEW.loan_application_id IS DISTINCT FROM OLD.loan_application_id
     OR NEW.version IS DISTINCT FROM OLD.version
     OR NEW.contract_number IS DISTINCT FROM OLD.contract_number
     OR NEW.start_date IS DISTINCT FROM OLD.start_date
     OR NEW.end_date IS DISTINCT FROM OLD.end_date
     OR NEW.interest_rate IS DISTINCT FROM OLD.interest_rate
     OR NEW.total_payable IS DISTINCT FROM OLD.total_payable
     OR NEW.terms_snapshot IS DISTINCT FROM OLD.terms_snapshot
     OR NEW.created_at IS DISTINCT FROM OLD.created_at
  THEN
    RAISE EXCEPTION 'loan_contract terms are immutable: create a new contract version instead';
  END IF;

  IF NEW.status IS DISTINCT FROM OLD.status AND OLD.status <> 'active' THEN
    RAISE EXCEPTION 'loan_contract status % is final and cannot change to %', OLD.status, NEW.status;
  END IF;

  RETURN NEW;
END;
$$;

-- 1. Onboarding tables
CREATE TABLE IF NOT EXISTS kyc_profile (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  customer_id uuid NOT NULL UNIQUE REFERENCES customer (id),
  birth_date date,
  commune varchar(100),
  neighborhood varchar(150),
  occupation varchar(150),
  income_source varchar(150),
  declared_monthly_income_usd numeric(14,2) CHECK (declared_monthly_income_usd IS NULL OR declared_monthly_income_usd >= 0),
  phone_operator varchar(50),
  phone_validated_at timestamptz,
  id_document_type varchar(20) CHECK (id_document_type IN ('voter_card', 'passport', 'driver_license')),
  id_document_photo_path text,
  selfie_path text,
  ocr_data jsonb,
  ocr_confidence numeric(4,3) CHECK (ocr_confidence IS NULL OR (ocr_confidence >= 0 AND ocr_confidence <= 1)),
  liveness_passed boolean,
  face_match_score numeric(4,3) CHECK (face_match_score IS NULL OR (face_match_score >= 0 AND face_match_score <= 1)),
  status varchar(20) NOT NULL DEFAULT 'draft' CHECK (status IN ('draft', 'submitted', 'verified', 'rejected')),
  collected_by uuid REFERENCES app_user (id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

COMMENT ON COLUMN kyc_profile.declared_monthly_income_usd IS 'Amount in USD (system currency)';

CREATE TABLE IF NOT EXISTS emergency_contact (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  customer_id uuid NOT NULL REFERENCES customer (id),
  full_name varchar(200) NOT NULL,
  relationship varchar(100) NOT NULL,
  phone_number varchar(20) NOT NULL CHECK (phone_number ~ '^\+[1-9][0-9]{7,14}$'),
  is_guarantor boolean NOT NULL DEFAULT false,
  verified_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS emergency_contact_customer_id_idx ON emergency_contact (customer_id);

CREATE TABLE IF NOT EXISTS customer_consent (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  customer_id uuid NOT NULL REFERENCES customer (id),
  consent_type varchar(30) NOT NULL CHECK (
    consent_type IN ('data_processing', 'risk_profile_check', 'imei_registration', 'remote_lock')
  ),
  granted boolean NOT NULL,
  text_version varchar(20) NOT NULL,
  collected_by uuid REFERENCES app_user (id),
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS customer_consent_lookup_idx ON customer_consent (customer_id, consent_type, created_at DESC);

-- 2. Credit policy (single row)
CREATE TABLE IF NOT EXISTS credit_policy (
  id boolean PRIMARY KEY DEFAULT true CHECK (id),
  down_payment_min_pct numeric(5,2) NOT NULL DEFAULT 20 CHECK (down_payment_min_pct BETWEEN 0 AND 100),
  down_payment_max_pct numeric(5,2) NOT NULL DEFAULT 40 CHECK (down_payment_max_pct BETWEEN 0 AND 100),
  allowed_durations integer[] NOT NULL DEFAULT ARRAY[30, 60, 90, 120, 180],
  processing_fee_rate numeric(7,4) NOT NULL DEFAULT 0 CHECK (processing_fee_rate >= 0),
  interest_rate_per_30_days numeric(7,4) NOT NULL DEFAULT 0 CHECK (interest_rate_per_30_days >= 0),
  penalty_rate_per_day numeric(7,4) NOT NULL DEFAULT 0 CHECK (penalty_rate_per_day >= 0),
  grace_days integer NOT NULL DEFAULT 0 CHECK (grace_days >= 0),
  lock_after_days_overdue integer CHECK (lock_after_days_overdue IS NULL OR lock_after_days_overdue >= 0),
  auto_approve_min_score integer NOT NULL DEFAULT 75 CHECK (auto_approve_min_score BETWEEN 0 AND 100),
  review_min_score integer NOT NULL DEFAULT 50 CHECK (review_min_score BETWEEN 0 AND 100),
  face_match_min numeric(4,3) NOT NULL DEFAULT 0.800 CHECK (face_match_min BETWEEN 0 AND 1),
  ocr_confidence_min numeric(4,3) NOT NULL DEFAULT 0.850 CHECK (ocr_confidence_min BETWEEN 0 AND 1),
  burden_good numeric(4,3) NOT NULL DEFAULT 0.150,
  burden_ok numeric(4,3) NOT NULL DEFAULT 0.250,
  burden_max numeric(4,3) NOT NULL DEFAULT 0.350,
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT credit_policy_down_range CHECK (down_payment_min_pct <= down_payment_max_pct),
  CONSTRAINT credit_policy_score_order CHECK (review_min_score <= auto_approve_min_score),
  CONSTRAINT credit_policy_burden_order CHECK (burden_good <= burden_ok AND burden_ok <= burden_max)
);

INSERT INTO credit_policy (id) VALUES (true) ON CONFLICT (id) DO NOTHING;

-- 3. Decisions and signatures
CREATE TABLE IF NOT EXISTS credit_decision (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  application_id uuid NOT NULL REFERENCES loan_application (id),
  decision_type varchar(10) NOT NULL CHECK (decision_type IN ('auto', 'manual')),
  outcome varchar(20) NOT NULL CHECK (outcome IN ('approved', 'pending_review', 'rejected')),
  score integer CHECK (score IS NULL OR score BETWEEN 0 AND 100),
  score_breakdown jsonb NOT NULL DEFAULT '{}'::jsonb,
  hard_rule_failures text[] NOT NULL DEFAULT ARRAY[]::text[],
  review_flags text[] NOT NULL DEFAULT ARRAY[]::text[],
  reason text NOT NULL,
  engine_version varchar(30) NOT NULL,
  policy_snapshot jsonb NOT NULL DEFAULT '{}'::jsonb,
  decided_by uuid REFERENCES app_user (id),
  created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT credit_decision_manual_has_decider CHECK (decision_type = 'auto' OR decided_by IS NOT NULL)
);

CREATE INDEX IF NOT EXISTS credit_decision_application_idx ON credit_decision (application_id, created_at DESC);

CREATE TABLE IF NOT EXISTS contract_signature (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  contract_id uuid NOT NULL UNIQUE REFERENCES loan_contract (id),
  method varchar(25) NOT NULL CHECK (method IN ('electronic', 'handover_validation')),
  signed_at timestamptz NOT NULL DEFAULT now(),
  signer_device_fingerprint varchar(128),
  document_hash varchar(128) NOT NULL,
  recorded_by uuid REFERENCES app_user (id)
);

-- Immutability and audit
DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['credit_decision', 'customer_consent', 'contract_signature']
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

  FOREACH t IN ARRAY ARRAY['kyc_profile', 'emergency_contact', 'credit_policy']
  LOOP
    EXECUTE format('DROP TRIGGER IF EXISTS %I ON public.%I', t || '_audit_trg', t);
    EXECUTE format(
      'CREATE TRIGGER %I AFTER INSERT OR UPDATE OR DELETE ON public.%I FOR EACH ROW EXECUTE FUNCTION public.audit_row_change()',
      t || '_audit_trg', t
    );
  END LOOP;
END $$;

DROP TRIGGER IF EXISTS kyc_profile_set_updated_at ON kyc_profile;
CREATE TRIGGER kyc_profile_set_updated_at BEFORE UPDATE ON kyc_profile
  FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

DROP TRIGGER IF EXISTS credit_policy_set_updated_at ON credit_policy;
CREATE TRIGGER credit_policy_set_updated_at BEFORE UPDATE ON credit_policy
  FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

-- 4. Calculator
CREATE OR REPLACE FUNCTION public.installment_plan(
  p_start date,
  p_frequency public.installment_frequency,
  p_duration_days integer,
  p_total numeric
)
RETURNS TABLE (installment_number integer, due_date date, amount_due numeric)
LANGUAGE plpgsql
IMMUTABLE
SET search_path = ''
AS $$
DECLARE
  period integer;
  n integer;
  base numeric;
BEGIN
  period := CASE p_frequency WHEN 'daily' THEN 1 WHEN 'weekly' THEN 7 ELSE 30 END;
  n := ceil(p_duration_days::numeric / period)::integer;
  IF n < 1 THEN
    RAISE EXCEPTION 'duration must produce at least one installment';
  END IF;
  base := round(p_total / n, 2);

  RETURN QUERY
    SELECT
      g,
      CASE WHEN p_frequency = 'monthly'
           THEN (p_start + (g * interval '1 month'))::date
           ELSE p_start + g * period END,
      CASE WHEN g = n THEN p_total - base * (n - 1) ELSE base END
    FROM generate_series(1, n) AS g;
END;
$$;

CREATE OR REPLACE FUNCTION public.simulate_loan(
  p_item uuid,
  p_down_payment numeric,
  p_duration_days integer,
  p_frequency public.installment_frequency,
  p_start date DEFAULT current_date
)
RETURNS jsonb
LANGUAGE plpgsql
STABLE
SET search_path = ''
AS $$
DECLARE
  it public.inventory_item%ROWTYPE;
  pol public.credit_policy%ROWTYPE;
  v_min_pct numeric;
  v_min_down numeric;
  v_fee numeric;
  v_financed numeric;
  v_interest numeric;
  v_total numeric;
  v_count integer;
  v_first_amount numeric;
  v_last_amount numeric;
  v_first_due date;
  v_end date;
  warnings text[] := ARRAY[]::text[];
BEGIN
  SELECT * INTO it FROM public.inventory_item WHERE id = p_item;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'unknown device';
  END IF;

  IF public.current_actor_id() IS NOT NULL
     AND NOT public.abac_allows('credit.simulate', NULL, it.agency_id) THEN
    RAISE EXCEPTION 'not allowed to simulate a credit for this device' USING ERRCODE = '42501';
  END IF;

  SELECT * INTO pol FROM public.credit_policy;

  IF NOT (p_duration_days = ANY (pol.allowed_durations)) THEN
    RAISE EXCEPTION 'duration % days is not offered (allowed: %)', p_duration_days, pol.allowed_durations;
  END IF;

  v_min_pct := coalesce(it.min_down_payment_pct, pol.down_payment_min_pct);
  v_min_down := round(it.retail_price * v_min_pct / 100, 2);

  IF p_down_payment < v_min_down THEN
    RAISE EXCEPTION 'down payment % USD is below the minimum of % USD (% percent)', p_down_payment, v_min_down, v_min_pct;
  END IF;
  IF p_down_payment > it.retail_price THEN
    RAISE EXCEPTION 'down payment cannot exceed the device price';
  END IF;
  IF p_down_payment > round(it.retail_price * pol.down_payment_max_pct / 100, 2) THEN
    warnings := array_append(warnings, 'down_payment_above_usual_range');
  END IF;

  v_fee := round(it.retail_price * pol.processing_fee_rate, 2);
  v_financed := it.retail_price + v_fee - p_down_payment;
  v_interest := round(v_financed * pol.interest_rate_per_30_days * p_duration_days / 30.0, 2);
  v_total := v_financed + v_interest;

  SELECT count(*), min(amount_due) FILTER (WHERE installment_number = 1), max(due_date)
    INTO v_count, v_first_amount, v_end
    FROM public.installment_plan(p_start, p_frequency, p_duration_days, v_total);

  SELECT amount_due INTO v_last_amount
    FROM public.installment_plan(p_start, p_frequency, p_duration_days, v_total)
    ORDER BY installment_number DESC LIMIT 1;

  SELECT due_date INTO v_first_due
    FROM public.installment_plan(p_start, p_frequency, p_duration_days, v_total)
    ORDER BY installment_number LIMIT 1;

  RETURN jsonb_build_object(
    'currency', 'USD',
    'inventory_item_id', it.id,
    'retail_price', it.retail_price,
    'down_payment', p_down_payment,
    'minimum_down_payment', v_min_down,
    'processing_fee', v_fee,
    'financed_amount', v_financed,
    'interest_amount', v_interest,
    'total_due', v_total,
    'duration_days', p_duration_days,
    'frequency', p_frequency,
    'installment_count', v_count,
    'installment_amount', v_first_amount,
    'last_installment_amount', v_last_amount,
    'first_due_date', v_first_due,
    'end_date', v_end,
    'penalty_terms', jsonb_build_object(
      'penalty_rate_per_day', pol.penalty_rate_per_day,
      'grace_days', pol.grace_days
    ),
    'lock_terms', jsonb_build_object(
      'remote_lock_enabled', true,
      'lock_after_days_overdue', pol.lock_after_days_overdue
    ),
    'warnings', to_jsonb(warnings)
  );
END;
$$;

-- 5. Consent helper
CREATE OR REPLACE FUNCTION public.has_required_consents(p_customer uuid)
RETURNS boolean
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = ''
AS $$
  SELECT count(*) = 4
  FROM (
    SELECT DISTINCT ON (cc.consent_type) cc.consent_type, cc.granted
    FROM public.customer_consent AS cc
    WHERE cc.customer_id = p_customer
    ORDER BY cc.consent_type, cc.created_at DESC, cc.id DESC
  ) AS latest
  WHERE latest.granted;
$$;

-- 6. Submit application
CREATE OR REPLACE FUNCTION public.submit_application(
  p_customer uuid,
  p_item uuid,
  p_down_payment numeric,
  p_duration_days integer,
  p_frequency public.installment_frequency,
  p_agent uuid
)
RETURNS uuid
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  c public.customer%ROWTYPE;
  offer jsonb;
  new_id uuid;
BEGIN
  SELECT * INTO c FROM public.customer WHERE id = p_customer;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'unknown customer';
  END IF;

  IF NOT EXISTS (
    SELECT 1
    FROM public.app_user AS u
    JOIN public.role_permission AS rp ON rp.role = u.role
    WHERE u.id = p_agent
      AND u.status = 'active'
      AND rp.permission = 'application.submit'
      AND (NOT rp.agency_scoped OR (u.agency_id IS NOT NULL AND u.agency_id = c.agency_id))
  ) THEN
    RAISE EXCEPTION 'this user cannot submit an application for this customer' USING ERRCODE = '42501';
  END IF;

  offer := public.simulate_loan(p_item, p_down_payment, p_duration_days, p_frequency);

  INSERT INTO public.loan_application (
    customer_id, inventory_item_id, total_price, processing_fee, down_payment,
    financed_amount, interest_amount, installment_count, duration_days,
    installment_frequency, status, agency_id, created_by
  ) VALUES (
    p_customer, p_item,
    (offer ->> 'retail_price')::numeric,
    (offer ->> 'processing_fee')::numeric,
    p_down_payment,
    (offer ->> 'financed_amount')::numeric,
    (offer ->> 'interest_amount')::numeric,
    (offer ->> 'installment_count')::integer,
    p_duration_days, p_frequency, 'pending', c.agency_id, p_agent
  )
  RETURNING id INTO new_id;

  RETURN new_id;
END;
$$;

-- 7. Risk engine
CREATE OR REPLACE FUNCTION public.evaluate_application(p_application uuid)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  a public.loan_application%ROWTYPE;
  c public.customer%ROWTYPE;
  it public.inventory_item%ROWTYPE;
  k public.kyc_profile%ROWTYPE;
  pol public.credit_policy%ROWTYPE;
  kyc_found boolean;
  hard text[] := ARRAY[]::text[];
  flags text[] := ARRAY[]::text[];
  b jsonb := '{}'::jsonb;
  v_score integer := 0;
  v_part integer;
  v_min_pct numeric;
  v_period integer;
  v_total numeric;
  v_first numeric;
  v_monthly numeric;
  v_burden numeric;
  v_completed integer;
  v_contacts integer;
  v_verified integer;
  v_down_pct numeric;
  v_outcome text;
  v_reason text;
BEGIN
  SELECT * INTO a FROM public.loan_application WHERE id = p_application FOR UPDATE;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'unknown application';
  END IF;
  IF a.status::text <> 'pending' THEN
    RAISE EXCEPTION 'only a pending application can be evaluated (status: %)', a.status;
  END IF;

  SELECT * INTO it FROM public.inventory_item WHERE id = a.inventory_item_id FOR UPDATE;
  SELECT * INTO c FROM public.customer WHERE id = a.customer_id;
  SELECT * INTO pol FROM public.credit_policy;
  SELECT * INTO k FROM public.kyc_profile WHERE customer_id = a.customer_id;
  kyc_found := FOUND;

  -- Hard rules
  IF c.status <> 'active' THEN
    hard := array_append(hard, 'customer_not_active');
  END IF;

  IF EXISTS (
    SELECT 1 FROM public.loan_application AS la
    WHERE la.customer_id = a.customer_id AND la.id <> a.id
      AND la.status::text IN ('approved', 'active', 'defaulted')
  ) OR public.customer_has_overdue(a.customer_id) THEN
    hard := array_append(hard, 'existing_credit_active_or_overdue');
  END IF;

  IF EXISTS (
    SELECT 1 FROM public.loan_application AS la
    WHERE la.customer_id = a.customer_id AND la.id <> a.id AND la.status::text = 'pending_review'
  ) THEN
    hard := array_append(hard, 'duplicate_open_application');
  END IF;

  IF it.status <> 'in_stock'
     OR EXISTS (
       SELECT 1 FROM public.loan_application AS la
       WHERE la.inventory_item_id = a.inventory_item_id AND la.id <> a.id
         AND la.status::text IN ('approved', 'active')
     )
     OR EXISTS (
       SELECT 1
       FROM public.loan_contract AS lc
       JOIN public.loan_application AS la ON la.id = lc.loan_application_id
       WHERE la.inventory_item_id = a.inventory_item_id AND lc.status = 'active'
     ) THEN
    hard := array_append(hard, 'device_unavailable');
  END IF;

  v_min_pct := coalesce(it.min_down_payment_pct, pol.down_payment_min_pct);
  IF a.down_payment < round(a.total_price * v_min_pct / 100, 2) THEN
    hard := array_append(hard, 'down_payment_below_minimum');
  END IF;

  IF NOT public.has_required_consents(a.customer_id) THEN
    hard := array_append(hard, 'consent_missing');
  END IF;

  IF NOT kyc_found OR k.status NOT IN ('submitted', 'verified') THEN
    hard := array_append(hard, 'kyc_missing_or_rejected');
  ELSIF k.birth_date IS NOT NULL AND k.birth_date > (current_date - interval '18 years')::date THEN
    hard := array_append(hard, 'customer_underage');
  END IF;

  -- Review flags (need a human)
  IF kyc_found THEN
    IF NOT coalesce(k.liveness_passed, false) THEN
      flags := array_append(flags, 'liveness_failed');
    END IF;
    IF coalesce(k.face_match_score, 0) < pol.face_match_min THEN
      flags := array_append(flags, 'face_match_low');
    END IF;
    IF coalesce(k.ocr_confidence, 0) < pol.ocr_confidence_min THEN
      flags := array_append(flags, 'ocr_low_confidence');
    END IF;
    IF k.birth_date IS NULL THEN
      flags := array_append(flags, 'birth_date_missing');
    END IF;
    IF coalesce(k.declared_monthly_income_usd, 0) <= 0 THEN
      flags := array_append(flags, 'income_not_declared');
    END IF;
  END IF;

  IF EXISTS (
    SELECT 1 FROM public.customer AS c2
    WHERE c2.national_id_number = c.national_id_number AND c2.id <> c.id
  ) THEN
    flags := array_append(flags, 'duplicate_identity');
  END IF;

  -- Score (0-100): KYC 30, affordability 35, history 20, contact 10, down payment 5
  v_part := 0;
  IF kyc_found THEN
    IF coalesce(k.liveness_passed, false) THEN v_part := v_part + 10; END IF;
    IF coalesce(k.face_match_score, 0) >= pol.face_match_min THEN v_part := v_part + 10; END IF;
    IF coalesce(k.ocr_confidence, 0) >= pol.ocr_confidence_min THEN v_part := v_part + 5; END IF;
    IF k.phone_validated_at IS NOT NULL THEN v_part := v_part + 5; END IF;
  END IF;
  b := b || jsonb_build_object('kyc', v_part);
  v_score := v_score + v_part;

  v_period := CASE a.installment_frequency WHEN 'daily' THEN 1 WHEN 'weekly' THEN 7 ELSE 30 END;
  v_total := a.financed_amount + a.interest_amount;
  SELECT p.amount_due INTO v_first
    FROM public.installment_plan(current_date, a.installment_frequency, a.duration_days, v_total) AS p
    WHERE p.installment_number = 1;
  v_monthly := v_first * 30.0 / v_period;

  v_part := 0;
  IF kyc_found AND coalesce(k.declared_monthly_income_usd, 0) > 0 THEN
    v_burden := v_monthly / k.declared_monthly_income_usd;
    v_part := CASE
      WHEN v_burden <= pol.burden_good THEN 35
      WHEN v_burden <= pol.burden_ok THEN 25
      WHEN v_burden <= pol.burden_max THEN 12
      ELSE 0 END;
    b := b || jsonb_build_object('income_burden_ratio', round(v_burden, 4));
  END IF;
  b := b || jsonb_build_object('affordability', v_part);
  v_score := v_score + v_part;

  SELECT count(*) INTO v_completed
    FROM public.loan_application AS la
    WHERE la.customer_id = a.customer_id AND la.id <> a.id AND la.status::text = 'completed';
  v_part := CASE WHEN v_completed >= 1 THEN 20 ELSE 10 END;
  b := b || jsonb_build_object('history', v_part, 'completed_loans', v_completed);
  v_score := v_score + v_part;

  SELECT count(*), count(*) FILTER (WHERE ec.verified_at IS NOT NULL)
    INTO v_contacts, v_verified
    FROM public.emergency_contact AS ec WHERE ec.customer_id = a.customer_id;
  v_part := (CASE WHEN v_contacts >= 1 THEN 5 ELSE 0 END) + (CASE WHEN v_verified >= 1 THEN 5 ELSE 0 END);
  b := b || jsonb_build_object('emergency_contact', v_part);
  v_score := v_score + v_part;

  v_down_pct := a.down_payment * 100.0 / a.total_price;
  v_part := CASE WHEN v_down_pct >= (pol.down_payment_min_pct + pol.down_payment_max_pct) / 2 THEN 5 ELSE 2 END;
  b := b || jsonb_build_object('down_payment', v_part);
  v_score := v_score + v_part;

  -- Outcome
  IF array_length(hard, 1) > 0 THEN
    v_outcome := 'rejected';
    v_reason := array_to_string(hard, ', ');
  ELSIF array_length(flags, 1) > 0 THEN
    v_outcome := 'pending_review';
    v_reason := 'manual verification needed: ' || array_to_string(flags, ', ');
  ELSIF v_score >= pol.auto_approve_min_score THEN
    v_outcome := 'approved';
    v_reason := 'all checks passed, score ' || v_score;
  ELSIF v_score >= pol.review_min_score THEN
    v_outcome := 'pending_review';
    v_reason := 'borderline score ' || v_score;
  ELSE
    v_outcome := 'rejected';
    v_reason := 'score ' || v_score || ' below review threshold';
  END IF;

  UPDATE public.loan_application
    SET status = v_outcome::public.loan_status
    WHERE id = a.id;

  INSERT INTO public.credit_decision (
    application_id, decision_type, outcome, score, score_breakdown,
    hard_rule_failures, review_flags, reason, engine_version, policy_snapshot
  ) VALUES (
    a.id, 'auto', v_outcome, v_score, b, hard, flags, v_reason, 'risk-engine-v1', to_jsonb(pol)
  );

  RETURN jsonb_build_object(
    'outcome', v_outcome,
    'score', v_score,
    'reason', v_reason,
    'hard_rule_failures', to_jsonb(hard),
    'review_flags', to_jsonb(flags),
    'score_breakdown', b
  );
END;
$$;

-- 8. Manual review
CREATE OR REPLACE FUNCTION public.resolve_review(
  p_application uuid,
  p_reviewer uuid,
  p_approve boolean,
  p_reason text
)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  a public.loan_application%ROWTYPE;
  perm text;
  rp public.role_permission%ROWTYPE;
  u public.app_user%ROWTYPE;
  last_score integer;
BEGIN
  SELECT * INTO a FROM public.loan_application WHERE id = p_application FOR UPDATE;
  IF NOT FOUND OR a.status::text <> 'pending_review' THEN
    RAISE EXCEPTION 'application is not waiting for a manual review';
  END IF;

  IF coalesce(btrim(p_reason), '') = '' THEN
    RAISE EXCEPTION 'a reason is required';
  END IF;

  IF p_reviewer IS NOT DISTINCT FROM a.created_by THEN
    RAISE EXCEPTION 'the reviewer cannot be the person who created the application' USING ERRCODE = '42501';
  END IF;

  perm := CASE WHEN p_approve THEN 'application.approve' ELSE 'application.reject' END;

  SELECT * INTO u FROM public.app_user WHERE id = p_reviewer AND status = 'active';
  IF NOT FOUND THEN
    RAISE EXCEPTION 'unknown or inactive reviewer' USING ERRCODE = '42501';
  END IF;

  SELECT * INTO rp FROM public.role_permission WHERE role = u.role AND permission = perm;
  IF NOT FOUND
     OR (rp.agency_scoped AND (u.agency_id IS NULL OR u.agency_id IS DISTINCT FROM a.agency_id))
     OR (rp.max_amount_usd IS NOT NULL AND a.financed_amount > rp.max_amount_usd) THEN
    RAISE EXCEPTION 'reviewer is not allowed to take this decision' USING ERRCODE = '42501';
  END IF;

  SELECT cd.score INTO last_score
    FROM public.credit_decision AS cd
    WHERE cd.application_id = a.id
    ORDER BY cd.created_at DESC LIMIT 1;

  IF p_approve THEN
    UPDATE public.loan_application
      SET status = 'approved', approved_by = p_reviewer
      WHERE id = a.id;
  ELSE
    UPDATE public.loan_application
      SET status = 'rejected'
      WHERE id = a.id;
  END IF;

  INSERT INTO public.credit_decision (
    application_id, decision_type, outcome, score, reason, engine_version, decided_by
  ) VALUES (
    a.id, 'manual', CASE WHEN p_approve THEN 'approved' ELSE 'rejected' END,
    last_score, btrim(p_reason), 'manual-review', p_reviewer
  );
END;
$$;

-- 9. Contract generation
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

  RETURN v_id;
END;
$$;

CREATE OR REPLACE FUNCTION public.contract_summary(p_contract uuid)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  r jsonb;
BEGIN
  SELECT jsonb_build_object(
    'contract_number', lc.contract_number,
    'version', lc.version,
    'status', lc.status,
    'currency', 'USD',
    'start_date', lc.start_date,
    'end_date', lc.end_date,
    'customer', jsonb_build_object(
      'name', cu.first_name || ' ' || cu.last_name,
      'phone_number', cu.phone_number,
      'address', cu.address,
      'city', cu.city
    ),
    'device', jsonb_build_object(
      'imei', it.imei, 'brand', it.brand, 'model', it.model, 'color', it.color
    ),
    'amounts', jsonb_build_object(
      'retail_price', la.total_price,
      'processing_fee', la.processing_fee,
      'down_payment', la.down_payment,
      'financed_amount', la.financed_amount,
      'interest_amount', la.interest_amount,
      'total_payable', lc.total_payable
    ),
    'terms', lc.terms_snapshot,
    'schedule', coalesce((
      SELECT jsonb_agg(
        jsonb_build_object('number', rs.installment_number, 'due_date', rs.due_date, 'amount_due', rs.amount_due)
        ORDER BY rs.installment_number)
      FROM public.repayment_schedule AS rs WHERE rs.contract_id = lc.id
    ), '[]'::jsonb),
    'signature', (
      SELECT jsonb_build_object('method', cs.method, 'signed_at', cs.signed_at)
      FROM public.contract_signature AS cs WHERE cs.contract_id = lc.id
    )
  )
  INTO r
  FROM public.loan_contract AS lc
  JOIN public.loan_application AS la ON la.id = lc.loan_application_id
  JOIN public.customer AS cu ON cu.id = la.customer_id
  JOIN public.inventory_item AS it ON it.id = la.inventory_item_id
  WHERE lc.id = p_contract;

  IF r IS NULL THEN
    RAISE EXCEPTION 'unknown contract';
  END IF;
  RETURN r;
END;
$$;

-- 10. Row level security
ALTER TABLE kyc_profile ENABLE ROW LEVEL SECURITY;
ALTER TABLE emergency_contact ENABLE ROW LEVEL SECURITY;
ALTER TABLE customer_consent ENABLE ROW LEVEL SECURITY;
ALTER TABLE credit_policy ENABLE ROW LEVEL SECURITY;
ALTER TABLE credit_decision ENABLE ROW LEVEL SECURITY;
ALTER TABLE contract_signature ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS kyc_profile_select ON kyc_profile;
CREATE POLICY kyc_profile_select ON kyc_profile FOR SELECT TO authenticated
  USING (
    public.current_app_role() <> 'collections'
    AND EXISTS (SELECT 1 FROM public.customer AS c WHERE c.id = customer_id)
  );

DROP POLICY IF EXISTS emergency_contact_select ON emergency_contact;
CREATE POLICY emergency_contact_select ON emergency_contact FOR SELECT TO authenticated
  USING (EXISTS (SELECT 1 FROM public.customer AS c WHERE c.id = customer_id));

DROP POLICY IF EXISTS customer_consent_select ON customer_consent;
CREATE POLICY customer_consent_select ON customer_consent FOR SELECT TO authenticated
  USING (EXISTS (SELECT 1 FROM public.customer AS c WHERE c.id = customer_id));

DROP POLICY IF EXISTS credit_policy_select ON credit_policy;
CREATE POLICY credit_policy_select ON credit_policy FOR SELECT TO authenticated
  USING (public.current_app_role() IS NOT NULL);

DROP POLICY IF EXISTS credit_decision_select ON credit_decision;
CREATE POLICY credit_decision_select ON credit_decision FOR SELECT TO authenticated
  USING (
    public.current_app_role() <> 'client'
    AND EXISTS (SELECT 1 FROM public.loan_application AS la WHERE la.id = application_id)
  );

DROP POLICY IF EXISTS contract_signature_select ON contract_signature;
CREATE POLICY contract_signature_select ON contract_signature FOR SELECT TO authenticated
  USING (EXISTS (SELECT 1 FROM public.loan_contract AS lc WHERE lc.id = contract_id));

-- 11. Privileges
REVOKE ALL ON kyc_profile, emergency_contact, customer_consent, credit_policy,
  credit_decision, contract_signature FROM anon, authenticated;

GRANT SELECT ON kyc_profile, emergency_contact, customer_consent, credit_policy,
  credit_decision, contract_signature TO authenticated;

GRANT SELECT, INSERT, UPDATE ON kyc_profile, emergency_contact, credit_policy TO service_role;
GRANT SELECT, INSERT ON customer_consent, credit_decision, contract_signature TO service_role;
GRANT USAGE ON SEQUENCE contract_number_seq TO service_role;

REVOKE EXECUTE ON FUNCTION public.installment_plan(date, public.installment_frequency, integer, numeric) FROM PUBLIC, anon;
REVOKE EXECUTE ON FUNCTION public.simulate_loan(uuid, numeric, integer, public.installment_frequency, date) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.installment_plan(date, public.installment_frequency, integer, numeric) TO authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.simulate_loan(uuid, numeric, integer, public.installment_frequency, date) TO authenticated, service_role;

REVOKE EXECUTE ON FUNCTION public.has_required_consents(uuid) FROM PUBLIC, anon, authenticated;
REVOKE EXECUTE ON FUNCTION public.submit_application(uuid, uuid, numeric, integer, public.installment_frequency, uuid) FROM PUBLIC, anon, authenticated;
REVOKE EXECUTE ON FUNCTION public.evaluate_application(uuid) FROM PUBLIC, anon, authenticated;
REVOKE EXECUTE ON FUNCTION public.resolve_review(uuid, uuid, boolean, text) FROM PUBLIC, anon, authenticated;
REVOKE EXECUTE ON FUNCTION public.generate_contract(uuid) FROM PUBLIC, anon, authenticated;
REVOKE EXECUTE ON FUNCTION public.contract_summary(uuid) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.has_required_consents(uuid) TO service_role;
GRANT EXECUTE ON FUNCTION public.submit_application(uuid, uuid, numeric, integer, public.installment_frequency, uuid) TO service_role;
GRANT EXECUTE ON FUNCTION public.evaluate_application(uuid) TO service_role;
GRANT EXECUTE ON FUNCTION public.resolve_review(uuid, uuid, boolean, text) TO service_role;
GRANT EXECUTE ON FUNCTION public.generate_contract(uuid) TO service_role;
GRANT EXECUTE ON FUNCTION public.contract_summary(uuid) TO service_role;
