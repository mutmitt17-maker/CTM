/*
# Step 7: server-side biometric checks and private photo storage

Plain English: until now nothing could create the server-side face check that the risk engine waits for, so every application stayed "pending". This adds the missing pieces.

1. claim_biometric_work(): lists customers whose current selfie and ID photo have not been checked yet and who have an application waiting. A worker calls it every minute.
2. fail_biometric_work(): when the check cannot be done (photo missing, provider down), records the failure and delays the next try (2, 8, 18, 32, 50 minutes). After 5 failures the customer is left alone and stays visible in kyc_biometric_attempt for a person to look at.
3. Storage: two private buckets, "kyc" (ID photo, selfie) and "handover" (handover photo). Agents may only ADD files: kyc/<customer id>/<file> for a customer of their own agency, handover/<delivery id>/<file> for their own delivery. Nobody can read, replace or delete through the app; only the server (service role) reads them. The storage part is skipped on a plain PostgreSQL (CI) where the storage schema does not exist.

Nothing here approves anyone by itself: the result still comes from record_biometric_verification(), written by the worker after the face provider answered.
*/

CREATE TABLE IF NOT EXISTS kyc_biometric_attempt (
  customer_id uuid NOT NULL REFERENCES customer (id),
  selfie_path text NOT NULL,
  document_path text NOT NULL,
  attempts integer NOT NULL DEFAULT 0,
  last_error text,
  next_try_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (customer_id, selfie_path, document_path)
);

ALTER TABLE kyc_biometric_attempt ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON kyc_biometric_attempt FROM anon, authenticated;
GRANT SELECT, INSERT, UPDATE ON kyc_biometric_attempt TO service_role;

CREATE OR REPLACE FUNCTION public.claim_biometric_work(p_limit integer DEFAULT 10)
RETURNS TABLE (customer_id uuid, selfie_path text, document_path text)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = ''
AS $$
  SELECT k.customer_id, k.selfie_path, k.id_document_photo_path
  FROM public.kyc_profile AS k
  WHERE k.status = 'submitted'
    AND k.selfie_path IS NOT NULL
    AND k.id_document_photo_path IS NOT NULL
    AND NOT public.biometrics_current(k.customer_id)
    AND EXISTS (
      SELECT 1 FROM public.loan_application AS la
      WHERE la.customer_id = k.customer_id AND la.status::text = 'pending'
    )
    AND NOT EXISTS (
      SELECT 1 FROM public.kyc_biometric_attempt AS a
      WHERE a.customer_id = k.customer_id
        AND a.selfie_path = k.selfie_path
        AND a.document_path = k.id_document_photo_path
        AND (a.attempts >= 5 OR a.next_try_at > now())
    )
  ORDER BY k.updated_at
  LIMIT greatest(1, least(coalesce(p_limit, 10), 50));
$$;

CREATE OR REPLACE FUNCTION public.fail_biometric_work(
  p_customer uuid,
  p_selfie text,
  p_document text,
  p_error text
)
RETURNS integer
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  v_attempts integer;
BEGIN
  INSERT INTO public.kyc_biometric_attempt (customer_id, selfie_path, document_path, attempts, last_error, next_try_at)
  VALUES (p_customer, p_selfie, p_document, 1, left(coalesce(p_error, ''), 500), now() + interval '2 minutes')
  ON CONFLICT (customer_id, selfie_path, document_path) DO UPDATE
    SET attempts = public.kyc_biometric_attempt.attempts + 1,
        last_error = EXCLUDED.last_error,
        next_try_at = now() + make_interval(
          mins => least(60, 2 * (public.kyc_biometric_attempt.attempts + 1) * (public.kyc_biometric_attempt.attempts + 1))),
        updated_at = now()
  RETURNING attempts INTO v_attempts;
  RETURN v_attempts;
END;
$$;

REVOKE EXECUTE ON FUNCTION public.claim_biometric_work(integer) FROM PUBLIC, anon, authenticated;
REVOKE EXECUTE ON FUNCTION public.fail_biometric_work(uuid, text, text, text) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.claim_biometric_work(integer) TO service_role;
GRANT EXECUTE ON FUNCTION public.fail_biometric_work(uuid, text, text, text) TO service_role;

-- Private photo storage (skipped where the Supabase storage schema does not exist)
DO $storage$
BEGIN
  IF to_regclass('storage.buckets') IS NULL OR to_regclass('storage.objects') IS NULL THEN
    RAISE NOTICE 'storage schema not found: skipping buckets and policies';
    RETURN;
  END IF;

  INSERT INTO storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
  VALUES
    ('kyc', 'kyc', false, 5242880, ARRAY['image/jpeg', 'image/png']),
    ('handover', 'handover', false, 5242880, ARRAY['image/jpeg', 'image/png'])
  ON CONFLICT (id) DO NOTHING;

  EXECUTE 'DROP POLICY IF EXISTS kyc_agent_add ON storage.objects';
  EXECUTE $p$
    CREATE POLICY kyc_agent_add ON storage.objects FOR INSERT TO authenticated
    WITH CHECK (
      bucket_id = 'kyc'
      AND CASE
        WHEN (storage.foldername(name))[1] ~ '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
        THEN EXISTS (
          SELECT 1 FROM public.customer AS c
          WHERE c.id = ((storage.foldername(name))[1])::uuid
            AND c.agency_id = public.current_agency_id()
            AND public.abac_allows('kyc.collect', NULL, c.agency_id)
        )
        ELSE false
      END
    )
  $p$;

  EXECUTE 'DROP POLICY IF EXISTS handover_agent_add ON storage.objects';
  EXECUTE $p$
    CREATE POLICY handover_agent_add ON storage.objects FOR INSERT TO authenticated
    WITH CHECK (
      bucket_id = 'handover'
      AND CASE
        WHEN (storage.foldername(name))[1] ~ '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
        THEN EXISTS (
          SELECT 1 FROM public.delivery AS d
          WHERE d.id = ((storage.foldername(name))[1])::uuid
            AND d.agent_id = auth.uid()
        )
        ELSE false
      END
    )
  $p$;
END
$storage$;
