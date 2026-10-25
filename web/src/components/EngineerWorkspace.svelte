<script lang="ts">
  import { onMount, untrack } from "svelte";
  import Tabs from "../vendor/bracket/components/Tabs.svelte";
  import type {
    BoardName,
    CameraMode,
    CourseInfo,
    EngineTuning,
    Events,
    FromBridge,
    HitlHello,
    HitlTargetSettings,
    LiveProfile,
    SensorFrame,
    WearerPose,
  } from "../../../world/src/contract";
  import {
    isLiveProfile,
    isStudioCommand,
    isStudioEvent,
    STUDIO_CHANNEL,
    STUDIO_VERSION,
    type CommandName,
  } from "../../../world/src/studioProtocol";
  const boardNames: readonly BoardName[] = ["cue", "cam"];
  let {
    initialMode = "sim",
    initialSetup,
  }: {
    initialMode?: "sim" | "hitl";
    initialSetup?: { endpoint: string; profile: LiveProfile };
  } = $props();
  // Only the professional wizard supplies an explicitly requested, one-shot setup.
  const requestedSetup = untrack(() =>
    initialSetup
      ? {
          endpoint: initialSetup.endpoint,
          profile: { ...initialSetup.profile },
          mode: initialMode,
        }
      : null,
  );
  const initialEndpoint = untrack(
    () =>
      requestedSetup?.endpoint ??
      (initialMode === "hitl"
        ? "http://127.0.0.1:8766"
        : "http://127.0.0.1:8765"),
  );

  let endpoint = $state(initialEndpoint);
  let frameUrl = $state("");
  let frameOrigin = "";
  let iframe = $state<HTMLIFrameElement>();
  let phase = $state<
    "idle" | "connecting" | "connected" | "reconnecting" | "failed"
  >("idle");
  let error = $state("");
  let linkUp = false;
  let helloSeen = false;
  let deadline = 0;
  let now = $state(0);
  let activeTab = $state("session");
  let courses = $state<CourseInfo[]>([]);
  let course = $state<Events["course"] | null>(null);
  let camera = $state<CameraMode>("director");
  let sensor = $state<SensorFrame | null>(null);
  let pose = $state<WearerPose | null>(null);
  let sensorAt = $state(0);
  let cameraFrameAt = $state(0);
  let hitlAt = $state(0);
  let hitl = $state<Extract<FromBridge, { t: "hitl" }> | null>(null);
  let hitlInfo = $state<HitlHello | null>(null);
  let engine = $state("unknown");
  let phone = $state("");
  let systemState = $state("WAITING");
  let reason = $state("");
  let boards = $state<Record<BoardName, "up" | "down"> | null>(null);
  let net = $state("");
  let tuning = $state<EngineTuning | null>(null);
  let diagnostics = $state<Record<string, string>>({});
  let alert = $state<Extract<FromBridge, { t: "alert" }>["alert"]>(null);
  let ack = $state<Extract<FromBridge, { t: "played" }> | null>(null);
  let audio = $state<Extract<FromBridge, { t: "audio" }> | null>(null);
  let muted = $state(true);
  let previewEnabled = $state(false);
  let previewUrl = $state("");
  let previewAt = $state(0);
  let log = $state<{ id: number; at: string; type: string; text: string }[]>(
    [],
  );
  let nextLogId = 0;
  let profileSupported = $state(false);
  let confirmed = $state<LiveProfile | null>(null);
  let profile = $state<LiveProfile>({
    mode: "HAPTIC_ONLY",
    ceilingDb: -30,
    hapticMax: 180,
  });
  let profilePending = $state(false);
  let profileError = $state("");
  let profileDeadline = 0;
  let setupState = $state<
    "none" | "waiting" | "applying" | "confirmed" | "failed"
  >("none");
  let setupStatus = $state("");
  let setupDeadline = 0;
  let describeEnabled = $state(false);
  let describePending = $state(false);
  let describeResult = $state<Events["describeResult"] | null>(null);
  let describeDeadline = 0;
  let target = $state<HitlTargetSettings>({
    on: false,
    distanceMm: 500,
    widthMm: 300,
    heightMm: 300,
    reflectance: "white",
  });
  let overlayCourse = $state(false);
  const connected = $derived(phase === "connected");
  const isHitl = $derived(hitlInfo?.enabled === true);
  const editable = $derived(connected && engine === "running");
  const profileEditable = $derived(
    editable && profileSupported && !profilePending,
  );
  const selectedCourse = $derived(courses.find((c) => c.id === course?.id));
  const profileChanged = $derived(
    !confirmed ||
      profile.mode !== confirmed.mode ||
      profile.ceilingDb !== confirmed.ceilingDb ||
      profile.hapticMax !== confirmed.hapticMax,
  );

  function sameProfile(actual: LiveProfile | null, desired: LiveProfile) {
    return (
      actual?.mode === desired.mode &&
      actual.ceilingDb === desired.ceilingDb &&
      actual.hapticMax === desired.hapticMax
    );
  }

  function profileSummary(value: LiveProfile) {
    return `${value.mode.replaceAll("_", " ")} · haptic ≤ ${value.hapticMax}/255 · audio ≤ ${value.ceilingDb} dBFS`;
  }

  function stopSetup(message: string) {
    if (setupState === "none" || setupState === "failed") return;
    setupState = "failed";
    setupDeadline = 0;
    setupStatus = `${message} Guided setup will not reapply; review Live profile before applying manually.`;
  }

  function maybeApplySetup() {
    if (setupState !== "waiting" || !requestedSetup || !connected) return;
    const actualMode = hitlInfo?.enabled ? "hitl" : "sim";
    if (actualMode !== requestedSetup.mode) {
      stopSetup(
        `The bridge reports ${actualMode.toUpperCase()}, but ${requestedSetup.mode.toUpperCase()} was requested. Change setup to match before applying a profile.`,
      );
      return;
    }
    if (engine === "external") {
      stopSetup(
        "External phone: configure its profile on the phone. No guided application is confirmed.",
      );
      return;
    }
    if (!profileSupported) {
      stopSetup(
        "This bridge does not advertise live profile support. No guided application is confirmed.",
      );
      return;
    }
    if (engine === "exited") {
      stopSetup(
        "The spawned engine is stopped. No guided application is confirmed.",
      );
      return;
    }
    if (engine !== "running") return;
    // The bridge rejects changes until the engine has acknowledged its initial profile.
    if (!confirmed) {
      setupStatus =
        "Connected. Waiting for the engine's initial profile acknowledgement.";
      return;
    }
    if (sameProfile(confirmed, requestedSetup.profile)) {
      setupState = "confirmed";
      setupDeadline = 0;
      setupStatus = `Engine-confirmed match: ${profileSummary(confirmed)}. No change was needed.`;
      return;
    }
    profile = { ...requestedSetup.profile };
    setupState = "applying";
    setupDeadline = 0;
    setupStatus =
      "Profile sent to the live engine. Waiting for its acknowledgement; not yet confirmed.";
    if (!applyProfile())
      stopSetup(
        "The profile command could not be sent. No guided application is confirmed.",
      );
  }

  function revokePreview() {
    if (previewUrl) URL.revokeObjectURL(previewUrl);
    previewUrl = "";
    previewAt = 0;
  }

  function clearTelemetry() {
    sensor = null;
    pose = null;
    sensorAt = 0;
    cameraFrameAt = 0;
    hitlAt = 0;
    hitl = null;
    hitlInfo = null;
    engine = "unknown";
    phone = "";
    systemState = "WAITING";
    reason = "";
    boards = null;
    net = "";
    tuning = null;
    diagnostics = {};
    alert = null;
    ack = null;
    audio = null;
    confirmed = null;
    profileSupported = false;
    profilePending = false;
    profileDeadline = 0;
    describeEnabled = false;
    describePending = false;
    describeResult = null;
    describeDeadline = 0;
    revokePreview();
  }

  function disconnect() {
    stopSetup(
      "Browser disconnected. Any previous profile confirmation is no longer live.",
    );
    frameUrl = "";
    frameOrigin = "";
    phase = "idle";
    deadline = 0;
    linkUp = false;
    helloSeen = false;
    clearTelemetry();
    courses = [];
    course = null;
    log = [];
    error = "";
    profileError = "";
    muted = true;
    previewEnabled = false;
    target.on = false;
    overlayCourse = false;
  }

  function connect() {
    disconnect();
    try {
      const url = new URL(endpoint.trim());
      if (!["http:", "https:"].includes(url.protocol))
        throw new Error("Use an http:// or https:// bridge endpoint.");
      if (url.username || url.password)
        throw new Error("Bridge URLs must not contain embedded credentials.");
      if (window.location.protocol === "https:" && url.protocol !== "https:")
        throw new Error(
          "HTTPS pages require an HTTPS bridge. Open the local HTTP studio to use a local HTTP bridge.",
        );
      url.pathname = "/";
      url.search = "";
      url.hash = "";
      frameOrigin = url.origin;
      endpoint = url.origin;
      url.searchParams.set("studio", "1");
      url.searchParams.set("parentOrigin", window.location.origin);
      url.searchParams.set("quality", "balanced");
      phase = "connecting";
      deadline = performance.now() + 20_000;
      frameUrl = url.href;
    } catch (cause) {
      error =
        cause instanceof Error
          ? cause.message
          : "The bridge URL could not be opened.";
      phase = "failed";
    }
  }

  function send<K extends CommandName>(event: K, payload: Events[K]) {
    if (!connected || !iframe?.contentWindow || !frameOrigin) return false;
    const message = {
      channel: STUDIO_CHANNEL,
      version: STUDIO_VERSION,
      kind: "command",
      event,
      payload,
    };
    if (!isStudioCommand(message)) return false;
    iframe.contentWindow.postMessage(message, frameOrigin);
    return true;
  }

  function pushLog(msg: FromBridge) {
    // Only bridge facts enter the event log; high-rate status/diagnostic messages belong in the rail.
    if (["bridge", "hitl", "diag", "alert"].includes(msg.t)) return;
    let text: string;
    switch (msg.t) {
      case "log":
        text = msg.line;
        break;
      case "state":
        text = `${msg.state} · ${msg.reason}`;
        break;
      case "hello":
        text = `${msg.engine} engine · ${msg.phone}${msg.hitl?.enabled ? " · real-board relay" : " · simulated boards"}`;
        break;
      case "profile":
        text = `${msg.status}${msg.error ? ` · ${msg.error}` : ` · ${JSON.stringify(msg.profile)}`}`;
        break;
      case "played":
        text = `${msg.status} · seq ${msg.seq ?? "—"} · ${msg.haptic} ${msg.hapticMax} · ${msg.source === "real" ? "real board ACK" : "simulated board"}`;
        break;
      case "audio":
        text = `${msg.cue} · ${msg.glDb}/${msg.grDb} dBFS · ${msg.status}`;
        break;
      case "cueTx":
        text = `${msg.cue} · seq ${msg.seq} · haptic ${Math.max(msg.hl, msg.hr)}`;
        break;
      case "speak":
        text = msg.text;
        break;
      default:
        text = JSON.stringify(msg);
    }
    log = [
      {
        id: nextLogId++,
        at: new Date().toLocaleTimeString([], { hour12: false }),
        type: msg.t,
        text: text.slice(0, 1200),
      },
      ...log,
    ].slice(0, 80);
  }

  function bridgeMessage(msg: FromBridge) {
    pushLog(msg);
    switch (msg.t) {
      case "hello":
        helloSeen = true;
        engine = msg.engine === "spawned" ? "starting" : "external";
        phone = msg.phone;
        hitlInfo = msg.hitl ?? null;
        tuning = msg.tuning;
        profileSupported = Object.hasOwn(msg, "profile");
        describeEnabled = msg.describe === true;
        if (isLiveProfile(msg.profile)) {
          confirmed = { ...msg.profile };
          profile = { ...msg.profile };
        }
        if (linkUp) {
          phase = "connected";
          deadline = 0;
        }
        // These are browser monitor/scene controls, never device configuration.
        send("setMuted", { muted });
        send("hitlTarget", { ...target });
        send("hitlOverlayCourse", { on: overlayCourse });
        break;
      case "bridge":
        engine = msg.engine;
        boards = msg.boards;
        net = msg.net;
        tuning = msg.tuning;
        profileSupported = Object.hasOwn(msg, "profile");
        if (msg.profile === null) confirmed = null;
        if (isLiveProfile(msg.profile)) {
          const firstConfirmation = confirmed === null;
          confirmed = { ...msg.profile };
          if (firstConfirmation && !profilePending)
            profile = { ...msg.profile };
        }
        if (msg.engine === "exited") {
          systemState = "UNAVAILABLE";
          reason = "Phone engine stopped";
        }
        if (msg.engine !== "running" && profilePending) {
          profilePending = false;
          profileDeadline = 0;
          stopSetup(
            "The engine became unavailable before confirming the guided profile.",
          );
          profileError =
            "Engine unavailable before profile confirmation. No application is confirmed.";
        }
        break;
      case "hitl":
        hitl = msg;
        hitlAt = performance.now();
        break;
      case "state":
        systemState = msg.state;
        reason = msg.reason;
        break;
      case "diag":
        diagnostics = msg.kv;
        // STATE is transition-only; diagnostics provide the current state to late joiners.
        if (
          ["STARTING", "READY", "DEGRADED", "UNAVAILABLE", "PAUSED"].includes(
            msg.kv.state,
          ) &&
          systemState !== msg.kv.state
        ) {
          systemState = msg.kv.state;
          reason = "Engine diagnostic snapshot";
        }
        break;
      case "alert":
        alert = msg.alert;
        break;
      case "played":
        ack = msg;
        break;
      case "audio":
        audio = msg;
        break;
      case "profile":
        profilePending = false;
        profileDeadline = 0;
        if (msg.status === "applied" && isLiveProfile(msg.profile)) {
          confirmed = { ...msg.profile };
          profile = { ...msg.profile };
          profileError = "";
        } else
          profileError =
            msg.error ??
            "The engine rejected the profile. No new settings were confirmed.";
        if (setupState === "applying") {
          if (
            msg.status === "applied" &&
            sameProfile(msg.profile, requestedSetup!.profile)
          ) {
            setupState = "confirmed";
            setupStatus = `Engine acknowledged: ${profileSummary(msg.profile!)}. Guided profile applied.`;
          } else {
            stopSetup(
              msg.status === "rejected"
                ? `Engine rejected the guided profile: ${profileError}`
                : "The engine acknowledged different values; the guided profile is not confirmed.",
            );
          }
        }
        break;
    }
    maybeApplySetup();
  }

  function receive(event: MessageEvent<unknown>) {
    if (
      !frameUrl ||
      event.source !== iframe?.contentWindow ||
      event.origin !== frameOrigin ||
      !isStudioEvent(event.data)
    )
      return;
    const message = event.data;
    switch (message.event) {
      case "courses":
        courses = message.payload;
        break;
      case "course":
        course = message.payload;
        break;
      case "cameraMode":
        camera = message.payload;
        break;
      case "sensor":
        if (connected) {
          sensor = message.payload;
          sensorAt = performance.now();
        }
        break;
      case "cameraFrame":
        if (connected) cameraFrameAt = performance.now();
        break;
      case "pose":
        if (connected) pose = message.payload;
        break;
      case "bridge":
        bridgeMessage(message.payload);
        break;
      case "link":
        linkUp = message.payload.connected;
        if (!linkUp) {
          const wasConnected = phase === "connected";
          // World replays its initial offline state before its first socket hello.
          // Only a connection lost after readiness invalidates the one-shot setup.
          if (
            wasConnected ||
            setupState === "applying" ||
            setupState === "confirmed"
          )
            stopSetup(
              "Bridge connection lost. Any previous confirmation is no longer live.",
            );
          helloSeen = false;
          if (profilePending)
            profileError =
              "Connection lost before profile confirmation. Reconnect before applying again.";
          clearTelemetry();
          if (wasConnected) {
            phase = "reconnecting";
            deadline = performance.now() + 20_000;
          }
        } else if (helloSeen) {
          phase = "connected";
          deadline = 0;
        }
        maybeApplySetup();
        break;
      case "hitlPreview":
        if (connected && previewEnabled) {
          revokePreview();
          previewUrl = URL.createObjectURL(message.payload.jpeg);
          previewAt = performance.now();
        }
        break;
      case "describeResult":
        if (connected && describeEnabled) {
          describeResult = message.payload;
          describePending = message.payload.pending;
          describeDeadline = message.payload.pending
            ? performance.now() + 25_000
            : 0;
        }
        break;
    }
  }

  function applyProfile() {
    if (!profileEditable || !isLiveProfile(profile)) return false;
    profileError = "";
    profilePending = true;
    profileDeadline = performance.now() + 10_000;
    if (!send("setProfile", { ...profile })) {
      profilePending = false;
      profileDeadline = 0;
      profileError =
        "The profile command was not sent. No application is confirmed.";
      return false;
    }
    return true;
  }

  function requestDescribe() {
    if (!connected || !describeEnabled || describePending) return;
    describeResult = null;
    if (!send("describe", {})) {
      describeResult = {
        ok: false,
        pending: false,
        error: "Scene request was not sent; reconnect the bridge.",
      };
      return;
    }
    describePending = true;
    describeDeadline = performance.now() + 25_000;
  }

  function updateTarget(patch: Partial<HitlTargetSettings>) {
    const next = { ...target, ...patch };
    if (![next.distanceMm, next.widthMm, next.heightMm].every(Number.isFinite))
      return;
    next.distanceMm = Math.max(30, Math.min(1300, Math.round(next.distanceMm)));
    next.widthMm = Math.max(20, Math.min(3000, Math.round(next.widthMm)));
    next.heightMm = Math.max(20, Math.min(3000, Math.round(next.heightMm)));
    target = next;
    send("hitlTarget", { ...next });
  }

  function rangeText(
    mm: number | null | undefined,
    status: number | null | undefined,
  ) {
    if (status === 0 && typeof mm === "number") return `${Math.round(mm)} mm`;
    if (status === 1) return "NO TARGET";
    if (status === 2) return "UNKNOWN";
    return "—";
  }

  function ageText(at: number) {
    if (!at) return "not received";
    const age = Math.max(0, now - at);
    return age < 1000
      ? `${Math.round(age)} ms`
      : `${(age / 1000).toFixed(1)} s`;
  }

  function boardAge(age: number | null | undefined) {
    if (typeof age !== "number" || !hitlAt) return "not heard";
    return `${Math.round(age + Math.max(0, now - hitlAt))} ms`;
  }

  onMount(() => {
    window.addEventListener("message", receive);
    const timer = window.setInterval(() => {
      now = performance.now();
      if (deadline && now >= deadline && phase !== "connected") {
        error =
          "No live bridge hello arrived. Start the bridge serving World, check the endpoint, then reconnect. A loaded page alone is not a connection.";
        frameUrl = "";
        frameOrigin = "";
        phase = "failed";
        deadline = 0;
        stopSetup(
          "No live bridge hello arrived. No guided application is confirmed.",
        );
        clearTelemetry();
        courses = [];
        course = null;
      }
      if (profilePending && now >= profileDeadline) {
        profilePending = false;
        profileError =
          "No engine acknowledgement within 10 seconds. Application is not confirmed; check the bridge event log.";
        profileDeadline = 0;
        stopSetup(
          "No engine acknowledgement arrived within 10 seconds. The guided application is not confirmed.",
        );
      }
      if (setupState === "waiting" && setupDeadline && now >= setupDeadline)
        stopSetup(
          "The engine did not become ready within 20 seconds. No guided application is confirmed.",
        );
      if (describePending && describeDeadline && now >= describeDeadline) {
        describePending = false;
        describeDeadline = 0;
        describeResult = {
          ok: false,
          pending: false,
          error:
            "No valid Scene AI result arrived within 25 seconds. Check the bridge connection and provider setup.",
        };
      }
    }, 250);
    if (requestedSetup) {
      if (!isLiveProfile(requestedSetup.profile)) {
        setupState = "failed";
        setupStatus =
          "The guided profile is invalid. No automatic connection or profile application was attempted.";
      } else {
        // Listener registration precedes assigning iframe.src, including the wizard's explicit connection.
        connect();
        setupState = frameUrl ? "waiting" : "failed";
        setupDeadline = frameUrl ? performance.now() + 20_000 : 0;
        setupStatus = frameUrl
          ? "Connecting the requested bridge. Profile application waits for the running engine."
          : `Guided connection failed: ${error} No profile application is confirmed.`;
      }
    }
    return () => {
      window.removeEventListener("message", receive);
      window.clearInterval(timer);
      revokePreview();
    };
  });
</script>

<section
  class="engineer-workspace"
  aria-labelledby="engineer-title"
  data-testid="live-studio"
  class:has-session={!!frameUrl}
>
  <header class="workspace-header">
    <div>
      <p class="eyebrow">Determine Studio</p>
      <h1 id="engineer-title">
        {frameUrl ? "Your live workspace." : "Make the connection."}
      </h1>
      <p class="intro">
        {frameUrl
          ? "One scene. The real phone engine. Every response in view."
          : "Explore a spatial world with the real phone engine. Start in software, or bring your boards into the loop."}
      </p>
      {#if setupState !== "none"}
        <p
          class="onboarding-status"
          class:configuration-failed={setupState === "failed"}
          role="status"
          data-testid="onboarding-configuration-status"
        >
          {setupStatus}
        </p>
      {/if}
    </div>
    {#if frameUrl}
      <div class="session-label" role="status">
        <span class:online={connected}></span>
        <div>
          <strong
            >{connected
              ? isHitl
                ? "Hardware in the loop"
                : "Live simulation"
              : phase === "reconnecting"
                ? "Reconnecting"
                : "Connecting"}</strong
          >
          <small
            >{connected
              ? "Bridge hello received"
              : "Waiting for a live bridge hello"}</small
          >
        </div>
      </div>
    {/if}
  </header>

  <form
    class="connection-bar"
    class:compact={!!frameUrl}
    onsubmit={(event) => {
      event.preventDefault();
      connect();
    }}
  >
    <div class="endpoint-presets" aria-label="Bridge presets">
      <button
        type="button"
        data-testid="preset-sim"
        disabled={!!frameUrl}
        class:chosen={endpoint === "http://127.0.0.1:8765"}
        aria-pressed={endpoint === "http://127.0.0.1:8765"}
        onclick={() => (endpoint = "http://127.0.0.1:8765")}
      >
        <span class="mode-topline"
          ><span>SIM</span><span class="selection-mark" aria-hidden="true"
          ></span></span
        >
        <strong>Browser simulation</strong>
        <span class="mode-description"
          >The real phone engine, software boards, and an interactive World. No
          hardware required.</span
        >
        <span class="mode-detail"
          >Explore courses, live profiles, and simulated faults.</span
        >
      </button>
      <button
        type="button"
        data-testid="preset-hitl"
        disabled={!!frameUrl}
        class:chosen={endpoint === "http://127.0.0.1:8766"}
        aria-pressed={endpoint === "http://127.0.0.1:8766"}
        onclick={() => (endpoint = "http://127.0.0.1:8766")}
      >
        <span class="mode-topline"
          ><span>HITL</span><span class="selection-mark" aria-hidden="true"
          ></span></span
        >
        <strong>Hardware in the loop</strong>
        <span class="mode-description"
          >Connect real boards through the local relay. Blend physical inputs
          with the browser scene.</span
        >
        <span class="mode-detail"
          >Working ESP32 camera support. Boards appear when discovered.</span
        >
      </button>
    </div>
    <div class="connection-actions">
      <label class="endpoint"
        ><span>HTTP bridge endpoint</span><input
          data-testid="bridge-endpoint"
          aria-label="HTTP bridge endpoint"
          type="text"
          bind:value={endpoint}
          disabled={!!frameUrl}
          spellcheck="false"
          placeholder="http://127.0.0.1:8765"
        /></label
      >
      {#if frameUrl}<button
          class="connect-button disconnect"
          type="button"
          data-testid="disconnect"
          onclick={disconnect}
          >Disconnect <span aria-hidden="true">×</span></button
        >{:else}<button
          class="connect-button"
          type="submit"
          data-testid="connect"
          >Connect bridge <span aria-hidden="true">↗</span></button
        >{/if}
    </div>
    <details class="setup-notes">
      <summary>Local setup & connection details</summary>
      <p>
        Start locally with <code>npm run live</code> in <code>web/</code> for SIM,
        or run your hardware relay for HITL. The defaults use ports 8765 and 8766.
        Use a custom endpoint for another bridge; an HTTPS studio requires an HTTPS
        bridge.
      </p>
      <p>
        Connection is confirmed only after the live bridge hello. A loaded scene
        alone is not a connection.
      </p>
    </details>
    <p class="connection-hint">
      Disconnect stops this browser stream—not the bridge, phone engine, or
      physical hardware.
    </p>
  </form>
  {#if error}<div
      class="error-banner"
      role="alert"
      data-testid="connection-error"
    >
      <strong>Connection unavailable.</strong>
      {error}
    </div>{/if}

  {#if frameUrl}
    <div class="cockpit">
      <div class="stage-column">
        <div class="stage-header">
          <span class="stage-kicker">World</span><span class="truth-tag"
            >{connected
              ? isHitl
                ? "Scene → real-board relay → phone"
                : "Scene → simulated boards → phone"
              : "Connect to start the browser session"}</span
          >
        </div>
        <div
          class="world-stage"
          data-testid="world-stage"
          aria-busy={phase === "connecting" || phase === "reconnecting"}
        >
          {#if frameUrl}<iframe
              bind:this={iframe}
              src={frameUrl}
              title="Live Determine World"
              data-testid="world-frame"
              allow="autoplay"
              referrerpolicy="origin"
            ></iframe>{/if}
          {#if !connected}
            <div class="stage-cover" class:loading={!!frameUrl}>
              <div class="loading-symbol" aria-hidden="true"><span></span></div>
              <p class="stage-kicker">
                {phase === "reconnecting"
                  ? "Bridge link interrupted"
                  : "Connecting your workspace"}
              </p>
              <h2>
                {phase === "connecting"
                  ? "Waiting for the bridge."
                  : phase === "reconnecting"
                    ? "Waiting for a fresh hello."
                    : "Bring the system into view."}
              </h2>
              <p>
                {frameUrl
                  ? "The live scene is starting. Connected means a real WebSocket link and bridge hello — never just a loaded canvas."
                  : "Connect to SIM for the real engine with simulated boards, or HITL to relay real hardware. Missing boards stay missing."}
              </p>
              <div class="signal-chain" aria-label="System signal chain">
                <span>World</span><i>→</i><span>Bridge</span><i>→</i><span
                  >Phone</span
                >
              </div>
            </div>
          {/if}
          {#if connected}<div class="stage-live">
              <span></span> Live <b>{isHitl ? "HITL" : "SIM"}</b>
            </div>{/if}
        </div>
        <div class="transport">
          <button
            type="button"
            data-testid="play-pause"
            disabled={!connected}
            class="play-button"
            onclick={() => send("play", { playing: !course?.playing })}
            >{course?.playing ? "Ⅱ Pause" : "▶ Play"}</button
          >
          <button
            type="button"
            data-testid="restart-course"
            disabled={!connected}
            title="Restart the course, not the engine"
            onclick={() => send("restart", {})}
            >↺ <span>Reset course</span></button
          >
          <div class="timeline">
            <div class="timeline-track">
              <span
                style:width={`${selectedCourse && course ? Math.min(100, (course.tS / selectedCourse.durationS) * 100) : 0}%`}
              ></span>
            </div>
            <span
              >{course ? course.tS.toFixed(1) : "0.0"} / {selectedCourse?.durationS.toFixed(
                1,
              ) ?? "—"} s</span
            >
          </div>
          <button
            type="button"
            data-testid="mute"
            disabled={!connected}
            aria-pressed={muted}
            title="Browser audio monitor only; never mutes the phone's warning policy"
            onclick={() => {
              muted = !muted;
              send("setMuted", { muted });
            }}>{muted ? "Audio off" : "Audio on"}</button
          >
        </div>
        <div class="view-strip">
          <span>View</span>
          <div class="segmented" aria-label="World viewpoint">
            {#each ["director", "wearer", "orbit", "plan"] as view}<button
                type="button"
                data-testid={`view-${view}`}
                disabled={!connected}
                aria-pressed={camera === view}
                onclick={() =>
                  send("setCameraMode", { mode: view as CameraMode })}
                >{view}</button
              >{/each}
          </div>
          <span class="speed"
            >{pose ? `${pose.speedMps.toFixed(2)} m/s` : "— m/s"}</span
          >
        </div>
        <p class="stage-footnote">
          Browser audio monitors the phone output; it is not integrated wearable
          audio. Enable it inside the scene, then unmute here. Haptic ACKs
          report command acceptance, never measured motor onset.
        </p>
      </div>

      <aside class="inspector" aria-label="Live engineering inspector">
        <div class="inspector-heading">
          <span class="stage-kicker">Inspector</span><span
            class="live-dot"
            class:online={connected}
          ></span>
        </div>
        {#snippet sessionContent()}
          <div class="inspector-content">
            <label class="field-label" for="studio-course">Scenario</label>
            <select
              id="studio-course"
              data-testid="course-select"
              value={course?.id ?? ""}
              disabled={!connected}
              onchange={(event) =>
                send("selectCourse", { id: event.currentTarget.value })}
              ><option value="" disabled>Choose a live course</option
              >{#each courses as c}<option value={c.id}>{c.title}</option
                >{/each}</select
            >
            <p class="scenario-summary">
              {selectedCourse?.summary ??
                "Courses are supplied by the connected World runtime."}
            </p>
            {#if selectedCourse}<div class="expectation">
                <span>What to observe</span>
                <p>{selectedCourse.expect}</p>
              </div>{/if}
            <div class="inspector-divider"></div>
            <div class="section-title">
              <h3>{isHitl ? "Real board relay" : "Fault injection"}</h3>
              <span>{isHitl ? "Hardware" : "SIM only"}</span>
            </div>
            {#if isHitl}
              <p class="help">
                Real boards are discovered from their UDP packets. No simulated
                unplug controls are available in HITL.
              </p>
              <p class="mono-detail">Relay {hitlInfo?.listen ?? "—"}</p>
              {#each boardNames as board}<div class="hardware-card">
                  <strong
                    >{board === "cue"
                      ? "Cue / range board"
                      : "Head camera board"}</strong
                  ><span class:missing={!hitl?.[board].addr}
                    >{hitl?.[board].addr ?? "Not discovered"}</span
                  ><small
                    >Packet age {boardAge(hitl?.[board].ageMs)} · RSSI {hitl?.[
                      board
                    ].rssi ?? "—"} dBm<br />Faults {hitl?.[board].faults == null
                      ? "—"
                      : `0x${hitl[board].faults!.toString(16)}`}</small
                  >
                </div>{/each}
            {:else}
              <div class="toggle-list">
                {#each boardNames as board}<label
                    ><span
                      >{board === "cue"
                        ? "Unplug cue board"
                        : "Unplug camera board"}</span
                    ><input
                      type="checkbox"
                      data-testid={`unplug-${board}`}
                      disabled={!connected || !boards}
                      checked={boards?.[board] === "down"}
                      onchange={(event) =>
                        send("setBoard", {
                          board,
                          down: event.currentTarget.checked,
                        })}
                    /></label
                  >{/each}
                <label
                  ><span>Wi-Fi jitter</span><input
                    type="checkbox"
                    data-testid="wifi-jitter"
                    disabled={!connected || !net}
                    checked={net === "wifi_jitter"}
                    onchange={(event) =>
                      send("setNet", {
                        profile: event.currentTarget.checked
                          ? "wifi_jitter"
                          : "clean",
                      })}
                  /></label
                >
              </div>
            {/if}
            <div class="inspector-divider"></div>
            <div class="section-title">
              <h3>Engine experiments</h3>
              <span>Restarts engine</span>
            </div>
            <p class="help">
              These switches restart the actual spawned phone engine. Live
              profile changes do not.
            </p>
            <div class="toggle-list">
              <label
                ><span>Approach escalation</span><input
                  data-testid="tuning-approach"
                  type="checkbox"
                  disabled={!editable || !tuning}
                  checked={tuning?.approach ?? false}
                  onchange={(event) =>
                    tuning &&
                    send("setTuning", {
                      ...tuning,
                      approach: event.currentTarget.checked,
                    })}
                /></label
              ><label
                ><span>Explore pulse train</span><input
                  data-testid="tuning-explore"
                  type="checkbox"
                  disabled={!editable || !tuning}
                  checked={tuning?.explore ?? false}
                  onchange={(event) =>
                    tuning &&
                    send("setTuning", {
                      ...tuning,
                      explore: event.currentTarget.checked,
                    })}
                /></label
              >
            </div>
            <button
              class="secondary-button"
              type="button"
              data-testid="restart-engine"
              disabled={!editable}
              onclick={() => send("restartEngine", {})}
              >↺ Restart phone engine</button
            >
            <p class="help">
              {engine === "external"
                ? "External phone: configure its engine and profile on the phone."
                : "Engine controls require a running, bridge-spawned engine."}
            </p>
          </div>
        {/snippet}
        {#snippet profileContent()}
          <div class="inspector-content">
            <div class="section-title">
              <h3>Live output profile</h3>
              <span>No restart</span>
            </div>
            <p class="help">
              Applied by the actual phone engine, then sent as a wire PROFILE to
              the learned cue board. This is not a local draft.
            </p>
            <fieldset disabled={!profileEditable}>
              <label class="field-label" for="hearing-mode">Hearing mode</label
              ><select
                id="hearing-mode"
                data-testid="hearing-mode"
                bind:value={profile.mode}
                ><option value="HAPTIC_ONLY">Haptic only · default</option
                ><option value="STEREO">Stereo phone audio</option><option
                  value="SSD">SSD · phone hearing profile</option
                ></select
              >
              <div class="range-heading">
                <label class="field-label" for="haptic-max"
                  >Haptic ceiling</label
                ><output>{profile.hapticMax}<small> / 255</small></output>
              </div>
              <input
                data-testid="haptic-max"
                id="haptic-max"
                type="range"
                min="1"
                max="255"
                step="1"
                bind:value={profile.hapticMax}
              />
              <p class="help">
                1–255 · watchdog-preserving limit; one Vibro motor, no
                left/right actuation.
              </p>
              <div class="range-heading">
                <label class="field-label" for="audio-ceiling"
                  >Phone audio ceiling</label
                ><output>{profile.ceilingDb}<small> dBFS</small></output>
              </div>
              <input
                data-testid="audio-ceiling"
                id="audio-ceiling"
                type="range"
                min="-60"
                max="-10"
                step="1"
                bind:value={profile.ceilingDb}
              />
              <p class="help">
                Audio cues remain silent in haptic-only mode. Phone failure
                announcements retain their existing warning policy.
              </p>
            </fieldset>
            <button
              class="apply-button"
              type="button"
              data-testid="apply-profile"
              disabled={!profileEditable || !profileChanged}
              onclick={applyProfile}
              >{profilePending
                ? "Waiting for engine ACK…"
                : "Apply to live engine"}<span>↗</span></button
            >
            {#if profileError}<p
                class="profile-error"
                role="alert"
                data-testid="profile-error"
              >
                {profileError}
              </p>{/if}
            <div class="confirmed-profile" data-testid="confirmed-profile">
              <span>Engine-confirmed profile</span>{#if confirmed}<strong
                  >{confirmed.mode.replaceAll("_", " ")}</strong
                >
                <p>
                  Haptic ≤ {confirmed.hapticMax} / 255<br />Phone audio ≤ {confirmed.ceilingDb}
                  dBFS
                </p>{:else}<strong>Not confirmed</strong>
                <p>
                  No successful engine application has been reported for this
                  session.
                </p>{/if}
            </div>
            <p class="help">
              {!connected
                ? "Connect a bridge to edit live configuration."
                : engine === "external"
                  ? "External phone: live profile changes are unavailable. Configure the phone itself."
                  : !profileSupported
                    ? "This bridge does not advertise live profile support."
                    : !editable
                      ? "Waiting for the spawned engine to run."
                      : "Only an engine acknowledgement confirms new settings. Board delivery is not proof of physical actuation."}
            </p>
          </div>
        {/snippet}
        {#snippet targetContent()}
          <div class="inspector-content">
            <div class="section-title">
              <h3>Scene / hardware blend</h3>
              <span>{isHitl ? "Live HITL" : "HITL required"}</span>
            </div>
            <p class="help">
              Virtual targets live in this browser scene, not in a physical
              sensor. The bridge reports which source actually reaches the
              engine.
            </p>
            <fieldset disabled={!connected || !isHitl}>
              <label class="field-label" for="range-source"
                >Engine range source</label
              ><select
                id="range-source"
                data-testid="hitl-range"
                value={hitl?.range.mode ?? hitlInfo?.range ?? "real"}
                onchange={(event) =>
                  send("setHitl", {
                    range: event.currentTarget.value as
                      "real" | "world" | "fuse",
                  })}
                ><option value="real">Real · physical ToF only</option><option
                  value="world">World · browser raycast</option
                ><option value="fuse">Fuse · bridge arbitration</option></select
              >
              <label class="field-label" for="camera-source"
                >Engine camera source</label
              ><select
                id="camera-source"
                data-testid="hitl-camera"
                value={hitl?.camera.mode ?? hitlInfo?.camera ?? "real"}
                onchange={(event) =>
                  send("setHitl", {
                    camera: event.currentTarget.value as "real" | "composite",
                  })}
                ><option value="real">Real · camera JPEG</option><option
                  value="composite"
                  disabled={!hitlInfo?.compositeAvailable}
                  >Composite · real + overlay</option
                ></select
              >
              <div class="source-readout">
                <span>Bridge actual output</span><strong
                  >{rangeText(hitl?.range.outMm, hitl?.range.outStatus)}</strong
                ><small
                  >source: {hitl?.range.source ?? "not reported"}<br />Real {rangeText(
                    hitl?.range.realMm,
                    hitl?.range.realStatus,
                  )}<br />World {rangeText(
                    hitl?.range.worldMm,
                    hitl?.range.worldStatus,
                  )}</small
                >
              </div>
              {#if hitl?.camera.lastError}<p class="profile-error" role="alert">
                  {hitl.camera.lastError}
                </p>{/if}
              <div class="toggle-list">
                <label
                  ><span>Head-locked virtual target</span><input
                    data-testid="target-enabled"
                    type="checkbox"
                    checked={target.on}
                    onchange={(event) =>
                      updateTarget({ on: event.currentTarget.checked })}
                  /></label
                >
              </div>
              <div class="range-heading">
                <label class="field-label" for="target-distance"
                  >Target distance</label
                ><output>{target.distanceMm}<small> mm</small></output>
              </div>
              <input
                data-testid="target-distance"
                id="target-distance"
                type="range"
                min="30"
                max="1300"
                step="10"
                value={target.distanceMm}
                oninput={(event) =>
                  updateTarget({
                    distanceMm: event.currentTarget.valueAsNumber,
                  })}
              />
              <div class="dimension-fields">
                <label
                  >Width / mm<input
                    data-testid="target-width"
                    type="number"
                    min="20"
                    max="3000"
                    step="10"
                    value={target.widthMm}
                    oninput={(event) =>
                      updateTarget({
                        widthMm: event.currentTarget.valueAsNumber,
                      })}
                  /></label
                ><label
                  >Height / mm<input
                    data-testid="target-height"
                    type="number"
                    min="20"
                    max="3000"
                    step="10"
                    value={target.heightMm}
                    oninput={(event) =>
                      updateTarget({
                        heightMm: event.currentTarget.valueAsNumber,
                      })}
                  /></label
                >
              </div>
              <label class="field-label" for="target-surface"
                >Target reflectance</label
              ><select
                data-testid="target-reflectance"
                id="target-surface"
                value={target.reflectance}
                onchange={(event) =>
                  updateTarget({
                    reflectance: event.currentTarget
                      .value as HitlTargetSettings["reflectance"],
                  })}
                ><option value="white">White · 88%</option><option value="grey"
                  >Grey · 17%</option
                ><option value="glass">Glass · unknown range</option></select
              >
              <div class="toggle-list">
                <label
                  ><span>Course objects in overlay</span><input
                    data-testid="overlay-course"
                    type="checkbox"
                    checked={overlayCourse}
                    onchange={(event) => {
                      overlayCourse = event.currentTarget.checked;
                      send("hitlOverlayCourse", { on: overlayCourse });
                    }}
                  /></label
                ><label
                  ><span>Show real head-camera preview</span><input
                    data-testid="preview-enabled"
                    type="checkbox"
                    checked={previewEnabled}
                    onchange={(event) => {
                      previewEnabled = event.currentTarget.checked;
                      if (!previewEnabled) revokePreview();
                    }}
                  /></label
                >
              </div>
            </fieldset>
            {#if previewEnabled}<div class="camera-preview">
                {#if previewUrl}<img
                    src={previewUrl}
                    alt="Actual tagged HITL frame sent by the bridge to the phone engine"
                    data-testid="head-camera-preview"
                  />{:else}<p>
                    Waiting for a real tagged camera frame.<br />No physical
                    camera is assumed connected.
                  </p>{/if}<span
                  >Engine input · {previewAt
                    ? `${ageText(previewAt)} old`
                    : "no frame"}</span
                >
              </div>{/if}
            <p class="help">
              {hitl
                ? `${hitl.camera.framesOut} frames relayed · ${hitl.camera.composited} composited`
                : "HITL telemetry is supplied by the bridge, not inferred from a preset."}
            </p>
          </div>
        {/snippet}
        {#snippet describeContent()}
          <div class="inspector-content">
            <div class="section-title">
              <h3>Scene AI</h3>
              <span>Optional · on demand</span>
            </div>
            <p class="help">
              Ask for a short description of what the rendered scene shows. This
              is not navigation, obstacle detection, or a safety guarantee. The
              real-time hazard loop does not depend on it.
            </p>
            <p class="capture-source" data-testid="describe-source">
              Source: rendered World head view · 640 × 480 JPEG. Even in HITL,
              this is not the physical camera feed.
            </p>
            <button
              class="apply-button"
              type="button"
              data-testid="describe-scene"
              disabled={!connected || !describeEnabled || describePending}
              onclick={requestDescribe}
              >{describePending
                ? "Waiting for Scene AI…"
                : "What's ahead?"}<span>↗</span></button
            >
            {#if !connected || !describeEnabled}
              <p class="help" data-testid="describe-setup">
                {!connected
                  ? "Connect a bridge first. Scene AI requires an explicit Describe capability in its live hello."
                  : "This bridge has not enabled Describe. Configure the service server-side, restart the bridge, then reconnect."}
                <a href="#/professionals/api">API &amp; provider setup</a>.
                Provider keys never belong in the browser.
              </p>
            {:else}
              <p class="help">
                The bridge advertises Describe. Provider availability is only
                established by a real response.
                <a href="#/professionals/api">API &amp; provider setup</a>
              </p>
            {/if}
            {#if describePending}
              <p class="help" role="status" data-testid="describe-pending">
                Capturing the rendered World view and requesting the bridge
                service.
              </p>
            {:else if describeResult}
              <div class="describe-result" data-testid="describe-result">
                {#if describeResult.ok}
                  <p class="describe-answer" role="status">
                    {describeResult.text}
                  </p>
                {:else}
                  <p class="profile-error" role="alert">
                    {describeResult.error}
                  </p>
                {/if}
                {#if describeResult.backend || describeResult.model || describeResult.silicon || describeResult.timings}
                  <dl class="describe-metadata" data-testid="describe-metadata">
                    {#if describeResult.backend}<div>
                        <dt>Backend</dt>
                        <dd>{describeResult.backend}</dd>
                      </div>{/if}
                    {#if describeResult.model}<div>
                        <dt>Model</dt>
                        <dd>{describeResult.model}</dd>
                      </div>{/if}
                    {#if describeResult.silicon}<div>
                        <dt>Silicon</dt>
                        <dd>{describeResult.silicon}</dd>
                      </div>{/if}
                    {#each Object.entries(describeResult.timings ?? {}) as [name, ms]}
                      <div>
                        <dt>{name.replaceAll("_", " ")}</dt>
                        <dd>{Math.round(ms)} ms</dd>
                      </div>
                    {/each}
                  </dl>
                {/if}
              </div>
            {/if}
          </div>
        {/snippet}
        <Tabs
          bind:value={activeTab}
          variant="line"
          tabs={[
            { value: "session", label: "Session", content: sessionContent },
            {
              value: "profile",
              label: "Live profile",
              content: profileContent,
            },
            { value: "target", label: "HITL / Target", content: targetContent },
            { value: "describe", label: "Scene AI", content: describeContent },
          ]}
        />
      </aside>
    </div>

    <div class="telemetry-rail" data-testid="telemetry-rail">
      <div class="state-cell">
        <span class="rail-label">Phone state</span><strong
          class:warning={systemState === "UNAVAILABLE" ||
            systemState === "DEGRADED"}
          >{connected ? systemState : "OFFLINE"}</strong
        ><small title={reason}
          >{reason ||
            (connected ? `${engine} engine` : "No live telemetry")}</small
        >
      </div>
      <div>
        <span class="rail-label">{isHitl ? "Engine range" : "World range"}</span
        ><strong
          >{isHitl
            ? rangeText(hitl?.range.outMm, hitl?.range.outStatus)
            : rangeText(sensor?.mm, sensor?.status)}</strong
        ><small
          >{isHitl
            ? `source: ${hitl?.range.source ?? "—"}`
            : "One forward ToF · 18° cone"}</small
        >
      </div>
      <div>
        <span class="rail-label">Engine band</span><strong
          class:warning={alert?.band === "NEAR"}>{alert?.band ?? "—"}</strong
        ><small
          >{alert?.distMm != null
            ? `${alert.distMm} mm reported`
            : "No active alert reported"}</small
        >
      </div>
      <div>
        <span class="rail-label">Data freshness</span><strong
          >{sensorAt ? ageText(sensorAt) : "—"}</strong
        ><small
          >World range age · engine {diagnostics.range_age_ms ?? "—"} ms<br
          />{isHitl
            ? `Camera packet ${boardAge(hitl?.cam.ageMs)}`
            : `World camera capture ${cameraFrameAt ? ageText(cameraFrameAt) : "not received"}`}</small
        >
      </div>
      <div>
        <span class="rail-label">Cue / camera boards</span><strong
          class="board-values"
          ><span
            class:board-up={isHitl
              ? !!hitl?.cue.addr &&
                (hitl.cue.ageMs ?? Infinity) + now - hitlAt < 3000
              : boards?.cue === "up"}
            >CUE {isHitl
              ? !hitl?.cue.addr
                ? "MISSING"
                : (hitl.cue.ageMs ?? Infinity) + now - hitlAt < 3000
                  ? "HEARD"
                  : "STALE"
              : (boards?.cue.toUpperCase() ?? "—")}</span
          ><span
            class:board-up={isHitl
              ? !!hitl?.cam.addr &&
                (hitl.cam.ageMs ?? Infinity) + now - hitlAt < 3000
              : boards?.cam === "up"}
            >CAM {isHitl
              ? !hitl?.cam.addr
                ? "MISSING"
                : (hitl.cam.ageMs ?? Infinity) + now - hitlAt < 3000
                  ? "HEARD"
                  : "STALE"
              : (boards?.cam.toUpperCase() ?? "—")}</span
          ></strong
        ><small
          >{isHitl
            ? "Packet discovery · not actuation proof"
            : connected
              ? "Simulated boards · actual engine"
              : "Nothing assumed connected"}</small
        >
      </div>
      <div>
        <span class="rail-label">Last haptic ACK</span><strong
          >{ack?.status ?? "—"}</strong
        ><small
          >{ack
            ? `${ack.haptic} · max ${ack.hapticMax} · ${ack.source === "real" ? "real ACK" : "simulated ACK"}`
            : "Waiting for a board acknowledgement"}<br />Cues / ACKs {diagnostics.cues ??
            "—"} / {diagnostics.acks ?? "—"}</small
        >
      </div>
    </div>

    <section class="event-console" aria-labelledby="event-title">
      <header>
        <div>
          <span class="stage-kicker">Bridge evidence</span>
          <h2 id="event-title">
            Live event stream <span>{log.length} / 80</span>
          </h2>
        </div>
        <div class="console-meta">
          <span
            >{audio
              ? `Phone audio ${audio.glDb}/${audio.grDb} dBFS`
              : "No phone audio reported"}</span
          ><button
            type="button"
            disabled={!log.length}
            onclick={() => (log = [])}>Clear view</button
          >
        </div>
      </header>
      <div class="event-lines" data-testid="event-log">
        {#each log as entry (entry.id)}<div class="event-row">
            <time>{entry.at}</time><span
              class="event-type"
              class:event-error={entry.type === "profile" &&
                entry.text.startsWith("rejected")}>{entry.type}</span
            >
            <p>{entry.text}</p>
          </div>{:else}<div class="empty-log">
            <span>∅</span>
            <p>
              No bridge events yet. Nothing here is generated as demonstration
              data.
            </p>
          </div>{/each}
      </div>
      <footer>
        <span
          >{connected
            ? `${phone} · ${engine} · ${isHitl ? "physical-board relay" : net || "network pending"}`
            : "Browser disconnected · bridge and phone engine may still be running"}</span
        ><span>Profile → engine → wire · ACK ≠ motor onset</span>
      </footer>
    </section>
  {/if}
</section>

<style>
  .engineer-workspace {
    max-width: 1720px;
    margin: 0 auto;
    padding: 76px clamp(16px, 4vw, 64px) 88px;
    color: var(--ink);
  }
  .workspace-header {
    text-align: center;
    max-width: 760px;
    margin: 0 auto 48px;
  }
  .eyebrow {
    color: var(--muted);
    font-size: 14px;
    font-weight: 600;
    margin: 0 0 17px;
  }
  h1 {
    font-size: clamp(40px, 5.6vw, 76px);
    font-weight: 600;
    line-height: 1.04;
    letter-spacing: -0.065em;
    margin: 0 0 23px;
    text-wrap: balance;
  }
  .intro {
    max-width: 590px;
    margin: auto;
    font-size: clamp(16px, 1.6vw, 20px);
    line-height: 1.55;
    color: var(--muted);
    text-wrap: pretty;
  }
  .onboarding-status {
    margin: 16px auto 0;
    max-width: 620px;
    font-size: 12px;
    line-height: 1.7;
    color: var(--muted);
    overflow-wrap: anywhere;
  }
  .onboarding-status.configuration-failed {
    color: var(--red);
  }
  button,
  input,
  select {
    font: inherit;
  }
  button {
    cursor: pointer;
    transition:
      background 160ms,
      border-color 160ms,
      box-shadow 160ms;
  }
  button:disabled {
    cursor: not-allowed;
    opacity: 0.45;
  }
  button:focus-visible,
  input:focus-visible,
  select:focus-visible,
  summary:focus-visible {
    outline: 3px solid var(--blue);
    outline-offset: 4px;
  }
  .connection-bar {
    max-width: 960px;
    margin: 0 auto;
  }
  .endpoint-presets {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 18px;
  }
  .endpoint-presets button {
    display: flex;
    flex-direction: column;
    align-items: flex-start;
    text-align: left;
    padding: 26px 28px;
    border-radius: 22px;
    border: 1px solid var(--line);
    background: var(--surface);
    color: var(--ink);
    box-shadow: 0 4px 20px #00000003;
    min-width: 0;
  }
  .endpoint-presets button:hover {
    border-color: color-mix(in srgb, var(--ink) 28%, var(--line));
  }
  .endpoint-presets button.chosen {
    border-color: var(--blue);
    box-shadow:
      0 0 0 1px var(--blue),
      var(--soft-shadow, 0 12px 36px #00000006);
  }
  .mode-topline {
    display: flex;
    justify-content: space-between;
    align-items: center;
    width: 100%;
    font-size: 11px;
    color: var(--muted);
    font-weight: 600;
    letter-spacing: 0.04em;
    margin-bottom: 24px;
  }
  .selection-mark {
    width: 19px;
    height: 19px;
    border: 1px solid var(--line);
    border-radius: 50%;
    box-sizing: border-box;
  }
  .chosen .selection-mark {
    background: var(--blue);
    border-color: var(--blue);
    box-shadow: inset 0 0 0 5px var(--surface);
  }
  .endpoint-presets strong {
    font-size: clamp(20px, 2vw, 25px);
    font-weight: 600;
    letter-spacing: -0.035em;
    margin-bottom: 12px;
  }
  .mode-description {
    font-size: 14px;
    line-height: 1.65;
    color: var(--muted);
  }
  .mode-detail {
    margin-top: 22px;
    font-size: 12px;
    line-height: 1.6;
    color: var(--muted);
  }
  .connection-actions {
    display: flex;
    align-items: center;
    gap: 24px;
    margin: 24px 0 14px;
  }
  .endpoint {
    flex: 1;
    min-width: 0;
  }
  .endpoint > span {
    display: block;
    color: var(--muted);
    font-size: 11px;
    margin: 0 0 6px 2px;
  }
  .endpoint input {
    width: 100%;
    box-sizing: border-box;
    padding: 10px 12px;
    border: 1px solid var(--line);
    border-radius: 10px;
    color: var(--ink);
    background: color-mix(in srgb, var(--surface) 65%, transparent);
    font: 12px var(--mono);
    min-height: 42px;
  }
  .connect-button {
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 28px;
    min-height: 46px;
    padding: 12px 24px;
    border: 0;
    border-radius: 999px;
    color: var(--on-accent);
    background: var(--blue);
    font-size: 14px;
    font-weight: 500;
    white-space: nowrap;
  }
  .connect-button:hover {
    filter: brightness(1.06);
  }
  .connect-button.disconnect {
    border: 1px solid var(--line);
    color: var(--ink);
    background: var(--surface);
  }
  .setup-notes {
    color: var(--muted);
    font-size: 12px;
    line-height: 1.8;
    margin-top: 20px;
  }
  .setup-notes summary {
    cursor: pointer;
    width: fit-content;
  }
  .setup-notes p {
    max-width: 760px;
    margin: 12px 0;
  }
  .setup-notes code {
    color: var(--ink);
    font: 11px var(--mono);
  }
  .connection-hint {
    color: var(--muted);
    font-size: 11px;
    line-height: 1.7;
    margin: 12px 0 0;
  }
  .error-banner,
  .profile-error {
    border: 1px solid color-mix(in srgb, var(--red) 30%, var(--line));
    border-radius: 14px;
    background: color-mix(in srgb, var(--red) 5%, var(--surface));
    color: var(--red);
    padding: 16px 20px;
    font-size: 13px;
    line-height: 1.65;
    overflow-wrap: anywhere;
  }
  .error-banner {
    max-width: 920px;
    margin: 22px auto;
  }
  .has-session {
    padding-top: 40px;
  }
  .has-session .workspace-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    gap: 28px;
    max-width: none;
    text-align: left;
    margin-bottom: 26px;
  }
  .has-session .eyebrow {
    font-size: 12px;
    margin-bottom: 10px;
  }
  .has-session h1 {
    font-size: clamp(30px, 3vw, 44px);
    margin-bottom: 10px;
  }
  .has-session .intro {
    font-size: 14px;
    margin: 0;
  }
  .session-label {
    display: flex;
    gap: 12px;
    align-items: center;
    flex-shrink: 0;
  }
  .session-label > span,
  .live-dot {
    width: 7px;
    height: 7px;
    border-radius: 50%;
    background: var(--muted);
  }
  .session-label .online,
  .live-dot.online {
    background: #318666;
    box-shadow: 0 0 0 4px #31866612;
  }
  .session-label strong {
    display: block;
    font-size: 13px;
    font-weight: 500;
  }
  .session-label small {
    display: block;
    color: var(--muted);
    font-size: 11px;
    margin-top: 5px;
  }
  .connection-bar.compact {
    max-width: none;
    margin-bottom: 28px;
  }
  .compact .endpoint-presets {
    display: none;
  }
  .compact .connection-actions {
    margin: 0;
    max-width: 650px;
    gap: 16px;
  }
  .compact .setup-notes {
    margin-top: 10px;
  }
  .compact .connection-hint {
    margin-top: 5px;
  }
  .compact .connect-button {
    align-self: flex-end;
    min-height: 42px;
    padding: 10px 20px;
    font-size: 12px;
  }
  .cockpit {
    display: grid;
    grid-template-columns: minmax(0, 1fr) 352px;
    gap: 22px;
    align-items: start;
  }
  .stage-column,
  .inspector {
    min-width: 0;
    border: 1px solid var(--line);
    border-radius: 22px;
    background: var(--surface);
    box-shadow: var(--soft-shadow, 0 8px 36px #00000005);
    overflow: hidden;
  }
  .stage-header,
  .inspector-heading {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    padding: 18px 22px;
  }
  .stage-kicker {
    font-size: 13px;
    font-weight: 600;
  }
  .truth-tag {
    font-size: 11px;
    color: var(--muted);
  }
  .world-stage {
    position: relative;
    height: clamp(440px, 41vw, 670px);
    background: #e3e9e9;
    overflow: hidden;
    isolation: isolate;
  }
  .world-stage iframe {
    position: absolute;
    inset: 0;
    width: 100%;
    height: 100%;
    border: 0;
  }
  .stage-cover {
    position: absolute;
    inset: 0;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    padding: 30px;
    text-align: center;
    background: color-mix(in srgb, var(--paper) 97%, transparent);
    color: var(--ink);
  }
  .stage-cover .stage-kicker {
    color: var(--muted);
    font-size: 12px;
    margin: 25px 0 15px;
  }
  .stage-cover h2 {
    font-size: clamp(26px, 3vw, 40px);
    font-weight: 600;
    letter-spacing: -0.045em;
    margin: 0 0 16px;
  }
  .stage-cover > p:not(.stage-kicker) {
    color: var(--muted);
    max-width: 400px;
    font-size: 13px;
    line-height: 1.75;
    margin: 0;
  }
  .loading-symbol {
    display: grid;
    place-items: center;
    width: 64px;
    height: 64px;
    border-radius: 20px;
    background: var(--surface);
    border: 1px solid var(--line);
    box-shadow: 0 8px 24px #00000005;
  }
  .loading-symbol span {
    width: 21px;
    height: 21px;
    border: 2px solid var(--line);
    border-top-color: var(--blue);
    border-radius: 50%;
    animation: waiting 1.1s linear infinite;
  }
  @keyframes waiting {
    to {
      transform: rotate(360deg);
    }
  }
  .signal-chain {
    display: flex;
    align-items: center;
    gap: 16px;
    margin-top: 28px;
    font-size: 11px;
    color: var(--muted);
  }
  .signal-chain i {
    font-style: normal;
  }
  .stage-live {
    position: absolute;
    top: 16px;
    left: 16px;
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 8px 12px;
    border: 1px solid #ffffff70;
    border-radius: 999px;
    background: var(--glass, #ffffffe6);
    color: var(--ink);
    backdrop-filter: blur(16px);
    font-size: 11px;
    pointer-events: none;
  }
  .stage-live > span {
    width: 5px;
    height: 5px;
    border-radius: 50%;
    background: #318666;
  }
  .stage-live b {
    font-weight: 500;
    border-left: 1px solid var(--line);
    padding-left: 8px;
    color: var(--muted);
  }
  .transport {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 18px 20px 14px;
  }
  .transport button,
  .secondary-button,
  .console-meta button {
    padding: 10px 13px;
    min-height: 38px;
    border: 1px solid var(--line);
    border-radius: 10px;
    background: transparent;
    color: var(--ink);
    font-size: 12px;
    white-space: nowrap;
  }
  .transport .play-button {
    background: var(--ink);
    border-color: var(--ink);
    color: var(--paper);
    min-width: 82px;
  }
  .timeline {
    display: flex;
    flex-direction: column;
    gap: 8px;
    flex: 1;
    min-width: 32px;
  }
  .timeline-track {
    height: 3px;
    border-radius: 8px;
    background: var(--line);
    overflow: hidden;
  }
  .timeline-track span {
    display: block;
    height: 100%;
    background: var(--blue);
  }
  .timeline > span {
    font-size: 10px;
    font-variant-numeric: tabular-nums;
    color: var(--muted);
    text-align: right;
  }
  .view-strip {
    display: flex;
    align-items: center;
    gap: 14px;
    padding: 0 20px 16px;
  }
  .view-strip > span {
    color: var(--muted);
    font-size: 11px;
  }
  .view-strip .speed {
    margin-left: auto;
    font-variant-numeric: tabular-nums;
  }
  .segmented {
    display: flex;
    gap: 2px;
    background: color-mix(in srgb, var(--ink) 5%, transparent);
    padding: 3px;
    border-radius: 10px;
    min-width: 0;
  }
  .segmented button {
    border: 0;
    border-radius: 7px;
    color: var(--muted);
    background: transparent;
    padding: 8px 12px;
    min-height: 34px;
    font-size: 11px;
    text-transform: capitalize;
  }
  .segmented button[aria-pressed="true"] {
    color: var(--ink);
    background: var(--surface);
    box-shadow: 0 1px 5px #0000000d;
  }
  .stage-footnote {
    padding: 0 22px 20px;
    color: var(--muted);
    font-size: 11px;
    line-height: 1.7;
    margin: 0;
    max-width: 740px;
  }
  .inspector-heading {
    padding-bottom: 15px;
  }
  .inspector-content {
    padding: 22px;
    overflow-wrap: anywhere;
  }
  .inspector :global(.br-tabs__list) {
    display: flex;
    flex-wrap: wrap;
    gap: 2px;
    border: 0;
    margin: 0 12px;
    padding: 4px;
    background: color-mix(in srgb, var(--ink) 5%, transparent);
    border-radius: 11px;
  }
  .inspector :global(.br-tab) {
    flex: 1 1 110px;
    border: 0;
    border-radius: 8px;
    padding: 10px 6px;
    font-size: 11px;
    min-width: 0;
    white-space: normal;
    overflow-wrap: anywhere;
    color: var(--muted);
  }
  .inspector :global(.br-tab[aria-selected="true"]) {
    background: var(--surface);
    color: var(--ink);
    box-shadow: 0 1px 5px #0000000d;
  }
  .inspector :global(.br-tabs__panel) {
    max-height: 745px;
    overflow-x: hidden;
    overflow-y: auto;
    scrollbar-width: thin;
  }
  .field-label {
    display: block;
    font-size: 12px;
    color: var(--ink);
    font-weight: 500;
    margin-bottom: 9px;
  }
  select {
    box-sizing: border-box;
    width: 100%;
    max-width: 100%;
    padding: 12px;
    color: var(--ink);
    background: var(--paper);
    border: 1px solid var(--line);
    border-radius: 10px;
    font-size: 12px;
    margin-bottom: 12px;
    min-height: 42px;
  }
  select:disabled,
  input:disabled {
    opacity: 0.5;
  }
  .scenario-summary,
  .help {
    color: var(--muted);
    font-size: 12px;
    line-height: 1.75;
    margin: 0 0 16px;
  }
  .help {
    font-size: 11px;
    margin: 10px 0 16px;
  }
  .capture-source,
  .describe-answer {
    font-size: 12px;
    line-height: 1.75;
    margin: 12px 0;
  }
  .capture-source {
    padding: 10px 12px;
    border-left: 2px solid var(--line);
    color: var(--muted);
    font-size: 11px;
  }
  .describe-answer {
    white-space: pre-wrap;
  }
  .describe-metadata {
    margin: 16px 0 0;
    font-size: 10px;
    line-height: 1.7;
    color: var(--muted);
  }
  .describe-metadata > div {
    display: flex;
    flex-wrap: wrap;
    gap: 2px 10px;
    padding: 5px 0;
    border-top: 1px solid var(--line);
  }
  .describe-metadata dt {
    flex: 1 1 110px;
  }
  .describe-metadata dd {
    margin: 0;
    max-width: 100%;
  }
  .expectation {
    border-left: 2px solid color-mix(in srgb, var(--blue) 40%, var(--line));
    padding-left: 14px;
  }
  .expectation > span,
  .source-readout > span,
  .confirmed-profile > span {
    color: var(--muted);
    font-size: 11px;
  }
  .expectation p {
    font-size: 12px;
    line-height: 1.7;
    margin: 7px 0 0;
  }
  .inspector-divider {
    border-top: 1px solid var(--line);
    margin: 26px 0 22px;
  }
  .section-title {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
    margin-bottom: 10px;
  }
  .section-title h3 {
    font-size: 14px;
    font-weight: 600;
    margin: 0;
    letter-spacing: -0.025em;
  }
  .section-title > span {
    color: var(--muted);
    font-size: 10px;
    white-space: nowrap;
  }
  .toggle-list {
    display: flex;
    flex-direction: column;
    margin: 12px 0;
  }
  .toggle-list label {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 15px;
    padding: 12px 0;
    min-height: 24px;
    border-bottom: 1px solid color-mix(in srgb, var(--line) 65%, transparent);
    font-size: 12px;
    cursor: pointer;
  }
  input[type="checkbox"] {
    appearance: none;
    position: relative;
    width: 34px;
    height: 21px;
    flex-shrink: 0;
    border: 1px solid var(--line);
    border-radius: 99px;
    background: color-mix(in srgb, var(--ink) 9%, var(--paper));
    cursor: pointer;
    transition: background 160ms;
  }
  input[type="checkbox"]::before {
    content: "";
    position: absolute;
    width: 15px;
    height: 15px;
    top: 2px;
    left: 2px;
    border-radius: 50%;
    background: var(--surface);
    box-shadow: 0 1px 3px #00000020;
    transition: transform 160ms;
  }
  input[type="checkbox"]:checked {
    background: var(--blue);
    border-color: var(--blue);
  }
  input[type="checkbox"]:checked::before {
    transform: translateX(13px);
    background: var(--on-accent);
  }
  fieldset {
    border: 0;
    padding: 0;
    margin: 0;
    min-width: 0;
  }
  fieldset:disabled {
    opacity: 0.65;
  }
  .secondary-button {
    width: 100%;
    margin-top: 9px;
  }
  .secondary-button:not(:disabled):hover,
  .transport button:not(:disabled):not(.play-button):hover {
    background: var(--paper);
  }
  .range-heading {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
    margin: 22px 0 10px;
  }
  .range-heading .field-label {
    margin: 0;
  }
  output {
    font-size: 20px;
    font-weight: 500;
    letter-spacing: -0.04em;
    font-variant-numeric: tabular-nums;
  }
  output small {
    color: var(--muted);
    font-size: 10px;
    letter-spacing: 0;
  }
  input[type="range"] {
    appearance: auto;
    width: 100%;
    accent-color: var(--blue);
    background: transparent;
    height: 20px;
    margin: 3px 0 8px;
    cursor: pointer;
  }
  .apply-button {
    display: flex;
    justify-content: space-between;
    align-items: center;
    width: 100%;
    min-height: 44px;
    background: var(--blue);
    color: var(--on-accent);
    border: 0;
    border-radius: 11px;
    padding: 13px 15px;
    font-size: 12px;
    margin: 20px 0;
  }
  .profile-error {
    font-size: 12px;
    padding: 12px;
  }
  .confirmed-profile,
  .source-readout {
    padding: 19px 0 0;
    border-top: 1px solid var(--line);
    margin-top: 20px;
  }
  .confirmed-profile strong,
  .source-readout strong {
    display: block;
    font-size: 17px;
    letter-spacing: -0.025em;
    font-weight: 500;
    margin: 11px 0 8px;
  }
  .confirmed-profile p,
  .source-readout small {
    color: var(--muted);
    font-size: 11px;
    line-height: 1.9;
    margin: 0;
  }
  .source-readout {
    margin: 4px 0 19px;
  }
  .source-readout small {
    display: block;
  }
  .dimension-fields {
    display: flex;
    gap: 12px;
    margin: 12px 0 18px;
  }
  .dimension-fields label {
    flex: 1;
    min-width: 0;
    font-size: 11px;
    color: var(--muted);
  }
  .dimension-fields input {
    width: 100%;
    box-sizing: border-box;
    color: var(--ink);
    background: var(--paper);
    border: 1px solid var(--line);
    border-radius: 9px;
    padding: 10px;
    min-height: 40px;
    font-size: 12px;
    margin-top: 7px;
  }
  .mono-detail {
    color: var(--muted);
    font: 10px var(--mono);
    overflow-wrap: anywhere;
  }
  .hardware-card {
    padding: 15px 0;
    border-bottom: 1px solid var(--line);
  }
  .hardware-card strong {
    display: block;
    font-size: 12px;
    margin-bottom: 8px;
  }
  .hardware-card > span {
    display: block;
    font-size: 12px;
    color: var(--blue);
    overflow-wrap: anywhere;
  }
  .hardware-card > span.missing {
    color: var(--muted);
  }
  .hardware-card small {
    display: block;
    color: var(--muted);
    font-size: 10px;
    line-height: 1.9;
    margin-top: 7px;
  }
  .camera-preview {
    background: var(--paper);
    color: var(--muted);
    border: 1px solid var(--line);
    border-radius: 12px;
    overflow: hidden;
    margin-top: 15px;
  }
  .camera-preview img {
    display: block;
    width: 100%;
    aspect-ratio: 4/3;
    object-fit: contain;
  }
  .camera-preview p {
    font-size: 12px;
    line-height: 1.8;
    text-align: center;
    padding: 26px 15px;
  }
  .camera-preview > span {
    display: block;
    border-top: 1px solid var(--line);
    padding: 10px 12px;
    font-size: 10px;
  }
  .telemetry-rail {
    display: grid;
    grid-template-columns: repeat(6, minmax(0, 1fr));
    margin-top: 26px;
    padding: 6px 0;
    border-top: 1px solid var(--line);
    border-bottom: 1px solid var(--line);
  }
  .telemetry-rail > div {
    min-width: 0;
    padding: 20px 17px;
  }
  .telemetry-rail > div:first-child {
    padding-left: 0;
  }
  .rail-label {
    display: block;
    color: var(--muted);
    font-size: 11px;
    margin-bottom: 12px;
  }
  .telemetry-rail strong {
    display: block;
    font-size: clamp(14px, 1.3vw, 19px);
    font-weight: 500;
    letter-spacing: -0.035em;
    overflow-wrap: anywhere;
  }
  .telemetry-rail small {
    display: block;
    color: var(--muted);
    font-size: 10px;
    line-height: 1.75;
    margin-top: 10px;
    overflow-wrap: anywhere;
  }
  .telemetry-rail strong.warning {
    color: var(--red);
  }
  .telemetry-rail .board-values {
    display: flex;
    flex-wrap: wrap;
    gap: 5px 12px;
    color: var(--muted);
    font-size: 11px;
    line-height: 1.6;
    letter-spacing: 0;
  }
  .board-up {
    color: #318666;
  }
  .event-console {
    margin-top: 32px;
    border: 1px solid var(--line);
    border-radius: 18px;
    background: var(--surface);
    overflow: hidden;
  }
  .event-console > header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 18px;
    padding: 22px;
  }
  .event-console .stage-kicker {
    color: var(--muted);
    font-size: 11px;
    font-weight: 400;
  }
  .event-console h2 {
    font-size: 19px;
    font-weight: 500;
    letter-spacing: -0.03em;
    margin: 8px 0 0;
  }
  .event-console h2 span {
    color: var(--muted);
    font-size: 10px;
    margin-left: 10px;
    letter-spacing: 0;
    font-variant-numeric: tabular-nums;
  }
  .console-meta {
    display: flex;
    align-items: center;
    gap: 18px;
    color: var(--muted);
    font-size: 11px;
  }
  .console-meta button {
    font-size: 11px;
  }
  .event-lines {
    height: 185px;
    overflow-y: auto;
    scrollbar-width: thin;
    border-top: 1px solid var(--line);
    background: color-mix(in srgb, var(--paper) 55%, var(--surface));
  }
  .event-row {
    display: grid;
    grid-template-columns: 75px 62px minmax(0, 1fr);
    align-items: baseline;
    gap: 12px;
    padding: 10px 22px;
    border-bottom: 1px solid color-mix(in srgb, var(--line) 55%, transparent);
    font: 10px/1.8 var(--mono);
  }
  .event-row time {
    color: var(--muted);
    font-size: 9px;
  }
  .event-type {
    color: var(--blue);
  }
  .event-type.event-error {
    color: var(--red);
  }
  .event-row p {
    margin: 0;
    overflow-wrap: anywhere;
  }
  .empty-log {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 8px;
    height: 100%;
    color: var(--muted);
  }
  .empty-log > span {
    font-size: 23px;
    opacity: 0.5;
  }
  .empty-log p {
    font-size: 12px;
    line-height: 1.7;
    text-align: center;
    margin: 0 20px;
  }
  .event-console footer {
    display: flex;
    justify-content: space-between;
    gap: 16px;
    padding: 14px 22px;
    color: var(--muted);
    font-size: 10px;
    line-height: 1.7;
    border-top: 1px solid var(--line);
    overflow-wrap: anywhere;
  }
  @media (max-width: 1200px) {
    .cockpit {
      grid-template-columns: minmax(0, 1fr) 320px;
      gap: 16px;
    }
    .stage-header {
      flex-wrap: wrap;
      gap: 6px;
    }
    .transport {
      flex-wrap: wrap;
      gap: 8px;
      padding: 15px;
    }
    .transport button {
      padding: 9px 10px;
      font-size: 11px;
    }
    .view-strip {
      flex-wrap: wrap;
      gap: 10px;
      padding: 0 15px 15px;
    }
    .view-strip .speed {
      display: none;
    }
    .inspector-content {
      padding: 20px;
    }
    .telemetry-rail {
      grid-template-columns: repeat(3, minmax(0, 1fr));
    }
    .telemetry-rail > div {
      padding: 18px 15px;
    }
    .telemetry-rail > div:nth-child(3n + 1) {
      padding-left: 0;
    }
    .telemetry-rail > div:nth-child(-n + 3) {
      border-bottom: 1px solid var(--line);
    }
  }
  @media (max-width: 900px) {
    .engineer-workspace {
      padding-top: 52px;
    }
    .has-session {
      padding-top: 30px;
    }
    .cockpit {
      grid-template-columns: minmax(0, 1fr);
    }
    .world-stage {
      height: 500px;
    }
    .inspector :global(.br-tabs__panel) {
      max-height: none;
    }
    .inspector-content {
      max-width: 620px;
      margin: auto;
      padding: 26px;
    }
    .session-label {
      display: none;
    }
    .console-meta > span {
      display: none;
    }
    .event-console footer {
      flex-direction: column;
      gap: 6px;
    }
  }
  @media (max-width: 600px) {
    .engineer-workspace {
      padding: 44px 16px 60px;
    }
    .workspace-header {
      margin-bottom: 34px;
    }
    h1 {
      margin-bottom: 18px;
    }
    .eyebrow {
      font-size: 12px;
    }
    .endpoint-presets {
      grid-template-columns: minmax(0, 1fr);
      gap: 14px;
    }
    .endpoint-presets button {
      padding: 22px;
      border-radius: 18px;
    }
    .mode-topline {
      margin-bottom: 15px;
    }
    .endpoint-presets strong {
      font-size: 23px;
    }
    .mode-detail {
      margin-top: 14px;
    }
    .connection-actions {
      flex-direction: column;
      align-items: stretch;
      gap: 16px;
    }
    .connect-button {
      width: 100%;
      box-sizing: border-box;
    }
    .has-session .workspace-header {
      margin-bottom: 24px;
    }
    .compact .connection-actions {
      flex-direction: row;
      align-items: flex-end;
      gap: 10px;
    }
    .compact .connect-button {
      width: auto;
      padding: 10px 13px;
      gap: 12px;
    }
    .compact .endpoint input {
      font-size: 10px;
      padding: 10px 8px;
    }
    .stage-column,
    .inspector {
      border-radius: 16px;
    }
    .stage-header {
      padding: 16px;
    }
    .truth-tag {
      font-size: 10px;
    }
    .world-stage {
      height: 420px;
    }
    .stage-cover {
      padding: 22px;
    }
    .stage-cover h2 {
      font-size: 28px;
    }
    .transport {
      gap: 8px;
      padding: 14px 12px;
    }
    .transport .timeline {
      order: 4;
      flex-basis: 100%;
      padding-top: 4px;
    }
    .transport .play-button {
      min-width: 72px;
    }
    .transport button {
      flex: 1;
      padding: 9px 8px;
    }
    .view-strip {
      padding: 0 12px 15px;
      gap: 8px;
    }
    .view-strip > span:first-child {
      display: none;
    }
    .segmented {
      width: 100%;
    }
    .segmented button {
      flex: 1;
      min-width: 0;
      padding: 8px 4px;
    }
    .stage-footnote {
      padding: 0 15px 18px;
      font-size: 10px;
    }
    .inspector-content {
      padding: 22px 18px;
    }
    .telemetry-rail {
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }
    .telemetry-rail > div {
      padding: 18px 12px;
      border-bottom: 1px solid var(--line);
    }
    .telemetry-rail > div:nth-child(3n + 1) {
      padding-left: 12px;
    }
    .telemetry-rail > div:nth-child(2n + 1) {
      padding-left: 0;
    }
    .telemetry-rail > div:nth-last-child(-n + 2) {
      border-bottom: 0;
    }
    .rail-label {
      font-size: 10px;
    }
    .telemetry-rail strong {
      font-size: 16px;
    }
    .event-console > header {
      padding: 18px 14px;
      gap: 10px;
    }
    .event-console h2 {
      font-size: 17px;
    }
    .event-console h2 span {
      margin-left: 4px;
      font-size: 9px;
    }
    .console-meta button {
      padding: 8px;
      font-size: 10px;
    }
    .event-row {
      grid-template-columns: 60px 48px minmax(0, 1fr);
      padding: 10px 12px;
      gap: 7px;
      font-size: 9px;
    }
    .event-row time {
      font-size: 8px;
    }
    .event-console footer {
      padding: 14px;
    }
  }
  @media (prefers-reduced-motion: reduce) {
    button,
    input[type="checkbox"],
    input[type="checkbox"]::before {
      transition: none;
    }
    .loading-symbol span {
      animation: none;
    }
  }
</style>
