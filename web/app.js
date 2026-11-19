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
  [/not allowed to lock a device/i, "Votre rôle ne permet pas de verrouiller un appareil."],
  [/not allowed to unlock a device/i, "Votre rôle ne permet pas de déverrouiller un appareil."],
  [/not allowed to clear a security hold/i, "Votre rôle ne permet pas de lever un blocage sécurité."],
  [/not allowed to decide on this SIM/i, "Votre rôle ne permet pas de décider sur cette SIM."],
  [/unknown or released device/i, "Appareil inconnu ou déjà libéré."],
  [/this device is on security hold/i, "Cet appareil est en blocage sécurité : levez d'abord le blocage."],
  [/unlock duration must be between/i, "Durée de déverrouillage hors limite (voir la politique des appareils)."],
  [/no pending SIM with this hash/i, "Cette SIM n'est plus en attente."],
  [/no changes provided/i, "Aucun changement à enregistrer."],
  [/unknown or read-only setting/i, "Réglage inconnu ou non modifiable."],
  [/unknown or inactive contract/i, "Contrat inconnu ou inactif."],
  [/the amount must be between 0 and the penalties/i, "Le montant doit être compris entre 0 et les pénalités encore dues."],
  [/the new duration must be between/i, "La nouvelle durée doit être comprise entre 1 et 365 jours."],
  [/not allowed to request a penalty waiver/i, "Votre rôle ne permet pas de demander une remise de pénalités."],
  [/not allowed to request a restructuring/i, "Votre rôle ne permet pas de demander une restructuration."],
  [/not allowed to assign collection tasks/i, "Votre rôle ne permet pas d'assigner des tâches de recouvrement."],
  [/not allowed to close this task/i, "Vous ne pouvez pas clôturer cette tâche."],
  [/the assignee must be/i, "La personne assignée doit être un agent de terrain ou de recouvrement actif."],
  [/no open task with this id/i, "Cette tâche n'est plus ouverte."],
  [/an outcome is required/i, "Le résultat est obligatoire."],
  [/every device must exist and be in stock/i, "Chaque appareil doit exister et être en stock pour changer d'agence."],
  [/no devices provided/i, "Sélectionnez au moins un appareil."],
  [/unknown agency/i, "Agence inconnue."],
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

  // Appareils : verrouillage / déverrouillage manuel et levée du blocage sécurité.
  async devices(me, notice) {
    const { data, error } = await sb.from("managed_device")
      .select("id, imei, lock_state, lock_reason, lock_provider, security_hold, last_seen_at")
      .order("created_at", { ascending: false }).limit(50);
    if (error) return message("error", error.message);

    const out = h("div");
    if (notice) out.append(message("ok", notice));
    const run = async (call, okText) => {
      try { await call(); show(await views.devices(me, okText)); }
      catch (err) { out.replaceChildren(message("error", friendlyError(err.message))); }
    };
    const ask = (question) => { const t = prompt(question); return t && t.trim() ? t.trim() : null; };

    const lock = (d) => {
      const reason = ask(`Motif du verrouillage de l'appareil ${d.imei} ?`);
      if (reason) run(() => api("manual_lock_device", { p_device: d.id, p_reason: reason }), `Appareil ${d.imei} : verrouillage demandé.`);
    };
    const unlock = (d) => {
      const hours = Number(prompt("Pendant combien d'heures déverrouiller ? (entier, selon la politique des appareils)", "24"));
      if (!Number.isInteger(hours) || hours < 1) { out.replaceChildren(message("error", "Durée invalide : entrez un nombre entier d'heures.")); return; }
      const reason = ask(`Motif du déverrouillage de l'appareil ${d.imei} ?`);
      if (reason) run(() => api("manual_unlock_device", { p_device: d.id, p_hours: hours, p_reason: reason }), `Appareil ${d.imei} : déverrouillé pour ${hours} h.`);
    };
    const clearHold = (d) => {
      const reason = ask(`Motif pour lever le blocage sécurité de ${d.imei} ?`);
      if (reason) run(() => api("clear_security_hold", { p_device: d.id, p_reason: reason }), `Appareil ${d.imei} : blocage sécurité levé.`);
    };

    return h("div", { class: "card" }, h("h2", {}, "Appareils gérés"), out, table(data, [
      { label: "IMEI", key: "imei" },
      { label: "État", render: (r) => pill(r.lock_state) },
      { label: "Raison", key: "lock_reason" },
      { label: "Fournisseur", key: "lock_provider" },
      { label: "Blocage sécurité", render: (r) => (r.security_hold ? "oui" : "non") },
      { label: "Dernière activité", render: (r) => (r.last_seen_at ? new Date(r.last_seen_at).toLocaleString("fr-FR") : "—") },
      { label: "Actions", render: (r) => {
        if (r.lock_state === "released") return "Libéré";
        const buttons = [];
        if (r.lock_state !== "locked") buttons.push(h("button", { onclick: () => lock(r) }, "Verrouiller"));
        if (r.lock_state === "locked") buttons.push(h("button", { class: "primary", onclick: () => unlock(r) }, "Déverrouiller"));
        if (r.security_hold) buttons.push(h("button", { onclick: () => clearHold(r) }, "Lever le blocage"));
        return h("span", {}, ...buttons.flatMap((b) => [b, " "]));
      } },
    ]));
  },

  // Changements de SIM en attente : autoriser ou refuser.
  async sims(me, notice) {
    const { data, error } = await sb.from("device_sim")
      .select("managed_device_id, sim_hash, first_seen_at, managed_device(imei)")
      .eq("status", "pending").order("first_seen_at", { ascending: true });
    if (error) return message("error", error.message);

    const out = h("div");
    if (notice) out.append(message("ok", notice));
    const decide = async (r, approve) => {
      const reason = prompt(`Motif pour ${approve ? "AUTORISER" : "REFUSER"} cette SIM ?`);
      if (!reason || !reason.trim()) return;
      try {
        await api("resolve_sim_change", { p_device: r.managed_device_id, p_sim_hash: r.sim_hash, p_approve: approve, p_reason: reason.trim() });
        show(await views.sims(me, approve ? "SIM autorisée." : "SIM refusée."));
      } catch (err) { out.replaceChildren(message("error", friendlyError(err.message))); }
    };

    return h("div", { class: "card" }, h("h2", {}, "SIM à valider"),
      h("p", {}, "Une nouvelle carte SIM a été détectée dans ces appareils. Tant qu'elle n'est pas autorisée, la politique de l'appareil peut le verrouiller."),
      out,
      table(data, [
        { label: "Appareil (IMEI)", render: (r) => r.managed_device?.imei ?? "—" },
        { label: "Empreinte SIM", render: (r) => `${r.sim_hash.slice(0, 12)}…` },
        { label: "Détectée le", render: (r) => new Date(r.first_seen_at).toLocaleString("fr-FR") },
        { label: "Décision", render: (r) => h("span", {},
          h("button", { class: "primary", onclick: () => decide(r, true) }, "Autoriser"), " ",
          h("button", { onclick: () => decide(r, false) }, "Refuser")) },
      ]));
  },

  // Politique des appareils et fournisseur de verrouillage par marque (administrateur système).
  async devicepolicy(me, notice) {
    const { data: pol, error } = await sb.from("device_restriction_policy").select("*").maybeSingle();
    if (error) return message("error", error.message);
    if (!pol) return message("error", "Politique des appareils introuvable.");
    const canEdit = me.role === "system_admin";
    const lock = canEdit ? {} : { disabled: "" };

    const BOOLS = [
      ["block_usb_debugging", "Bloquer le débogage USB"], ["block_developer_mode", "Bloquer le mode développeur"],
      ["block_factory_reset", "Bloquer la réinitialisation usine"], ["block_uninstall", "Bloquer la désinstallation de l'application"],
      ["block_unknown_sources", "Bloquer les sources inconnues"], ["block_root", "Bloquer les appareils rootés"],
      ["require_locked_bootloader", "Exiger un bootloader verrouillé"], ["require_verified_boot", "Exiger le démarrage vérifié"],
      ["require_oem_lock", "Exiger le verrou OEM"],
    ];
    const NUMS = [
      ["attestation_interval_hours", "Intervalle d'attestation (heures)"], ["max_attestation_failures", "Échecs d'attestation tolérés"],
      ["heartbeat_max_silence_days", "Silence maximal de l'appareil (jours)"], ["sim_grace_hours", "Délai de grâce nouvelle SIM (heures, 0 = aucun)"],
      ["max_manual_unlock_hours", "Durée maximale du déverrouillage manuel (heures)"],
    ];

    const out = h("div");
    if (notice) out.append(message("ok", notice));
    const boolInputs = BOOLS.map(([k]) => h("input", { type: "checkbox", ...(pol[k] ? { checked: "" } : {}), ...lock }));
    const numInputs = NUMS.map(([k]) => h("input", { type: "number", step: "1", value: String(pol[k]), ...lock }));
    const reasonInput = h("input", { placeholder: "Motif du changement", ...lock });

    const policyForm = h("form", {
      onsubmit: async (e) => {
        e.preventDefault();
        out.replaceChildren();
        const changes = {};
        BOOLS.forEach(([k], i) => { if (boolInputs[i].checked !== pol[k]) changes[k] = boolInputs[i].checked; });
        for (let i = 0; i < NUMS.length; i++) {
          const [k, label] = NUMS[i];
          const raw = numInputs[i].value.trim();
          if (!/^\d+$/.test(raw)) { out.append(message("error", `${label} : entrez un nombre entier.`)); return; }
          if (Number(raw) !== pol[k]) changes[k] = Number(raw);
        }
        if (!Object.keys(changes).length) { out.append(message("error", "Aucun changement à enregistrer.")); return; }
        if (!reasonInput.value.trim()) { out.append(message("error", "Un motif est obligatoire.")); return; }
        try {
          await api("update_device_policy", { p_changes: changes, p_reason: reasonInput.value.trim() });
          show(await views.devicepolicy(me, "Politique des appareils enregistrée."));
        } catch (err) { out.append(message("error", friendlyError(err.message))); }
      },
    },
      ...BOOLS.map(([, label], i) => h("label", {}, boolInputs[i], label)),
      ...NUMS.map(([, label], i) => h("label", {}, label, numInputs[i])),
      h("label", {}, "Motif", reasonInput),
      canEdit ? h("button", { type: "submit", class: "primary" }, "Enregistrer") : "");

    // Fournisseur de verrouillage par marque
    const { data: brands } = await sb.from("brand_lock_provider").select("brand, provider").order("brand");
    const PROVIDER_LABELS = { knox_guard: "Samsung Knox Guard", apple_abm: "Apple (ABM)", android_enterprise: "Android Enterprise" };
    const brandInput = h("input", { placeholder: "marque (ex. samsung)", maxlength: "50", ...lock });
    const providerSelect = h("select", { ...lock }, ...Object.entries(PROVIDER_LABELS).map(([v, l]) => h("option", { value: v }, l)));
    const brandResult = h("div");
    const brandForm = h("form", {
      onsubmit: async (e) => {
        e.preventDefault();
        brandResult.replaceChildren();
        if (!brandInput.value.trim()) { brandResult.append(message("error", "La marque est obligatoire.")); return; }
        try {
          await api("set_brand_lock_provider", { p_brand: brandInput.value.trim(), p_provider: providerSelect.value });
          show(await views.devicepolicy(me, "Fournisseur de verrouillage enregistré."));
        } catch (err) { brandResult.append(message("error", friendlyError(err.message))); }
      },
    }, h("label", {}, "Marque", brandInput), h("label", {}, "Fournisseur", providerSelect),
      canEdit ? h("button", { type: "submit" }, "Enregistrer") : "", brandResult);

    return h("div", { class: "card" }, h("h2", {}, "Politique des appareils"),
      canEdit ? "" : h("p", {}, "Lecture seule : seul l'administrateur système peut modifier ces réglages."),
      out, policyForm,
      h("h3", {}, "Fournisseur de verrouillage par marque"),
      table(brands ?? [], [{ label: "Marque", key: "brand" }, { label: "Fournisseur", render: (r) => PROVIDER_LABELS[r.provider] ?? r.provider }]),
      brandForm);
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

  // Recouvrement : tâches ouvertes (relance ou visite), assignation et clôture avec résultat.
  async collections(me, notice) {
    const { data, error } = await sb.from("collection_task")
      .select("id, task_type, priority, reason, status, due_date, assigned_to, loan_contract(contract_number), customer(first_name, last_name, phone_number)")
      .in("status", ["open", "in_progress"]).order("due_date", { ascending: true });
    if (error) return message("error", error.message);
    const tasks = [...(data ?? [])].sort((a, b) => (b.priority === "high") - (a.priority === "high"));

    // Agents assignables (visible selon votre rôle : administrateur ou superviseur d'agence).
    const { data: people } = await sb.from("app_user").select("id, role, zone")
      .in("role", ["field_agent", "collections"]).eq("status", "active");
    const ROLE_LABELS = { field_agent: "Agent de terrain", collections: "Recouvrement" };

    const out = h("div");
    if (notice) out.append(message("ok", notice));
    const run = async (call, okText) => {
      try { await call(); show(await views.collections(me, okText)); }
      catch (err) { out.replaceChildren(message("error", friendlyError(err.message))); }
    };
    const complete = (t) => {
      const outcome = prompt("Résultat du contact ou de la visite ?");
      if (outcome && outcome.trim()) run(() => api("complete_collection_task", { p_task: t.id, p_outcome: outcome.trim() }), "Tâche clôturée.");
    };

    const assignCell = (t) => {
      if (!(people ?? []).length) return "Agents non visibles avec votre rôle";
      const who = h("select", {}, ...people.map((p) => h("option", { value: p.id }, `${ROLE_LABELS[p.role] ?? p.role}${p.zone ? ` — ${p.zone}` : ""} (${p.id.slice(0, 8)})`)));
      const type = h("select", {}, h("option", { value: "followup" }, "Relance"), h("option", { value: "field_visit" }, "Visite terrain"));
      if (t.task_type) type.value = t.task_type;
      return h("span", {}, who, " ", type, " ",
        h("button", { onclick: () => run(() => api("assign_collection_task", { p_task: t.id, p_assignee: who.value, p_task_type: type.value }), "Tâche assignée.") }, "Assigner"));
    };

    return h("div", { class: "card" }, h("h2", {}, "Recouvrement"),
      h("p", {}, "Une seule tâche ouverte par contrat. Les tâches urgentes sont en premier."),
      out,
      table(tasks, [
        { label: "Contrat", render: (r) => r.loan_contract?.contract_number ?? "—" },
        { label: "Client", render: (r) => (r.customer ? `${r.customer.first_name} ${r.customer.last_name} — ${r.customer.phone_number}` : "—") },
        { label: "Type", render: (r) => (r.task_type === "field_visit" ? "Visite terrain" : "Relance") },
        { label: "Priorité", render: (r) => (r.priority === "high" ? "Urgente" : "Normale") },
        { label: "Motif", key: "reason" },
        { label: "Échéance", key: "due_date" },
        { label: "État", render: (r) => (r.assigned_to ? "Assignée" : "À assigner") },
        { label: "Assigner", render: assignCell },
        { label: "Clôturer", render: (r) => h("button", { onclick: () => complete(r) }, "Clôturer") },
      ]));
  },

  // Remise de pénalités et restructuration : ce sont des DEMANDES, décidées ensuite dans « Validations ».
  async contracts(me) {
    const out = h("div");
    const detail = h("div");
    const numberInput = h("input", { placeholder: "CT-2026-000123", maxlength: "20" });

    const lookup = async (e) => {
      e.preventDefault();
      out.replaceChildren(); detail.replaceChildren();
      const number = numberInput.value.trim().toUpperCase();
      if (!/^CT-\d{4}-\d{6}$/.test(number)) { out.append(message("error", "Numéro de contrat invalide (format : CT-2026-000123).")); return; }
      const { data: c, error } = await sb.from("loan_contract")
        .select("id, contract_number, status, customer(first_name, last_name)").eq("contract_number", number).maybeSingle();
      if (error) { out.append(message("error", error.message)); return; }
      if (!c) { out.append(message("error", "Contrat introuvable (ou non visible avec votre rôle).")); return; }
      let st = null;
      try { st = await api("contract_status", { p_contract: c.id }); } catch (err) { out.append(message("error", friendlyError(err.message))); }

      const penalty = Number(st?.penalty_outstanding_usd ?? 0);
      detail.append(h("div", { class: "pgroup" },
        h("h3", {}, `${c.contract_number} — ${c.customer ? `${c.customer.first_name} ${c.customer.last_name}` : "client"}`),
        st ? h("p", {}, `Statut : ${c.status} — Retard : ${st.overdue_days ?? 0} jour(s) — Pénalités dues : ${usd(penalty)} — Total restant dû : ${usd(st.total_outstanding_usd ?? 0)}`) : ""));

      // Remise de pénalités
      const wAmount = h("input", { type: "number", step: "0.01", min: "0.01", placeholder: "Montant à remettre (USD)" });
      const wReason = h("input", { placeholder: "Motif" });
      const wResult = h("div");
      detail.append(h("form", {
        class: "pgroup",
        onsubmit: async (ev) => {
          ev.preventDefault(); wResult.replaceChildren();
          const amount = Number(wAmount.value);
          if (!(amount > 0) || Math.abs(amount * 100 - Math.round(amount * 100)) > 1e-6) { wResult.append(message("error", "Montant invalide (positif, 2 décimales au plus).")); return; }
          if (st && amount > penalty) { wResult.append(message("error", `Le montant dépasse les pénalités dues (${usd(penalty)}).`)); return; }
          if (!wReason.value.trim()) { wResult.append(message("error", "Un motif est obligatoire.")); return; }
          try {
            await api("request_penalty_waiver", { p_contract: c.id, p_amount: amount, p_reason: wReason.value.trim() });
            wResult.append(message("ok", "Demande envoyée. Elle doit être validée dans l'écran « Validations »."));
            wAmount.value = ""; wReason.value = "";
          } catch (err) { wResult.append(message("error", friendlyError(err.message))); }
        },
      }, h("h3", {}, "Demander une remise de pénalités"), h("label", {}, "Montant", wAmount), h("label", {}, "Motif", wReason),
        h("button", { type: "submit" }, "Envoyer la demande"), wResult));

      // Restructuration
      const rDays = h("input", { type: "number", step: "1", min: "1", max: "365", placeholder: "Nouvelle durée (jours)" });
      const rFreq = h("select", {}, h("option", { value: "daily" }, "Par jour"), h("option", { value: "weekly" }, "Par semaine"), h("option", { value: "monthly", selected: "" }, "Par mois"));
      const rWaive = h("input", { type: "checkbox" });
      const rReason = h("input", { placeholder: "Motif" });
      const rResult = h("div");
      detail.append(h("form", {
        class: "pgroup",
        onsubmit: async (ev) => {
          ev.preventDefault(); rResult.replaceChildren();
          const days = Number(rDays.value);
          if (!Number.isInteger(days) || days < 1 || days > 365) { rResult.append(message("error", "La durée doit être un nombre entier de jours entre 1 et 365.")); return; }
          if (!rReason.value.trim()) { rResult.append(message("error", "Un motif est obligatoire.")); return; }
          try {
            await api("request_restructuring", { p_contract: c.id, p_duration_days: days, p_frequency: rFreq.value, p_waive_penalties: rWaive.checked, p_reason: rReason.value.trim() });
            rResult.append(message("ok", "Demande envoyée. Elle doit être validée dans l'écran « Validations »."));
            rDays.value = ""; rReason.value = ""; rWaive.checked = false;
          } catch (err) { rResult.append(message("error", friendlyError(err.message))); }
        },
      }, h("h3", {}, "Demander une restructuration"), h("label", {}, "Nouvelle durée (jours)", rDays), h("label", {}, "Fréquence", rFreq),
        h("label", {}, rWaive, "Effacer aussi les pénalités"), h("label", {}, "Motif", rReason),
        h("button", { type: "submit" }, "Envoyer la demande"), rResult));
    };

    return h("div", { class: "card" }, h("h2", {}, "Remises et restructuration"),
      h("p", {}, "Cherchez un contrat par son numéro. Les demandes sont ensuite décidées dans « Validations »."),
      h("form", { onsubmit: lookup }, h("label", {}, "Numéro de contrat", numberInput), h("button", { type: "submit" }, "Chercher")),
      out, detail);
  },

  // Stock : affecter des téléphones en stock à une agence (administrateur système).
  async stock(me, notice) {
    const { data, error } = await sb.from("inventory_item")
      .select("id, imei, brand, model, retail_price, agency(name)").eq("status", "in_stock")
      .order("created_at", { ascending: false }).limit(200);
    if (error) return message("error", error.message);
    const { data: agencies } = await sb.from("agency").select("id, name, region").order("name");
    const canEdit = me.role === "system_admin";

    const out = h("div");
    if (notice) out.append(message("ok", notice));
    const checks = new Map();
    const rows = (data ?? []).map((it) => {
      const box = h("input", { type: "checkbox", ...(canEdit ? {} : { disabled: "" }) });
      checks.set(it.id, box);
      return { it, box };
    });

    const agencySelect = h("select", {}, ...(agencies ?? []).map((a) => h("option", { value: a.id }, a.region ? `${a.name} (${a.region})` : a.name)));
    const assign = async () => {
      out.replaceChildren();
      const ids = rows.filter((r) => r.box.checked).map((r) => r.it.id);
      if (!ids.length) { out.append(message("error", "Sélectionnez au moins un appareil.")); return; }
      if (!agencySelect.value) { out.append(message("error", "Aucune agence disponible.")); return; }
      if (!confirm(`Affecter ${ids.length} appareil(s) à l'agence « ${agencySelect.selectedOptions[0].textContent} » ?`)) return;
      try {
        await api("assign_stock_to_agency", { p_items: ids, p_agency: agencySelect.value });
        show(await views.stock(me, `${ids.length} appareil(s) affecté(s).`));
      } catch (err) { out.append(message("error", friendlyError(err.message))); }
    };

    return h("div", { class: "card" }, h("h2", {}, "Stock en magasin"),
      h("p", {}, "Seuls les téléphones en stock peuvent changer d'agence."),
      canEdit
        ? h("p", {}, "Agence de destination : ", agencySelect, " ", h("button", { class: "primary", onclick: assign }, "Affecter la sélection"))
        : h("p", {}, "Lecture seule : seul l'administrateur système peut affecter le stock."),
      out,
      table(rows.map((r) => r.it), [
        { label: "", render: (it) => checks.get(it.id) },
        { label: "IMEI", key: "imei" },
        { label: "Appareil", render: (it) => `${it.brand} ${it.model}` },
        { label: "Prix", render: (it) => usd(it.retail_price) },
        { label: "Agence actuelle", render: (it) => it.agency?.name ?? "—" },
      ]));
  },

};

const MENU = [
  ["home", "Accueil"], ["simulator", "Simulateur"], ["policy", "Politique de crédit"],
  ["payment", "Paiement manuel"], ["applications", "Dossiers"], ["devices", "Appareils"], ["approvals", "Validations"],
  ["reviews", "Dossiers à décider"], ["cash", "Espèces à confirmer"], ["merchants", "Numéros marchands"],
  ["sims", "SIM à valider"], ["devicepolicy", "Politique des appareils"],
  ["collections", "Recouvrement"], ["contracts", "Remises et restructuration"],
  ["stock", "Stock"],
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
