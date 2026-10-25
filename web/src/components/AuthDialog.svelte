<script lang="ts">
  import { onMount, tick } from "svelte";
  import Dialog from "../vendor/bracket/components/Dialog.svelte";
  import Button from "../vendor/bracket/components/Button.svelte";
  import {
    accountSession,
    accountError,
    accountDestination,
    loadAccountStatus,
    refreshSession,
    signIn,
    createAccount,
    requestEmailCode,
    signInWithCode,
    signInWithGoogle,
    signOut,
    requestPasswordReset,
    resetPassword,
    type AccountStatus,
  } from "../auth";

  let {
    open = $bindable(false),
    onguest,
  }: { open?: boolean; onguest: () => void } = $props();
  type Mode =
    "sign-in" | "sign-up" | "code" | "reset-request" | "reset-password";
  let mode = $state<Mode>("sign-in");
  let name = $state("");
  let email = $state("");
  let password = $state("");
  let code = $state("");
  let codeEmail = $state("");
  let token = $state("");
  let busy = $state("");
  let error = $state("");
  let notice = $state("");
  let status = $state<AccountStatus | null>(null);
  let statusError = $state("");
  let statusPending = $state(false);
  let errorElement = $state<HTMLParagraphElement>();
  const id = $props.id();
  const sessionError = $derived(statusError || $accountSession.error);
  const originMatches = $derived(
    status ? new URL(status.baseURL).origin === window.location.origin : false,
  );
  const title = $derived(
    mode === "reset-password"
      ? "Choose a new password"
      : mode === "reset-request"
        ? "Reset your password"
        : $accountSession.user
          ? "Your account"
          : mode === "sign-up"
            ? "Create your account"
            : "Sign in to Determine",
  );

  async function loadAvailability() {
    statusPending = true;
    statusError = "";
    try {
      status = await loadAccountStatus();
    } catch (failure) {
      statusError = accountError(failure);
      status = null;
    } finally {
      statusPending = false;
    }
  }

  $effect(() => {
    if (open) {
      void loadAvailability();
      void refreshSession().catch(() => {});
    } else {
      password = "";
      code = "";
    }
  });

  onMount(() => {
    const url = new URL(window.location.href);
    if (url.searchParams.get("auth_action") === "reset") {
      token = url.searchParams.get("token") ?? "";
      mode = token ? "reset-password" : "reset-request";
      if (!token)
        error =
          "This password reset link is invalid or expired. Request a new link.";
    } else if (
      url.searchParams.has("auth_error") ||
      url.searchParams.has("error")
    ) {
      error =
        url.searchParams.get("auth_notice") === "verified"
          ? "This email verification link is invalid or expired. Sign in with an email code to verify your address."
          : "Google sign-in was cancelled or could not complete. Try again or use another method. Existing accounts need a verified email matching Google.";
    } else if (url.searchParams.get("auth_notice") === "verified") {
      notice =
        "Email verified. Set a new password or use an email code to sign in.";
    }
    for (const key of [
      "auth_action",
      "token",
      "auth_error",
      "error",
      "auth_notice",
    ])
      url.searchParams.delete(key);
    window.history.replaceState(
      window.history.state,
      "",
      url.pathname + url.search + url.hash,
    );
  });

  function changeMode(next: Mode) {
    mode = next;
    password = "";
    code = "";
    codeEmail = "";
    error = "";
    notice = "";
  }

  async function perform(action: string, operation: () => Promise<void>) {
    if (busy) return;
    busy = action;
    error = "";
    notice = "";
    try {
      await operation();
    } catch (failure) {
      error = accountError(failure);
      await tick();
      errorElement?.focus();
    } finally {
      busy = "";
      if (open && mode === "code" && codeEmail && !error) {
        await tick();
        document.getElementById(`${id}-code`)?.focus();
      }
    }
  }

  function continueToDestination() {
    const next = accountDestination();
    open = false;
    window.location.assign(next);
  }

  async function submit(event: SubmitEvent) {
    event.preventDefault();
    await perform("submit", async () => {
      if (mode === "reset-request") {
        await requestPasswordReset(email);
        notice = status?.localEmail
          ? "Request accepted. If this address has an account, its reset link is in the account server console. No email was sent."
          : "Request accepted. If this address has an account, check its inbox for a password reset link.";
      } else if (mode === "reset-password") {
        await resetPassword(token, password);
        token = "";
        changeMode("sign-in");
        notice = "Password updated. Sign in with your new password.";
        await refreshSession();
      } else if (mode === "code" && !codeEmail) {
        await sendCode();
      } else {
        if (mode === "sign-up") {
          if (!name.trim()) throw new Error("Enter your name.");
          await createAccount(name, email, password);
        } else if (mode === "code") await signInWithCode(codeEmail, code);
        else await signIn(email, password);
        password = "";
        code = "";
        if (open) continueToDestination();
      }
    });
  }

  async function sendCode() {
    await requestEmailCode(email);
    codeEmail = email.trim().toLowerCase();
    code = "";
    notice = status?.localEmail
      ? "Request accepted. If this address has an account, its code is in the account server console. No email was sent."
      : "Request accepted. If this address has an account, check its inbox for the code.";
  }
</script>

<Dialog
  bind:open
  {title}
  size="sm"
  closeOnOutside={!busy}
  closeOnEscape={!busy}
>
  <div class="auth-content">
    {#if error}<p
        class="auth-message error"
        role="alert"
        tabindex="-1"
        bind:this={errorElement}
      >
        {error}
      </p>{/if}
    {#if notice}<p class="auth-message" role="status">{notice}</p>{/if}
    {#if sessionError}
      <div class="service-notice" role="status">
        <p>{sessionError}</p>
        <button
          type="button"
          class="text-button"
          disabled={!!busy || statusPending || $accountSession.pending}
          onclick={() => {
            void loadAvailability();
            void refreshSession().catch(() => {});
          }}>Try account service again</button
        >
      </div>
    {/if}

    {#if $accountSession.user && mode !== "reset-request" && mode !== "reset-password"}
      <div class="account-summary">
        <span class="account-avatar" aria-hidden="true"
          >{($accountSession.user.name || $accountSession.user.email)
            .slice(0, 1)
            .toUpperCase()}</span
        >
        <h3>Welcome, {$accountSession.user.name || "you"}.</h3>
        <p class="account-email">{$accountSession.user.email}</p>
        <p class="hint">
          {$accountSession.user.emailVerified
            ? "Email verified"
            : "Email not yet verified. Use your verification email or an email code, then set a new password."}
        </p>
      </div>
      <div class="account-actions">
        <Button
          full
          variant="solid"
          intent="primary"
          disabled={!!busy}
          onclick={continueToDestination}>Open studio</Button
        >
        <Button
          full
          disabled={!!busy}
          loading={busy === "sign-out"}
          onclick={() =>
            perform("sign-out", async () => {
              await signOut();
              changeMode("sign-in");
              notice = "You’re signed out.";
            })}>Sign out</Button
        >
        <button
          type="button"
          class="text-button"
          disabled={!!busy || !status?.emailCodeEnabled}
          onclick={() => {
            email = $accountSession.user?.email ?? "";
            changeMode("reset-request");
          }}>Set or reset password</button
        >
      </div>
      <p class="privacy-note">
        This account does not sync engineering settings. Local simulations and
        hardware sessions stay available without signing in.
      </p>
    {:else}
      {#if mode === "sign-in" || mode === "sign-up" || mode === "code"}
        <p class="auth-intro">A simple way back to Determine.</p>
        <div class="auth-mode" role="group" aria-label="Account mode">
          <button
            type="button"
            aria-pressed={mode !== "sign-up"}
            disabled={!!busy}
            onclick={() => changeMode("sign-in")}>Sign in</button
          >
          <button
            type="button"
            aria-pressed={mode === "sign-up"}
            disabled={!!busy}
            onclick={() => changeMode("sign-up")}>Create account</button
          >
        </div>
        {#if mode !== "sign-up"}
          <div class="auth-method" role="group" aria-label="Sign-in method">
            <button
              type="button"
              aria-pressed={mode === "sign-in"}
              disabled={!!busy}
              onclick={() => changeMode("sign-in")}>Password</button
            >
            <button
              type="button"
              aria-pressed={mode === "code"}
              disabled={!!busy || !status?.emailCodeEnabled}
              onclick={() => changeMode("code")}>Email code</button
            >
          </div>
          {#if status && !status.emailCodeEnabled}<p class="hint">
              Email codes and password reset are unavailable: email delivery is
              not configured.
            </p>{/if}
        {/if}
      {:else}
        <p class="auth-intro">
          {mode === "reset-password"
            ? "Use at least 10 characters for your new password."
            : "We’ll send a one-use link for your existing account."}
        </p>
      {/if}

      <form onsubmit={submit} aria-busy={!!busy}>
        <fieldset disabled={!!busy || $accountSession.pending}>
          {#if mode === "sign-up"}
            <label for={`${id}-name`}>Name</label>
            <input
              id={`${id}-name`}
              name="name"
              autocomplete="name"
              required
              maxlength="100"
              bind:value={name}
            />
          {/if}
          {#if mode !== "reset-password"}
            <label for={`${id}-email`}>Email address</label>
            <input
              id={`${id}-email`}
              name="email"
              type="email"
              autocomplete="email"
              required
              maxlength="254"
              readonly={mode === "code" && !!codeEmail}
              bind:value={email}
              aria-describedby={mode === "code" ? `${id}-code-help` : undefined}
            />
          {/if}
          {#if mode === "sign-in" || mode === "sign-up" || mode === "reset-password"}
            <label for={`${id}-password`}
              >{mode === "reset-password" ? "New password" : "Password"}</label
            >
            <input
              id={`${id}-password`}
              name="password"
              type="password"
              autocomplete={mode === "sign-in"
                ? "current-password"
                : "new-password"}
              required
              minlength={mode === "sign-in" ? undefined : 10}
              maxlength="128"
              bind:value={password}
              aria-describedby={mode !== "sign-in"
                ? `${id}-password-help`
                : undefined}
            />
            {#if mode !== "sign-in"}<p class="hint" id={`${id}-password-help`}>
                At least 10 characters.
              </p>{/if}
          {/if}
          {#if mode === "code"}
            <p class="hint" id={`${id}-code-help`}>
              Existing accounts only. Codes expire in 5 minutes; 3 guesses, up
              to 3 requests per minute. Only the newest code works.
            </p>
            {#if status?.localEmail}<p class="delivery-note">
                Local delivery: no email is sent. Read the code in the account
                server console.
              </p>{/if}
            {#if codeEmail}
              <label for={`${id}-code`}>Six-digit code</label>
              <input
                id={`${id}-code`}
                name="code"
                type="text"
                inputmode="numeric"
                autocomplete="one-time-code"
                pattern={"[0-9]{6}"}
                minlength="6"
                maxlength="6"
                required
                bind:value={code}
              />
            {/if}
            <details>
              <summary>Account safety</summary>
              <p class="hint">
                First-time email verification removes any password and sessions
                created before ownership was proven. Use “Forgot password?” to
                set a new password afterward.
              </p>
            </details>
          {/if}
          {#if mode === "reset-request" && status?.localEmail}<p
              class="delivery-note"
            >
              Local delivery: no email is sent. Read the reset link in the
              account server console.
            </p>{/if}
          <Button
            type="submit"
            full
            variant="solid"
            intent="primary"
            loading={busy === "submit"}
            disabled={!!busy ||
              ((mode === "code" || mode === "reset-request") &&
                !status?.emailCodeEnabled)}
            >{mode === "sign-up"
              ? "Create account"
              : mode === "code"
                ? codeEmail
                  ? "Sign in with code"
                  : "Email me a code"
                : mode === "reset-request"
                  ? "Send reset link"
                  : mode === "reset-password"
                    ? "Update password"
                    : "Sign in"}</Button
          >
        </fieldset>
      </form>
      {#if mode === "code" && codeEmail}
        <div class="code-actions">
          <button
            type="button"
            class="text-button"
            disabled={!!busy || !status?.emailCodeEnabled}
            onclick={() => perform("resend", sendCode)}
            >{busy === "resend" ? "Sending…" : "Send a new code"}</button
          >
          <button
            type="button"
            class="text-button"
            disabled={!!busy}
            onclick={() => {
              codeEmail = "";
              code = "";
              notice = "";
              error = "";
            }}>Change email</button
          >
        </div>
      {/if}
      {#if mode === "sign-in"}
        <button
          type="button"
          class="text-button recovery-link"
          disabled={!!busy || !status?.emailCodeEnabled}
          onclick={() => changeMode("reset-request")}>Forgot password?</button
        >
      {/if}
      {#if mode === "reset-request" || mode === "reset-password"}
        <button
          type="button"
          class="text-button recovery-link"
          disabled={!!busy}
          onclick={() => changeMode("sign-in")}>Back to sign in</button
        >
      {:else}
        <div class="auth-divider"><span>or</span></div>
        {#if status?.googleConfigured && originMatches}
          <Button
            full
            disabled={!!busy}
            loading={busy === "google"}
            onclick={() =>
              perform("google", () => signInWithGoogle(accountDestination()))}
            ><span class="google-mark" aria-hidden="true">G</span>Continue with
            Google</Button
          >
        {:else if status?.googleConfigured}
          <Button full href={new URL(accountDestination(), status.baseURL).href}
            >Continue at account address <span aria-hidden="true">↗</span
            ></Button
          >
        {:else if status}
          <Button
            full
            href="#/professionals/google"
            onclick={() => (open = false)}
            ><span class="google-mark" aria-hidden="true">G</span>Set up Google
            sign-in <span aria-hidden="true">↗</span></Button
          >
        {:else}
          <Button
            full
            disabled={!!busy || statusPending}
            onclick={loadAvailability}
            >{statusPending
              ? "Checking Google…"
              : "Retry Google availability"}</Button
          >
        {/if}
        <p class="provider-note">
          {statusPending
            ? "Checking Google availability…"
            : !status
              ? "Google availability could not be checked."
              : !status.googleConfigured
                ? "A Google OAuth client is required. Setup instructions include the exact callback address."
                : !originMatches
                  ? "Google sign-in is unavailable at this address. Use the configured account address."
                  : "Existing accounts need a verified email matching Google."}
        </p>
        {#if mode === "sign-up" && status?.localEmail}<p class="hint">
            After signup, your email verification link is printed in the account
            server console.
          </p>{/if}
      {/if}
      <div class="guest-action">
        <Button
          full
          variant="ghost"
          disabled={!!busy}
          onclick={() => {
            open = false;
            onguest();
          }}
          >Explore without an account <span aria-hidden="true">↗</span></Button
        >
        <p class="privacy-note">
          No account needed for local simulation or hardware sessions.
        </p>
      </div>
    {/if}
  </div>
</Dialog>

<style>
  .auth-content {
    padding: 4px 12px 12px;
    color: var(--ink);
    font-size: 13px;
  }
  .auth-intro {
    color: var(--muted);
    margin: 0 0 18px;
    font-size: 14px;
  }
  .auth-mode {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 3px;
    padding: 3px;
    background: var(--paper);
    border: 1px solid var(--line);
    border-radius: 10px;
    margin-bottom: 18px;
  }
  .auth-mode button {
    border: 0;
    background: transparent;
    color: var(--muted);
    padding: 9px 5px;
    font: inherit;
    border-radius: 7px;
    cursor: pointer;
  }
  .auth-mode button[aria-pressed="true"] {
    background: var(--surface);
    color: var(--ink);
    box-shadow: 0 1px 4px #00000010;
  }
  .auth-method {
    display: flex;
    gap: 18px;
    margin: 0 0 16px;
  }
  .auth-method button {
    border: 0;
    border-bottom: 2px solid transparent;
    padding: 0 0 5px;
    background: none;
    color: var(--muted);
    font: inherit;
    cursor: pointer;
  }
  .auth-method button[aria-pressed="true"] {
    color: var(--ink);
    border-color: var(--blue);
  }
  fieldset {
    min-width: 0;
    border: 0;
    padding: 0;
    margin: 0;
    display: flex;
    flex-direction: column;
    gap: 8px;
  }
  label {
    margin-top: 7px;
    font-weight: 500;
  }
  input {
    box-sizing: border-box;
    width: 100%;
    min-width: 0;
    border: 1px solid var(--line);
    border-radius: 9px;
    padding: 11px 12px;
    background: var(--surface);
    color: var(--ink);
    font: inherit;
    font-size: 16px;
    margin-bottom: 4px;
  }
  input[readonly] {
    color: var(--muted);
    background: var(--paper);
  }
  input:focus-visible,
  button:focus-visible,
  summary:focus-visible {
    outline: 2px solid var(--blue);
    outline-offset: 3px;
  }
  button:disabled {
    cursor: not-allowed;
    opacity: 0.5;
  }
  fieldset:disabled input {
    opacity: 0.65;
  }
  fieldset :global(.br-btn) {
    margin-top: 10px;
  }
  .auth-content :global(.br-btn) {
    border-radius: 10px;
    min-height: 42px;
    font-family: inherit;
    font-size: 13px;
    letter-spacing: 0;
    text-transform: none;
  }
  .hint,
  .provider-note,
  .privacy-note {
    color: var(--muted);
    font-size: 11px;
    line-height: 1.55;
    margin: 2px 0 8px;
  }
  .delivery-note {
    padding: 10px 12px;
    background: var(--paper);
    border-radius: 8px;
    font-size: 12px;
    line-height: 1.5;
    margin: 2px 0 6px;
  }
  .text-button {
    border: 0;
    background: none;
    color: var(--blue);
    font: inherit;
    font-size: 12px;
    padding: 7px 0;
    cursor: pointer;
  }
  .recovery-link {
    display: block;
    margin: 5px auto 0;
  }
  .code-actions {
    display: flex;
    justify-content: space-between;
    gap: 12px;
    margin-top: 5px;
  }
  details {
    margin: 2px 0;
  }
  summary {
    cursor: pointer;
    color: var(--muted);
    font-size: 11px;
    padding: 3px 0;
  }
  details p {
    margin-top: 7px;
  }
  .auth-divider {
    display: flex;
    align-items: center;
    gap: 12px;
    color: var(--muted);
    font-size: 11px;
    margin: 17px 0;
  }
  .auth-divider::before,
  .auth-divider::after {
    content: "";
    flex: 1;
    height: 1px;
    background: var(--line);
  }
  .google-mark {
    font-family: Arial, sans-serif;
    font-size: 18px;
    font-weight: 700;
    line-height: 1;
  }
  .provider-note {
    text-align: center;
    margin: 8px 0 0;
  }
  .guest-action {
    border-top: 1px solid var(--line);
    margin-top: 18px;
    padding-top: 10px;
  }
  .privacy-note {
    text-align: center;
    margin: 7px 0 0;
  }
  .auth-message {
    border: 1px solid var(--line);
    border-radius: 9px;
    background: var(--paper);
    padding: 11px 12px;
    font-size: 12px;
    line-height: 1.5;
    margin: 0 0 14px;
    overflow-wrap: anywhere;
  }
  .auth-message.error {
    color: var(--red);
    border-color: color-mix(in srgb, var(--red) 35%, var(--line));
  }
  .service-notice {
    background: var(--paper);
    padding: 10px 12px;
    border-radius: 9px;
    margin-bottom: 14px;
    font-size: 12px;
  }
  .service-notice p {
    margin: 0;
  }
  .account-summary {
    text-align: center;
    padding: 6px 0 19px;
  }
  .account-avatar {
    display: grid;
    place-items: center;
    width: 52px;
    height: 52px;
    margin: 0 auto 13px;
    border-radius: 50%;
    background: var(--paper);
    color: var(--blue);
    font-size: 23px;
  }
  .account-summary h3 {
    font-size: 22px;
    font-weight: 500;
    letter-spacing: -0.035em;
    margin: 0 0 5px;
    overflow-wrap: anywhere;
  }
  .account-email {
    margin: 0 0 9px;
    color: var(--muted);
    overflow-wrap: anywhere;
  }
  .account-actions {
    display: grid;
    gap: 9px;
  }
  @media (max-width: 360px) {
    .auth-content {
      padding: 2px 2px 8px;
    }
  }
</style>
