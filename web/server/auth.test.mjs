import assert from "node:assert/strict";
import { randomBytes } from "node:crypto";
import { chmod, mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { createAccountService } from "./auth.mjs";

const origin = "http://127.0.0.1:5175";
const password = "Original-test-password-42!";
const newPassword = "Replacement-test-password-84!";

// Tests in this file intentionally run serially: local delivery uses console.info.
// Every case owns a private database, secret, mailbox, and rate-limit state.
async function fixture(t, { productionDelivery = false } = {}) {
  const directory = await mkdtemp(join(tmpdir(), "determine-account-test-"));
  const messages = [];
  const originalInfo = console.info;
  let service;
  console.info = (...args) => {
    if (
      typeof args[0] === "string" &&
      args[0].includes("[Determine local email — not sent]")
    ) {
      messages.push(args[0]);
      return;
    }
    originalInfo(...args);
  };
  t.after(async () => {
    try {
      service?.close();
    } finally {
      console.info = originalInfo;
      messages.length = 0;
      await rm(directory, { recursive: true, force: true });
    }
  });
  await chmod(directory, 0o700);
  const config = {
    production: productionDelivery,
    secret: randomBytes(48).toString("base64url"),
    dataDirectory: directory,
    baseURL: productionDelivery ? "https://accounts.example.test" : origin,
    trustedOrigins: [
      productionDelivery ? "https://accounts.example.test" : origin,
    ],
    googleConfigured: false,
    emailCodeEnabled: true,
    localEmail: !productionDelivery,
    email: {
      production: productionDelivery,
      enabled: true,
      allowlist: ["allowed@example.test"],
    },
  };
  service = await createAccountService(config);

  async function request(
    path,
    { body, cookie, ip = "127.0.0.1", navigation = false } = {},
  ) {
    const headers = new Headers({
      origin: config.baseURL,
      "x-determine-client-ip": ip,
    });
    if (navigation) {
      headers.delete("origin");
      headers.set("sec-fetch-site", "cross-site");
      headers.set("sec-fetch-mode", "navigate");
      headers.set("sec-fetch-dest", "document");
    }
    if (body !== undefined) headers.set("content-type", "application/json");
    if (cookie) headers.set("cookie", cookie);
    const response = await service.auth.handler(
      new Request(new URL(path, `${config.baseURL}/api/auth/`), {
        method: body === undefined ? "GET" : "POST",
        headers,
        ...(body === undefined ? {} : { body: JSON.stringify(body) }),
      }),
    );
    const text = await response.text();
    const data =
      response.headers.get("content-type")?.includes("application/json") && text
        ? JSON.parse(text)
        : null;
    return { response, data };
  }

  function takeMessage(email, subject) {
    const index = messages.findIndex(
      (message) =>
        message.includes(`To: ${email}\n`) &&
        message.includes(`Subject: ${subject}\n`),
    );
    assert.ok(
      index >= 0,
      "The real local delivery must produce the expected email",
    );
    return messages.splice(index, 1)[0];
  }

  function takeLink(email, subject) {
    const match = takeMessage(email, subject).match(/https?:\/\/[^\s]+/);
    assert.ok(Boolean(match), "The delivered email must contain a usable link");
    return new URL(match[0]);
  }

  async function signUp(email) {
    const result = await request("sign-up/email", {
      body: { email, password, name: "Account test" },
    });
    assert.equal(result.response.status, 200, "Password signup must succeed");
    return result;
  }

  async function sendCode(email, options = {}) {
    const result = await request("email-otp/send-verification-otp", {
      ...options,
      body: { email, type: "sign-in" },
    });
    assert.equal(
      result.response.status,
      200,
      "Requesting a sign-in code must succeed",
    );
    const match = takeMessage(email, "Your Determine sign-in code").match(
      /sign-in code is: (\d{6})\b/,
    );
    assert.ok(
      Boolean(match),
      "Local delivery must contain a six-digit sign-in code",
    );
    return match[1];
  }

  return {
    request,
    signUp,
    sendCode,
    takeMessage,
    takeLink,
    async restart() {
      service.close();
      service = undefined;
      service = await createAccountService(config);
    },
  };
}

function sessionCookie(result) {
  const cookies = result.response.headers
    .getSetCookie()
    .map((value) => value.split(";", 1)[0])
    .filter((value) => value.startsWith("better-auth.session_token="));
  assert.equal(cookies.length, 1, "Authentication must issue a session cookie");
  assert.ok(
    cookies[0].length > "better-auth.session_token=".length,
    "The session cookie must not be empty",
  );
  return cookies[0];
}

function rejected(result, statuses = [400, 401, 403]) {
  assert.ok(
    statuses.includes(result.response.status),
    "The request must fail at the authentication boundary, not by rate limit or server error",
  );
  assert.equal(
    result.response.headers
      .getSetCookie()
      .some((value) => /^better-auth\.session_token=[^;]/.test(value)),
    false,
    "Rejected authentication must not issue a session",
  );
}

async function assertSession(f, cookie, userId) {
  const result = await f.request("get-session", { cookie });
  assert.equal(result.response.status, 200);
  assert.equal(
    result.data?.user?.id,
    userId,
    "The cookie must resolve to the expected account",
  );
  return result.data;
}

async function assertRevoked(f, cookie) {
  const result = await f.request("get-session", { cookie });
  assert.equal(result.response.status, 200);
  assert.equal(
    result.data === null,
    true,
    "A revoked cookie must not recover an account session",
  );
}

function wrongCode(code) {
  // A guaranteed mismatch without assuming that a randomly generated OTP is nonzero.
  return `${code[0] === "0" ? "1" : "0"}${code.slice(1)}`;
}

test("password signup and login persist across service restart; sign-out revokes only its session", async (t) => {
  const f = await fixture(t);
  const email = "password@example.test";
  const signup = await f.signUp(email);
  const signupCookie = sessionCookie(signup);
  assert.equal(signup.data.user.emailVerified, false);
  const invalid = await f.request("sign-in/email", {
    body: { email, password: "Not-the-account-password-42!" },
  });
  rejected(invalid, [401]);
  const login = await f.request("sign-in/email", { body: { email, password } });
  assert.equal(login.response.status, 200);
  const loginCookie = sessionCookie(login);
  await assertSession(f, loginCookie, signup.data.user.id);
  await f.restart();
  await assertSession(f, loginCookie, signup.data.user.id);
  await assertSession(f, signupCookie, signup.data.user.id);
  const signout = await f.request("sign-out", {
    cookie: loginCookie,
    body: {},
  });
  assert.equal(signout.response.status, 200);
  await assertRevoked(f, loginCookie);
  await assertSession(f, signupCookie, signup.data.user.id);
});

test("a cross-site verification-link navigation claims an unverified account and revokes its old password and sessions", async (t) => {
  const f = await fixture(t);
  const email = "verified@example.test";
  const signup = await f.signUp(email);
  const signupCookie = sessionCookie(signup);
  const oldLogin = await f.request("sign-in/email", {
    body: { email, password },
  });
  assert.equal(oldLogin.response.status, 200);
  const oldCookie = sessionCookie(oldLogin);
  const link = f.takeLink(email, "Verify your Determine email");
  const verified = await f.request(link.href, { navigation: true });
  assert.equal(verified.response.status, 302);
  const verifiedCookie = sessionCookie(verified);
  const session = await assertSession(f, verifiedCookie, signup.data.user.id);
  assert.equal(session.user.emailVerified, true);
  await assertRevoked(f, signupCookie);
  await assertRevoked(f, oldCookie);
  rejected(
    await f.request("sign-in/email", { body: { email, password } }),
    [401],
  );
  await assertSession(f, verifiedCookie, signup.data.user.id);
});

test("OTP sign-in preserves passwords and sessions established after email ownership was proven", async (t) => {
  const f = await fixture(t);
  const email = "proven-password@example.test";
  const signup = await f.signUp(email);
  const otp = await f.sendCode(email);
  const owner = await f.request("sign-in/email-otp", { body: { email, otp } });
  assert.equal(owner.response.status, 200);
  const requested = await f.request("request-password-reset", {
    body: { email, redirectTo: `${origin}/` },
  });
  assert.equal(requested.response.status, 200);
  const resetLink = f.takeLink(email, "Reset your Determine password");
  const token = resetLink.pathname.split("/").at(-1);
  const reset = await f.request("reset-password", {
    body: { token, newPassword },
  });
  assert.equal(reset.response.status, 200);
  const passwordLogin = await f.request("sign-in/email", {
    body: { email, password: newPassword },
  });
  assert.equal(passwordLogin.response.status, 200);
  const provenCookie = sessionCookie(passwordLogin);
  const nextOtp = await f.sendCode(email);
  const nextLogin = await f.request("sign-in/email-otp", {
    body: { email, otp: nextOtp },
  });
  assert.equal(nextLogin.response.status, 200);
  await assertSession(f, provenCookie, signup.data.user.id);
  const laterPasswordLogin = await f.request("sign-in/email", {
    body: { email, password: newPassword },
  });
  assert.equal(
    laterPasswordLogin.response.status,
    200,
    "OTP must preserve a password established after ownership proof",
  );
});

test("rotating an email code rejects the old challenge, preserves the newest after resend throttling, and consumes it once", async (t) => {
  const f = await fixture(t);
  const email = "rotation@example.test";
  const signup = await f.signUp(email);
  const firstCode = await f.sendCode(email);
  const secondCode = await f.sendCode(email);
  const currentCode = await f.sendCode(email);
  // Random six-digit values can collide. Only distinct numeric values can
  // distinguish an old challenge from the newest; never fail on an RNG collision.
  const oldCode = [firstCode, secondCode].find((code) => code !== currentCode);
  const throttled = await f.request("email-otp/send-verification-otp", {
    body: { email, type: "sign-in" },
  });
  assert.equal(
    throttled.response.status,
    429,
    "A fourth delivery in the same minute must be throttled",
  );
  if (oldCode !== undefined) {
    rejected(
      await f.request("sign-in/email-otp", { body: { email, otp: oldCode } }),
      [400],
    );
  }
  const login = await f.request("sign-in/email-otp", {
    body: { email, otp: currentCode },
  });
  assert.equal(
    login.response.status,
    200,
    "A rejected resend must not invalidate the last delivered code",
  );
  await assertSession(f, sessionCookie(login), signup.data.user.id);
  rejected(
    await f.request("sign-in/email-otp", { body: { email, otp: currentCode } }),
    [400],
  );
});

test("email-code retry exhaustion is account-scoped across client IPs, and a fresh challenge restores access", async (t) => {
  const f = await fixture(t);
  const email = "attempts@example.test";
  const signup = await f.signUp(email);
  const otp = await f.sendCode(email);
  for (let attempt = 1; attempt <= 3; attempt++) {
    rejected(
      await f.request("sign-in/email-otp", {
        body: { email, otp: wrongCode(otp) },
        ip: `198.51.100.${attempt}`,
      }),
      [400],
    );
  }
  const locked = await f.request("sign-in/email-otp", {
    body: { email, otp },
    ip: "198.51.100.4",
  });
  rejected(locked, [403]);
  assert.equal(locked.data?.code, "TOO_MANY_ATTEMPTS");
  const fresh = await f.sendCode(email);
  const login = await f.request("sign-in/email-otp", {
    body: { email, otp: fresh },
    ip: "198.51.100.5",
  });
  assert.equal(login.response.status, 200);
  await assertSession(f, sessionCookie(login), signup.data.user.id);
});

test("OTP ownership of an unverified account revokes its old password and every old session", async (t) => {
  const f = await fixture(t);
  const email = "ownership@example.test";
  const signup = await f.signUp(email);
  const signupCookie = sessionCookie(signup);
  const oldLogin = await f.request("sign-in/email", {
    body: { email, password },
  });
  assert.equal(oldLogin.response.status, 200);
  const oldCookie = sessionCookie(oldLogin);
  const otp = await f.sendCode(email);
  const owner = await f.request("sign-in/email-otp", { body: { email, otp } });
  assert.equal(owner.response.status, 200);
  assert.equal(owner.data.user.id, signup.data.user.id);
  assert.equal(owner.data.user.emailVerified, true);
  const ownerCookie = sessionCookie(owner);
  await assertRevoked(f, signupCookie);
  await assertRevoked(f, oldCookie);
  rejected(
    await f.request("sign-in/email", { body: { email, password } }),
    [401],
  );
  await assertSession(f, ownerCookie, signup.data.user.id);
});

test("a delivered password reset replaces the credential, revokes every old session, and cannot be replayed", async (t) => {
  const f = await fixture(t);
  const email = "reset@example.test";
  const signup = await f.signUp(email);
  const signupCookie = sessionCookie(signup);
  const login = await f.request("sign-in/email", { body: { email, password } });
  assert.equal(login.response.status, 200);
  const loginCookie = sessionCookie(login);
  const requested = await f.request("request-password-reset", {
    body: { email, redirectTo: `${origin}/` },
  });
  assert.equal(requested.response.status, 200);
  const link = f.takeLink(email, "Reset your Determine password");
  const callback = await f.request(link.href, { navigation: true });
  assert.equal(callback.response.status, 302);
  const destination = new URL(callback.response.headers.get("location"));
  assert.equal(destination.origin, origin);
  const token = destination.searchParams.get("token");
  assert.ok(Boolean(token), "The reset link must redirect with a usable token");
  const reset = await f.request("reset-password", {
    body: { token, newPassword },
  });
  assert.equal(reset.response.status, 200);
  await assertRevoked(f, signupCookie);
  await assertRevoked(f, loginCookie);
  rejected(
    await f.request("sign-in/email", { body: { email, password } }),
    [401],
  );
  const replacement = await f.request("sign-in/email", {
    body: { email, password: newPassword },
  });
  assert.equal(replacement.response.status, 200);
  const replacementCookie = sessionCookie(replacement);
  rejected(
    await f.request("reset-password", {
      body: { token, newPassword: password },
    }),
    [400],
  );
  await assertSession(f, replacementCookie, signup.data.user.id);
});

test("an unknown email receives no usable OTP and cannot acquire an account through email-code sign-in", async (t) => {
  const f = await fixture(t);
  const email = "not-registered@example.test";
  const requested = await f.request("email-otp/send-verification-otp", {
    body: { email, type: "sign-in" },
  });
  assert.equal(requested.response.status, 200);
  const notice = f.takeMessage(email, "Sign in to Determine");
  assert.equal(
    /\b\d{6}\b/.test(notice),
    false,
    "An unknown account must not receive a numeric challenge",
  );
  rejected(
    await f.request("sign-in/email-otp", { body: { email, otp: "000000" } }),
    [400],
  );
  const signup = await f.signUp(email);
  assert.equal(
    signup.data.user.emailVerified,
    false,
    "Only explicit signup may create this account",
  );
  await assertSession(f, sessionCookie(signup), signup.data.user.id);
});

test("signup reports verification-mail failure instead of success when production delivery rejects its recipient", async (t) => {
  const f = await fixture(t, { productionDelivery: true });
  // The real production delivery rejects this address before contacting any provider.
  // No API key or sender is configured, and no fetch response is mocked.
  const signup = await f.request("sign-up/email", {
    body: {
      email: "outside-allowlist@example.test",
      password,
      name: "Delivery failure test",
    },
  });
  rejected(signup, [503]);
});
