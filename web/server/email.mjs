import { APIError } from "better-auth/api";

export function createDelivery(config) {
  return async function deliver(to, message) {
    if (!config.production) {
      // Development-only mailbox. Never expose these messages through an API,
      // and never use this mode on a publicly reachable deployment.
      console.info(
        `\n[Determine local email — not sent]\nTo: ${to}\nSubject: ${message.subject}\n${message.text}\n[End local email]\n`,
      );
      return;
    }
    const unavailable = () =>
      new APIError("SERVICE_UNAVAILABLE", {
        message:
          "Email delivery is unavailable. Try again later or use your password.",
      });
    if (!config.enabled || !config.allowlist.includes(to.toLowerCase()))
      throw unavailable();
    try {
      const response = await fetch("https://api.resend.com/emails", {
        method: "POST",
        headers: {
          Authorization: `Bearer ${config.apiKey}`,
          "Content-Type": "application/json",
        },
        body: JSON.stringify({ from: config.from, to, ...message }),
        signal: AbortSignal.timeout(15_000),
      });
      if (!response.ok) throw unavailable();
      const result = await response.json();
      if (typeof result.id !== "string" || !result.id) throw unavailable();
    } catch {
      // Provider responses may contain private metadata: do not log or echo.
      throw unavailable();
    }
  };
}
