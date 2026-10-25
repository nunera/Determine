import { createServer } from "node:http";
import { readConfig } from "./config.mjs";

try {
  const config = await readConfig();
  const { createAccountService } = await import("./auth.mjs");
  const { toNodeHandler } = await import("better-auth/node");
  const service = await createAccountService(config);
  const handleAuth = toNodeHandler(service.auth);
  const allowedHosts = new Set(
    config.trustedOrigins.map((origin) => new URL(origin).host),
  );
  const sendJSON = (response, status, data) => {
    response.writeHead(status, {
      "Content-Type": "application/json; charset=utf-8",
      "Cache-Control": "no-store",
      "X-Content-Type-Options": "nosniff",
    });
    response.end(JSON.stringify(data));
  };
  const server = createServer(
    { maxHeaderSize: 16_384, requestTimeout: 30_000 },
    async (request, response) => {
      response.setHeader("Cache-Control", "no-store");
      response.setHeader("X-Content-Type-Options", "nosniff");
      try {
        // Bind loopback and validate the preserved browser Host. Forwarded public
        // hosts/protocols never widen the configured origin allowlist.
        if (!allowedHosts.has(request.headers.host)) {
          return sendJSON(response, 403, {
            message: "Untrusted request host.",
          });
        }
        const origin = request.headers.origin;
        if (origin && !config.trustedOrigins.includes(origin)) {
          return sendJSON(response, 403, {
            message: "Untrusted request origin.",
          });
        }
        if (!["GET", "POST"].includes(request.method)) {
          response.setHeader("Allow", "GET, POST");
          return sendJSON(response, 405, { message: "Method not allowed." });
        }
        if (request.method === "POST" && !origin) {
          return sendJSON(response, 403, {
            message: "An allowed browser Origin is required.",
          });
        }
        const path = new URL(request.url, config.baseURL).pathname;
        // These GET navigations carry their own signed/one-time proof. Better
        // Auth still validates tokens, OAuth state, and callback origins.
        const externalLink =
          request.method === "GET" &&
          (path === "/api/auth/callback/google" ||
            path === "/api/auth/verify-email" ||
            /^\/api\/auth\/reset-password\/[^/]+$/.test(path));
        if (
          request.headers["sec-fetch-site"] === "cross-site" &&
          !externalLink
        ) {
          return sendJSON(response, 403, {
            message: "Cross-site requests are not allowed.",
          });
        }
        // Overwrite instead of trusting client-supplied forwarding/IP headers.
        for (const header of [
          "forwarded",
          "x-forwarded-host",
          "x-forwarded-proto",
          "x-forwarded-port",
          "x-forwarded-for",
          "x-real-ip",
          "x-determine-client-ip",
        ]) {
          delete request.headers[header];
        }
        request.headers["x-determine-client-ip"] =
          request.socket.remoteAddress || "127.0.0.1";
        // The listener is HTTP loopback even behind the configured HTTPS proxy.
        // Its scheme comes solely from explicit BETTER_AUTH_URL, never the client.
        request.headers["x-forwarded-proto"] = new URL(
          config.baseURL,
        ).protocol.slice(0, -1);
        if (path === "/api/account/status") {
          if (request.method !== "GET") {
            response.setHeader("Allow", "GET");
            return sendJSON(response, 405, { message: "Method not allowed." });
          }
          return sendJSON(response, 200, {
            googleConfigured: config.googleConfigured,
            emailCodeEnabled: config.emailCodeEnabled,
            localEmail: config.localEmail,
            baseURL: config.baseURL,
          });
        }
        if (!path.startsWith("/api/auth/"))
          return sendJSON(response, 404, { message: "Not found." });
        await handleAuth(request, response);
      } catch {
        // Never echo request bodies, provider errors, credentials or cookies.
        if (!response.headersSent)
          sendJSON(response, 500, {
            message: "The account request could not be completed.",
          });
        else response.end();
      }
    },
  );
  server.on("error", (error) => {
    console.error(
      `[Determine accounts] Cannot listen on 127.0.0.1:8770 (${error.code || "server error"}).`,
    );
    service.close();
    process.exitCode = 1;
  });
  server.listen(8770, "127.0.0.1", () => {
    console.log(
      `[Determine accounts] Ready at 127.0.0.1:8770; browser origin ${config.baseURL}`,
    );
    console.log(
      `[Determine accounts] Google ${config.googleConfigured ? "configured" : "unavailable (credentials not configured)"}; email ${config.localEmail ? "local server console only — do not share logs" : config.emailCodeEnabled ? "Resend with recipient allowlist" : "unavailable (provider/sender/allowlist not configured)"}.`,
    );
  });
  let closing = false;
  function stop() {
    if (closing) return;
    closing = true;
    server.close(() => service.close());
    server.closeIdleConnections();
  }
  process.on("SIGINT", stop);
  process.on("SIGTERM", stop);
} catch (error) {
  // Startup errors are configuration/schema messages, never raw config values.
  console.error(`[Determine accounts] ${error.message}`);
  process.exitCode = 1;
}
