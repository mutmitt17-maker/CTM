// Outgoing channels. Default mode is "dry-run": nothing is sent, the message is only logged.
// Switch with NOTIFY_MODE=live once WhatsApp / SMS credentials are set.

const live = () => (Deno.env.get("NOTIFY_MODE") ?? "dry-run") === "live";

export async function sendWhatsApp(phoneE164: string, templateName: string, params: string[]): Promise<string> {
  if (!live()) {
    console.log("[dry-run] whatsapp", phoneE164, templateName, params);
    return "dry-run";
  }
  const token = Deno.env.get("WHATSAPP_TOKEN");
  const phoneId = Deno.env.get("WHATSAPP_PHONE_NUMBER_ID");
  if (!token || !phoneId) throw new Error("WhatsApp credentials are missing");

  const res = await fetch(`https://graph.facebook.com/v20.0/${phoneId}/messages`, {
    method: "POST",
    headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
    body: JSON.stringify({
      messaging_product: "whatsapp",
      to: phoneE164.replace(/^\+/, ""),
      type: "template",
      template: {
        name: templateName,
        language: { code: "fr" },
        components: [{
          type: "body",
          parameters: params.map((text) => ({ type: "text", text: String(text).replace(/\s+/g, " ") })),
        }],
      },
    }),
  });
  const body = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(`WhatsApp ${res.status}: ${JSON.stringify(body)}`);
  return body?.messages?.[0]?.id ?? "sent";
}

// Generic HTTP SMS gateway: POST {to, message} with a bearer key.
// Replace with your provider's real API (Africa's Talking, Infobip, Twilio...) before going live.
export async function sendSms(phoneE164: string, message: string): Promise<string> {
  if (!live()) {
    console.log("[dry-run] sms", phoneE164, message);
    return "dry-run";
  }
  const url = Deno.env.get("SMS_API_URL");
  const key = Deno.env.get("SMS_API_KEY");
  if (!url || !key) throw new Error("SMS credentials are missing");

  const res = await fetch(url, {
    method: "POST",
    headers: { Authorization: `Bearer ${key}`, "Content-Type": "application/json" },
    body: JSON.stringify({ to: phoneE164, message }),
  });
  if (!res.ok) throw new Error(`SMS ${res.status}: ${await res.text()}`);
  return "sent";
}
