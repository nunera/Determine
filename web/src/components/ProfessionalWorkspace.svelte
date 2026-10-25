<script lang="ts">
  import { onMount } from "svelte";
  import { loadAccountStatus, type AccountStatus } from "../auth";
  import Button from "../vendor/bracket/components/Button.svelte";
  import EngineerWorkspace from "./EngineerWorkspace.svelte";
  import type { LiveProfile } from "../../../world/src/contract";
  import { isLiveProfile } from "../../../world/src/studioProtocol";

  let step = $state(0);
  let mode = $state<"sim" | "hitl">("sim");
  let endpoint = $state("http://127.0.0.1:8765");
  let profile = $state<LiveProfile>({
    mode: "HAPTIC_ONLY",
    hapticMax: 96,
    ceilingDb: -20,
  });
  let benchConfirmed = $state(false);
  let setupError = $state("");
  let session = $state<{ endpoint: string; profile: LiveProfile } | null>(null);
  let sessionMode = $state<"sim" | "hitl">("sim");
  let accountStatus = $state<AccountStatus | null>(null);
  let accountStatusError = $state("");
  const googleCallback = $derived(
    accountStatus
      ? new URL("/api/auth/callback/google", accountStatus.baseURL).href
      : null,
  );
  onMount(() => {
    let active = true;
    void loadAccountStatus()
      .then((status) => {
        if (active) accountStatus = status;
      })
      .catch(() => {
        if (active)
          accountStatusError =
            "The account service could not be reached. Start it before configuring the callback.";
      });
    return () => {
      active = false;
    };
  });
  const steps = ["Connection", "Output profile", "Review"];

  function chooseMode(next: "sim" | "hitl") {
    const oldDefault =
      mode === "sim" ? "http://127.0.0.1:8765" : "http://127.0.0.1:8766";
    if (endpoint === oldDefault)
      endpoint =
        next === "sim" ? "http://127.0.0.1:8765" : "http://127.0.0.1:8766";
    mode = next;
    benchConfirmed = false;
  }
  function advance(event: SubmitEvent) {
    event.preventDefault();
    setupError = "";
    try {
      const url = new URL(endpoint.trim());
      if (
        !["http:", "https:"].includes(url.protocol) ||
        url.username ||
        url.password
      )
        throw new Error(
          "Use an HTTP or HTTPS bridge address without embedded credentials.",
        );
      if (location.protocol === "https:" && url.protocol !== "https:")
        throw new Error("An HTTPS workspace needs an HTTPS bridge.");
      endpoint = url.origin;
      if (!isLiveProfile(profile))
        throw new Error("Choose a valid output profile before connecting.");
      if (step < 2) {
        step += 1;
        return;
      }
      if (mode === "hitl" && !benchConfirmed)
        throw new Error(
          "Confirm the supervised bench setup before connecting hardware.",
        );
      sessionMode = mode;
      session = { endpoint, profile: { ...profile } };
    } catch (error) {
      setupError =
        error instanceof Error
          ? error.message
          : "Check your setup before continuing.";
    }
  }
  function downloadSetup() {
    if (!session) return;
    const blob = new Blob(
      [
        JSON.stringify(
          {
            schema: "determine.studio-setup/1",
            mode: sessionMode,
            ...session,
            status: "requested-configuration-not-an-engine-ack",
          },
          null,
          2,
        ),
      ],
      { type: "application/json" },
    );
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = "determine-setup.json";
    link.click();
    window.setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
</script>

<div class="professional-workspace" data-testid="professional-workspace">
  <header class="professional-heading">
    <div>
      <p class="eyebrow">For engineers & doctors</p>
      <h1>A closer connection.</h1>
      <p class="intro">
        Set up a session. Tune the response. See the real engine at work.
      </p>
    </div>
    <a class="text-link" href="#/professionals/api"
      >APIs & compatibility <span aria-hidden="true">↗</span></a
    >
  </header>

  {#if !session}
    <section
      class="onboarding"
      aria-labelledby="setup-title"
      data-testid="professional-onboarding"
    >
      <div class="setup-heading">
        <p class="eyebrow">Session setup</p>
        <span>No account required</span>
      </div>
      <ol class="setup-steps" aria-label="Setup progress">
        {#each steps as label, index}<li>
            <button
              type="button"
              aria-current={step === index ? "step" : undefined}
              disabled={index > step}
              onclick={() => {
                step = index;
                setupError = "";
              }}><span>0{index + 1}</span>{label}</button
            >
          </li>{/each}
      </ol>
      <form onsubmit={advance}>
        {#if step === 0}
          <h2 id="setup-title">Choose your connection.</h2>
          <div class="mode-choices" role="group" aria-label="Session type">
            <button
              type="button"
              aria-pressed={mode === "sim"}
              onclick={() => chooseMode("sim")}
              data-testid="setup-sim"
              ><span class="choice-index">01</span><strong
                >Browser simulation</strong
              ><span>The phone engine, with software boards.</span></button
            >
            <button
              type="button"
              aria-pressed={mode === "hitl"}
              onclick={() => chooseMode("hitl")}
              data-testid="setup-hitl"
              ><span class="choice-index">02</span><strong
                >Hardware in the loop</strong
              ><span>Real boards, through your local relay.</span></button
            >
          </div>
          <label for="setup-endpoint">Bridge address</label>
          <input
            id="setup-endpoint"
            data-testid="setup-endpoint"
            type="url"
            required
            bind:value={endpoint}
            spellcheck="false"
            autocomplete="off"
          />
          <p class="field-note">
            Start <code>npm run live</code> in <code>web/</code>. Connection is
            checked when you launch—not assumed from this address.
          </p>
        {:else if step === 1}
          <h2 id="setup-title">Make the response yours.</h2>
          <div class="profile-fields">
            <div>
              <label for="setup-mode">Output mode</label><select
                id="setup-mode"
                bind:value={profile.mode}
                ><option value="HAPTIC_ONLY">Haptic only</option><option
                  value="STEREO">Stereo phone audio</option
                ><option value="SSD">SSD phone hearing profile</option></select
              >
              <p class="field-note">
                One vibration motor. Phone audio is separate.
              </p>
            </div>
            <div>
              <div class="range-label">
                <label for="setup-haptic">Haptic ceiling</label><output
                  >{profile.hapticMax}<span> / 255</span></output
                >
              </div>
              <input
                id="setup-haptic"
                data-testid="setup-haptic"
                type="range"
                min="1"
                max="255"
                step="1"
                bind:value={profile.hapticMax}
              />
              <p class="field-note">
                A limit, not a calibrated force measurement.
              </p>
            </div>
            <div>
              <div class="range-label">
                <label for="setup-audio">Phone audio ceiling</label><output
                  >{profile.ceilingDb}<span> dBFS</span></output
                >
              </div>
              <input
                id="setup-audio"
                type="range"
                min="-60"
                max="-10"
                step="1"
                bind:value={profile.ceilingDb}
              />
              <p class="field-note">
                Audio cues stay silent in haptic-only mode.
              </p>
            </div>
          </div>
        {:else}
          <h2 id="setup-title">Ready for the real loop.</h2>
          <dl class="setup-review">
            <div>
              <dt>Session</dt>
              <dd>
                {mode === "sim" ? "Browser simulation" : "Hardware in the loop"}
              </dd>
            </div>
            <div>
              <dt>Bridge</dt>
              <dd><code>{endpoint}</code></dd>
            </div>
            <div>
              <dt>Output</dt>
              <dd>{profile.mode.replaceAll("_", " ")}</dd>
            </div>
            <div>
              <dt>Limits</dt>
              <dd>
                Haptic ≤ {profile.hapticMax} / 255 · Audio ≤ {profile.ceilingDb} dBFS
              </dd>
            </div>
          </dl>
          <p class="field-note">
            Your profile is sent to the running phone engine. Only its
            acknowledgement confirms application. Scene AI is optional; it never
            replaces the distance warning.
          </p>
          {#if mode === "hitl"}<label class="bench-check"
              ><input
                type="checkbox"
                required
                bind:checked={benchConfirmed}
              />I’m using a supervised bench setup, not a mobility trial.</label
            >{/if}
        {/if}
        {#if setupError}<p class="setup-error" role="alert">
            {setupError}
          </p>{/if}
        <div class="setup-actions">
          <span>0{step + 1} / 03</span>
          <div>
            {#if step > 0}<button
                class="back-button"
                type="button"
                onclick={() => {
                  step -= 1;
                  setupError = "";
                }}>Back</button
              >{/if}<Button
              type="submit"
              variant="solid"
              intent="primary"
              data-testid="setup-next"
              >{step === 2 ? "Connect & apply profile" : "Continue"}<span
                aria-hidden="true">↗</span
              ></Button
            >
          </div>
        </div>
      </form>
    </section>
    <div class="demo-notes" aria-label="Demo sequence">
      <span><b>01</b> Choose a course</span><span
        ><b>02</b> Watch distance become a cue</span
      ><span><b>03</b> Inspect the engine acknowledgement</span>
    </div>
  {:else}
    <div class="session-actions">
      <span
        >{sessionMode === "sim" ? "Simulation preset" : "Hardware preset"}
        <code>{session.endpoint}</code></span
      >
      <div>
        <button type="button" onclick={downloadSetup}>Download setup ↓</button
        ><button
          type="button"
          onclick={() => {
            session = null;
            step = 0;
            benchConfirmed = false;
          }}>Change setup ↗</button
        >
      </div>
    </div>
    <p class="session-note">
      Changing setup disconnects this browser stream. It does not stop the relay
      or physical hardware.
    </p>
    <EngineerWorkspace initialMode={sessionMode} initialSetup={session} />
  {/if}

  <section
    id="professional-api"
    class="professional-api"
    aria-labelledby="api-title"
  >
    <header>
      <p class="eyebrow">APIs & compatibility</p>
      <h2 id="api-title">More ways to connect.</h2>
    </header>
    <div class="integration-grid">
      <article>
        <div class="integration-label">
          <span>01 / Intelligence</span><span class="integration-tag"
            >On request</span
          >
        </div>
        <h3>Scene descriptions.<br /><span>OpenAI, on request.</span></h3>
        <p>
          Ask what’s ahead from the live workspace’s <strong>Scene AI</strong> tab.
          A camera frame becomes a short description, separate from the distance-to-haptic
          loop.
        </p>
        <dl class="capabilities">
          <div>
            <dt>Transport</dt>
            <dd>World → local relay → Describe API</dd>
          </div>
          <div>
            <dt>Provider key</dt>
            <dd>Server-side only</dd>
          </div>
          <div>
            <dt>Availability</dt>
            <dd>Reported by the connected bridge</dd>
          </div>
        </dl>
        <details>
          <summary>Configure the API</summary>
          <p>
            Connect your existing Describe service in <code>web/.env</code>,
            then restart <code>npm run live</code>:
          </p>
          <pre>DESCRIBE_URL=https://your-service
DESCRIBE_KEY=your-service-bearer</pre>
          <p>
            Or run the local service with the project’s OpenRouter/OpenAI
            backend:
          </p>
          <pre>OPENROUTER_API_KEY=your-provider-key
OPENROUTER_MODEL=your-vision-model-id</pre>
          <p>
            The served provider and model appear after a successful request.
            Never put secrets in <code>VITE_*</code> variables. Browser Scene AI sends
            a rendered World view, including in HITL—not the physical ESP32 feed.
            The mobile camera description path is separate.
          </p>
        </details>
        <a
          class="text-link"
          href="https://github.com/nunera/Determine/blob/main/cloud/API.md"
          target="_blank"
          rel="noreferrer">API contract <span aria-hidden="true">↗</span></a
        >
      </article>
      <article>
        <div class="integration-label">
          <span>02 / Camera compatibility</span><span class="integration-tag"
            >Simulation study</span
          >
        </div>
        <h3>A view toward<br /><span>Meta glasses.</span></h3>
        <p>
          A Ray-Ban Meta camera-profile study is included in World. It explores
          image framing.
        </p>
        <dl class="capabilities">
          <div>
            <dt>Camera study</dt>
            <dd>720 × 1280 portrait crop/export</dd>
          </div>
          <div>
            <dt>Meta DAT SDK</dt>
            <dd>Not integrated</dd>
          </div>
          <div>
            <dt>Warning hardware</dt>
            <dd>Determine range sensor + haptic motor</dd>
          </div>
        </dl>
        <details>
          <summary>What the study does</summary>
          <p>
            The existing standalone World camera study crops and resamples a 320
            × 240 rendered frame, with a JSON metadata export. It does not add
            optical detail or emulate Meta hardware latency. It does not replace
            the working ESP32 camera.
          </p>
          <p>
            Live Meta capture requires a mobile DAT integration and compatible
            hardware. Camera field of view and optical calibration are not
            validated by this browser study.
          </p>
        </details>
        <a
          class="text-link"
          href="https://github.com/nunera/Determine/blob/main/world/README-profiles.md"
          target="_blank"
          rel="noreferrer"
          >Camera study details <span aria-hidden="true">↗</span></a
        >
      </article>
    </div>
    <details
      id="professional-google"
      class="account-setup"
      data-testid="google-setup"
    >
      <summary>Google sign-in setup</summary>
      <p>
        {accountStatus?.googleConfigured
          ? "OAuth client configured. Google still validates the registered callback and allowed accounts."
          : "A Google OAuth Web application client is required."}
      </p>
      <p>
        Add this exact authorized redirect URI, read from the account service:
      </p>
      <code class="callback-uri"
        >{googleCallback ??
          (accountStatusError || "Checking the account service…")}</code
      >
      <p>
        Set <code>GOOGLE_CLIENT_ID</code> and <code>GOOGLE_CLIENT_SECRET</code>
        in <code>web/.env</code>, then restart <code>npm run live</code>. For a
        published site, also set <code>BETTER_AUTH_URL</code> to its HTTPS origin
        and register that origin’s callback. A Google test-mode app must allow your
        demo accounts.
      </p>
      <p>
        The internal account port is not the callback origin. Preview ports and <code
          >localhost</code
        >
        differ from <code>127.0.0.1</code>. Local simulation and hardware
        sessions do not require an account.
      </p>
      <a
        class="text-link"
        href="https://console.cloud.google.com/apis/credentials"
        target="_blank"
        rel="noreferrer"
        >Open Google Cloud credentials <span aria-hidden="true">↗</span></a
      >
    </details>
  </section>
</div>

<style>
  .professional-workspace {
    padding: 66px 0 100px;
  }
  .professional-heading {
    display: flex;
    justify-content: space-between;
    align-items: flex-end;
    gap: 30px;
    margin-bottom: 58px;
  }
  h1 {
    font-size: clamp(40px, 5.4vw, 72px);
    letter-spacing: -0.055em;
    line-height: 1.06;
    margin: 14px 0 20px;
    font-weight: 600;
  }
  .intro {
    max-width: 490px;
    color: var(--muted);
    font-size: 18px;
    line-height: 1.55;
  }
  .text-link {
    font-size: 14px;
    white-space: nowrap;
  }
  .onboarding {
    border-block: 1px solid var(--line);
    padding: 28px 0 32px;
  }
  .setup-heading,
  .range-label,
  .setup-actions,
  .session-actions,
  .integration-label {
    display: flex;
    justify-content: space-between;
    align-items: center;
    gap: 16px;
  }
  .setup-heading > span,
  .setup-actions > span {
    color: var(--muted);
    font-size: 11px;
  }
  .setup-steps {
    display: flex;
    gap: 32px;
    padding: 0;
    margin: 30px 0 42px;
    list-style: none;
  }
  .setup-steps button {
    border: 0;
    padding: 0 0 12px;
    background: transparent;
    color: var(--muted);
    font-size: 12px;
    border-bottom: 1px solid transparent;
  }
  .setup-steps button span {
    margin-right: 10px;
    font-size: 10px;
  }
  .setup-steps button[aria-current] {
    color: var(--ink);
    border-color: var(--ink);
  }
  .setup-steps button:disabled {
    opacity: 0.5;
    cursor: default;
  }
  h2 {
    font-size: clamp(28px, 3vw, 42px);
    line-height: 1.12;
    font-weight: 550;
    letter-spacing: -0.045em;
    margin-bottom: 30px;
  }
  .mode-choices {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 20px;
    margin-bottom: 32px;
  }
  .mode-choices button {
    text-align: left;
    border: 1px solid var(--line);
    border-radius: 12px;
    background: transparent;
    color: var(--ink);
    padding: 22px 24px;
    display: grid;
    gap: 10px;
    transition:
      border-color 0.2s,
      background 0.2s;
  }
  .mode-choices button[aria-pressed="true"] {
    border-color: var(--blue);
    background: color-mix(in srgb, var(--blue) 4%, transparent);
  }
  .choice-index {
    font:
      10px ui-monospace,
      monospace;
    color: var(--blue);
  }
  .mode-choices strong {
    font-size: 20px;
    font-weight: 500;
    letter-spacing: -0.025em;
  }
  .mode-choices button > span:last-child {
    font-size: 13px;
    color: var(--muted);
    line-height: 1.5;
  }
  label {
    display: block;
    font-size: 12px;
    font-weight: 500;
    margin-bottom: 9px;
  }
  input[type="url"],
  select {
    display: block;
    width: 100%;
    max-width: 540px;
    background: transparent;
    color: var(--ink);
    border: 1px solid var(--line);
    border-radius: 8px;
    padding: 13px 14px;
    font: inherit;
    font-size: 14px;
  }
  option {
    background: var(--paper);
    color: var(--ink);
  }
  .field-note,
  .session-note {
    color: var(--muted);
    font-size: 12px;
    line-height: 1.7;
    max-width: 650px;
    margin-top: 12px;
  }
  code,
  pre {
    font-family: ui-monospace, monospace;
    font-size: 0.92em;
  }
  .profile-fields {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 30px 40px;
    max-width: 780px;
  }
  .profile-fields > div:first-child {
    grid-column: 1/-1;
  }
  .range-label label {
    margin: 0;
  }
  output {
    font-size: 15px;
    font-variant-numeric: tabular-nums;
  }
  output span {
    color: var(--muted);
    font-size: 11px;
  }
  input[type="range"] {
    appearance: auto;
    width: 100%;
    min-height: 24px;
    margin: 22px 0 0;
    accent-color: var(--blue);
    background: transparent;
    cursor: pointer;
  }
  .setup-actions {
    margin-top: 38px;
  }
  .setup-actions > div {
    display: flex;
    gap: 20px;
    align-items: center;
  }
  .back-button,
  .session-actions button {
    border: 0;
    background: transparent;
    color: var(--blue);
    padding: 8px 0;
    font-size: 12px;
  }
  .setup-actions :global(button[data-testid="setup-next"]) {
    border-radius: 99px;
    gap: 22px;
  }
  .setup-review {
    max-width: 700px;
    margin: 0 0 24px;
  }
  .setup-review > div,
  .capabilities > div {
    display: grid;
    grid-template-columns: 100px minmax(0, 1fr);
    gap: 15px;
    padding: 13px 0;
    border-bottom: 1px solid var(--line);
    font-size: 13px;
  }
  dt {
    color: var(--muted);
  }
  dd {
    margin: 0;
    overflow-wrap: anywhere;
  }
  .bench-check {
    display: flex;
    align-items: flex-start;
    gap: 10px;
    margin-top: 22px;
    line-height: 1.5;
  }
  .bench-check input {
    appearance: auto;
    width: 16px;
    height: 16px;
    flex: 0 0 16px;
    accent-color: var(--blue);
    margin-top: 2px;
  }
  .setup-error {
    color: var(--error, #b42d34);
    font-size: 13px;
    margin-top: 20px;
  }
  .demo-notes {
    display: flex;
    justify-content: space-between;
    gap: 24px;
    margin-top: 24px;
    color: var(--muted);
    font-size: 12px;
    line-height: 1.6;
  }
  .demo-notes b {
    color: var(--blue);
    font-size: 10px;
    font-weight: 400;
    margin-right: 8px;
  }
  .session-actions {
    flex-wrap: wrap;
    border-block: 1px solid var(--line);
    padding: 14px 0;
    font-size: 12px;
  }
  .session-actions code {
    margin-left: 16px;
    color: var(--muted);
  }
  .session-actions > div {
    display: flex;
    gap: 24px;
  }
  .session-note {
    margin-bottom: 30px;
  }
  .professional-api {
    margin-top: 110px;
    padding-top: 50px;
    border-top: 1px solid var(--line);
    scroll-margin-top: 85px;
  }
  .professional-api header .eyebrow {
    margin-bottom: 18px;
  }
  .integration-grid {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 70px;
  }
  .integration-label {
    color: var(--muted);
    font-size: 10px;
    padding-block: 16px;
    border-top: 1px solid var(--line);
  }
  .integration-tag {
    color: var(--blue);
  }
  h3 {
    font-size: clamp(25px, 2.8vw, 35px);
    line-height: 1.16;
    letter-spacing: -0.035em;
    font-weight: 550;
    margin: 25px 0 22px;
  }
  h3 span {
    color: var(--muted);
  }
  article > p {
    font-size: 14px;
    color: var(--muted);
    line-height: 1.75;
    min-height: 100px;
  }
  article strong {
    font-weight: 500;
    color: var(--ink);
  }
  .capabilities {
    margin: 28px 0;
  }
  .capabilities > div {
    grid-template-columns: 110px minmax(0, 1fr);
    font-size: 11px;
    line-height: 1.6;
  }
  details {
    font-size: 12px;
    color: var(--muted);
    line-height: 1.75;
  }
  summary {
    cursor: pointer;
    color: var(--ink);
    padding: 8px 0;
  }
  details p {
    margin: 12px 0;
  }
  pre {
    white-space: pre-wrap;
    overflow-wrap: anywhere;
    background: color-mix(in srgb, var(--ink) 4%, transparent);
    padding: 15px;
    border-radius: 6px;
    color: var(--ink);
  }
  article > .text-link {
    display: inline-block;
    margin-top: 22px;
    font-size: 12px;
  }
  .account-setup {
    border-top: 1px solid var(--line);
    margin-top: 54px;
    padding-top: 20px;
    max-width: 760px;
    scroll-margin-top: 85px;
  }
  .callback-uri {
    display: block;
    color: var(--ink);
    overflow-wrap: anywhere;
    padding: 12px 0;
  }
  @media (max-width: 700px) {
    .professional-workspace {
      padding-top: 35px;
    }
    .professional-heading {
      align-items: flex-start;
      flex-direction: column;
      margin-bottom: 35px;
      gap: 18px;
    }
    .intro {
      font-size: 16px;
    }
    .setup-steps {
      gap: 14px;
      margin: 26px 0 32px;
    }
    .setup-steps button {
      font-size: 10px;
      text-align: left;
    }
    .setup-steps button span {
      display: block;
      margin-bottom: 8px;
    }
    .mode-choices {
      gap: 10px;
    }
    .mode-choices button {
      padding: 17px 13px;
      border-radius: 9px;
    }
    .mode-choices strong {
      font-size: 17px;
    }
    .mode-choices button > span:last-child {
      font-size: 11px;
    }
    .profile-fields {
      grid-template-columns: minmax(0, 1fr);
      gap: 28px;
    }
    .demo-notes {
      flex-direction: column;
      gap: 12px;
    }
    .integration-grid {
      grid-template-columns: minmax(0, 1fr);
      gap: 55px;
    }
    .professional-api {
      margin-top: 75px;
      padding-top: 35px;
    }
    article > p {
      min-height: 0;
    }
    .session-actions code {
      display: block;
      margin: 8px 0 0;
    }
    .setup-actions > div {
      gap: 15px;
    }
    .setup-review > div {
      grid-template-columns: 70px minmax(0, 1fr);
      font-size: 12px;
    }
  }
</style>
