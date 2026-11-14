// Reads the device command queue (lock, unlock, warning, release, attestation request, policy).
// Called every minute by the scheduler with the header x-worker-secret.
import { json } from "../_shared/cors.ts";
import { requireWorkerSecret } from "../_shared/auth.ts";
import { serviceClient } from "../_shared/clients.ts";
import { sendCommand } from "../_shared/mdm.ts";

Deno.serve(async (req) => {
  try {
    requireWorkerSecret(req);
  } catch {
    return json({ error: "unauthorized" }, 401);
  }

  const svc = serviceClient();
  const { data: batch, error } = await svc.rpc("claim_device_commands", { p_limit: 20, p_worker: "edge" });
  if (error) return json({ error: error.message }, 500);

  const results: Record<string, number> = {};
  for (const cmd of batch ?? []) {
    let outcome: string;
    try {
      const ref = await sendCommand(cmd);
      const done = await svc.rpc("complete_device_command", {
        p_id: cmd.id, p_ok: true, p_error: null, p_reference: ref,
      });
      outcome = done.data ?? "acked";
    } catch (e) {
      const done = await svc.rpc("complete_device_command", {
        p_id: cmd.id, p_ok: false, p_error: String((e as Error).message ?? e).slice(0, 500), p_reference: null,
      });
      outcome = done.data ?? "failed";
    }
    results[outcome] = (results[outcome] ?? 0) + 1;
  }
  return json({ claimed: batch?.length ?? 0, results });
});
