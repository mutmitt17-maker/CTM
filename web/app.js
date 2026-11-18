import { createClient } from "https://esm.sh/@supabase/supabase-js@2.45.4";

const cfg = window.APP_CONFIG;
const sb = createClient(cfg.SUPABASE_URL, cfg.SUPABASE_ANON_KEY);

const $ = (s) => document.querySelector(s);

// Opened from a password-reset e-mail link: show the "new password" form instead of the app.
let recovering = /type=recovery/.test(location.hash + location.search);
sb.auth.onAuthStateChange((event) => {
  if (event === "PASSWORD_RECOVERY") { recovering = true; start(); }
});

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

// ---------------------------------------------------------------- credit policy form
// kind: "pct" = stored as a fraction (0.06) but shown/typed as a percent (6)
//       "num" = number as is, "int" = whole number, "list" = list of whole numbers
const POLICY_GROUPS = [
  { title: "1. Coûts pour le client", fields: [
    { key: "processing_fee_rate", kind: "pct", max: 50, label: "Frais de dossier", unit: "% du prix", help: "Payés une seule fois, à l'ouverture du crédit." },
    { key: "interest_rate_per_30_days", kind: "pct", max: 100, label: "Intérêt", unit: "% par 30 jours", help: "Le coût du crédit, calculé par tranche de 30 jours." },
    { key: "penalty_rate_per_day", kind: "pct", max: 10, label: "Pénalité de retard", unit: "% par jour", help: "Ajoutée chaque jour de retard, après les jours de grâce." },
    { key: "penalty_cap_rate", kind: "pct", max: 100, label: "Plafond des pénalités", unit: "% de l'échéance", help: "Les pénalités ne dépassent jamais cette part de l'échéance en retard." },
  ] },
  { title: "2. Retards", fields: [
    { key: "grace_days", kind: "int", label: "Jours de grâce", unit: "jours", help: "Délai de tolérance avant que les pénalités commencent." },
    { key: "lock_after_days_overdue", kind: "int", label: "Blocage du téléphone après", unit: "jours de retard", help: "Au-delà, le téléphone est verrouillé." },
  ] },
  { title: "3. Acceptation", fields: [
    { key: "auto_approve_min_score", kind: "int", max: 100, label: "Acceptation automatique à partir du score", unit: "sur 100", help: "À partir de ce score, le dossier est accepté sans vérification." },
    { key: "review_min_score", kind: "int", max: 100, label: "Vérification humaine à partir du score", unit: "sur 100", help: "Entre ce score et le précédent, une personne décide. En dessous, refusé." },
    { key: "down_payment_min_pct", kind: "num", max: 100, label: "Acompte minimum", unit: "% du prix", help: "Le client doit payer au moins cette part au départ." },
    { key: "down_payment_max_pct", kind: "num", max: 100, label: "Acompte maximum", unit: "% du prix", help: "Part maximale acceptée au départ." },
    { key: "allowed_durations", kind: "list", label: "Durées autorisées", unit: "jours, séparées par des virgules", help: "Exemple : 30, 60, 90, 120, 180", wide: true },
  ] },
  { title: "4. Sécurité", fields: [
    { key: "face_match_min", kind: "pct", max: 100, label: "Reconnaissance du visage minimum", unit: "%", help: "Le visage du client doit ressembler à sa pièce d'identité à ce niveau au moins." },
    { key: "ocr_confidence_min", kind: "pct", max: 100, label: "Lecture de la pièce d'identité minimum", unit: "%", help: "Niveau de certitude requis pour lire automatiquement la pièce." },
  ] },
  { title: "Avancé (rarement modifié)", advanced: true, fields: [
    { key: "burden_good", kind: "pct", max: 100, label: "Charge sur le revenu : bonne", unit: "% du revenu", help: "Mensualité jusqu'à cette part du revenu : meilleure note." },
    { key: "burden_ok", kind: "pct", max: 100, label: "Charge sur le revenu : acceptable", unit: "% du revenu", help: "Note moyenne jusqu'à cette part." },
    { key: "burden_max", kind: "pct", max: 100, label: "Charge sur le revenu : maximale", unit: "% du revenu", help: "Au-delà, la note de charge est nulle." },
  ] },
];

const POLICY_ERRORS = [
  ["credit_policy_interest_ceiling", "L'intérêt ne peut pas dépasser 100 % par 30 jours."],
  ["credit_policy_fee_ceiling", "Les frais de dossier ne peuvent pas dépasser 50 %."],
  ["credit_policy_penalty_ceiling", "La pénalité ne peut pas dépasser 10 % par jour."],
  ["credit_policy_down_range", "L'acompte minimum ne peut pas dépasser l'acompte maximum."],
  ["credit_policy_score_order", "Le score de vérification humaine ne peut pas dépasser le score d'acceptation automatique."],
  ["credit_policy_burden_order", "Les charges sur le revenu doivent aller de la plus petite (bonne) à la plus grande (maximale)."],
];

function friendlyPolicyError(msg) {
  for (const [key, text] of POLICY_ERRORS) if (msg.includes(key)) return text;
  if (/only the system administrator/i.test(msg)) return "Seul l'administrateur système peut modifier la politique.";
  if (/violates check constraint/i.test(msg)) return "Une valeur est hors des limites autorisées. Vérifiez les chiffres saisis.";
  return msg;
}

const round = (n, d = 6) => Number(n.toFixed(d));
function policyToInput(f, v) {
  if (f.kind === "list") return (v ?? []).join(", ");
  if (v === null || v === undefined) return "";
  return String(f.kind === "pct" ? round(v * 100, 4) : v);
}
// Returns the value to store, or throws a readable error.
function policyFromInput(f, text) {
  const raw = text.trim();
  if (f.kind === "list") {
    const nums = raw.split(/[\s,;]+/).filter(Boolean).map(Number);
    if (!nums.length || nums.some((n) => !Number.isInteger(n) || n <= 0)) throw new Error(`« ${f.label} » : saisissez des nombres entiers positifs séparés par des virgules.`);
    return [...new Set(nums)].sort((a, b) => a - b);
  }
  if (raw === "") throw new Error(`« ${f.label} » : champ vide.`);
  const n = Number(raw.replace(",", "."));
  if (!Number.isFinite(n) || n < 0) throw new Error(`« ${f.label} » : saisissez un nombre positif.`);
  if (f.kind === "int" && !Number.isInteger(n)) throw new Error(`« ${f.label} » : saisissez un nombre entier.`);
  if (f.max !== undefined && n > f.max) throw new Error(`« ${f.label} » : ${f.max} au maximum.`);
  return f.kind === "pct" ? round(n / 100) : n;
}
function sameValue(f, a, b) {
  return f.kind === "list" ? JSON.stringify(a) === JSON.stringify(b) : Number(a) === Number(b);
}

// ---------------------------------------------------------------- erreurs du serveur, en français
const SERVER_ERRORS = [
  [/only the system administrator/i, "Seul l'administrateur système peut faire cette action."],
  [/only Finance can confirm/i, "Seule la Finance peut confirmer un paiement en espèces."],
  [/the collector cannot confirm/i, "La personne qui a encaissé l'argent ne peut pas confirmer son propre paiement."],
  [/not a pending cash payment/i, "Ce paiement n'est plus en attente (déjà confirmé ou annulé)."],
  [/amount exceeds the outstanding balance/i, "Le montant dépasse le solde restant dû du contrat."],
  [/application is not waiting for a manual review/i, "Ce dossier n'attend plus de décision (déjà traité)."],
  [/a reason is required/i, "Un motif est obligatoire."],
  [/reviewer cannot be the person who created/i, "Vous ne pouvez pas décider d'un dossier que vous avez créé."],
  [/reviewer is not allowed/i, "Votre rôle ou votre limite de montant ne vous permet pas cette décision."],
  [/unknown or inactive reviewer/i, "Compte inconnu ou inactif."],
  [/violates check constraint/i, "Une valeur est hors des limites autorisées."],
];

function friendlyError(msg) {
  for (const [pattern, text] of SERVER_ERRORS) if (pattern.test(msg)) return text;
  return msg;
}

const usd = (n) => `${Number(n).toFixed(2)} USD`;
const FREQUENCY_LABELS = { daily: "par jour", weekly: "par semaine", monthly: "par mois" };
const PROVIDERS = [["mpesa", "M-Pesa"], ["airtel_money", "Airtel Money"], ["orange_money", "Orange Money"]];

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

  async policy(me, notice) {
    const { data, error } = await sb.from("credit_policy").select("*").maybeSingle();
    if (error) return message("error", error.message);
    if (!data) return message("error", "Aucune politique de crédit trouvée.");
    const canEdit = me.role === "system_admin";

    const inputs = {};
    const renderField = (f) => {
      const input = h("input", {
        name: f.key, type: f.kind === "list" ? "text" : "number", step: "any", min: "0",
        value: policyToInput(f, data[f.key]), ...(canEdit ? {} : { disabled: "" }),
      });
      if (f.kind === "list") input.removeAttribute("min");
      inputs[f.key] = input;
      return h("label", { class: "pfield" },
        h("strong", {}, f.label),
        h("span", { class: "help" }, f.help),
        h("span", { class: `row${f.wide ? " wide" : ""}` }, input, h("span", { class: "unit" }, f.unit)));
    };
    const groups = POLICY_GROUPS.map((g) => g.advanced
      ? h("details", { class: "advanced" }, h("summary", {}, g.title), g.fields.map(renderField))
      : h("div", { class: "pgroup" }, h("h3", {}, g.title), g.fields.map(renderField)));

    const out = h("div");
    if (notice) out.append(message("ok", notice));

    const box = h("div", { class: "card" }, h("h2", {}, "Politique de crédit"),
      h("p", {}, canEdit
        ? "Les règles appliquées à tous les nouveaux dossiers. Les contrats déjà signés ne changent pas."
        : "Les règles appliquées à tous les nouveaux dossiers (lecture seule : seul l'administrateur système peut les modifier)."));

    if (!canEdit) { box.append(...groups); return box; }

    const reason = h("input", { name: "reason", required: "", placeholder: "Ex. : ajustement de l'intérêt" });
    const form = h("form", {
      onsubmit: async (e) => {
        e.preventDefault();
        out.replaceChildren();
        try {
          const changes = {};
          for (const g of POLICY_GROUPS) for (const f of g.fields) {
            const value = policyFromInput(f, inputs[f.key].value);
            if (!sameValue(f, value, data[f.key])) changes[f.key] = value;
          }
          if (Object.keys(changes).length === 0) { out.append(message("error", "Aucun changement à enregistrer.")); return; }
          await api("update_credit_policy", { p_changes: changes, p_reason: reason.value.trim() });
          show(await views.policy(me, "Politique mise à jour."));
        } catch (err) { out.append(message("error", friendlyPolicyError(err.message))); }
      },
    },
      ...groups,
      h("label", {}, h("strong", {}, "Motif du changement"), reason),
      h("button", { type: "submit" }, "Enregistrer"),
    );
    box.append(form, out);
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
  // Numéros marchands : où le client envoie son paiement Mobile Money (réservé à l'administrateur système).
  async merchants(me, notice) {
    const { data, error } = await sb.from("merchant_account").select("provider, merchant_number, display_name, active");
    if (error) return message("error", error.message);
    const byProvider = new Map((data ?? []).map((r) => [r.provider, r]));
    const canEdit = me.role === "system_admin";
    const out = h("div");
    if (notice) out.append(message("ok", notice));

    const cards = PROVIDERS.map(([key, label]) => {
      const cur = byProvider.get(key);
      const lock = canEdit ? {} : { disabled: "" };
      const number = h("input", { value: cur?.merchant_number ?? "", placeholder: "+243…", maxlength: "30", ...lock });
      const name = h("input", { value: cur?.display_name ?? `CTM ${label}`, maxlength: "100", ...lock });
      const active = h("input", { type: "checkbox", ...((cur?.active ?? true) ? { checked: "" } : {}), ...lock });
      const result = h("div");
      const form = h("form", {
        onsubmit: async (e) => {
          e.preventDefault();
          result.replaceChildren();
          const num = number.value.trim();
          if (!/^[+0-9 ]{6,30}$/.test(num)) { result.append(message("error", "Numéro invalide : chiffres, espaces et « + » seulement (6 à 30 caractères).")); return; }
          if (!name.value.trim()) { result.append(message("error", "Le nom affiché est obligatoire.")); return; }
          try {
            await api("set_merchant_account", { p_provider: key, p_number: num, p_name: name.value.trim(), p_active: active.checked });
            show(await views.merchants(me, `${label} : enregistré.`));
          } catch (err) { result.append(message("error", friendlyError(err.message))); }
        },
      },
        h("label", {}, "Numéro marchand", number),
        h("label", {}, "Nom affiché au client", name),
        h("label", {}, active, "Actif (visible dans l'application client)"),
        canEdit ? h("button", { type: "submit" }, "Enregistrer") : "",
        result,
      );
      return h("div", { class: "pgroup" }, h("h3", {}, label), form);
    });

    const hasActive = (data ?? []).some((r) => r.active && r.merchant_number);
    return h("div", { class: "card" }, h("h2", {}, "Numéros marchands (Mobile Money)"),
      h("p", {}, "Le client paie vers ces numéros en indiquant son numéro de contrat comme référence."),
      hasActive ? "" : message("error", "Aucun numéro actif : l'application client ne pourra pas afficher d'instructions de paiement."),
      canEdit ? "" : h("p", {}, "Lecture seule : seul l'administrateur système peut modifier ces numéros."),
      out, ...cards);
  },

  // Dossiers en révision : une personne décide (le créateur du dossier ne peut pas décider).
  async reviews(me, notice) {
    const { data, error } = await sb.from("loan_application")
      .select("id, total_price, down_payment, financed_amount, duration_days, installment_frequency, created_at, created_by, customer(first_name, last_name, phone_number), inventory_item(brand, model)")
      .eq("status", "pending_review").order("created_at", { ascending: true });
    if (error) return message("error", error.message);

    // Dernière décision automatique de chaque dossier (score, motifs). Facultatif : le dossier reste décidable sans.
    const latest = new Map();
    if ((data ?? []).length) {
      const { data: decisions } = await sb.from("credit_decision")
        .select("application_id, score, review_flags, hard_rule_failures, reason, created_at")
        .in("application_id", data.map((a) => a.id)).order("created_at", { ascending: false });
      for (const d of decisions ?? []) if (!latest.has(d.application_id)) latest.set(d.application_id, d);
    }

    const out = h("div");
    if (notice) out.append(message("ok", notice));
    const decide = async (a, approve, result) => {
      const verb = approve ? "APPROUVER" : "REFUSER";
      const reason = prompt(`Motif pour ${verb} ce dossier ?`);
      if (!reason || !reason.trim()) return;
      try {
        await api("resolve_review", { p_application: a.id, p_approve: approve, p_reason: reason.trim() });
        show(await views.reviews(me, approve ? "Dossier approuvé." : "Dossier refusé."));
      } catch (err) { result.replaceChildren(message("error", friendlyError(err.message))); }
    };

    const cards = (data ?? []).map((a) => {
      const d = latest.get(a.id);
      const c = a.customer;
      const result = h("div");
      return h("div", { class: "pgroup" },
        h("h3", {}, c ? `${c.first_name} ${c.last_name}` : "Client (non visible avec votre rôle)"),
        h("p", {}, [
          c?.phone_number ? `Téléphone : ${c.phone_number}` : "",
          a.inventory_item ? ` — Appareil : ${a.inventory_item.brand} ${a.inventory_item.model}` : "",
        ].join("")),
        h("p", {}, `Prix : ${usd(a.total_price)} — Acompte : ${usd(a.down_payment)} — Financé : ${usd(a.financed_amount)} — Durée : ${a.duration_days} jours (paiement ${FREQUENCY_LABELS[a.installment_frequency] ?? a.installment_frequency})`),
        d ? h("p", {}, `Score : ${d.score ?? "—"}/100. ${d.review_flags?.length ? `À vérifier : ${d.review_flags.join(", ")}. ` : ""}${d.hard_rule_failures?.length ? `Règles non respectées : ${d.hard_rule_failures.join(", ")}. ` : ""}${d.reason}`) : "",
        a.created_by === me.id ? message("error", "Vous avez créé ce dossier : vous ne pouvez pas le décider.") : "",
        h("p", {},
          h("button", { class: "primary", onclick: () => decide(a, true, result) }, "Approuver"), " ",
          h("button", { onclick: () => decide(a, false, result) }, "Refuser")),
        result);
    });

    return h("div", { class: "card" }, h("h2", {}, "Dossiers à décider"),
      h("p", {}, "Dossiers que le score automatique n'a pas pu trancher. Un motif est obligatoire."),
      out, ...(cards.length ? cards : [h("p", {}, "Aucun dossier en attente de décision.")]));
  },

  // Paiements en espèces : la Finance confirme ce qu'un agent a encaissé (jamais l'agent lui-même).
  async cash(me, notice) {
    const { data, error } = await sb.from("payment")
      .select("id, amount, created_at, collected_by, loan_contract(contract_number)")
      .eq("payment_method", "cash").eq("status", "pending").order("created_at", { ascending: true });
    if (error) return message("error", error.message);

    const out = h("div");
    if (notice) out.append(message("ok", notice));
    const confirmPayment = async (r) => {
      const number = r.loan_contract?.contract_number ?? "—";
      if (!confirm(`Confirmer la réception de ${usd(r.amount)} pour le contrat ${number} ?`)) return;
      try {
        await api("confirm_cash_payment", { p_payment: r.id });
        show(await views.cash(me, `Paiement de ${usd(r.amount)} confirmé (contrat ${number}).`));
      } catch (err) { out.replaceChildren(message("error", friendlyError(err.message))); }
    };

    return h("div", { class: "card" }, h("h2", {}, "Espèces à confirmer"),
      h("p", {}, "Paiements encaissés par les agents. La confirmation les écrit au grand livre et peut débloquer le téléphone."),
      out,
      table(data, [
        { label: "Contrat", render: (r) => r.loan_contract?.contract_number ?? "—" },
        { label: "Montant", render: (r) => usd(r.amount) },
        { label: "Encaissé le", render: (r) => new Date(r.created_at).toLocaleString("fr-FR") },
        { label: "Action", render: (r) => (r.collected_by === me.id
          ? "Vous avez encaissé ce paiement"
          : h("button", { class: "primary", onclick: () => confirmPayment(r) }, "Confirmer")) },
      ]));
  },

};

const MENU = [
  ["home", "Accueil"], ["simulator", "Simulateur"], ["policy", "Politique de crédit"],
  ["payment", "Paiement manuel"], ["applications", "Dossiers"], ["devices", "Appareils"], ["approvals", "Validations"],
  ["reviews", "Dossiers à décider"], ["cash", "Espèces à confirmer"], ["merchants", "Numéros marchands"],
];

let me = null;

async function open(name) {
  document.querySelectorAll("#nav button").forEach((b) => b.classList.toggle("active", b.dataset.view === name));
  show(h("p", {}, "Chargement..."));
  try { show(await views[name](me)); } catch (e) { show(message("error", e.message)); }
}

async function start() {
  const { data: { session } } = await sb.auth.getSession();
  if (recovering && session) {
    $("#login").hidden = true; $("#app").hidden = true; $("#reset").hidden = false;
    $("#logout").hidden = true;
    return;
  }
  $("#reset").hidden = true;
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

$("#forgot").addEventListener("click", async (e) => {
  e.preventDefault();
  const err = $("#login-error");
  err.className = "error"; err.textContent = "";
  const email = $("#email").value.trim();
  if (!email) { err.textContent = "Saisissez d'abord votre e-mail ci-dessus."; return; }
  const { error } = await sb.auth.resetPasswordForEmail(email, { redirectTo: location.origin + "/" });
  if (error) { err.textContent = error.message; return; }
  err.className = "ok";
  err.textContent = "Si ce compte existe, un e-mail de réinitialisation vient d'être envoyé.";
});

$("#reset-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  $("#reset-error").textContent = "";
  const { error } = await sb.auth.updateUser({ password: $("#new-password").value });
  if (error) { $("#reset-error").textContent = error.message; return; }
  recovering = false;
  history.replaceState(null, "", location.pathname);
  $("#new-password").value = "";
  start();
});

$("#logout").addEventListener("click", async () => { await sb.auth.signOut(); start(); });

start();
