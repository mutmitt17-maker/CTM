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

// ---------------------------------------------------------------- composants d'interface
// Les icônes sont des constantes fixes ci-dessous (jamais du texte venant du serveur).
const ICONS = {
  home: '<path d="M3 11l9-8 9 8"/><path d="M5 10v10h5v-6h4v6h5V10"/>',
  calculator: '<rect x="5" y="3" width="14" height="18" rx="2"/><path d="M8 7h8M8 12h.01M12 12h.01M16 12h.01M8 16h.01M12 16h.01M16 16h.01"/>',
  folder: '<path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/>',
  review: '<rect x="6" y="4" width="12" height="17" rx="2"/><path d="M9 4V3h6v1M9.5 13l2 2 3.5-4"/>',
  sliders: '<path d="M4 7h9M17 7h3M4 17h3M11 17h9"/><circle cx="15" cy="7" r="2"/><circle cx="9" cy="17" r="2"/>',
  shield: '<path d="M12 3l7 3v5c0 4.5-3 8-7 10-4-2-7-5.5-7-10V6z"/><path d="M9 12l2 2 4-4"/>',
  card: '<rect x="3" y="6" width="18" height="13" rx="2"/><path d="M3 10h18M7 15h3"/>',
  cash: '<rect x="2.5" y="6.5" width="19" height="11" rx="2"/><circle cx="12" cy="12" r="2.5"/><path d="M6 12h.01M18 12h.01"/>',
  store: '<path d="M4 9l1.5-5h13L20 9"/><path d="M4 9v10h16V9M4 9h16M9 19v-5h6v5"/>',
  phone: '<rect x="7" y="2.5" width="10" height="19" rx="2.5"/><path d="M10.5 18h3"/>',
  sim: '<path d="M7 3h7l4 4v13a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z"/><rect x="9" y="12" width="6" height="6" rx="1"/>',
  lock: '<rect x="5" y="11" width="14" height="9" rx="2.5"/><path d="M8 11V8a4 4 0 0 1 8 0v3"/>',
  box: '<path d="M21 8l-9-5-9 5v8l9 5 9-5z"/><path d="M3.5 8L12 13l8.5-5M12 13v8"/>',
  call: '<path d="M5 4h4l2 5-2.5 1.5a11 11 0 0 0 5 5L15 13l5 2v4a2 2 0 0 1-2 2A16 16 0 0 1 3 6a2 2 0 0 1 2-2z"/>',
  percent: '<path d="M5 19L19 5"/><circle cx="7" cy="7" r="2"/><circle cx="17" cy="17" r="2"/>',
  check: '<circle cx="12" cy="12" r="9"/><path d="M8 12l3 3 5-6"/>',
  alert: '<circle cx="12" cy="12" r="9"/><path d="M12 8v5M12 16h.01"/>',
  warn: '<path d="M12 3L2.5 20h19z"/><path d="M12 10v4M12 17h.01"/>',
  info: '<circle cx="12" cy="12" r="9"/><path d="M12 11v5M12 8h.01"/>',
  inbox: '<path d="M3 13l3-8h12l3 8v6H3z"/><path d="M3 13h5l1 2h6l1-2h5"/>',
};

function icon(name) {
  const doc = new DOMParser().parseFromString(
    `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" aria-hidden="true">${ICONS[name] ?? ""}</svg>`,
    "image/svg+xml",
  );
  return document.importNode(doc.documentElement, true);
}

function table(rows, columns, emptyText = "Aucune donnée.") {
  if (!rows || rows.length === 0) return empty(emptyText);
  return h("div", { class: "table-wrap" },
    h("table", { class: "stack" },
      h("thead", {}, h("tr", {}, columns.map((c) => h("th", { class: c.cls === "num" ? "num" : "" }, c.label)))),
      h("tbody", {}, rows.map((r) => h("tr", {}, columns.map((c) => h("td", { "data-label": c.label, class: c.cls ?? "" }, c.render ? c.render(r) : String(r[c.key] ?? ""))))))));
}

function badge(label, tone = "neutral") {
  return h("span", { class: `badge ${tone}` }, label);
}

const LOCK_STATES = { unlocked: ["Déverrouillé", "ok"], warning: ["Alerte", "warn"], locked: ["Verrouillé", "bad"], released: ["Libéré", "info"] };
function pill(state) {
  const [label, tone] = LOCK_STATES[state] ?? [state, "neutral"];
  return badge(label, tone);
}

const STATUSES = {
  pending: ["En attente", "warn"], pending_review: ["À décider", "warn"], approved: ["Approuvé", "ok"], rejected: ["Refusé", "bad"],
  active: ["Actif", "ok"], completed: ["Terminé", "info"], defaulted: ["En défaut", "bad"],
  closed: ["Clôturé", "info"], breached: ["En défaut", "bad"], locked: ["Verrouillé", "bad"], suspended: ["Suspendu", "bad"],
};
function statusBadge(status) {
  const [label, tone] = STATUSES[status] ?? [status ?? "—", "neutral"];
  return badge(label, tone);
}

const ROLE_LABELS_ALL = {
  client: "Client", field_agent: "Agent de terrain", distributor: "Distributeur", agency_supervisor: "Superviseur d'agence",
  risk_analyst: "Analyste risque", collections: "Recouvrement", finance: "Finance", system_admin: "Administrateur système",
};
const roleLabel = (role) => ROLE_LABELS_ALL[role] ?? role ?? "—";
const statusLabel = (status) => (STATUSES[status] ?? [status ?? "—"])[0];

function empty(text = "Aucune donnée.", name = "inbox") {
  return h("div", { class: "empty" }, icon(name), h("p", {}, text));
}

function loading(text = "Chargement…") {
  return h("div", { class: "loading", role: "status" }, h("span", { class: "spinner", "aria-hidden": "true" }), text);
}

function show(el) {
  const view = $("#view");
  view.replaceChildren(el);
  // Après une action, rend le message de confirmation visible même si la page était défilée.
  view.querySelector(".alert.ok")?.scrollIntoView({ block: "nearest" });
}

// kind : "ok", "error", "warn" ou "info"
function message(kind, text) {
  const iconName = { ok: "check", error: "alert", warn: "warn", info: "info" }[kind] ?? "info";
  return h("div", { class: `alert ${kind}`, role: kind === "error" ? "alert" : "status" }, icon(iconName), h("span", {}, text));
}

// Page = titre + sous-titre, puis une carte qui contient le contenu.
function page(title, lead, ...children) {
  return h("div", { class: "page" },
    h("div", { class: "page-head" }, h("h2", {}, title), lead ? h("p", { class: "lead" }, lead) : ""),
    h("div", { class: "card" }, ...children));
}

// Liste "libellé / valeur" (remplace l'affichage JSON brut).
const humanize = (k) => { const t = String(k).replace(/_/g, " "); return t.charAt(0).toUpperCase() + t.slice(1); };
function formatValue(v) {
  if (v === null || v === undefined || v === "") return "—";
  if (typeof v === "boolean") return v ? "Oui" : "Non";
  if (typeof v === "object") return JSON.stringify(v);
  return String(v);
}
const kvItem = (label, value) => h("div", {}, h("dt", {}, label), h("dd", {}, value instanceof Node ? value : formatValue(value)));
const kvList = (items) => h("dl", { class: "kv" }, items.map(([label, value]) => kvItem(label, value)));
const rawJson = (data) => h("details", { class: "raw" }, h("summary", {}, "Voir le détail technique (JSON)"), h("pre", {}, JSON.stringify(data, null, 2)));

function resultBlock(title, data) {
  const kids = [message("ok", title)];
  if (data && typeof data === "object" && !Array.isArray(data)) {
    const flat = Object.entries(data).filter(([, v]) => v === null || typeof v !== "object");
    if (flat.length) kids.push(kvList(flat.map(([k, v]) => [humanize(k), v])));
  }
  kids.push(rawJson(data));
  return h("div", { class: "result" }, ...kids);
}

// Fenêtres de dialogue (remplacent prompt() et confirm()).
// Résout avec les valeurs saisies ({ nom: valeur }), ou avec null si la personne annule.
function dialogForm({ title, text, fields = [], confirmText = "Confirmer", danger = false }) {
  return new Promise((resolve) => {
    const controls = fields.map((f) => {
      let el;
      if (f.options) {
        el = h("select", { name: f.name }, f.options.map(([v, l]) => h("option", { value: v }, l)));
      } else {
        const attrs = { name: f.name, ...(f.required === false ? {} : { required: "" }) };
        if (f.placeholder) attrs.placeholder = f.placeholder;
        if (f.textarea) attrs.rows = "3";
        else { attrs.type = f.type ?? "text"; if (f.min !== undefined) attrs.min = String(f.min); if (f.step !== undefined) attrs.step = String(f.step); }
        el = h(f.textarea ? "textarea" : "input", attrs);
      }
      if (f.value !== undefined) el.value = String(f.value);
      return el;
    });

    let settled = false;
    const dlg = h("dialog", { class: "modal", "aria-labelledby": "dialog-title" });
    const finish = (value) => {
      if (settled) return;
      settled = true;
      if (dlg.open) dlg.close();
      dlg.remove();
      resolve(value);
    };

    const form = h("form", {
      onsubmit: (e) => {
        e.preventDefault();
        finish(Object.fromEntries(fields.map((f, i) => [f.name, controls[i].value.trim()])));
      },
    },
      h("div", { class: "modal-body" },
        h("h3", { id: "dialog-title" }, title),
        text ? h("p", { class: "modal-text" }, text) : "",
        ...fields.map((f, i) => h("label", {}, f.label, controls[i])),
        h("div", { class: "modal-actions" },
          h("button", { type: "button", onclick: () => finish(null) }, "Annuler"),
          h("button", { type: "submit", class: `primary${danger ? " danger" : ""}` }, confirmText))));

    dlg.append(form);
    dlg.addEventListener("close", () => finish(null));               // touche Échap
    dlg.addEventListener("click", (e) => { if (e.target === dlg) finish(null); }); // clic sur le fond
    document.body.append(dlg);
    dlg.showModal();
  });
}

// Demande un motif (obligatoire). Renvoie le texte, ou null si annulé.
const askReason = (title, text, opts = {}) => dialogForm({
  title, text, confirmText: opts.confirmText ?? "Valider", danger: opts.danger ?? false,
  fields: [{ name: "reason", label: opts.label ?? "Motif", textarea: true, placeholder: opts.placeholder }],
}).then((r) => (r && r.reason ? r.reason : null));

const confirmDialog = (title, text, confirmText = "Confirmer") =>
  dialogForm({ title, text, confirmText }).then((r) => r !== null);

function setBusy(button, on) {
  if (!button) return;
  button.disabled = on;
  if (on) button.setAttribute("aria-busy", "true"); else button.removeAttribute("aria-busy");
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

// ---------------------------------------------------------------- navigation (groupes du menu)
const NAV_GROUPS = [
  { title: null, items: [
    { key: "home", label: "Accueil", icon: "home", desc: "" },
  ] },
  { title: "Dossiers de crédit", items: [
    { key: "simulator", label: "Simulateur", icon: "calculator", desc: "Calculez une offre : acompte, durée, échéances." },
    { key: "applications", label: "Dossiers", icon: "folder", desc: "Consultez les derniers dossiers de crédit." },
    { key: "reviews", label: "Dossiers à décider", icon: "review", desc: "Décidez des dossiers que le score automatique n'a pas tranchés." },
    { key: "policy", label: "Politique de crédit", icon: "sliders", desc: "Frais, intérêts, pénalités et seuils de score." },
  ] },
  { title: "Paiements", items: [
    { key: "payment", label: "Paiement manuel", icon: "card", desc: "Enregistrez un paiement Mobile Money reçu." },
    { key: "cash", label: "Espèces à confirmer", icon: "cash", desc: "Confirmez les espèces encaissées par les agents." },
    { key: "merchants", label: "Numéros marchands", icon: "store", desc: "Numéros où les clients envoient leurs paiements." },
  ] },
  { title: "Appareils", items: [
    { key: "devices", label: "Appareils", icon: "phone", desc: "Verrouillez, déverrouillez et suivez les appareils." },
    { key: "sims", label: "SIM à valider", icon: "sim", desc: "Autorisez ou refusez les changements de carte SIM." },
    { key: "devicepolicy", label: "Politique des appareils", icon: "lock", desc: "Règles de sécurité et fournisseur de verrouillage." },
    { key: "stock", label: "Stock", icon: "box", desc: "Affectez les téléphones en stock aux agences." },
  ] },
  { title: "Recouvrement", items: [
    { key: "collections", label: "Recouvrement", icon: "call", desc: "Tâches de relance et de visite : assignation et clôture." },
    { key: "contracts", label: "Remises et restructuration", icon: "percent", desc: "Demandez une remise de pénalités ou une restructuration." },
    { key: "approvals", label: "Validations", icon: "shield", desc: "Double validation des demandes sensibles." },
  ] },
];
const NAV_ITEMS = NAV_GROUPS.flatMap((g) => g.items);
const VIEW_LABELS = Object.fromEntries(NAV_ITEMS.map((i) => [i.key, i.label]));

const FREQ_OPTIONS = [["daily", "Par jour"], ["weekly", "Par semaine"], ["monthly", "Par mois"]];
const fmtDay = (s) => { const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(String(s ?? "")); return m ? `${m[3]}/${m[2]}/${m[1]}` : formatValue(s); };
const money = (v) => (v === null || v === undefined ? "—" : usd(v));
const fmtDate = (s) => (s ? new Date(s).toLocaleString("fr-FR", { dateStyle: "short", timeStyle: "short" }) : "—");

// Résumé lisible d'une offre de crédit (réponse de simulate_loan).
function offerView(o) {
  const freq = FREQ_OPTIONS.find(([k]) => k === o.frequency)?.[1]?.toLowerCase() ?? o.frequency;
  const stat = (label, value, sub) => h("div", { class: "stat" },
    h("div", { class: "stat-label" }, label), h("div", { class: "stat-value" }, value), sub ? h("div", { class: "stat-sub" }, sub) : "");
  const pen = o.penalty_terms;
  const lock = o.lock_terms;
  const warnings = (Array.isArray(o.warnings) ? o.warnings : []).map((w) => message("warn", typeof w === "string" ? w : JSON.stringify(w)));
  return h("div", { class: "result" },
    ...warnings,
    h("div", { class: "stats" },
      stat("Échéance", money(o.installment_amount), `${o.installment_count ?? "?"} échéance(s), ${freq}`),
      stat("Total à rembourser", money(o.total_due), `dont intérêt ${money(o.interest_amount)}`),
      stat("Montant financé", money(o.financed_amount), `après un acompte de ${money(o.down_payment)}`)),
    kvList([
      ["Prix du téléphone", money(o.retail_price)],
      ["Acompte", money(o.down_payment)],
      ["Acompte minimum", money(o.minimum_down_payment)],
      ["Frais de dossier", money(o.processing_fee)],
      ["Durée", o.duration_days === undefined ? "—" : `${o.duration_days} jours`],
      ["Dernière échéance", money(o.last_installment_amount)],
      ["Première échéance le", fmtDay(o.first_due_date)],
      ["Fin du crédit le", fmtDay(o.end_date)],
      ["Pénalité de retard", pen ? `${round(Number(pen.penalty_rate_per_day) * 100, 4)} % par jour, après ${pen.grace_days} jour(s) de grâce` : "—"],
      ["Verrouillage du téléphone", lock ? `après ${lock.lock_after_days_overdue} jour(s) de retard` : "—"],
    ]),
    rawJson(o));
}

// ---------------------------------------------------------------- views
const views = {
  async home(me) {
    const tile = (i) => h("a", { class: "tile", href: `#/${i.key}` },
      h("span", { class: "tile-icon" }, icon(i.icon)),
      h("span", {}, h("div", { class: "tile-title" }, i.label), h("div", { class: "tile-desc" }, i.desc)));
    return h("div", { class: "page" },
      h("section", { class: "hero" },
        h("div", {}, h("h2", {}, "Bienvenue"), h("p", {}, "Choisissez une rubrique ci-dessous ou utilisez le menu pour naviguer.")),
        h("div", { class: "hero-chips" },
          h("span", { class: "chip" }, roleLabel(me.role)),
          h("span", { class: "chip" }, `Statut : ${statusLabel(me.status)}`))),
      ...NAV_GROUPS.filter((g) => g.title).map((g) => h("section", { class: "tile-group" },
        h("h3", {}, g.title),
        h("div", { class: "tiles" }, g.items.map(tile)))));
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
        out.append(offerView(data));
      },
    },
      h("div", { class: "form-grid" },
        h("label", {}, "Téléphone", h("select", { name: "item" }, items.map((i) => h("option", { value: i.id }, `${i.brand} ${i.model} — ${i.retail_price} USD`)))),
        h("label", {}, "Acompte (USD)", h("input", { name: "down", type: "number", step: "0.01", required: "", placeholder: "0.00" })),
        h("label", {}, "Durée (jours)", h("select", { name: "duration" }, [30, 60, 90, 120, 180].map((d) => h("option", { value: d }, `${d} jours`)))),
        h("label", {}, "Fréquence de paiement", h("select", { name: "frequency" }, FREQ_OPTIONS.map(([v, l]) => h("option", { value: v }, l))))),
      h("button", { type: "submit" }, "Simuler"));
    return page("Simulateur de crédit", "Réservé aux agents de terrain de l'agence (règle de l'étape 2).",
      (items ?? []).length ? form : empty("Aucun téléphone en stock à simuler."), out);
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

    const lead = "Les règles appliquées à tous les nouveaux dossiers." + (canEdit ? " Les contrats déjà signés ne changent pas." : "");
    if (!canEdit) {
      return page("Politique de crédit", lead, message("info", "Lecture seule : seul l'administrateur système peut modifier ces règles."), ...groups);
    }

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
      h("div", { class: "savebar" },
        h("label", {}, "Motif du changement", reason),
        h("button", { type: "submit" }, "Enregistrer")));
    return page("Politique de crédit", lead, out, form);
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
          out.append(resultBlock("Opération réussie.", res));
        } catch (err) { out.append(message("error", err.message)); }
      },
    },
      h("div", { class: "form-grid" },
        h("label", {}, "Opérateur", h("select", { name: "method" }, PROVIDERS.map(([v, l]) => h("option", { value: v }, l)))),
        h("label", {}, "Référence de transaction opérateur", h("input", { name: "ref", required: "" })),
        h("label", {}, "Numéro de contrat", h("input", { name: "contract", required: "", placeholder: "CT-2026-000123" })),
        h("label", {}, "Montant (USD)", h("input", { name: "amount", type: "number", step: "0.01", required: "", placeholder: "0.00" })),
        h("label", {}, "Note (facultatif)", h("input", { name: "note" }))),
      h("button", { type: "submit" }, "Valider le paiement"));
    return page("Paiement Mobile Money (saisie manuelle)",
      "Réservé à la Finance et aux superviseurs. La validation écrit au grand livre et peut débloquer le téléphone.",
      form, out);
  },

  async applications() {
    const { data, error } = await sb.from("loan_application")
      .select("id, status, total_price, down_payment, financed_amount, duration_days, created_at")
      .order("created_at", { ascending: false }).limit(50);
    if (error) return message("error", error.message);
    return page("Dossiers", "Les 50 derniers dossiers de crédit.", table(data, [
      { label: "Statut", render: (r) => statusBadge(r.status) },
      { label: "Prix (USD)", key: "total_price", cls: "num" },
      { label: "Acompte", key: "down_payment", cls: "num" },
      { label: "Financé", key: "financed_amount", cls: "num" },
      { label: "Durée (jours)", key: "duration_days", cls: "num" },
      { label: "Créé", render: (r) => fmtDate(r.created_at) },
    ], "Aucun dossier pour le moment."));
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

    const lock = async (d) => {
      const reason = await askReason("Verrouiller l'appareil", `IMEI ${d.imei}. Indiquez le motif du verrouillage.`, { confirmText: "Verrouiller", danger: true });
      if (reason) run(() => api("manual_lock_device", { p_device: d.id, p_reason: reason }), `Appareil ${d.imei} : verrouillage demandé.`);
    };
    const unlock = async (d) => {
      const r = await dialogForm({
        title: "Déverrouiller l'appareil", text: `IMEI ${d.imei}. Durée en heures (nombre entier), selon la politique des appareils.`, confirmText: "Déverrouiller",
        fields: [
          { name: "hours", label: "Durée du déverrouillage (heures)", type: "number", min: 1, step: 1, value: 24 },
          { name: "reason", label: "Motif", textarea: true },
        ],
      });
      if (!r) return;
      const hours = Number(r.hours);
      if (!Number.isInteger(hours) || hours < 1) { out.replaceChildren(message("error", "Durée invalide : entrez un nombre entier d'heures.")); return; }
      if (r.reason) run(() => api("manual_unlock_device", { p_device: d.id, p_hours: hours, p_reason: r.reason }), `Appareil ${d.imei} : déverrouillé pour ${hours} h.`);
    };
    const clearHold = async (d) => {
      const reason = await askReason("Lever le blocage sécurité", `IMEI ${d.imei}. Indiquez le motif.`);
      if (reason) run(() => api("clear_security_hold", { p_device: d.id, p_reason: reason }), `Appareil ${d.imei} : blocage sécurité levé.`);
    };

    return page("Appareils gérés", "Les 50 derniers appareils : état de verrouillage et actions manuelles.", out, table(data, [
      { label: "IMEI", key: "imei", cls: "mono" },
      { label: "État", render: (r) => pill(r.lock_state) },
      { label: "Raison", render: (r) => r.lock_reason ?? "—", cls: "wrap-text" },
      { label: "Fournisseur", render: (r) => r.lock_provider ?? "—" },
      { label: "Blocage sécurité", render: (r) => (r.security_hold ? badge("Oui", "bad") : badge("Non")) },
      { label: "Dernière activité", render: (r) => fmtDate(r.last_seen_at) },
      { label: "Actions", cls: "actions-cell", render: (r) => {
        if (r.lock_state === "released") return "Libéré";
        const buttons = [];
        if (r.lock_state !== "locked") buttons.push(h("button", { class: "danger", onclick: () => lock(r) }, "Verrouiller"));
        if (r.lock_state === "locked") buttons.push(h("button", { class: "primary", onclick: () => unlock(r) }, "Déverrouiller"));
        if (r.security_hold) buttons.push(h("button", { onclick: () => clearHold(r) }, "Lever le blocage"));
        return h("div", { class: "actions" }, ...buttons);
      } },
    ], "Aucun appareil géré pour le moment."));
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
      const reason = await askReason(approve ? "Autoriser cette SIM" : "Refuser cette SIM",
        `Appareil ${r.managed_device?.imei ?? "—"}. Indiquez le motif de la décision.`,
        { confirmText: approve ? "Autoriser" : "Refuser", danger: !approve });
      if (!reason) return;
      try {
        await api("resolve_sim_change", { p_device: r.managed_device_id, p_sim_hash: r.sim_hash, p_approve: approve, p_reason: reason });
        show(await views.sims(me, approve ? "SIM autorisée." : "SIM refusée."));
      } catch (err) { out.replaceChildren(message("error", friendlyError(err.message))); }
    };

    return page("SIM à valider",
      "Une nouvelle carte SIM a été détectée dans ces appareils. Tant qu'elle n'est pas autorisée, la politique de l'appareil peut le verrouiller.",
      out,
      table(data, [
        { label: "Appareil (IMEI)", render: (r) => r.managed_device?.imei ?? "—", cls: "mono" },
        { label: "Empreinte SIM", render: (r) => `${r.sim_hash.slice(0, 12)}…`, cls: "mono" },
        { label: "Détectée le", render: (r) => fmtDate(r.first_seen_at) },
        { label: "Décision", cls: "actions-cell", render: (r) => h("div", { class: "actions" },
          h("button", { class: "primary", onclick: () => decide(r, true) }, "Autoriser"),
          h("button", { class: "danger", onclick: () => decide(r, false) }, "Refuser")) },
      ], "Aucune SIM en attente de validation."));
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
    const boolInputs = BOOLS.map(([k]) => h("input", { type: "checkbox", class: "switch", ...(pol[k] ? { checked: "" } : {}), ...lock }));
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
      h("div", { class: "checks" }, BOOLS.map(([, label], i) => h("label", { class: "check" }, boolInputs[i], h("span", {}, label)))),
      h("div", { class: "form-grid" }, NUMS.map(([, label], i) => h("label", {}, label, numInputs[i]))),
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
    }, h("div", { class: "form-grid" }, h("label", {}, "Marque", brandInput), h("label", {}, "Fournisseur", providerSelect)),
      canEdit ? h("button", { type: "submit" }, "Enregistrer") : "", brandResult);

    return page("Politique des appareils", "Règles de sécurité imposées aux téléphones et fournisseur de verrouillage par marque.",
      canEdit ? "" : message("info", "Lecture seule : seul l'administrateur système peut modifier ces réglages."),
      out, policyForm,
      h("h3", {}, "Fournisseur de verrouillage par marque"),
      table(brands ?? [], [{ label: "Marque", key: "brand" }, { label: "Fournisseur", render: (r) => PROVIDER_LABELS[r.provider] ?? r.provider }], "Aucun fournisseur défini."),
      brandForm);
  },

  async approvals() {
    const { data, error } = await sb.from("approval_request")
      .select("id, kind, payload, reason, status, created_at").eq("status", "pending")
      .order("created_at", { ascending: false });
    if (error) return message("error", error.message);
    const out = h("div");
    const decide = async (id, approve) => {
      const reason = await askReason(approve ? "Approuver cette demande" : "Rejeter cette demande", "Indiquez le motif de la décision.",
        { confirmText: approve ? "Approuver" : "Rejeter", danger: !approve });
      if (!reason) return;
      try {
        const res = await api("decide_approval", { p_request: id, p_approve: approve, p_reason: reason });
        out.replaceChildren(resultBlock("Décision enregistrée.", res));
      } catch (err) { out.replaceChildren(message("error", err.message)); }
    };
    return page("Demandes à valider (double validation)", "Les remises de pénalités et restructurations demandées attendent ici une seconde décision.",
      out,
      table(data, [
        { label: "Type", key: "kind" },
        { label: "Détail", render: (r) => JSON.stringify(r.payload), cls: "mono wrap-text" },
        { label: "Motif", key: "reason", cls: "wrap-text" },
        { label: "Actions", cls: "actions-cell", render: (r) => h("div", { class: "actions" },
          h("button", { class: "primary", onclick: () => decide(r.id, true) }, "Approuver"),
          h("button", { class: "danger", onclick: () => decide(r.id, false) }, "Rejeter")) },
      ], "Aucune demande en attente de validation."));
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
      const active = h("input", { type: "checkbox", class: "switch", ...((cur?.active ?? true) ? { checked: "" } : {}), ...lock });
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
        h("div", { class: "form-grid" },
          h("label", {}, "Numéro marchand", number),
          h("label", {}, "Nom affiché au client", name)),
        h("label", { class: "check" }, active, h("span", {}, "Actif (visible dans l'application client)")),
        canEdit ? h("div", { class: "form-actions" }, h("button", { type: "submit" }, "Enregistrer")) : "",
        result,
      );
      return h("div", { class: "pgroup" }, h("h3", {}, label), form);
    });

    const hasActive = (data ?? []).some((r) => r.active && r.merchant_number);
    return page("Numéros marchands (Mobile Money)", "Le client paie vers ces numéros en indiquant son numéro de contrat comme référence.",
      hasActive ? "" : message("warn", "Aucun numéro actif : l'application client ne pourra pas afficher d'instructions de paiement."),
      canEdit ? "" : message("info", "Lecture seule : seul l'administrateur système peut modifier ces numéros."),
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
      const reason = await askReason(approve ? "Approuver ce dossier" : "Refuser ce dossier", "Un motif est obligatoire.",
        { confirmText: approve ? "Approuver" : "Refuser", danger: !approve });
      if (!reason) return;
      try {
        await api("resolve_review", { p_application: a.id, p_approve: approve, p_reason: reason });
        show(await views.reviews(me, approve ? "Dossier approuvé." : "Dossier refusé."));
      } catch (err) { result.replaceChildren(message("error", friendlyError(err.message))); }
    };

    const cards = (data ?? []).map((a) => {
      const d = latest.get(a.id);
      const c = a.customer;
      const result = h("div");
      const own = a.created_by === me.id;
      const meta = [
        c?.phone_number ? `Téléphone : ${c.phone_number}` : "",
        a.inventory_item ? `Appareil : ${a.inventory_item.brand} ${a.inventory_item.model}` : "",
      ].filter(Boolean).join(" — ");
      return h("div", { class: "pgroup case" },
        h("div", { class: "case-head" },
          h("h3", {}, c ? `${c.first_name} ${c.last_name}` : "Client (non visible avec votre rôle)"),
          d ? badge(`Score ${d.score ?? "—"}/100`, "warn") : ""),
        meta ? h("p", { class: "case-meta" }, meta) : "",
        kvList([
          ["Prix", usd(a.total_price)], ["Acompte", usd(a.down_payment)], ["Financé", usd(a.financed_amount)],
          ["Durée", `${a.duration_days} jours`], ["Paiement", FREQUENCY_LABELS[a.installment_frequency] ?? a.installment_frequency],
        ]),
        d?.review_flags?.length ? message("warn", `À vérifier : ${d.review_flags.join(", ")}`) : "",
        d?.hard_rule_failures?.length ? message("error", `Règles non respectées : ${d.hard_rule_failures.join(", ")}`) : "",
        d?.reason ? h("p", { class: "note" }, d.reason) : "",
        own ? message("warn", "Vous avez créé ce dossier : vous ne pouvez pas le décider.") : "",
        h("div", { class: "actions" },
          h("button", { class: "primary", ...(own ? { disabled: "" } : {}), onclick: () => decide(a, true, result) }, "Approuver"),
          h("button", { class: "danger", ...(own ? { disabled: "" } : {}), onclick: () => decide(a, false, result) }, "Refuser")),
        result);
    });

    return page("Dossiers à décider", "Dossiers que le score automatique n'a pas pu trancher. Un motif est obligatoire.",
      out, ...(cards.length ? cards : [empty("Aucun dossier en attente de décision.", "check")]));
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
      if (!(await confirmDialog(`Confirmer la réception de ${usd(r.amount)} ?`, `Contrat ${number}. Le paiement sera écrit au grand livre.`, "Confirmer la réception"))) return;
      try {
        await api("confirm_cash_payment", { p_payment: r.id });
        show(await views.cash(me, `Paiement de ${usd(r.amount)} confirmé (contrat ${number}).`));
      } catch (err) { out.replaceChildren(message("error", friendlyError(err.message))); }
    };

    return page("Espèces à confirmer", "Paiements encaissés par les agents. La confirmation les écrit au grand livre et peut débloquer le téléphone.",
      out,
      table(data, [
        { label: "Contrat", render: (r) => r.loan_contract?.contract_number ?? "—", cls: "mono" },
        { label: "Montant", render: (r) => usd(r.amount), cls: "num" },
        { label: "Encaissé le", render: (r) => fmtDate(r.created_at) },
        { label: "Action", cls: "actions-cell", render: (r) => (r.collected_by === me.id
          ? h("span", { class: "note" }, "Vous avez encaissé ce paiement")
          : h("div", { class: "actions" }, h("button", { class: "primary", onclick: () => confirmPayment(r) }, "Confirmer"))) },
      ], "Aucun paiement en espèces à confirmer."));
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
    const complete = async (t) => {
      const r = await dialogForm({
        title: "Clôturer la tâche", text: `Contrat ${t.loan_contract?.contract_number ?? "—"}.`, confirmText: "Clôturer",
        fields: [{ name: "outcome", label: "Résultat du contact ou de la visite", textarea: true }],
      });
      if (r && r.outcome) run(() => api("complete_collection_task", { p_task: t.id, p_outcome: r.outcome }), "Tâche clôturée.");
    };

    const assign = async (t) => {
      const r = await dialogForm({
        title: "Assigner la tâche", text: `Contrat ${t.loan_contract?.contract_number ?? "—"}.`, confirmText: "Assigner",
        fields: [
          { name: "assignee", label: "Agent", options: (people ?? []).map((p) => [p.id, `${ROLE_LABELS[p.role] ?? p.role}${p.zone ? ` — ${p.zone}` : ""} (${p.id.slice(0, 8)})`]) },
          { name: "type", label: "Type de tâche", options: [["followup", "Relance"], ["field_visit", "Visite terrain"]], value: t.task_type || "followup" },
        ],
      });
      if (r) run(() => api("assign_collection_task", { p_task: t.id, p_assignee: r.assignee, p_task_type: r.type }), "Tâche assignée.");
    };

    const actionsCell = (t) => {
      const kids = [];
      if ((people ?? []).length) kids.push(h("button", { onclick: () => assign(t) }, t.assigned_to ? "Réassigner" : "Assigner"));
      else kids.push(h("span", { class: "note" }, "Agents non visibles avec votre rôle"));
      kids.push(h("button", { class: "primary", onclick: () => complete(t) }, "Clôturer"));
      return h("div", { class: "actions" }, ...kids);
    };

    return page("Recouvrement", "Une seule tâche ouverte par contrat. Les tâches urgentes sont en premier.",
      out,
      table(tasks, [
        { label: "Contrat", render: (r) => r.loan_contract?.contract_number ?? "—", cls: "mono" },
        { label: "Client", render: (r) => (r.customer ? h("div", {}, h("div", {}, `${r.customer.first_name} ${r.customer.last_name}`), h("div", { class: "note nowrap" }, r.customer.phone_number ?? "")) : "—") },
        { label: "Type", render: (r) => (r.task_type === "field_visit" ? "Visite terrain" : "Relance") },
        { label: "Priorité", render: (r) => (r.priority === "high" ? badge("Urgente", "bad") : badge("Normale")) },
        { label: "Motif", key: "reason", cls: "wrap-text" },
        { label: "Échéance", render: (r) => fmtDay(r.due_date), cls: "nowrap" },
        { label: "État", render: (r) => (r.assigned_to ? badge("Assignée", "ok") : badge("À assigner", "warn")) },
        { label: "Actions", cls: "actions-cell", render: actionsCell },
      ], "Aucune tâche de recouvrement ouverte."));
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
        .select("id, contract_number, status, loan_application(customer(first_name, last_name))").eq("contract_number", number).maybeSingle();
      if (error) { out.append(message("error", error.message)); return; }
      if (!c) { out.append(message("error", "Contrat introuvable (ou non visible avec votre rôle).")); return; }
      let st = null;
      try { st = await api("contract_status", { p_contract: c.id }); } catch (err) { out.append(message("error", friendlyError(err.message))); }

      const penalty = Number(st?.penalty_outstanding_usd ?? 0);
      detail.append(h("div", { class: "pgroup" },
        h("h3", {}, `${c.contract_number} — ${c.loan_application?.customer ? `${c.loan_application.customer.first_name} ${c.loan_application.customer.last_name}` : "client"}`),
        st ? kvList([
          ["Statut", statusBadge(c.status)],
          ["Retard", `${st.overdue_days ?? 0} jour(s)`],
          ["Pénalités dues", usd(penalty)],
          ["Total restant dû", usd(st.total_outstanding_usd ?? 0)],
        ]) : ""));

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
      }, h("h3", {}, "Demander une remise de pénalités"),
        h("div", { class: "form-grid" }, h("label", {}, "Montant", wAmount), h("label", {}, "Motif", wReason)),
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
      }, h("h3", {}, "Demander une restructuration"),
        h("div", { class: "form-grid" }, h("label", {}, "Nouvelle durée (jours)", rDays), h("label", {}, "Fréquence", rFreq), h("label", {}, "Motif", rReason)),
        h("label", { class: "check" }, rWaive, h("span", {}, "Effacer aussi les pénalités")),
        h("button", { type: "submit" }, "Envoyer la demande"), rResult));
    };

    return page("Remises et restructuration", "Cherchez un contrat par son numéro. Les demandes sont ensuite décidées dans « Validations ».",
      h("form", { class: "search-row", onsubmit: lookup }, h("label", {}, "Numéro de contrat", numberInput), h("button", { type: "submit" }, "Chercher")),
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
      const box = h("input", { type: "checkbox", "aria-label": `Sélectionner ${it.brand} ${it.model} (${it.imei})`, ...(canEdit ? {} : { disabled: "" }) });
      checks.set(it.id, box);
      return { it, box };
    });

    const agencySelect = h("select", { "aria-label": "Agence de destination" }, ...(agencies ?? []).map((a) => h("option", { value: a.id }, a.region ? `${a.name} (${a.region})` : a.name)));
    const toggleAll = () => {
      const all = rows.length > 0 && rows.every((r) => r.box.checked);
      rows.forEach((r) => { r.box.checked = !all; });
    };
    const assign = async () => {
      out.replaceChildren();
      const ids = rows.filter((r) => r.box.checked).map((r) => r.it.id);
      if (!ids.length) { out.append(message("error", "Sélectionnez au moins un appareil.")); return; }
      if (!agencySelect.value) { out.append(message("error", "Aucune agence disponible.")); return; }
      const agencyName = agencySelect.selectedOptions[0].textContent;
      if (!(await confirmDialog("Affecter à une agence", `Affecter ${ids.length} appareil(s) à l'agence « ${agencyName} » ?`, "Affecter"))) return;
      try {
        await api("assign_stock_to_agency", { p_items: ids, p_agency: agencySelect.value });
        show(await views.stock(me, `${ids.length} appareil(s) affecté(s).`));
      } catch (err) { out.append(message("error", friendlyError(err.message))); }
    };

    return page("Stock en magasin", "Seuls les téléphones en stock peuvent changer d'agence.",
      canEdit
        ? h("div", { class: "toolbar" },
          h("span", { class: "inline-label" }, "Agence de destination :"), agencySelect,
          h("button", { class: "primary", onclick: assign }, "Affecter la sélection"),
          h("button", { class: "btn ghost", onclick: toggleAll }, "Tout sélectionner / désélectionner"))
        : message("info", "Lecture seule : seul l'administrateur système peut affecter le stock."),
      out,
      table(rows.map((r) => r.it), [
        { label: "", render: (it) => checks.get(it.id) },
        { label: "IMEI", key: "imei", cls: "mono" },
        { label: "Appareil", render: (it) => `${it.brand} ${it.model}` },
        { label: "Prix", render: (it) => usd(it.retail_price), cls: "num" },
        { label: "Agence actuelle", render: (it) => it.agency?.name ?? "—" },
      ], "Aucun téléphone en stock."));
  },

};

// ---------------------------------------------------------------- navigation et démarrage
let me = null;
let currentView = null;

const viewFromHash = () => {
  const m = /^#\/([a-z]+)$/.exec(location.hash);
  return m && VIEW_LABELS[m[1]] ? m[1] : null;
};

function setNav(isOpen) {
  document.body.classList.toggle("nav-open", isOpen);
  $("#menu-toggle").setAttribute("aria-expanded", String(isOpen));
  $("#menu-toggle").setAttribute("aria-label", isOpen ? "Fermer le menu" : "Ouvrir le menu");
  $("#scrim").hidden = !isOpen;
}
const closeNav = () => setNav(false);

async function open(name) {
  currentView = name;
  document.querySelectorAll("#nav a").forEach((a) => {
    if (a.dataset.view === name) a.setAttribute("aria-current", "page"); else a.removeAttribute("aria-current");
  });
  document.title = `${VIEW_LABELS[name]} · CTM`;
  closeNav();
  show(loading());
  let el;
  try { el = await views[name](me); } catch (e) { el = message("error", e.message); }
  if (currentView !== name) return; // la personne est déjà passée à une autre page
  show(el);
  window.scrollTo(0, 0);
  $("#view").focus({ preventScroll: true });
}

function renderWho(email, role) {
  $("#who").replaceChildren(
    h("span", { class: "avatar", "aria-hidden": "true" }, (email || "?").trim().slice(0, 2)),
    h("span", { class: "who-text" }, h("span", { class: "who-mail" }, email), role ? h("span", { class: "who-role" }, roleLabel(role)) : ""));
}

async function start() {
  const { data: { session } } = await sb.auth.getSession();
  if (recovering && session) {
    $("#login").hidden = true; $("#app").hidden = true; $("#reset").hidden = false;
    $("#logout").hidden = true; $("#menu-toggle").hidden = true;
    return;
  }
  $("#reset").hidden = true;
  $("#login").hidden = !!session;
  $("#app").hidden = !session;
  $("#logout").hidden = !session;
  $("#menu-toggle").hidden = !session;
  document.body.classList.toggle("logged", !!session);
  if (!session) { $("#who").replaceChildren(); currentView = null; closeNav(); document.title = "CTM · Crédit Téléphone"; return; }

  try { me = await api("me"); } catch (e) { me = null; }
  if (!me) {
    renderWho(session.user.email);
    $("#nav").replaceChildren();
    $("#menu-toggle").hidden = true;
    show(message("error", "Compte non configuré : aucune fiche app_user pour cet utilisateur."));
    return;
  }
  renderWho(session.user.email, me.role);
  $("#nav").replaceChildren(...NAV_GROUPS.map((g) => h("div", { class: "nav-group" },
    g.title ? h("p", { class: "nav-title" }, g.title) : "",
    ...g.items.map((i) => h("a", {
      class: "nav-link", href: `#/${i.key}`, "data-view": i.key,
      onclick: (e) => { if (viewFromHash() === i.key) { e.preventDefault(); open(i.key); } closeNav(); },
    }, icon(i.icon), h("span", {}, i.label))))));
  open(viewFromHash() ?? "home");
}

window.addEventListener("hashchange", () => {
  const name = viewFromHash();
  if (name && me && !$("#app").hidden) open(name);
});
$("#menu-toggle").addEventListener("click", () => setNav(!document.body.classList.contains("nav-open")));
$("#scrim").addEventListener("click", closeNav);
document.addEventListener("keydown", (e) => { if (e.key === "Escape") closeNav(); });
window.matchMedia("(min-width: 901px)").addEventListener("change", (e) => { if (e.matches) closeNav(); });

// Messages d'authentification en français
const AUTH_ERRORS = [
  [/invalid login credentials/i, "Email ou mot de passe incorrect."],
  [/email not confirmed/i, "Cette adresse e-mail n'est pas encore confirmée."],
  [/rate limit|too many/i, "Trop de tentatives. Réessayez dans quelques minutes."],
  [/failed to fetch|network|load failed/i, "Connexion impossible. Vérifiez votre accès internet."],
];
const friendlyAuthError = (msg) => { for (const [p, t] of AUTH_ERRORS) if (p.test(msg)) return t; return msg; };

$("#toggle-password").addEventListener("click", (e) => {
  const input = $("#password");
  const reveal = input.type === "password";
  input.type = reveal ? "text" : "password";
  e.currentTarget.setAttribute("aria-pressed", String(reveal));
  e.currentTarget.setAttribute("aria-label", reveal ? "Masquer le mot de passe" : "Afficher le mot de passe");
});

$("#login-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  const err = $("#login-error");
  const btn = $("#login-form button[type=submit]");
  err.className = "form-msg error"; err.textContent = "";
  setBusy(btn, true);
  try {
    const { error } = await sb.auth.signInWithPassword({ email: $("#email").value, password: $("#password").value });
    if (error) { err.textContent = friendlyAuthError(error.message); return; }
  } finally { setBusy(btn, false); }
  start();
});

$("#forgot").addEventListener("click", async (e) => {
  e.preventDefault();
  const err = $("#login-error");
  err.className = "form-msg error"; err.textContent = "";
  const email = $("#email").value.trim();
  if (!email) { err.textContent = "Saisissez d'abord votre e-mail ci-dessus."; $("#email").focus(); return; }
  const { error } = await sb.auth.resetPasswordForEmail(email, { redirectTo: location.origin + "/" });
  if (error) { err.textContent = friendlyAuthError(error.message); return; }
  err.className = "form-msg ok";
  err.textContent = "Si ce compte existe, un e-mail de réinitialisation vient d'être envoyé.";
});

$("#reset-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  const btn = $("#reset-form button[type=submit]");
  $("#reset-error").textContent = "";
  setBusy(btn, true);
  try {
    const { error } = await sb.auth.updateUser({ password: $("#new-password").value });
    if (error) { $("#reset-error").textContent = friendlyAuthError(error.message); return; }
  } finally { setBusy(btn, false); }
  recovering = false;
  history.replaceState(null, "", location.pathname);
  $("#new-password").value = "";
  start();
});

$("#logout").addEventListener("click", async () => {
  await sb.auth.signOut();
  history.replaceState(null, "", location.pathname + location.search);
  start();
});

start();
