import { writable } from "svelte/store";

export interface AccountUser {
  id: string;
  name: string;
  email: string;
  emailVerified: boolean;
}

export interface AccountSession {
  user: AccountUser | null;
  pending: boolean;
  error: string | null;
}

export interface AccountStatus {
  googleConfigured: boolean;
  emailCodeEnabled: boolean;
  localEmail: boolean;
  baseURL: string;
}

export const accountSession = writable<AccountSession>({
  user: null,
  pending: true,
  error: null,
});

class AccountError extends Error {
  constructor(
    message: string,
    readonly status: number,
  ) {
    super(message);
  }
}

async function request<T>(
  path: string,
  body?: Record<string, unknown>,
): Promise<T> {
  const controller = new AbortController();
  const timeout = window.setTimeout(() => controller.abort(), 15_000);
  try {
    const response = await fetch(path, {
      method: body === undefined ? "GET" : "POST",
      credentials: "same-origin",
      cache: "no-store",
      headers:
        body === undefined ? undefined : { "Content-Type": "application/json" },
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: controller.signal,
    });
    const data: unknown = await response.json().catch(() => null);
    if (!response.ok) {
      const message =
        data &&
        typeof data === "object" &&
        "message" in data &&
        typeof data.message === "string"
          ? data.message
          : "The account service could not complete this request.";
      throw new AccountError(
        response.status === 429
          ? "Too many attempts. Wait one minute before trying again."
          : message,
        response.status,
      );
    }
    if (!response.headers.get("content-type")?.includes("application/json")) {
      throw new AccountError(
        "The account service is unavailable. Your local studio is still available.",
        503,
      );
    }
    return data as T;
  } catch (error) {
    if (error instanceof AccountError) throw error;
    throw new AccountError(
      "Cannot reach the account service. Try again, or continue without an account.",
      503,
    );
  } finally {
    window.clearTimeout(timeout);
  }
}

export function accountError(error: unknown): string {
  return error instanceof Error
    ? error.message
    : "The request could not complete. Please try again.";
}

let sessionRequest: Promise<AccountUser | null> | undefined;
export function refreshSession(): Promise<AccountUser | null> {
  if (sessionRequest) return sessionRequest;
  accountSession.update((value) => ({ ...value, pending: true, error: null }));
  sessionRequest = (async () => {
    try {
      const data = await request<{ user?: AccountUser } | null>(
        "/api/auth/get-session",
      );
      if (
        data !== null &&
        (!data.user ||
          typeof data.user.id !== "string" ||
          typeof data.user.email !== "string")
      ) {
        throw new AccountError(
          "The account service returned an invalid session. Please try again.",
          503,
        );
      }
      const user = data?.user ?? null;
      accountSession.set({ user, pending: false, error: null });
      return user;
    } catch (error) {
      accountSession.update((value) => ({
        ...value,
        pending: false,
        error: accountError(error),
      }));
      throw error;
    } finally {
      sessionRequest = undefined;
    }
  })();
  return sessionRequest;
}

export async function loadAccountStatus(): Promise<AccountStatus> {
  const status = await request<AccountStatus>("/api/account/status");
  if (
    !status ||
    typeof status.googleConfigured !== "boolean" ||
    typeof status.emailCodeEnabled !== "boolean" ||
    typeof status.localEmail !== "boolean" ||
    typeof status.baseURL !== "string"
  ) {
    throw new AccountError(
      "Account configuration is unavailable. Try again later.",
      503,
    );
  }
  try {
    const origin = new URL(status.baseURL);
    if (origin.protocol !== "http:" && origin.protocol !== "https:")
      throw new Error();
  } catch {
    throw new AccountError(
      "Account configuration is unavailable. Try again later.",
      503,
    );
  }
  return status;
}

async function signedIn(
  path: string,
  body: Record<string, unknown>,
): Promise<void> {
  await request(path, body);
  // Settle any focus-triggered read started before this mutation, then fetch fresh.
  if (sessionRequest) await sessionRequest.catch(() => null);
  // Read the HttpOnly-cookie session back from the server, never assume a form succeeded.
  const user = await refreshSession();
  if (!user)
    throw new AccountError(
      "No session was created. Please sign in again.",
      503,
    );
}

export function signIn(email: string, password: string): Promise<void> {
  return signedIn("/api/auth/sign-in/email", {
    email: email.trim().toLowerCase(),
    password,
  });
}

export function createAccount(
  name: string,
  email: string,
  password: string,
): Promise<void> {
  return signedIn("/api/auth/sign-up/email", {
    name: name.trim(),
    email: email.trim().toLowerCase(),
    password,
    callbackURL: new URL("/?auth_notice=verified", window.location.origin).href,
  });
}

export async function requestEmailCode(email: string): Promise<void> {
  await request("/api/auth/email-otp/send-verification-otp", {
    email: email.trim().toLowerCase(),
    type: "sign-in",
  });
}

export function signInWithCode(email: string, otp: string): Promise<void> {
  return signedIn("/api/auth/sign-in/email-otp", {
    email: email.trim().toLowerCase(),
    otp: otp.trim(),
  });
}

export async function signOut(): Promise<void> {
  await request("/api/auth/sign-out", {});
  if (sessionRequest) await sessionRequest.catch(() => null);
  accountSession.set({ user: null, pending: false, error: null });
}

/** Only local destinations are allowed, including this app's hash routes. */
export function safeAccountDestination(
  value: string | null | undefined,
): string {
  if (!value || /[\\\u0000-\u001f\u007f]/.test(value)) return "/#/studio";
  const path = value.startsWith("#/") ? `/${value}` : value;
  if (!path.startsWith("/") || path.startsWith("//")) return "/#/studio";
  try {
    const destination = new URL(path, window.location.origin);
    if (destination.origin !== window.location.origin) return "/#/studio";
    return destination.pathname + destination.search + destination.hash;
  } catch {
    return "/#/studio";
  }
}

export async function requestPasswordReset(email: string): Promise<void> {
  const destination = new URL("/?auth_action=reset", window.location.origin);
  destination.searchParams.set("next", accountDestination());
  await request("/api/auth/request-password-reset", {
    email: email.trim().toLowerCase(),
    redirectTo: destination.href,
  });
}

export async function resetPassword(
  token: string,
  newPassword: string,
): Promise<void> {
  await request("/api/auth/reset-password", { token, newPassword });
}

export function accountDestination(): string {
  const next = new URLSearchParams(window.location.search).get("next");
  return safeAccountDestination(
    next ??
      (window.location.hash.startsWith("#/studio") ||
      window.location.hash.startsWith("#/professionals")
        ? window.location.hash
        : null),
  );
}

export async function signInWithGoogle(next: string): Promise<void> {
  const destination = safeAccountDestination(next);
  const failure = new URL("/", window.location.origin);
  failure.searchParams.set("auth_error", "google");
  failure.searchParams.set("next", destination);
  const result = await request<{ url?: string }>("/api/auth/sign-in/social", {
    provider: "google",
    callbackURL: new URL(destination, window.location.origin).href,
    errorCallbackURL: failure.href,
    disableRedirect: true,
  });
  if (
    !result?.url ||
    new URL(result.url).origin !== "https://accounts.google.com"
  ) {
    throw new AccountError(
      "Google sign-in could not start. Choose another method or try again.",
      503,
    );
  }
  window.location.assign(result.url);
}
