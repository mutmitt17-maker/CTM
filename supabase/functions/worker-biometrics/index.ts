// Checks the faces of customers whose application is waiting for it, then records the result.
// Called every minute by the scheduler with the header x-worker-secret.
import { json } from "../_shared/cors.ts";
import { requireWorkerSecret } from "../_shared/auth.ts";
import { serviceClient } from "../_shared/clients.ts";
import { checkFaces } from "../_shared/biometrics.ts";

type Work = { customer_id: string; selfie_path: string; document_path: string };

async function download(svc: ReturnType<typeof serviceClient>, path: string): Promise<Uint8Array> {
  const { data, error } = await svc.storage.from("kyc").download(path);
  if (error || !data) throw new Error(`photo not found in storage: ${path}`);
  return new Uint8Array(await data.arrayBuffer());
}

Deno.serve(async (req) => {
  try {
    requireWorkerSecret(req);
  } catch {
    return json({ error: "unauthorized" }, 401);
  }

  const svc = serviceClient();
  const { data: work, error } = await svc.rpc("claim_biometric_work", { p_limit: 10 });
  if (error) return json({ error: error.message }, 500);

  const counts = { recorded: 0, waiting: 0, failed: 0 };
  for (const w of (work ?? []) as Work[]) {
    try {
      const [selfie, document] = await Promise.all([
        download(svc, w.selfie_path),
        download(svc, w.document_path),
      ]);
      const check = await checkFaces(selfie, document);
      if (!check) {
        counts.waiting++; // dry-run: nothing decided
        continue;
      }
      const { error: recordError } = await svc.rpc("record_biometric_verification", {
        p_customer: w.customer_id,
        p_provider: check.provider,
        p_liveness_passed: check.liveness_passed,
        p_face_match_score: check.face_match_score,
        p_selfie_path: w.selfie_path,
        p_document_path: w.document_path,
      });
      if (recordError) throw new Error(recordError.message);
      counts.recorded++;
    } catch (e) {
      await svc.rpc("fail_biometric_work", {
        p_customer: w.customer_id,
        p_selfie: w.selfie_path,
        p_document: w.document_path,
        p_error: String((e as Error).message ?? e),
      });
      counts.failed++;
    }
  }
  return json({ claimed: (work ?? []).length, ...counts });
});
