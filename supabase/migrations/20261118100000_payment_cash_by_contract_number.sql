/*
Migration: payment.cash accepts a contract number.

The agent app lets the agent type the contract number (for example CT-2026-000123).
process_sync_action now resolves it to the contract id when the payload has no contract_id.
An unknown number still ends as 'unknown_contract'. Nothing else in the function changes.
Existing grants are kept by CREATE OR REPLACE.
*/

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
        -- L'agent saisit le numéro de contrat (CT-AAAA-NNNNNN) ; l'identifiant reste accepté.
        IF nullif(p_payload ->> 'contract_id', '') IS NOT NULL THEN
          v_contract_id := (p_payload ->> 'contract_id')::uuid;
        ELSE
          SELECT lc.id INTO v_contract_id
            FROM public.loan_contract AS lc
            WHERE lc.contract_number = upper(btrim(coalesce(p_payload ->> 'contract_number', '')));
        END IF;
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
