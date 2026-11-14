/*
# Step 4: server side of the offline-first agent app (sync, offline catalogue, delivery, field log)

Plain English: the Android app itself (Room, SQLCipher, camera, scanner, QR enrolment) is client code. This migration is what the server must provide for it: a safe idempotent sync endpoint, a catalogue the agent can download for offline simulation, the delivery flow with IMEI scan and MDM gate, and a field activity log.

1. Sync (idempotent, batch)
- sync_request: one row per client action, keyed by the UUID idempotency key. Replaying the same key returns the stored outcome; reusing a key with a different payload is rejected.
- process_sync_action / process_sync_batch: apply queued actions as the agent. Supported actions: customer.create, customer.update, kyc.submit, emergency_contact.add, consent.record, application.submit, payment.cash.
- Outcomes: applied, conflict (business conflict reported to the agent, e.g. phone already registered, device taken by another agent, stale update, offer changed) or failed.
- Conflict rule: last update wins (client_updated_at must be newer than the server row), plus server constraints.
- application.submit also checks the device is still free, optionally compares the expected total due, then runs the risk engine immediately.
- Client-generated UUIDs are accepted for new customers so later queued actions can reference them.

2. Offline catalogue
- offline_catalogue(agent): in-stock devices of the agent's agency (no purchase price, no full IMEI) plus the commercial terms needed by the local calculator (no scoring thresholds).

3. Delivery (online only, server-validated)
- delivery: IMEI scan check (Luhn + match with the contracted device), handover photo, e-signature, MDM enrolment state
- The database itself refuses to mark a delivery completed unless IMEI verified, proof recorded and MDM enrolment confirmed by the server (confirm_mdm_enrollment is server only)
- There is no unlock function for agents anywhere.

4. Field log and dashboard
- field_activity_log: append-only, with optional GPS
- agent_target + agent_dashboard(): targets, progress, application counts, overdue alerts of the agent's portfolio

5. Changes to existing objects
- payment.collected_by (frozen after insert)
- New permissions: field_agent gets cash.collect, delivery.perform, catalogue.read; distributor gets delivery.perform
- The rule "the person who hands over cannot be the person who created the file" is removed, because step 4 has the same agent create the file and deliver the phone. The hand-over person must still differ from the approver.
- Cash payments from the field are recorded as 'pending' until Finance reconciles them.
*/

-- 0. Existing objects
ALTER TABLE loan_application DROP CONSTRAINT IF EXISTS loan_application_handover_not_creator;

ALTER TABLE payment ADD COLUMN IF NOT EXISTS collected_by uuid REFERENCES app_user (id);

CREATE OR REPLACE FUNCTION public.payment_guard_update()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = ''
AS $$
BEGIN
  IF NEW.contract_id IS DISTINCT FROM OLD.contract_id
     OR NEW.external_transaction_id IS DISTINCT FROM OLD.external_transaction_id
     OR NEW.amount IS DISTINCT FROM OLD.amount
     OR NEW.payment_method IS DISTINCT FROM OLD.payment_method
     OR NEW.collected_by IS DISTINCT FROM OLD.collected_by
     OR NEW.created_at IS DISTINCT FROM OLD.created_at
  THEN
    RAISE EXCEPTION 'payment details are immutable: record a reversal instead';
  END IF;

  IF NEW.status IS DISTINCT FROM OLD.status AND OLD.status <> 'pending' THEN
    RAISE EXCEPTION 'payment status % is final and cannot change to %', OLD.status, NEW.status;
  END IF;

  RETURN NEW;
END;
$$;

INSERT INTO role_permission (role, permission, agency_scoped) VALUES
  ('field_agent', 'cash.collect', true),
  ('field_agent', 'delivery.perform', true),
  ('field_agent', 'catalogue.read', true),
  ('distributor', 'delivery.perform', true)
ON CONFLICT (role, permission) DO NOTHING;

-- 1. Tables
CREATE TABLE IF NOT EXISTS sync_request (
  idempotency_key uuid PRIMARY KEY,
  agent_id uuid NOT NULL REFERENCES app_user (id),
  device_fingerprint varchar(128),
  action_type varchar(40) NOT NULL,
  payload_hash varchar(64) NOT NULL,
  status varchar(10) NOT NULL CHECK (status IN ('applied', 'conflict', 'failed')),
  result jsonb NOT NULL,
  error_code text,
  client_created_at timestamptz,
  client_updated_at timestamptz,
  received_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS sync_request_agent_idx ON sync_request (agent_id, received_at DESC);

CREATE TABLE IF NOT EXISTS field_activity_log (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  event_id uuid NOT NULL UNIQUE,
  agent_id uuid NOT NULL REFERENCES app_user (id),
  device_fingerprint varchar(128),
  action varchar(60) NOT NULL,
  entity_type varchar(40),
  entity_id uuid,
  client_timestamp timestamptz NOT NULL,
  latitude numeric(9,6) CHECK (latitude IS NULL OR latitude BETWEEN -90 AND 90),
  longitude numeric(9,6) CHECK (longitude IS NULL OR longitude BETWEEN -180 AND 180),
  gps_accuracy_m numeric(8,2) CHECK (gps_accuracy_m IS NULL OR gps_accuracy_m >= 0),
  details jsonb NOT NULL DEFAULT '{}'::jsonb,
  received_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS field_activity_agent_idx ON field_activity_log (agent_id, client_timestamp DESC);

CREATE TABLE IF NOT EXISTS agent_target (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  agent_id uuid NOT NULL REFERENCES app_user (id),
  period_start date NOT NULL,
  period_end date NOT NULL,
  target_applications integer NOT NULL DEFAULT 0 CHECK (target_applications >= 0),
  target_collections_usd numeric(14,2) NOT NULL DEFAULT 0 CHECK (target_collections_usd >= 0),
  created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT agent_target_period CHECK (period_end >= period_start),
  CONSTRAINT agent_target_unique UNIQUE (agent_id, period_start)
);

COMMENT ON COLUMN agent_target.target_collections_usd IS 'Amount in USD (system currency)';

CREATE TABLE IF NOT EXISTS delivery (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  application_id uuid NOT NULL REFERENCES loan_application (id),
  contract_id uuid NOT NULL REFERENCES loan_contract (id),
  agent_id uuid NOT NULL REFERENCES app_user (id),
  status varchar(15) NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'completed', 'cancelled')),
  scanned_imei varchar(15),
  imei_attempts integer NOT NULL DEFAULT 0 CHECK (imei_attempts >= 0),
  imei_verified_at timestamptz,
  handover_photo_path text,
  proof_recorded_at timestamptz,
  mdm_status varchar(20) NOT NULL DEFAULT 'not_started'
    CHECK (mdm_status IN ('not_started', 'token_requested', 'enrolled', 'failed')),
  mdm_enrollment_ref varchar(100),
  mdm_updated_at timestamptz,
  handed_over_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT delivery_completion_requires_all_checks CHECK (
    status <> 'completed' OR (
      imei_verified_at IS NOT NULL
      AND proof_recorded_at IS NOT NULL
      AND mdm_status = 'enrolled'
      AND handed_over_at IS NOT NULL
    )
  )
);

CREATE UNIQUE INDEX IF NOT EXISTS delivery_one_open_per_application_idx
  ON delivery (application_id) WHERE status <> 'cancelled';

CREATE OR REPLACE FUNCTION public.delivery_guard_update()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = ''
AS $$
BEGIN
  IF OLD.status IN ('completed', 'cancelled') THEN
    RAISE EXCEPTION 'a % delivery cannot be changed', OLD.status;
  END IF;
  IF NEW.application_id IS DISTINCT FROM OLD.application_id
     OR NEW.contract_id IS DISTINCT FROM OLD.contract_id
     OR NEW.agent_id IS DISTINCT FROM OLD.agent_id THEN
    RAISE EXCEPTION 'delivery application, contract and agent are fixed';
  END IF;
  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS delivery_guard_update_trg ON delivery;
CREATE TRIGGER delivery_guard_update_trg BEFORE UPDATE ON delivery
  FOR EACH ROW EXECUTE FUNCTION public.delivery_guard_update();

DROP TRIGGER IF EXISTS delivery_set_updated_at ON delivery;
CREATE TRIGGER delivery_set_updated_at BEFORE UPDATE ON delivery
  FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

DROP TRIGGER IF EXISTS delivery_block_delete_trg ON delivery;
CREATE TRIGGER delivery_block_delete_trg BEFORE DELETE ON delivery
  FOR EACH ROW EXECUTE FUNCTION public.block_row_or_truncate();

DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['sync_request', 'field_activity_log']
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

  FOREACH t IN ARRAY ARRAY['delivery', 'agent_target']
  LOOP
    EXECUTE format('DROP TRIGGER IF EXISTS %I ON public.%I', t || '_audit_trg', t);
    EXECUTE format(
      'CREATE TRIGGER %I AFTER INSERT OR UPDATE OR DELETE ON public.%I FOR EACH ROW EXECUTE FUNCTION public.audit_row_change()',
      t || '_audit_trg', t
    );
  END LOOP;
END $$;

-- 2. IMEI check (15 digits + Luhn)
CREATE OR REPLACE FUNCTION public.is_valid_imei(p text)
RETURNS boolean
LANGUAGE plpgsql
IMMUTABLE
SET search_path = ''
AS $$
DECLARE
  i integer;
  d integer;
  s integer := 0;
BEGIN
  IF p IS NULL OR p !~ '^[0-9]{15}$' THEN
    RETURN false;
  END IF;
  FOR i IN 1..15 LOOP
    d := substr(p, i, 1)::integer;
    IF i % 2 = 0 THEN
      d := d * 2;
      IF d > 9 THEN d := d - 9; END IF;
    END IF;
    s := s + d;
  END LOOP;
  RETURN s % 10 = 0;
END;
$$;

-- 3. Idempotent sync of one action
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
        v_decision := public.evaluate_application(v_id);
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

CREATE OR REPLACE FUNCTION public.process_sync_batch(
  p_agent uuid,
  p_device text,
  p_items jsonb
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  v_item jsonb;
  v_out jsonb := '[]'::jsonb;
BEGIN
  IF p_items IS NULL OR jsonb_typeof(p_items) <> 'array' THEN
    RAISE EXCEPTION 'items must be a JSON array';
  END IF;
  IF jsonb_array_length(p_items) > 200 THEN
    RAISE EXCEPTION 'a batch holds at most 200 actions';
  END IF;

  FOR v_item IN SELECT e.value FROM jsonb_array_elements(p_items) WITH ORDINALITY AS e(value, ord) ORDER BY e.ord
  LOOP
    BEGIN
      v_out := v_out || jsonb_build_array(
        public.process_sync_action(
          p_agent, p_device,
          (v_item ->> 'idempotency_key')::uuid,
          v_item ->> 'action',
          v_item -> 'payload',
          (v_item ->> 'client_created_at')::timestamptz,
          (v_item ->> 'client_updated_at')::timestamptz
        )
      );
    EXCEPTION WHEN OTHERS THEN
      v_out := v_out || jsonb_build_array(jsonb_build_object(
        'idempotency_key', v_item ->> 'idempotency_key',
        'status', 'failed',
        'error', 'malformed_item'
      ));
    END;
  END LOOP;

  RETURN v_out;
END;
$$;

-- 4. Field activity log (batch, idempotent by event id)
CREATE OR REPLACE FUNCTION public.log_field_activities(
  p_agent uuid,
  p_device text,
  p_events jsonb
)
RETURNS integer
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  v_count integer;
BEGIN
  IF NOT EXISTS (SELECT 1 FROM public.app_user AS u WHERE u.id = p_agent AND u.status = 'active') THEN
    RAISE EXCEPTION 'agent not allowed' USING ERRCODE = '42501';
  END IF;
  IF p_events IS NULL OR jsonb_typeof(p_events) <> 'array' OR jsonb_array_length(p_events) > 500 THEN
    RAISE EXCEPTION 'events must be a JSON array of at most 500 items';
  END IF;

  INSERT INTO public.field_activity_log (
    event_id, agent_id, device_fingerprint, action, entity_type, entity_id,
    client_timestamp, latitude, longitude, gps_accuracy_m, details
  )
  SELECT
    (e ->> 'event_id')::uuid, p_agent, left(p_device, 128), e ->> 'action', e ->> 'entity_type',
    (e ->> 'entity_id')::uuid, (e ->> 'client_timestamp')::timestamptz,
    (e ->> 'latitude')::numeric, (e ->> 'longitude')::numeric, (e ->> 'gps_accuracy_m')::numeric,
    coalesce(e -> 'details', '{}'::jsonb)
  FROM jsonb_array_elements(p_events) AS e
  ON CONFLICT (event_id) DO NOTHING;

  GET DIAGNOSTICS v_count = ROW_COUNT;
  RETURN v_count;
END;
$$;

-- 5. Offline catalogue for the local calculator
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

-- 6. Delivery flow
CREATE OR REPLACE FUNCTION public.start_delivery(p_agent uuid, p_application uuid)
RETURNS uuid
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  a public.loan_application%ROWTYPE;
  v_contract uuid;
  v_id uuid;
BEGIN
  PERFORM set_config('app.actor_id', p_agent::text, true);

  SELECT * INTO a FROM public.loan_application WHERE id = p_application FOR UPDATE;
  IF NOT FOUND OR a.status::text <> 'approved' THEN
    RAISE EXCEPTION 'only an approved application can be delivered';
  END IF;
  IF NOT public.abac_allows('delivery.perform', NULL, a.agency_id) THEN
    RAISE EXCEPTION 'not allowed to deliver this application' USING ERRCODE = '42501';
  END IF;
  IF p_agent IS NOT DISTINCT FROM a.approved_by THEN
    RAISE EXCEPTION 'the person who approved the file cannot hand over the device' USING ERRCODE = '42501';
  END IF;

  SELECT d.id INTO v_id FROM public.delivery AS d WHERE d.application_id = a.id AND d.status <> 'cancelled';
  IF FOUND THEN
    RETURN v_id;
  END IF;

  SELECT lc.id INTO v_contract
    FROM public.loan_contract AS lc
    WHERE lc.loan_application_id = a.id AND lc.status = 'active';
  IF v_contract IS NULL THEN
    v_contract := public.generate_contract(a.id);
  END IF;

  INSERT INTO public.delivery (application_id, contract_id, agent_id)
  VALUES (a.id, v_contract, p_agent)
  RETURNING id INTO v_id;

  RETURN v_id;
END;
$$;

CREATE OR REPLACE FUNCTION public.verify_delivery_imei(p_agent uuid, p_delivery uuid, p_scanned_imei text)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  d public.delivery%ROWTYPE;
  v_expected text;
  v_ok boolean;
  v_reason text;
BEGIN
  PERFORM set_config('app.actor_id', p_agent::text, true);

  SELECT * INTO d FROM public.delivery WHERE id = p_delivery FOR UPDATE;
  IF NOT FOUND OR d.agent_id <> p_agent OR d.status <> 'pending' THEN
    RAISE EXCEPTION 'delivery not available for this agent' USING ERRCODE = '42501';
  END IF;
  IF d.imei_verified_at IS NOT NULL THEN
    RETURN jsonb_build_object('matched', true, 'already_verified', true);
  END IF;

  SELECT it.imei INTO v_expected
    FROM public.loan_application AS la
    JOIN public.inventory_item AS it ON it.id = la.inventory_item_id
    WHERE la.id = d.application_id;

  IF NOT public.is_valid_imei(p_scanned_imei) THEN
    v_ok := false;
    v_reason := 'invalid_imei';
  ELSIF p_scanned_imei <> v_expected THEN
    v_ok := false;
    v_reason := 'imei_mismatch';
  ELSE
    v_ok := true;
  END IF;

  IF v_ok THEN
    UPDATE public.delivery
      SET scanned_imei = p_scanned_imei, imei_verified_at = now(), imei_attempts = imei_attempts + 1
      WHERE id = d.id;
    RETURN jsonb_build_object('matched', true);
  END IF;

  UPDATE public.delivery SET imei_attempts = imei_attempts + 1 WHERE id = d.id;

  IF d.imei_attempts + 1 >= 3 THEN
    INSERT INTO public.security_event (user_id, event_type, details)
    VALUES (p_agent, 'imei_mismatch_repeated',
            jsonb_build_object('delivery_id', d.id, 'attempts', d.imei_attempts + 1));
  END IF;

  RETURN jsonb_build_object('matched', false, 'reason', v_reason, 'attempts', d.imei_attempts + 1);
END;
$$;

CREATE OR REPLACE FUNCTION public.record_handover_proof(
  p_agent uuid,
  p_delivery uuid,
  p_photo_path text,
  p_signature_hash text,
  p_signer_device text
)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  d public.delivery%ROWTYPE;
BEGIN
  PERFORM set_config('app.actor_id', p_agent::text, true);

  SELECT * INTO d FROM public.delivery WHERE id = p_delivery FOR UPDATE;
  IF NOT FOUND OR d.agent_id <> p_agent OR d.status <> 'pending' THEN
    RAISE EXCEPTION 'delivery not available for this agent' USING ERRCODE = '42501';
  END IF;
  IF d.imei_verified_at IS NULL THEN
    RAISE EXCEPTION 'scan and verify the IMEI first';
  END IF;
  IF d.proof_recorded_at IS NOT NULL THEN
    RAISE EXCEPTION 'proof already recorded';
  END IF;
  IF coalesce(btrim(p_photo_path), '') = '' OR coalesce(btrim(p_signature_hash), '') = '' THEN
    RAISE EXCEPTION 'handover photo and signature are both required';
  END IF;

  INSERT INTO public.contract_signature (contract_id, method, document_hash, signer_device_fingerprint, recorded_by)
  VALUES (d.contract_id, 'electronic', p_signature_hash, left(p_signer_device, 128), p_agent);

  UPDATE public.delivery
    SET handover_photo_path = p_photo_path, proof_recorded_at = now()
    WHERE id = d.id;
END;
$$;

CREATE OR REPLACE FUNCTION public.request_mdm_enrollment(p_agent uuid, p_delivery uuid)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  d public.delivery%ROWTYPE;
BEGIN
  PERFORM set_config('app.actor_id', p_agent::text, true);

  SELECT * INTO d FROM public.delivery WHERE id = p_delivery FOR UPDATE;
  IF NOT FOUND OR d.agent_id <> p_agent OR d.status <> 'pending' THEN
    RAISE EXCEPTION 'delivery not available for this agent' USING ERRCODE = '42501';
  END IF;
  IF d.imei_verified_at IS NULL THEN
    RAISE EXCEPTION 'scan and verify the IMEI first';
  END IF;
  IF d.mdm_status = 'enrolled' THEN
    RAISE EXCEPTION 'device already enrolled';
  END IF;

  UPDATE public.delivery
    SET mdm_status = 'token_requested', mdm_updated_at = now()
    WHERE id = d.id;
END;
$$;

-- Server only: called by the MDM integration after it really sees the device enrolled
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
END;
$$;

CREATE OR REPLACE FUNCTION public.complete_delivery(p_agent uuid, p_delivery uuid)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  d public.delivery%ROWTYPE;
  a public.loan_application%ROWTYPE;
BEGIN
  PERFORM set_config('app.actor_id', p_agent::text, true);

  SELECT * INTO d FROM public.delivery WHERE id = p_delivery FOR UPDATE;
  IF NOT FOUND OR d.agent_id <> p_agent OR d.status <> 'pending' THEN
    RAISE EXCEPTION 'delivery not available for this agent' USING ERRCODE = '42501';
  END IF;

  SELECT * INTO a FROM public.loan_application WHERE id = d.application_id FOR UPDATE;
  IF a.status::text <> 'approved' THEN
    RAISE EXCEPTION 'application is not in the approved state';
  END IF;
  IF NOT public.abac_allows('delivery.perform', NULL, a.agency_id) THEN
    RAISE EXCEPTION 'not allowed to deliver this application' USING ERRCODE = '42501';
  END IF;

  IF d.imei_verified_at IS NULL THEN
    RAISE EXCEPTION 'IMEI not verified';
  END IF;
  IF d.proof_recorded_at IS NULL THEN
    RAISE EXCEPTION 'handover photo and signature are missing';
  END IF;
  IF d.mdm_status <> 'enrolled' THEN
    RAISE EXCEPTION 'MDM enrolment is not confirmed by the server yet (status: %)', d.mdm_status;
  END IF;

  UPDATE public.delivery
    SET status = 'completed', handed_over_at = now()
    WHERE id = d.id;

  UPDATE public.loan_application
    SET status = 'active', handed_over_by = p_agent
    WHERE id = a.id;
END;
$$;

-- 7. Agent dashboard
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
        WHERE la.created_by = p_agent AND rs.status = 'overdue'
        ORDER BY rs.due_date
        LIMIT 50
      ) AS x
    ), '[]'::jsonb)
  );
END;
$$;

-- 8. Row level security
ALTER TABLE sync_request ENABLE ROW LEVEL SECURITY;
ALTER TABLE field_activity_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE agent_target ENABLE ROW LEVEL SECURITY;
ALTER TABLE delivery ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS sync_request_select ON sync_request;
CREATE POLICY sync_request_select ON sync_request FOR SELECT TO authenticated
  USING (agent_id = auth.uid() OR public.abac_allows('audit.read'));

DROP POLICY IF EXISTS field_activity_log_select ON field_activity_log;
CREATE POLICY field_activity_log_select ON field_activity_log FOR SELECT TO authenticated
  USING (agent_id = auth.uid() OR public.abac_allows('audit.read'));

DROP POLICY IF EXISTS agent_target_select ON agent_target;
CREATE POLICY agent_target_select ON agent_target FOR SELECT TO authenticated
  USING (
    agent_id = auth.uid()
    OR (public.current_app_role() = 'agency_supervisor'
        AND EXISTS (SELECT 1 FROM public.app_user AS u WHERE u.id = agent_id))
  );

DROP POLICY IF EXISTS delivery_select ON delivery;
CREATE POLICY delivery_select ON delivery FOR SELECT TO authenticated
  USING (
    agent_id = auth.uid()
    OR EXISTS (SELECT 1 FROM public.loan_application AS la WHERE la.id = application_id)
  );

-- 9. Privileges
REVOKE ALL ON sync_request, field_activity_log, agent_target, delivery FROM anon, authenticated;
GRANT SELECT ON sync_request, field_activity_log, agent_target, delivery TO authenticated;
GRANT SELECT, INSERT ON sync_request, field_activity_log TO service_role;
GRANT SELECT, INSERT, UPDATE ON agent_target, delivery TO service_role;

REVOKE EXECUTE ON FUNCTION public.is_valid_imei(text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.is_valid_imei(text) TO authenticated, service_role;

REVOKE EXECUTE ON FUNCTION public.delivery_guard_update() FROM PUBLIC, anon, authenticated;
REVOKE EXECUTE ON FUNCTION public.process_sync_action(uuid, text, uuid, text, jsonb, timestamptz, timestamptz) FROM PUBLIC, anon, authenticated;
REVOKE EXECUTE ON FUNCTION public.process_sync_batch(uuid, text, jsonb) FROM PUBLIC, anon, authenticated;
REVOKE EXECUTE ON FUNCTION public.log_field_activities(uuid, text, jsonb) FROM PUBLIC, anon, authenticated;
REVOKE EXECUTE ON FUNCTION public.offline_catalogue(uuid) FROM PUBLIC, anon, authenticated;
REVOKE EXECUTE ON FUNCTION public.start_delivery(uuid, uuid) FROM PUBLIC, anon, authenticated;
REVOKE EXECUTE ON FUNCTION public.verify_delivery_imei(uuid, uuid, text) FROM PUBLIC, anon, authenticated;
REVOKE EXECUTE ON FUNCTION public.record_handover_proof(uuid, uuid, text, text, text) FROM PUBLIC, anon, authenticated;
REVOKE EXECUTE ON FUNCTION public.request_mdm_enrollment(uuid, uuid) FROM PUBLIC, anon, authenticated;
REVOKE EXECUTE ON FUNCTION public.confirm_mdm_enrollment(uuid, boolean, text) FROM PUBLIC, anon, authenticated;
REVOKE EXECUTE ON FUNCTION public.complete_delivery(uuid, uuid) FROM PUBLIC, anon, authenticated;
REVOKE EXECUTE ON FUNCTION public.agent_dashboard(uuid) FROM PUBLIC, anon, authenticated;

GRANT EXECUTE ON FUNCTION public.process_sync_action(uuid, text, uuid, text, jsonb, timestamptz, timestamptz) TO service_role;
GRANT EXECUTE ON FUNCTION public.process_sync_batch(uuid, text, jsonb) TO service_role;
GRANT EXECUTE ON FUNCTION public.log_field_activities(uuid, text, jsonb) TO service_role;
GRANT EXECUTE ON FUNCTION public.offline_catalogue(uuid) TO service_role;
GRANT EXECUTE ON FUNCTION public.start_delivery(uuid, uuid) TO service_role;
GRANT EXECUTE ON FUNCTION public.verify_delivery_imei(uuid, uuid, text) TO service_role;
GRANT EXECUTE ON FUNCTION public.record_handover_proof(uuid, uuid, text, text, text) TO service_role;
GRANT EXECUTE ON FUNCTION public.request_mdm_enrollment(uuid, uuid) TO service_role;
GRANT EXECUTE ON FUNCTION public.confirm_mdm_enrollment(uuid, boolean, text) TO service_role;
GRANT EXECUTE ON FUNCTION public.complete_delivery(uuid, uuid) TO service_role;
GRANT EXECUTE ON FUNCTION public.agent_dashboard(uuid) TO service_role;
