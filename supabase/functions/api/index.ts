// Single entry point for the back-office and the agent app.
// Each action maps to one database function. The database enforces who may do what;
// this layer only (1) proves who is calling and (2) refuses unknown actions and arguments.
import { corsHeaders, HttpError, json } from "../_shared/cors.ts";
import { authenticate } from "../_shared/auth.ts";
import { serviceClient, userClient } from "../_shared/clients.ts";

type Spec = {
  rpc: string;
  args: string[];
  actor?: string; // name of the argument that receives the verified user id
  device?: string; // name of the argument that receives the x-device-fingerprint header
  asUser?: boolean; // call with the user's own token (row level security applies)
};

const ACTIONS: Record<string, Spec> = {
  // Calculator (runs as the user)
  simulate_loan: {
    rpc: "simulate_loan",
    args: ["p_item", "p_down_payment", "p_duration_days", "p_frequency", "p_start"],
    asUser: true,
  },

  // Payments
  record_manual_mobile_money_payment: {
    rpc: "record_manual_mobile_money_payment", actor: "p_actor",
    args: ["p_external_id", "p_contract_number", "p_amount", "p_method", "p_note"],
  },
  confirm_cash_payment: { rpc: "confirm_cash_payment", actor: "p_actor", args: ["p_payment"] },

  // Credit decisions
  resolve_review: {
    rpc: "resolve_review", actor: "p_reviewer",
    args: ["p_application", "p_approve", "p_reason"],
  },

  // Tier 3 and approvals
  request_penalty_waiver: {
    rpc: "request_penalty_waiver", actor: "p_actor",
    args: ["p_contract", "p_amount", "p_reason"],
  },
  request_restructuring: {
    rpc: "request_restructuring", actor: "p_actor",
    args: ["p_contract", "p_duration_days", "p_frequency", "p_waive_penalties", "p_reason"],
  },
  decide_approval: {
    rpc: "decide_approval", actor: "p_actor",
    args: ["p_request", "p_approve", "p_reason"],
  },

  // Collection
  assign_collection_task: {
    rpc: "assign_collection_task", actor: "p_actor",
    args: ["p_task", "p_assignee", "p_task_type"],
  },
  complete_collection_task: {
    rpc: "complete_collection_task", actor: "p_actor",
    args: ["p_task", "p_outcome"],
  },

  // Device controls (humans)
  manual_lock_device: {
    rpc: "manual_lock_device", actor: "p_actor", args: ["p_device", "p_reason"],
  },
  manual_unlock_device: {
    rpc: "manual_unlock_device", actor: "p_actor", args: ["p_device", "p_hours", "p_reason"],
  },
  clear_security_hold: {
    rpc: "clear_security_hold", actor: "p_actor", args: ["p_device", "p_reason"],
  },
  resolve_sim_change: {
    rpc: "resolve_sim_change", actor: "p_actor",
    args: ["p_device", "p_sim_hash", "p_approve", "p_reason"],
  },

  // System administrator settings
  update_credit_policy: {
    rpc: "update_credit_policy", actor: "p_admin", args: ["p_changes", "p_reason"],
  },
  update_device_policy: {
    rpc: "update_device_policy", actor: "p_admin", args: ["p_changes", "p_reason"],
  },
  assign_stock_to_agency: {
    rpc: "assign_stock_to_agency", actor: "p_admin", args: ["p_items", "p_agency"],
  },
  set_merchant_account: {
    rpc: "set_merchant_account", actor: "p_admin",
    args: ["p_provider", "p_number", "p_name", "p_active"],
  },
  set_brand_lock_provider: {
    rpc: "set_brand_lock_provider", actor: "p_admin", args: ["p_brand", "p_provider"],
  },

  // Field agent app
  sync_batch: {
    rpc: "process_sync_batch", actor: "p_agent", device: "p_device", args: ["p_items"],
  },
  offline_catalogue: { rpc: "offline_catalogue", actor: "p_agent", args: [] },
  agent_dashboard: { rpc: "agent_dashboard", actor: "p_agent", args: [] },
  log_field_activities: {
    rpc: "log_field_activities", actor: "p_agent", device: "p_device", args: ["p_events"],
  },
  start_delivery: { rpc: "start_delivery", actor: "p_agent", args: ["p_application"] },
  verify_delivery_imei: {
    rpc: "verify_delivery_imei", actor: "p_agent", args: ["p_delivery", "p_scanned_imei"],
  },
  record_handover_proof: {
    rpc: "record_handover_proof", actor: "p_agent",
    args: ["p_delivery", "p_photo_path", "p_signature_hash", "p_signer_device"],
  },
  request_mdm_enrollment: {
    rpc: "request_mdm_enrollment", actor: "p_agent", args: ["p_delivery"],
  },
  complete_delivery: { rpc: "complete_delivery", actor: "p_agent", args: ["p_delivery"] },
};

function dbErrorStatus(code?: string): number {
  if (code === "42501") return 403; // insufficient privilege
  if (code === "P0409") return 409; // business conflict
  return 400;
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });

  try {
    if (req.method !== "POST") throw new HttpError(405, "POST only");
    const { userId, jwt } = await authenticate(req);

    const body = await req.json().catch(() => null);
    if (!body || typeof body.action !== "string") throw new HttpError(400, "action is required");
    const params: Record<string, unknown> = body.params ?? {};

    const svc = serviceClient();

    // Who am I
    if (body.action === "me") {
      const { data, error } = await svc
        .from("app_user")
        .select("id, role, status, agency_id, customer_id, locked_until")
        .eq("id", userId)
        .maybeSingle();
      if (error) throw new HttpError(500, error.message);
      return json({ data });
    }

    // Collection status of one contract (the caller must be allowed to see the contract)
    if (body.action === "contract_status") {
      const contractId = String(params.p_contract ?? "");
      const visible = await userClient(jwt).from("loan_contract").select("id").eq("id", contractId).maybeSingle();
      if (visible.error || !visible.data) throw new HttpError(403, "contract not visible");
      const { data, error } = await svc.rpc("contract_collection_status", { p_contract: contractId });
      if (error) throw new HttpError(400, error.message);
      return json({ data });
    }

    const spec = ACTIONS[body.action];
    if (!spec) throw new HttpError(400, "unknown action");

    for (const key of Object.keys(params)) {
      if (!spec.args.includes(key)) throw new HttpError(400, `unexpected argument: ${key}`);
    }

    const args: Record<string, unknown> = { ...params };
    if (spec.actor) args[spec.actor] = userId; // always overwritten, never trusted from the client
    if (spec.device) args[spec.device] = req.headers.get("x-device-fingerprint") ?? "unknown";

    const client = spec.asUser ? userClient(jwt) : svc;
    const { data, error } = await client.rpc(spec.rpc, args);
    if (error) {
      return json({ error: error.message, code: error.code }, dbErrorStatus(error.code));
    }
    return json({ data });
  } catch (e) {
    if (e instanceof HttpError) return json({ error: e.message }, e.status);
    console.error(e);
    return json({ error: "internal error" }, 500);
  }
});
