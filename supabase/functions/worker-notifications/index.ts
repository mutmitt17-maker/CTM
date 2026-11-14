// Reads the notification queue and sends WhatsApp (SMS as fallback).
// Called every minute by the scheduler with the header x-worker-secret.
import { json } from "../_shared/cors.ts";
import { requireWorkerSecret } from "../_shared/auth.ts";
import { serviceClient } from "../_shared/clients.ts";
import { render } from "../_shared/messages.ts";
import { sendSms, sendWhatsApp } from "../_shared/providers.ts";

Deno.serve(async (req) => {
  try {
    requireWorkerSecret(req);
  } catch {
    return json({ error: "unauthorized" }, 401);
  }

  const svc = serviceClient();
  const { data: batch, error } = await svc.rpc("claim_notifications", { p_limit: 25 });
  if (error) return json({ error: error.message }, 500);

  const results: Record<string, number> = {};
  for (const n of batch ?? []) {
    let outcome: string;
    try {
      const extra: Record<string, unknown> = {};
      if (n.template_code === "closure_documents") {
        const ids = n.payload?.document_ids ?? [];
        const { data: docs } = await svc.from("closure_document").select("document_number").in("id", ids);
        extra.document_numbers = (docs ?? []).map((d: any) => d.document_number);
      }
      const msg = render(n.template_code, n.payload ?? {}, extra);
      const ref = n.channel === "whatsapp"
        ? await sendWhatsApp(n.recipient_phone, msg.whatsappName, msg.params)
        : await sendSms(n.recipient_phone, msg.sms);
      const done = await svc.rpc("complete_notification", { p_id: n.id, p_ok: true, p_error: null, p_reference: ref });
      outcome = done.data ?? "sent";
    } catch (e) {
      const done = await svc.rpc("complete_notification", {
        p_id: n.id, p_ok: false, p_error: String((e as Error).message ?? e).slice(0, 500), p_reference: null,
      });
      outcome = done.data ?? "failed";
    }
    results[outcome] = (results[outcome] ?? 0) + 1;
  }
  return json({ claimed: batch?.length ?? 0, results });
});
