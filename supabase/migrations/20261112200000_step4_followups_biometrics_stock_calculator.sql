/*
# Step 4 follow-ups: server-verified biometrics, stock per agency, calculator parity

Plain English: closes the three open points of step 4.

1. Biometrics must be verified by the server
- kyc_biometric_check (append-only): liveness and face-match results computed by the server from the stored selfie and ID photo. The values sent by the phone are no longer used by the risk engine.
- The engine only trusts a check made on the SAME selfie and ID photo as the current KYC. No check, or a check on old photos, sends the file to manual review (biometrics_not_verified / biometrics_outdated).
- record_biometric_verification() (server only) stores the result and then automatically evaluates the customer's waiting applications.
- application.submit no longer evaluates immediately if the server check is missing: the application stays 'pending' and the app is told 'awaiting_biometric_verification'.

2. Stock must belong to an agency
- New devices cannot be inserted without an agency (older rows are left alone so existing flows keep working).
- inventory_without_agency: list of devices still to assign.
- assign_stock_to_agency(): system administrator only (new permission stock.manage), in-stock devices only.

3. Local calculator must match the server
- compute_offer(): the single place where the offer maths lives. simulate_loan() now calls it.
- calculator_spec(): version, written rules and test vectors. The offline catalogue now carries it, so the app can check its own calculator after every download and fall back to online-only if it does not match.
*/

-- A. Server-verified biometrics
CREATE TABLE IF NOT EXISTS kyc_biometric_check (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  customer_id uuid NOT NULL REFERENCES customer (id),
  provider varchar(50) NOT NULL,
  liveness_passed boolean NOT NULL,
  face_match_score numeric(4,3) NOT NULL CHECK (face_match_score BETWEEN 0 AND 1),
  selfie_path text NOT NULL,
  document_path text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS kyc_biometric_check_customer_idx ON kyc_biometric_check (customer_id, created_at DESC);

DROP TRIGGER IF EXISTS kyc_biometric_check_block_change_trg ON kyc_biometric_check;
CREATE TRIGGER kyc_biometric_check_block_change_trg BEFORE UPDATE OR DELETE ON kyc_biometric_check
  FOR EACH ROW EXECUTE FUNCTION public.block_row_or_truncate();

DROP TRIGGER IF EXISTS kyc_biometric_check_block_truncate_trg ON kyc_biometric_check;
CREATE TRIGGER kyc_biometric_check_block_truncate_trg BEFORE TRUNCATE ON kyc_biometric_check
  FOR EACH STATEMENT EXECUTE FUNCTION public.block_row_or_truncate();

ALTER TABLE kyc_biometric_check ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS kyc_biometric_check_select ON kyc_biometric_check;
CREATE POLICY kyc_biometric_check_select ON kyc_biometric_check FOR SELECT TO authenticated
  USING (
    public.current_app_role() NOT IN ('client', 'collections')
    AND EXISTS (SELECT 1 FROM public.customer AS c WHERE c.id = customer_id)
  );

REVOKE ALL ON kyc_biometric_check FROM anon, authenticated;
GRANT SELECT ON kyc_biometric_check TO authenticated;
GRANT SELECT, INSERT ON kyc_biometric_check TO service_role;

CREATE OR REPLACE FUNCTION public.biometrics_current(p_customer uuid)
RETURNS boolean
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = ''
AS $$
  SELECT EXISTS (
    SELECT 1
    FROM public.kyc_biometric_check AS b
    JOIN public.kyc_profile AS k ON k.customer_id = b.customer_id
    WHERE b.id = (
        SELECT b2.id FROM public.kyc_biometric_check AS b2
        WHERE b2.customer_id = p_customer
        ORDER BY b2.created_at DESC, b2.id DESC LIMIT 1
      )
      AND b.selfie_path IS NOT DISTINCT FROM k.selfie_path
      AND b.document_path IS NOT DISTINCT FROM k.id_document_photo_path
  );
$$;

CREATE OR REPLACE FUNCTION public.record_biometric_verification(
  p_customer uuid,
  p_provider text,
  p_liveness_passed boolean,
  p_face_match_score numeric,
  p_selfie_path text,
  p_document_path text
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  k public.kyc_profile%ROWTYPE;
  v_app uuid;
  v_decisions jsonb := '[]'::jsonb;
BEGIN
  SELECT * INTO k FROM public.kyc_profile WHERE customer_id = p_customer;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'no KYC profile for this customer';
  END IF;
  IF k.selfie_path IS DISTINCT FROM p_selfie_path OR k.id_document_photo_path IS DISTINCT FROM p_document_path THEN
    RAISE EXCEPTION 'the checked photos are not the current KYC photos';
  END IF;

  INSERT INTO public.kyc_biometric_check (
    customer_id, provider, liveness_passed, face_match_score, selfie_path, document_path
  ) VALUES (
    p_customer, p_provider, p_liveness_passed, p_face_match_score, p_selfie_path, p_document_path
  );

  FOR v_app IN
    SELECT la.id FROM public.loan_application AS la
    WHERE la.customer_id = p_customer AND la.status::text = 'pending'
    ORDER BY la.created_at
  LOOP
    v_decisions := v_decisions || jsonb_build_array(
      jsonb_build_object('application_id', v_app, 'decision', public.evaluate_application(v_app))
    );
  END LOOP;

  RETURN jsonb_build_object('recorded', true, 'evaluated_applications', v_decisions);
END;
$$;

REVOKE EXECUTE ON FUNCTION public.biometrics_current(uuid) FROM PUBLIC, anon, authenticated;
REVOKE EXECUTE ON FUNCTION public.record_biometric_verification(uuid, text, boolean, numeric, text, text) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.biometrics_current(uuid) TO service_role;
GRANT EXECUTE ON FUNCTION public.record_biometric_verification(uuid, text, boolean, numeric, text, text) TO service_role;

-- The two functions below are redefined to use the server-verified biometrics

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
  b_chk public.kyc_biometric_check%ROWTYPE;
  v_bio_found boolean := false;
  v_bio_current boolean := false;
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

  -- Biometrics come from the server check only, never from the phone
  SELECT * INTO b_chk
    FROM public.kyc_biometric_check
    WHERE customer_id = a.customer_id
    ORDER BY created_at DESC, id DESC
    LIMIT 1;
  v_bio_found := FOUND;
  v_bio_current := v_bio_found AND kyc_found
    AND b_chk.selfie_path IS NOT DISTINCT FROM k.selfie_path
    AND b_chk.document_path IS NOT DISTINCT FROM k.id_document_photo_path;

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
    IF NOT v_bio_found THEN
      flags := array_append(flags, 'biometrics_not_verified');
    ELSIF NOT v_bio_current THEN
      flags := array_append(flags, 'biometrics_outdated');
    ELSE
      IF NOT b_chk.liveness_passed THEN
        flags := array_append(flags, 'liveness_failed');
      END IF;
      IF b_chk.face_match_score < pol.face_match_min THEN
        flags := array_append(flags, 'face_match_low');
      END IF;
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
    IF v_bio_current AND b_chk.liveness_passed THEN v_part := v_part + 10; END IF;
    IF v_bio_current AND b_chk.face_match_score >= pol.face_match_min THEN v_part := v_part + 10; END IF;
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

CREATE OR REPLACE FUNCTION public.process_sync_action(
  p_agent uuid,
  p_device text,
  p_key uuid,
  p_action text,
  p_payload jsonb,
  p_client_created_at timestamptz DEFAULT NULL,
  p_client_updated_at timestamptz DEFAULT NULL
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  v_hash text;
  v_prev public.sync_request%ROWTYPE;
  v_agency uuid;
  v_status text := 'applied';
  v_result jsonb := '{}'::jsonb;
  v_error text;
  v_constraint text;
  v_detail text;
  v_state text;
  v_outcome jsonb;
  v_id uuid;
  c public.customer%ROWTYPE;
  k public.kyc_profile%ROWTYPE;
  it public.inventory_item%ROWTYPE;
  v_offer jsonb;
  v_decision jsonb;
  v_down numeric;
  v_duration integer;
  v_freq public.installment_frequency;
  v_contract_id uuid;
  v_contract_status public.contract_status;
  v_contract_agency uuid;
  v_amount numeric;
BEGIN
  IF p_key IS NULL OR p_agent IS NULL OR p_action IS NULL THEN
    RETURN jsonb_build_object('status', 'failed', 'error', 'missing_key_agent_or_action');
  END IF;

  PERFORM pg_advisory_xact_lock(hashtextextended(p_key::text, 0));

  v_hash := encode(
    sha256(convert_to(coalesce(p_payload, '{}'::jsonb)::text || '|' || p_action, 'UTF8')), 'hex');

  SELECT * INTO v_prev FROM public.sync_request WHERE idempotency_key = p_key;
  IF FOUND THEN
    IF v_prev.payload_hash <> v_hash OR v_prev.agent_id <> p_agent THEN
      RETURN jsonb_build_object('status', 'failed', 'error', 'idempotency_key_reused_with_different_payload');
    END IF;
    RETURN v_prev.result || jsonb_build_object('replayed', true);
  END IF;

  PERFORM set_config('app.actor_id', p_agent::text, true);
  PERFORM set_config('app.device_fingerprint', coalesce(p_device, ''), true);

  SELECT u.agency_id INTO v_agency
    FROM public.app_user AS u
    WHERE u.id = p_agent AND u.status = 'active' AND (u.locked_until IS NULL OR u.locked_until < now());
  IF v_agency IS NULL THEN
    RETURN jsonb_build_object('status', 'failed', 'error', 'agent_not_allowed');
  END IF;

  BEGIN
    CASE p_action

      WHEN 'customer.create' THEN
        IF NOT public.abac_allows('prospect.create', NULL, v_agency) THEN
          RAISE EXCEPTION 'forbidden' USING ERRCODE = '42501';
        END IF;
        v_id := coalesce((p_payload ->> 'id')::uuid, gen_random_uuid());
        INSERT INTO public.customer (id, phone_number, first_name, last_name, national_id_number, address, city, agency_id)
        VALUES (
          v_id, p_payload ->> 'phone_number', p_payload ->> 'first_name', p_payload ->> 'last_name',
          p_payload ->> 'national_id_number', p_payload ->> 'address', p_payload ->> 'city', v_agency
        );
        v_result := jsonb_build_object('customer_id', v_id);

      WHEN 'customer.update' THEN
        SELECT * INTO c FROM public.customer WHERE id = (p_payload ->> 'id')::uuid FOR UPDATE;
        IF NOT FOUND THEN
          RAISE EXCEPTION 'unknown_customer';
        END IF;
        IF c.agency_id IS DISTINCT FROM v_agency OR NOT public.abac_allows('prospect.create', NULL, c.agency_id) THEN
          RAISE EXCEPTION 'forbidden' USING ERRCODE = '42501';
        END IF;
        IF p_client_updated_at IS NULL OR p_client_updated_at <= c.updated_at THEN
          RAISE EXCEPTION 'stale_update' USING ERRCODE = 'P0409',
            DETAIL = jsonb_build_object('server_updated_at', c.updated_at)::text;
        END IF;
        UPDATE public.customer SET
          first_name = coalesce(p_payload ->> 'first_name', first_name),
          last_name = coalesce(p_payload ->> 'last_name', last_name),
          address = coalesce(p_payload ->> 'address', address),
          city = coalesce(p_payload ->> 'city', city),
          national_id_number = coalesce(p_payload ->> 'national_id_number', national_id_number)
        WHERE id = c.id;
        v_result := jsonb_build_object('customer_id', c.id);

      WHEN 'kyc.submit' THEN
        SELECT * INTO c FROM public.customer WHERE id = (p_payload ->> 'customer_id')::uuid;
        IF NOT FOUND THEN
          RAISE EXCEPTION 'unknown_customer';
        END IF;
        IF c.agency_id IS DISTINCT FROM v_agency OR NOT public.abac_allows('kyc.collect', NULL, c.agency_id) THEN
          RAISE EXCEPTION 'forbidden' USING ERRCODE = '42501';
        END IF;
        SELECT * INTO k FROM public.kyc_profile WHERE customer_id = c.id FOR UPDATE;
        IF FOUND THEN
          IF k.status = 'verified' THEN
            RAISE EXCEPTION 'kyc_already_verified' USING ERRCODE = 'P0409', DETAIL = '{}';
          END IF;
          IF p_client_updated_at IS NULL OR p_client_updated_at <= k.updated_at THEN
            RAISE EXCEPTION 'stale_update' USING ERRCODE = 'P0409',
              DETAIL = jsonb_build_object('server_updated_at', k.updated_at)::text;
          END IF;
        END IF;
        INSERT INTO public.kyc_profile (
          customer_id, birth_date, commune, neighborhood, occupation, income_source,
          declared_monthly_income_usd, id_document_type, id_document_photo_path, selfie_path,
          ocr_data, ocr_confidence, liveness_passed, face_match_score, status, collected_by
        ) VALUES (
          c.id, (p_payload ->> 'birth_date')::date, p_payload ->> 'commune', p_payload ->> 'neighborhood',
          p_payload ->> 'occupation', p_payload ->> 'income_source',
          (p_payload ->> 'declared_monthly_income_usd')::numeric, p_payload ->> 'id_document_type',
          p_payload ->> 'id_document_photo_path', p_payload ->> 'selfie_path',
          p_payload -> 'ocr_data', (p_payload ->> 'ocr_confidence')::numeric,
          (p_payload ->> 'liveness_passed')::boolean, (p_payload ->> 'face_match_score')::numeric,
          'submitted', p_agent
        )
        ON CONFLICT (customer_id) DO UPDATE SET
          birth_date = EXCLUDED.birth_date,
          commune = EXCLUDED.commune,
          neighborhood = EXCLUDED.neighborhood,
          occupation = EXCLUDED.occupation,
          income_source = EXCLUDED.income_source,
          declared_monthly_income_usd = EXCLUDED.declared_monthly_income_usd,
          id_document_type = EXCLUDED.id_document_type,
          id_document_photo_path = EXCLUDED.id_document_photo_path,
          selfie_path = EXCLUDED.selfie_path,
          ocr_data = EXCLUDED.ocr_data,
          ocr_confidence = EXCLUDED.ocr_confidence,
          liveness_passed = EXCLUDED.liveness_passed,
          face_match_score = EXCLUDED.face_match_score,
          status = 'submitted',
          collected_by = EXCLUDED.collected_by;
        v_result := jsonb_build_object('customer_id', c.id);

      WHEN 'emergency_contact.add' THEN
        SELECT * INTO c FROM public.customer WHERE id = (p_payload ->> 'customer_id')::uuid;
        IF NOT FOUND THEN
          RAISE EXCEPTION 'unknown_customer';
        END IF;
        IF c.agency_id IS DISTINCT FROM v_agency OR NOT public.abac_allows('kyc.collect', NULL, c.agency_id) THEN
          RAISE EXCEPTION 'forbidden' USING ERRCODE = '42501';
        END IF;
        INSERT INTO public.emergency_contact (customer_id, full_name, relationship, phone_number, is_guarantor)
        VALUES (
          c.id, p_payload ->> 'full_name', p_payload ->> 'relationship', p_payload ->> 'phone_number',
          coalesce((p_payload ->> 'is_guarantor')::boolean, false)
        )
        RETURNING id INTO v_id;
        v_result := jsonb_build_object('emergency_contact_id', v_id);

      WHEN 'consent.record' THEN
        SELECT * INTO c FROM public.customer WHERE id = (p_payload ->> 'customer_id')::uuid;
        IF NOT FOUND THEN
          RAISE EXCEPTION 'unknown_customer';
        END IF;
        IF c.agency_id IS DISTINCT FROM v_agency OR NOT public.abac_allows('kyc.collect', NULL, c.agency_id) THEN
          RAISE EXCEPTION 'forbidden' USING ERRCODE = '42501';
        END IF;
        INSERT INTO public.customer_consent (customer_id, consent_type, granted, text_version, collected_by)
        VALUES (
          c.id, p_payload ->> 'consent_type', (p_payload ->> 'granted')::boolean,
          p_payload ->> 'text_version', p_agent
        )
        RETURNING id INTO v_id;
        v_result := jsonb_build_object('consent_id', v_id);

      WHEN 'application.submit' THEN
        SELECT * INTO c FROM public.customer WHERE id = (p_payload ->> 'customer_id')::uuid;
        IF NOT FOUND THEN
          RAISE EXCEPTION 'unknown_customer';
        END IF;
        IF c.agency_id IS DISTINCT FROM v_agency OR NOT public.abac_allows('application.submit', NULL, c.agency_id) THEN
          RAISE EXCEPTION 'forbidden' USING ERRCODE = '42501';
        END IF;

        SELECT * INTO it FROM public.inventory_item WHERE id = (p_payload ->> 'item_id')::uuid FOR UPDATE;
        IF NOT FOUND THEN
          RAISE EXCEPTION 'unknown_device';
        END IF;
        IF it.status <> 'in_stock' OR EXISTS (
          SELECT 1 FROM public.loan_application AS la
          WHERE la.inventory_item_id = it.id AND la.status::text IN ('approved', 'active')
        ) THEN
          RAISE EXCEPTION 'device_unavailable' USING ERRCODE = 'P0409', DETAIL = '{}';
        END IF;

        v_down := (p_payload ->> 'down_payment')::numeric;
        v_duration := (p_payload ->> 'duration_days')::integer;
        v_freq := (p_payload ->> 'frequency')::public.installment_frequency;

        v_offer := public.simulate_loan(it.id, v_down, v_duration, v_freq);
        IF p_payload ? 'expected_total_due'
           AND (p_payload ->> 'expected_total_due')::numeric <> (v_offer ->> 'total_due')::numeric THEN
          RAISE EXCEPTION 'offer_changed' USING ERRCODE = 'P0409', DETAIL = jsonb_build_object('offer', v_offer)::text;
        END IF;

        v_id := public.submit_application(c.id, it.id, v_down, v_duration, v_freq, p_agent);
        IF public.biometrics_current(c.id) THEN
          v_decision := public.evaluate_application(v_id);
        ELSE
          v_decision := jsonb_build_object('outcome', 'awaiting_biometric_verification');
        END IF;
        v_result := jsonb_build_object('application_id', v_id, 'decision', v_decision, 'offer', v_offer);

      WHEN 'payment.cash' THEN
        v_contract_id := (p_payload ->> 'contract_id')::uuid;
        v_amount := (p_payload ->> 'amount')::numeric;
        SELECT lc.status, la.agency_id INTO v_contract_status, v_contract_agency
          FROM public.loan_contract AS lc
          JOIN public.loan_application AS la ON la.id = lc.loan_application_id
          WHERE lc.id = v_contract_id;
        IF NOT FOUND THEN
          RAISE EXCEPTION 'unknown_contract';
        END IF;
        IF v_contract_status <> 'active' THEN
          RAISE EXCEPTION 'contract_not_active';
        END IF;
        IF NOT public.abac_allows('cash.collect', v_amount, v_contract_agency) THEN
          RAISE EXCEPTION 'forbidden' USING ERRCODE = '42501';
        END IF;
        INSERT INTO public.payment (contract_id, external_transaction_id, amount, payment_method, status, collected_by)
        VALUES (v_contract_id, 'CASH-' || p_key::text, v_amount, 'cash', 'pending', p_agent)
        RETURNING id INTO v_id;
        v_result := jsonb_build_object('payment_id', v_id, 'awaiting_reconciliation', true);

      ELSE
        RAISE EXCEPTION 'unknown_action';
    END CASE;
  EXCEPTION WHEN OTHERS THEN
    v_state := SQLSTATE;
    GET STACKED DIAGNOSTICS
      v_error = MESSAGE_TEXT,
      v_constraint = CONSTRAINT_NAME,
      v_detail = PG_EXCEPTION_DETAIL;

    IF v_state = 'P0409' THEN
      v_status := 'conflict';
      v_result := coalesce(nullif(v_detail, '')::jsonb, '{}'::jsonb);
    ELSIF v_state = '23505' THEN
      v_status := 'conflict';
      v_result := '{}'::jsonb;
      v_error := CASE v_constraint
        WHEN 'customer_phone_number_key' THEN 'phone_already_registered'
        WHEN 'loan_application_one_live_loan_per_device_idx' THEN 'device_unavailable'
        ELSE 'duplicate_record' END;
    ELSE
      v_status := 'failed';
      v_result := '{}'::jsonb;
    END IF;
  END;

  v_outcome := jsonb_build_object(
    'idempotency_key', p_key,
    'action', p_action,
    'status', v_status,
    'error', v_error,
    'result', v_result
  );

  INSERT INTO public.sync_request (
    idempotency_key, agent_id, device_fingerprint, action_type, payload_hash,
    status, result, error_code, client_created_at, client_updated_at
  ) VALUES (
    p_key, p_agent, left(p_device, 128), p_action, v_hash,
    v_status, v_outcome, v_error, p_client_created_at, p_client_updated_at
  );

  RETURN v_outcome;
END;
$$;

-- B. Stock per agency
INSERT INTO role_permission (role, permission, agency_scoped)
VALUES ('system_admin', 'stock.manage', false)
ON CONFLICT (role, permission) DO NOTHING;

CREATE OR REPLACE FUNCTION public.inventory_require_agency()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = ''
AS $$
BEGIN
  IF NEW.agency_id IS NULL THEN
    RAISE EXCEPTION 'a new device must be assigned to an agency';
  END IF;
  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS inventory_item_require_agency_trg ON inventory_item;
CREATE TRIGGER inventory_item_require_agency_trg BEFORE INSERT ON inventory_item
  FOR EACH ROW EXECUTE FUNCTION public.inventory_require_agency();

CREATE OR REPLACE VIEW inventory_without_agency
WITH (security_invoker = true) AS
  SELECT id, brand, model, color, status, created_at
  FROM inventory_item
  WHERE agency_id IS NULL;

REVOKE ALL ON inventory_without_agency FROM anon, authenticated;
GRANT SELECT ON inventory_without_agency TO service_role;

CREATE OR REPLACE FUNCTION public.assign_stock_to_agency(
  p_admin uuid,
  p_items uuid[],
  p_agency uuid
)
RETURNS integer
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  v_count integer;
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM public.app_user AS u
    JOIN public.role_permission AS rp ON rp.role = u.role
    WHERE u.id = p_admin AND u.status = 'active' AND rp.permission = 'stock.manage'
  ) THEN
    RAISE EXCEPTION 'only the system administrator can assign stock' USING ERRCODE = '42501';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM public.agency WHERE id = p_agency) THEN
    RAISE EXCEPTION 'unknown agency';
  END IF;
  IF p_items IS NULL OR cardinality(p_items) = 0 THEN
    RAISE EXCEPTION 'no devices provided';
  END IF;
  IF EXISTS (
    SELECT 1 FROM public.inventory_item AS it
    WHERE it.id = ANY (p_items) AND it.status <> 'in_stock'
  ) OR (SELECT count(*) FROM public.inventory_item AS it WHERE it.id = ANY (p_items)) <> cardinality(p_items) THEN
    RAISE EXCEPTION 'every device must exist and be in stock to change its agency';
  END IF;

  PERFORM set_config('app.actor_id', p_admin::text, true);

  UPDATE public.inventory_item SET agency_id = p_agency WHERE id = ANY (p_items);
  GET DIAGNOSTICS v_count = ROW_COUNT;
  RETURN v_count;
END;
$$;

REVOKE EXECUTE ON FUNCTION public.inventory_require_agency() FROM PUBLIC, anon, authenticated;
REVOKE EXECUTE ON FUNCTION public.assign_stock_to_agency(uuid, uuid[], uuid) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.assign_stock_to_agency(uuid, uuid[], uuid) TO service_role;

-- C. Calculator parity
CREATE OR REPLACE FUNCTION public.compute_offer(
  p_retail numeric,
  p_down numeric,
  p_duration_days integer,
  p_frequency public.installment_frequency,
  p_start date,
  p_fee_rate numeric,
  p_interest_rate_30d numeric
)
RETURNS jsonb
LANGUAGE plpgsql
STABLE
SET search_path = ''
AS $$
DECLARE
  v_fee numeric;
  v_financed numeric;
  v_interest numeric;
  v_total numeric;
  v_count integer;
  v_first numeric;
  v_last numeric;
  v_first_due date;
  v_end date;
BEGIN
  v_fee := round(p_retail * p_fee_rate, 2);
  v_financed := p_retail + v_fee - p_down;
  v_interest := round(v_financed * p_interest_rate_30d * p_duration_days / 30.0, 2);
  v_total := v_financed + v_interest;

  SELECT count(*), min(amount_due) FILTER (WHERE installment_number = 1), max(due_date)
    INTO v_count, v_first, v_end
    FROM public.installment_plan(p_start, p_frequency, p_duration_days, v_total);

  SELECT amount_due INTO v_last
    FROM public.installment_plan(p_start, p_frequency, p_duration_days, v_total)
    ORDER BY installment_number DESC LIMIT 1;

  SELECT due_date INTO v_first_due
    FROM public.installment_plan(p_start, p_frequency, p_duration_days, v_total)
    ORDER BY installment_number LIMIT 1;

  RETURN jsonb_build_object(
    'processing_fee', v_fee,
    'financed_amount', v_financed,
    'interest_amount', v_interest,
    'total_due', v_total,
    'installment_count', v_count,
    'installment_amount', v_first,
    'last_installment_amount', v_last,
    'first_due_date', v_first_due,
    'end_date', v_end
  );
END;
$$;

CREATE OR REPLACE FUNCTION public.calculator_spec()
RETURNS jsonb
LANGUAGE sql
STABLE
SET search_path = ''
AS $$
  SELECT jsonb_build_object(
    'version', 'v1',
    'rules', jsonb_build_object(
      'currency', 'USD',
      'rounding', 'half away from zero, 2 decimals',
      'processing_fee', 'round(retail_price * processing_fee_rate, 2)',
      'financed_amount', 'retail_price + processing_fee - down_payment',
      'interest_amount', 'round(financed_amount * interest_rate_per_30_days * duration_days / 30, 2)',
      'total_due', 'financed_amount + interest_amount',
      'period_days', jsonb_build_object('daily', 1, 'weekly', 7, 'monthly', 30),
      'installment_count', 'ceil(duration_days / period_days)',
      'installment_amount', 'round(total_due / installment_count, 2) for all but the last installment',
      'last_installment_amount', 'total_due - installment_amount * (installment_count - 1)',
      'due_dates', 'daily and weekly: start_date + n * period_days. monthly: start_date + n calendar months, counted from start_date each time (not month after month)'
    ),
    'test_vectors', (
      SELECT jsonb_agg(
        jsonb_build_object(
          'input', jsonb_build_object(
            'retail_price', v.r, 'down_payment', v.d, 'duration_days', v.n, 'frequency', v.f,
            'start_date', v.s, 'processing_fee_rate', v.fee, 'interest_rate_per_30_days', v.i
          ),
          'expected', public.compute_offer(v.r, v.d, v.n, v.f::public.installment_frequency, v.s, v.fee, v.i)
        )
      )
      FROM (VALUES
        (100::numeric, 30::numeric, 90, 'weekly', DATE '2026-01-01', 0.03::numeric, 0.06::numeric),
        (149.99::numeric, 45::numeric, 60, 'daily', DATE '2026-01-31', 0.03::numeric, 0.06::numeric),
        (250::numeric, 75::numeric, 120, 'monthly', DATE '2026-01-31', 0.03::numeric, 0.06::numeric),
        (80.50::numeric, 24.15::numeric, 30, 'weekly', DATE '2026-02-15', 0::numeric, 0::numeric),
        (199.99::numeric, 60::numeric, 180, 'monthly', DATE '2026-03-01', 0.05::numeric, 0.075::numeric)
      ) AS v(r, d, n, f, s, fee, i)
    )
  );
$$;

REVOKE EXECUTE ON FUNCTION public.compute_offer(numeric, numeric, integer, public.installment_frequency, date, numeric, numeric) FROM PUBLIC, anon;
REVOKE EXECUTE ON FUNCTION public.calculator_spec() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.compute_offer(numeric, numeric, integer, public.installment_frequency, date, numeric, numeric) TO authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.calculator_spec() TO authenticated, service_role;

-- simulate_loan and offline_catalogue redefined on top of the above

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
  v_calc jsonb;
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

  v_calc := public.compute_offer(
    it.retail_price, p_down_payment, p_duration_days, p_frequency, p_start,
    pol.processing_fee_rate, pol.interest_rate_per_30_days);
  v_fee := (v_calc ->> 'processing_fee')::numeric;
  v_financed := (v_calc ->> 'financed_amount')::numeric;
  v_interest := (v_calc ->> 'interest_amount')::numeric;
  v_total := (v_calc ->> 'total_due')::numeric;
  v_count := (v_calc ->> 'installment_count')::integer;
  v_first_amount := (v_calc ->> 'installment_amount')::numeric;
  v_last_amount := (v_calc ->> 'last_installment_amount')::numeric;
  v_first_due := (v_calc ->> 'first_due_date')::date;
  v_end := (v_calc ->> 'end_date')::date;

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

CREATE OR REPLACE FUNCTION public.offline_catalogue(p_agent uuid)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  v_agency uuid;
  pol public.credit_policy%ROWTYPE;
BEGIN
  PERFORM set_config('app.actor_id', p_agent::text, true);

  SELECT u.agency_id INTO v_agency FROM public.app_user AS u WHERE u.id = p_agent AND u.status = 'active';
  IF v_agency IS NULL OR NOT public.abac_allows('catalogue.read', NULL, v_agency) THEN
    RAISE EXCEPTION 'not allowed to read the catalogue' USING ERRCODE = '42501';
  END IF;

  SELECT * INTO pol FROM public.credit_policy;

  RETURN jsonb_build_object(
    'generated_at', now(),
    'currency', 'USD',
    'calculator', public.calculator_spec(),
    'terms', jsonb_build_object(
      'down_payment_min_pct', pol.down_payment_min_pct,
      'down_payment_max_pct', pol.down_payment_max_pct,
      'allowed_durations', to_jsonb(pol.allowed_durations),
      'processing_fee_rate', pol.processing_fee_rate,
      'interest_rate_per_30_days', pol.interest_rate_per_30_days,
      'penalty_rate_per_day', pol.penalty_rate_per_day,
      'grace_days', pol.grace_days,
      'lock_after_days_overdue', pol.lock_after_days_overdue
    ),
    'items', coalesce((
      SELECT jsonb_agg(jsonb_build_object(
        'item_id', it.id,
        'brand', it.brand,
        'model', it.model,
        'color', it.color,
        'retail_price', it.retail_price,
        'min_down_payment_pct', coalesce(it.min_down_payment_pct, pol.down_payment_min_pct),
        'imei_suffix', right(it.imei, 4)
      ) ORDER BY it.brand, it.model)
      FROM public.inventory_item AS it
      WHERE it.agency_id = v_agency AND it.status = 'in_stock'
    ), '[]'::jsonb)
  );
END;
$$;
