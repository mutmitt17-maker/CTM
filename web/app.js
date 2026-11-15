import { createClient } from "https://esm.sh/@supabase/supabase-js@2.45.4";

const cfg = window.APP_CONFIG;
const sb = createClient(cfg.SUPABASE_URL, cfg.SUPABASE_ANON_KEY);

const $ = (s) => document.querySelector(s);

// Small DOM helper: text is always inserted as text, never as HTML.
function h(tag, attrs = {}, ...kids) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (k === "class") el.className = v;
    else if (k.startsWith("on")) el[k] = v;
    else el.setAttribute(k, v);
  }
  for (const k of kids.flat()) el.append(k instanceof Node ? k : document.createTextNode(k ?? ""));
  return el;
}

async function api(action, params = {}) {
  const { data: { session } } = await sb.auth.getSession();
  const res = await fetch(cfg.API_URL, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${session.access_token}`,
      apikey: cfg.SUPABASE_ANON_KEY,
    },
    body: JSON.stringify({ action, params }),
  });
  const body = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(body.error || res.statusText);
  return body.data;
}

function table(rows, columns) {
  if (!rows || rows.length === 0) return h("p", {}, "Aucune donnée.");
  return h("table", {},
    h("thead", {}, h("tr", {}, columns.map((c) => h("th", {}, c.label)))),
    h("tbody", {}, rows.map((r) => h("tr", {}, columns.map((c) => h("td", {}, c.render ? c.render(r) : String(r[c.key] ?? "")))))),
  );
}

function pill(state) {
  return h("span", { class: `pill ${state}` }, state);
}

function show(el) {
  const view = $("#view");
  view.replaceChildren(el);
}

function message(kind, text) {
  return h("p", { class: kind }, text);
}

// ---------------------------------------------------------------- views
const views = {
  async home(me) {
    return h("div", { class: "card" },
      h("h2", {}, "Accueil"),
      h("p", {}, `Rôle : ${me.role} — statut : ${me.status}`),
      h("p", {}, "Utilisez le menu pour tester chaque partie."),
    );
  },

  async simulator() {
    const { data: items, error } = await sb.from("inventory_item")
      .select("id, brand, model, retail_price").eq("status", "in_stock");
    if (error) return message("error", error.message);

    const out = h("div");
    const form = h("form", {
      onsubmit: async (e) => {
        e.preventDefault();
        out.replaceChildren();
        const f = new FormData(form);
        const { data, error } = await sb.rpc("simulate_loan", {
          p_item: f.get("item"),
          p_down_payment: Number(f.get("down")),
          p_duration_days: Number(f.get("duration")),
          p_frequency: f.get("frequency"),
        });
        if (error) return out.append(message("error", error.message));
        out.append(h("pre", {}, JSON.stringify(data, null, 2)));
      },
    },
      h("label", {}, "Téléphone",
        h("select", { name: "item" }, items.map((i) => h("option", { value: i.id }, `${i.brand} ${i.model} — ${i.retail_price} USD`)))),
      h("label", {}, "Acompte (USD)", h("input", { name: "down", type: "number", step: "0.01", required: "" })),
      h("label", {}, "Durée (jours)", h("select", { name: "duration" }, [30, 60, 90, 120, 180].map((d) => h("option", { value: d }, d)))),
      h("label", {}, "Fréquence", h("select", { name: "frequency" }, ["daily", "weekly", "monthly"].map((x) => h("option", { value: x }, x)))),
      h("button", { type: "submit" }, "Simuler"),
    );
    return h("div", { class: "card" }, h("h2", {}, "Simulateur de crédit"),
      h("p", {}, "Réservé aux agents de terrain de l'agence (règle de l'étape 2)."), form, out);
  },

  async policy(me) {
    const { data, error } = await sb.from("credit_policy").select("*").maybeSingle();
    if (error) return message("error", error.message);
    const box = h("div", { class: "card" }, h("h2", {}, "Politique de crédit"), h("pre", {}, JSON.stringify(data, null, 2)));

    if (me.role === "system_admin") {
      const out = h("div");
      const form = h("form", {
        onsubmit: async (e) => {
          e.preventDefault();
          out.replaceChildren();
          try {
            const f = new FormData(form);
            const res = await api("update_credit_policy", {
              p_changes: JSON.parse(f.get("changes")),
              p_reason: f.get("reason"),
            });
            out.append(message("ok", "Politique mise à jour."), h("pre", {}, JSON.stringify(res, null, 2)));
          } catch (err) { out.append(message("error", err.message)); }
        },
      },
        h("label", {}, 'Changements (JSON, ex : {"interest_rate_per_30_days": 0.05})', h("textarea", { name: "changes", rows: "3", required: "" })),
        h("label", {}, "Motif", h("input", { name: "reason", required: "" })),
        h("button", { type: "submit" }, "Enregistrer"),
      );
      box.append(h("h3", {}, "Modifier (super-admin)"), form, out);
    }
    return box;
  },

  async payment() {
    const out = h("div");
    const form = h("form", {
      onsubmit: async (e) => {
        e.preventDefault();
        out.replaceChildren();
        try {
          const f = new FormData(form);
          const res = await api("record_manual_mobile_money_payment", {
            p_external_id: f.get("ref"),
            p_contract_number: f.get("contract"),
            p_amount: Number(f.get("amount")),
            p_method: f.get("method"),
            p_note: f.get("note") || null,
          });
          out.append(h("pre", {}, JSON.stringify(res, null, 2)));
        } catch (err) { out.append(message("error", err.message)); }
      },
    },
      h("label", {}, "Opérateur", h("select", { name: "method" }, ["mpesa", "airtel_money", "orange_money"].map((x) => h("option", { value: x }, x)))),
      h("label", {}, "Référence de transaction opérateur", h("input", { name: "ref", required: "" })),
      h("label", {}, "Numéro de contrat", h("input", { name: "contract", required: "" })),
      h("label", {}, "Montant (USD)", h("input", { name: "amount", type: "number", step: "0.01", required: "" })),
      h("label", {}, "Note", h("input", { name: "note" })),
      h("button", { type: "submit" }, "Valider le paiement"),
    );
    return h("div", { class: "card" }, h("h2", {}, "Paiement Mobile Money (saisie manuelle)"),
      h("p", {}, "Réservé à la Finance et aux superviseurs. La validation écrit au grand livre et peut débloquer le téléphone."),
      form, out);
  },

  async applications() {
    const { data, error } = await sb.from("loan_application")
      .select("id, status, total_price, down_payment, financed_amount, duration_days, created_at")
      .order("created_at", { ascending: false }).limit(50);
    if (error) return message("error", error.message);
    return h("div", { class: "card" }, h("h2", {}, "Dossiers"), table(data, [
      { label: "Statut", key: "status" }, { label: "Prix (USD)", key: "total_price" },
      { label: "Acompte", key: "down_payment" }, { label: "Financé", key: "financed_amount" },
      { label: "Durée", key: "duration_days" }, { label: "Créé", render: (r) => new Date(r.created_at).toLocaleString("fr-FR") },
    ]));
  },

  async devices() {
    const { data, error } = await sb.from("managed_device")
      .select("id, imei, lock_state, lock_reason, lock_provider, security_hold, last_seen_at")
      .order("created_at", { ascending: false }).limit(50);
    if (error) return message("error", error.message);
    return h("div", { class: "card" }, h("h2", {}, "Appareils gérés"), table(data, [
      { label: "IMEI", key: "imei" }, { label: "État", render: (r) => pill(r.lock_state) },
      { label: "Raison", key: "lock_reason" }, { label: "Fournisseur", key: "lock_provider" },
      { label: "Blocage sécurité", render: (r) => (r.security_hold ? "oui" : "non") },
    ]));
  },

  async approvals() {
    const { data, error } = await sb.from("approval_request")
      .select("id, kind, payload, reason, status, created_at").eq("status", "pending")
      .order("created_at", { ascending: false });
    if (error) return message("error", error.message);
    const out = h("div");
    const decide = async (id, approve) => {
      const reason = prompt("Motif de la décision ?");
      if (!reason) return;
      try {
        const res = await api("decide_approval", { p_request: id, p_approve: approve, p_reason: reason });
        out.replaceChildren(message("ok", "Décision enregistrée."), h("pre", {}, JSON.stringify(res, null, 2)));
      } catch (err) { out.replaceChildren(message("error", err.message)); }
    };
    return h("div", { class: "card" }, h("h2", {}, "Demandes à valider (double validation)"),
      table(data, [
        { label: "Type", key: "kind" }, { label: "Détail", render: (r) => JSON.stringify(r.payload) },
        { label: "Motif", key: "reason" },
        { label: "Actions", render: (r) => h("span", {},
          h("button", { class: "primary", onclick: () => decide(r.id, true) }, "Approuver"), " ",
          h("button", { onclick: () => decide(r.id, false) }, "Rejeter")) },
      ]), out);
  },
};

const MENU = [
  ["home", "Accueil"], ["simulator", "Simulateur"], ["policy", "Politique de crédit"],
  ["payment", "Paiement manuel"], ["applications", "Dossiers"], ["devices", "Appareils"], ["approvals", "Validations"],
];

let me = null;

async function open(name) {
  document.querySelectorAll("#nav button").forEach((b) => b.classList.toggle("active", b.dataset.view === name));
  show(h("p", {}, "Chargement..."));
  try { show(await views[name](me)); } catch (e) { show(message("error", e.message)); }
}

async function start() {
  const { data: { session } } = await sb.auth.getSession();
  $("#login").hidden = !!session;
  $("#app").hidden = !session;
  $("#logout").hidden = !session;
  document.body.classList.toggle("logged", !!session);
  if (!session) { $("#who").textContent = ""; return; }

  try { me = await api("me"); } catch (e) { me = null; }
  if (!me) {
    $("#who").textContent = session.user.email;
    show(message("error", "Compte non configuré : aucune fiche app_user pour cet utilisateur."));
    return;
  }
  $("#who").textContent = `${session.user.email} (${me.role})`;
  $("#nav").replaceChildren(...MENU.map(([k, label]) => h("button", { "data-view": k, onclick: () => open(k) }, label)));
  open("home");
}

$("#login-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  $("#login-error").textContent = "";
  const { error } = await sb.auth.signInWithPassword({ email: $("#email").value, password: $("#password").value });
  if (error) { $("#login-error").textContent = error.message; return; }
  start();
});

$("#logout").addEventListener("click", async () => { await sb.auth.signOut(); start(); });

start();
