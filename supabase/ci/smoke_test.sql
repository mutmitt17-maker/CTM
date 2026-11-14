-- End-to-end scenario on a database where all migrations were applied.
-- It follows one customer from onboarding to ownership transfer and checks the key rules.
-- Everything is rolled back at the end.
BEGIN;

DO $$
DECLARE
  v_agency uuid; v_admin uuid := gen_random_uuid(); v_agent uuid := gen_random_uuid();
  v_item uuid; v_customer uuid; v_app uuid; v_contract uuid; v_delivery uuid;
  v_eval jsonb; v_pay jsonb; v_out numeric; v_n integer; v_state text; v_flag boolean;
BEGIN
  INSERT INTO agency (name, region) VALUES ('Agence CI', 'Test') RETURNING id INTO v_agency;

  INSERT INTO auth.users (id, email) VALUES (v_admin, 'admin@test'), (v_agent, 'agent@test');
  INSERT INTO app_user (id, role, status, mfa_method) VALUES (v_admin, 'system_admin', 'active', 'totp');
  INSERT INTO app_user (id, role, status, mfa_method, agency_id) VALUES (v_agent, 'field_agent', 'active', 'totp', v_agency);

  INSERT INTO inventory_item (imei, model, brand, color, purchase_price, retail_price, agency_id)
  VALUES ('356923100000010', 'Spark 20', 'Tecno', 'Noir', 70, 100, v_agency) RETURNING id INTO v_item;

  INSERT INTO customer (phone_number, first_name, last_name, national_id_number, address, city, agency_id)
  VALUES ('+243810000001', 'Jean', 'Test', 'ID-CI-001', 'Av. Test 1', 'Lubumbashi', v_agency) RETURNING id INTO v_customer;

  INSERT INTO customer_consent (customer_id, consent_type, granted, text_version, collected_by)
  SELECT v_customer, t, true, 'v1', v_agent FROM unnest(ARRAY['data_processing','risk_profile_check','imei_registration','remote_lock']) AS t;

  INSERT INTO kyc_profile (customer_id, birth_date, commune, neighborhood, occupation, income_source,
    declared_monthly_income_usd, phone_validated_at, id_document_type, id_document_photo_path, selfie_path,
    ocr_confidence, status, collected_by)
  VALUES (v_customer, DATE '1990-01-01', 'Lubumbashi', 'Centre', 'Commercant', 'Boutique',
    400, now(), 'voter_card', 'doc.jpg', 'selfie.jpg', 0.95, 'submitted', v_agent);

  INSERT INTO emergency_contact (customer_id, full_name, relationship, phone_number, verified_at)
  VALUES (v_customer, 'Marie Test', 'Soeur', '+243810000002', now());

  PERFORM record_biometric_verification(v_customer, 'ci', true, 0.95, 'selfie.jpg', 'doc.jpg');

  v_app := submit_application(v_customer, v_item, 30, 90, 'weekly', v_agent);
  v_eval := evaluate_application(v_app);
  IF v_eval ->> 'outcome' <> 'approved' THEN
    RAISE EXCEPTION 'expected approved, got %', v_eval;
  END IF;

  v_delivery := start_delivery(v_agent, v_app);
  SELECT contract_id INTO v_contract FROM delivery WHERE id = v_delivery;
  IF NOT (verify_delivery_imei(v_agent, v_delivery, '356923100000010') ->> 'matched')::boolean THEN
    RAISE EXCEPTION 'IMEI should match';
  END IF;
  PERFORM record_handover_proof(v_agent, v_delivery, 'photo.jpg', 'sighash', 'fp-agent');
  PERFORM request_mdm_enrollment(v_agent, v_delivery);
  PERFORM confirm_mdm_enrollment(v_delivery, true, 'enroll-ref');
  PERFORM complete_delivery(v_agent, v_delivery);

  SELECT lock_state INTO v_state FROM managed_device WHERE contract_id = v_contract;
  IF v_state <> 'unlocked' THEN RAISE EXCEPTION 'device should be unlocked, is %', v_state; END IF;

  -- ledger is append-only
  v_flag := false;
  BEGIN
    UPDATE ledger_entry SET amount = 1;
  EXCEPTION WHEN OTHERS THEN
    v_flag := true;
  END;
  IF NOT v_flag THEN RAISE EXCEPTION 'ledger update should be refused'; END IF;

  -- partial payment, duplicate reference, then payoff
  v_out := contract_outstanding(v_contract);
  v_pay := process_mobile_money_payment('ci', 'TX-001', v_contract, 10, 'mpesa', '{}'::jsonb);
  IF v_pay ->> 'status' <> 'applied' THEN RAISE EXCEPTION 'partial payment not applied: %', v_pay; END IF;

  v_pay := process_mobile_money_payment('ci', ' tx-001 ', v_contract, 10, 'mpesa', '{}'::jsonb);
  IF v_pay ->> 'status' <> 'duplicate' THEN RAISE EXCEPTION 'duplicate not detected: %', v_pay; END IF;

  v_pay := process_mobile_money_payment('ci', 'TX-002', v_contract, contract_outstanding(v_contract) + 1, 'mpesa', '{}'::jsonb);
  IF v_pay ->> 'status' <> 'needs_review' THEN RAISE EXCEPTION 'excess not held: %', v_pay; END IF;

  v_pay := process_mobile_money_payment('ci', 'TX-003', v_contract, contract_outstanding(v_contract), 'mpesa', '{}'::jsonb);
  IF v_pay ->> 'status' <> 'applied' OR NOT (v_pay ->> 'contract_closed')::boolean THEN
    RAISE EXCEPTION 'payoff failed: %', v_pay;
  END IF;

  IF (SELECT status FROM loan_contract WHERE id = v_contract) <> 'closed' THEN RAISE EXCEPTION 'contract not closed'; END IF;
  IF (SELECT status::text FROM loan_application WHERE id = v_app) <> 'completed' THEN RAISE EXCEPTION 'application not completed'; END IF;
  SELECT lock_state INTO v_state FROM managed_device WHERE contract_id = v_contract;
  IF v_state <> 'released' THEN RAISE EXCEPTION 'device should be released, is %', v_state; END IF;
  SELECT count(*) INTO v_n FROM closure_document WHERE contract_id = v_contract;
  IF v_n <> 2 THEN RAISE EXCEPTION 'expected 2 closure documents, got %', v_n; END IF;
  IF (SELECT status::text FROM inventory_item WHERE id = v_item) <> 'sold' THEN RAISE EXCEPTION 'device should be sold'; END IF;

  RAISE NOTICE 'SMOKE TEST PASSED';
END $$;

ROLLBACK;
