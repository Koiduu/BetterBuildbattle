/** Sends one email through Resend's HTTP API. Needs RESEND_API_KEY; the sender is SUPPORT_FROM. */
export function emailConfigured() {
  return !!process.env.RESEND_API_KEY;
}

export async function sendEmail(to: string, subject: string, text: string, html: string, category: string): Promise<boolean> {
  const key = process.env.RESEND_API_KEY;
  if (!key) return false;
  const res = await fetch("https://api.resend.com/emails", {
    method: "POST",
    headers: { authorization: `Bearer ${key}`, "content-type": "application/json" },
    body: JSON.stringify({
      from: process.env.SUPPORT_FROM ?? "Better Battle <noreply@aspir3.net>",
      to: [to],
      subject,
      text,
      html,
      tags: [{ name: "category", value: category }],
    }),
    signal: AbortSignal.timeout(10_000),
  }).catch((e) => {
    console.error(`${category} email failed: ${e?.message ?? e}`);
    return null;
  });
  if (res && !res.ok) console.error(`${category} email failed: HTTP ${res.status} ${await res.text().catch(() => "")}`);
  return !!res?.ok;
}
