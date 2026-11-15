-- Checks the biometric work queue (step 7). Everything is rolled back at the end.
BEGIN;

DO $$
DECLARE
  v_agency uuid; v_agent uuid := gen_random_uuid(); v_item uuid; v_customer uuid; v_app uuid;
  v_n integer; v_status text; v_i integer;
BEGIN
  INSERT INTO agency (name, region) VALUES ('Agence Bio CI', 'Test') RETURNING id INTO v_agency;
  INSERT INTO auth.users (id, email) VALUES (v_agent, 'agent-bio@test');
  INSERT INTO app_user (id, role, status, mfa_method, agency_id) VALUES (v_agent, 'field_agent', 'active', 'totp', v_agency);

  INSERT INTO inventory_item (imei, model, brand, color, purchase_price, retail_price, agency_id)
  VALUES ('356923100000028', 'Spark 20', 'Tecno', 'Noir', 70, 100, v_agency) RETURNING id INTO v_item;

  INSERT INTO customer (phone_number, first_name, last_name, national_id_number, address, city, agency_id)
  VALUES ('+243810000011', 'Paul', 'Bio', 'ID-BIO-001', 'Av. Test 2', 'Lubumbashi', v_agency) RETURNING id INTO v_customer;

  INSERT INTO customer_consent (customer_id, consent_type, granted, text_version, collected_by)
  SELECT v_customer, t, true, 'v1', v_agent FROM unnest(ARRAY['data_processing','risk_profile_check','imei_registration','remote_lock']) AS t;

  INSERT INTO kyc_profile (customer_id, birth_date, commune, neighborhood, occupation, income_source,
    declared_monthly_income_usd, phone_validated_at, id_document_type, id_document_photo_path, selfie_path,
    ocr_confidence, status, collected_by)
  VALUES (v_customer, DATE '1990-01-01', 'Lubumbashi', 'Centre', 'Commercant', 'Boutique',
    400, now(), 'voter_card', 'bio-doc.jpg', 'bio-selfie.jpg', 0.95, 'submitted', v_agent);

  INSERT INTO emergency_contact (customer_id, full_name, relationship, phone_number, verified_at)
  VALUES (v_customer, 'Marie Bio', 'Soeur', '+243810000012', now());

  -- No application yet: nothing to check
  SELECT count(*) INTO v_n FROM claim_biometric_work(10) WHERE customer_id = v_customer;
  IF v_n <> 0 THEN RAISE EXCEPTION 'no waiting application: nothing should be claimed'; END IF;

  v_app := submit_application(v_customer, v_item, 30, 90, 'weekly', v_agent);
  SELECT status::text INTO v_status FROM loan_application WHERE id = v_app;
  IF v_status <> 'pending' THEN RAISE EXCEPTION 'application should be pending, is %', v_status; END IF;

  SELECT count(*) INTO v_n FROM claim_biometric_work(10) WHERE customer_id = v_customer;
  IF v_n <> 1 THEN RAISE EXCEPTION 'customer should be claimed once, got %', v_n; END IF;

  -- A failure delays the next try
  IF fail_biometric_work(v_customer, 'bio-selfie.jpg', 'bio-doc.jpg', 'provider down') <> 1 THEN
    RAISE EXCEPTION 'first failure should count 1';
  END IF;
  SELECT count(*) INTO v_n FROM claim_biometric_work(10) WHERE customer_id = v_customer;
  IF v_n <> 0 THEN RAISE EXCEPTION 'a failed customer must wait before the next try'; END IF;

  -- After 5 failures the customer is left alone even when the delay has passed
  FOR v_i IN 2..5 LOOP
    PERFORM fail_biometric_work(v_customer, 'bio-selfie.jpg', 'bio-doc.jpg', 'provider down');
  END LOOP;
  UPDATE kyc_biometric_attempt SET next_try_at = now() - interval '1 hour' WHERE customer_id = v_customer;
  SELECT count(*) INTO v_n FROM claim_biometric_work(10) WHERE customer_id = v_customer;
  IF v_n <> 0 THEN RAISE EXCEPTION 'after 5 failures the customer must not be claimed again'; END IF;

  -- New photos start a fresh attempt series
  UPDATE kyc_profile SET selfie_path = 'bio-selfie-2.jpg' WHERE customer_id = v_customer;
  SELECT count(*) INTO v_n FROM claim_biometric_work(10) WHERE customer_id = v_customer;
  IF v_n <> 1 THEN RAISE EXCEPTION 'new photos must be claimed again'; END IF;

  -- Recording the result evaluates the waiting application and ends the claim
  PERFORM record_biometric_verification(v_customer, 'ci', true, 0.95, 'bio-selfie-2.jpg', 'bio-doc.jpg');
  SELECT status::text INTO v_status FROM loan_application WHERE id = v_app;
  IF v_status = 'pending' THEN RAISE EXCEPTION 'the application should have been evaluated'; END IF;
  SELECT count(*) INTO v_n FROM claim_biometric_work(10) WHERE customer_id = v_customer;
  IF v_n <> 0 THEN RAISE EXCEPTION 'a checked customer must not be claimed'; END IF;

  RAISE NOTICE 'biometric queue test passed (application is now %)', v_status;
END $$;

ROLLBACK;
