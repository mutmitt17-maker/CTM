// Phase 2: instant notifications from the operators or an aggregator.
// IMPORTANT: every aggregator has its own signature format and field names.
// This file is a safe generic template (HMAC-SHA256 of the raw body, hex, header "x-signature").
// Adapt `parse()` and `verify()` to the aggregator you choose before going live.
import { json } from "../_shared/cors.ts";
import { serviceClient } from "../_shared/clients.ts";

const encoder = new TextEncoder();

function hexToBytes(hex: string): Uint8Array<ArrayBuffer> {
  const clean = hex.trim().toLowerCase();
  if (!/^[0-9a-f]+$/.test(clean) || clean.length % 2 !== 0) return new Uint8Array();
  const out = new Uint8Array(clean.length / 2);
  for (let i = 0; i < out.length; i++) out[i] = parseInt(clean.slice(i * 2, i * 2 + 2), 16);
  return out;
}

async function verify(raw: string, signatureHex: string): Promise<boolean> {
  const secret = Deno.env.get("MOBILE_MONEY_WEBHOOK_SECRET");
  if (!secret) return false;
  const key = await crypto.subtle.importKey(
    "raw", encoder.encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["verify"],
  );
  const given = hexToBytes(signatureHex);
  if (given.length === 0) return false;
  return await crypto.subtle.verify("HMAC", key, given, encoder.encode(raw)); // constant time
}

type Parsed = {
  provider: "mpesa" | "airtel_money" | "orange_money";
  transactionId: string;
  contractNumber: string;
  amount: number;
  currency: string;
};

function parse(body: Record<string, unknown>): Parsed | null {
  const provider = String(body.provider ?? "");
  if (!["mpesa", "airtel_money", "orange_money"].includes(provider)) return null;
  const amount = Number(body.amount);
  if (!Number.isFinite(amount) || amount <= 0) return null;
  return {
    provider: provider as Parsed["provider"],
    transactionId: String(body.transaction_id ?? ""),
    contractNumber: String(body.contract_number ?? ""),
    amount,
    currency: String(body.currency ?? ""),
  };
}

Deno.serve(async (req) => {
  if (req.method !== "POST") return json({ error: "POST only" }, 405);

  const raw = await req.text();
  const signature = req.headers.get("x-signature") ?? "";
  if (!(await verify(raw, signature))) return json({ error: "invalid signature" }, 401);

  let body: Record<string, unknown>;
  try {
    body = JSON.parse(raw);
  } catch {
    return json({ error: "invalid json" }, 400);
  }

  const p = parse(body);
  if (!p || !p.transactionId || !p.contractNumber) return json({ error: "invalid payload" }, 422);
  // The whole system is in USD. Anything else is refused and left for a human.
  if (p.currency !== "USD") return json({ error: "only USD is accepted" }, 422);

  const svc = serviceClient();

  const { data: contract } = await svc
    .from("loan_contract").select("id").eq("contract_number", p.contractNumber).maybeSingle();

  if (!contract) {
    await svc.from("payment_webhook_log").insert({
      provider: p.provider,
      external_transaction_id: p.transactionId,
      raw: body,
      outcome: "unknown_contract",
    });
    // 202: received, will be handled manually. Do not make the operator retry forever.
    return json({ status: "unknown_contract" }, 202);
  }

  const { data, error } = await svc.rpc("process_mobile_money_payment", {
    p_provider: p.provider,
    p_external_id: p.transactionId,
    p_contract: contract.id,
    p_amount: p.amount,
    p_method: p.provider,
    p_raw: body,
  });
  if (error) {
    console.error(error);
    return json({ error: "processing failed" }, 500); // the operator will retry; the call is idempotent
  }
  return json(data, 200);
});
