// Message content for each notification template.
// WhatsApp business-initiated messages must use templates approved by Meta.
// Create templates with EXACTLY these names (language: fr) and body variables {{1}}, {{2}}... in this order.
// The SMS text is the fallback when WhatsApp fails.

type Payload = Record<string, any>;
type Rendered = { whatsappName: string; params: string[]; sms: string };

const usd = (v: unknown) => `${Number(v ?? 0).toFixed(2)} USD`;

function merchants(list: unknown): string {
  if (!Array.isArray(list) || list.length === 0) return "contactez votre agent";
  return list.map((m: any) => `${m.display_name}: ${m.merchant_number}`).join(" / ");
}

export function render(template: string, p: Payload, extra: Payload = {}): Rendered {
  switch (template) {
    case "reminder_pre_due": {
      const params = [p.contract_number, p.due_date, usd(p.installment_due_usd), merchants(p.merchant_accounts)];
      return {
        whatsappName: template,
        params,
        sms: `Rappel: votre echeance de ${params[2]} (contrat ${params[0]}) est due le ${params[1]}. Paiement: ${params[3]}`,
      };
    }
    case "reminder_due_today": {
      const params = [p.contract_number, usd(p.installment_due_usd), merchants(p.merchant_accounts)];
      return {
        whatsappName: template,
        params,
        sms: `Aujourd'hui: echeance de ${params[1]} (contrat ${params[0]}). Paiement: ${params[2]}`,
      };
    }
    case "reminder_grace": {
      const lockText = p.days_before_lock == null
        ? "votre telephone sera bientot verrouille"
        : `votre telephone sera verrouille dans ${p.days_before_lock} jour(s)`;
      const params = [
        p.contract_number, String(p.days_late), usd(p.total_outstanding_usd), lockText, merchants(p.merchant_accounts),
      ];
      return {
        whatsappName: template,
        params,
        sms: `Retard de ${params[1]} jour(s), contrat ${params[0]}. A payer: ${params[2]}. Attention: ${params[3]}. Paiement: ${params[4]}`,
      };
    }
    case "payment_receipt": {
      const a = p.allocation ?? {};
      const detail = `penalites ${usd(a.penalty ?? 0)}, interets et frais ${usd(a.interest_fee ?? 0)}, capital ${usd(a.principal ?? 0)}`;
      const params = [p.contract_number, usd(p.amount_usd), detail, usd(p.outstanding_usd)];
      return {
        whatsappName: template,
        params,
        sms: `Paiement recu: ${params[1]} (contrat ${params[0]}). Reste a payer: ${params[3]}.`,
      };
    }
    case "closure_documents": {
      const numbers = (extra.document_numbers as string[] | undefined)?.join(", ") ?? "";
      const params = [p.contract_number, numbers];
      return {
        whatsappName: template,
        params,
        sms: `Felicitations: contrat ${params[0]} solde. Votre telephone vous appartient. Documents: ${numbers}`,
      };
    }
    default:
      throw new Error(`unknown template: ${template}`);
  }
}
