/**
 * Determine World HUD: a DOM/SVG/canvas overlay over the full-bleed 3-D canvas. It mirrors what the real phone
 * engine reports through the bridge and drives the world and link through the Bus; it never decides a hazard and
 * never shows an alert the engine did not send. When the link is down or the engine has exited it says so.
 *
 * Layout: top bar (brand, course transport, camera), left column (course card, Describe, Experiments), right column
 * (phone panel with the single-zone range gauge), bottom strip (the two-stave score: headset sound, board vibration). The centre of the frame — where the wearer is — stays clear.
 */
import "./hud.css";
import type {
  Band, BoardName, Bus, CameraMode, CourseInfo, CreateHud, DescribeResult, EngineTuning, FromBridge, SensorFrame,
  SystemState,
} from "../contract";
import { el, setText } from "./dom";
import { createGauge } from "./gauge";
import { Score } from "./score";

type Hello = Extract<FromBridge, { t: "hello" }>;
type BridgeStatus = Extract<FromBridge, { t: "bridge" }>;
type Alert = NonNullable<Extract<FromBridge, { t: "alert" }>["alert"]>;
type CueTx = Extract<FromBridge, { t: "cueTx" }>;
type PhoneAudio = Extract<FromBridge, { t: "audio" }>;
type Played = Extract<FromBridge, { t: "played" }>;

const CAMERA_MODES: { mode: CameraMode; label: string; key: string }[] = [
  { mode: "director", label: "Director", key: "1" },
  { mode: "wearer", label: "Wearer", key: "2" },
  { mode: "orbit", label: "Orbit", key: "3" },
  { mode: "plan", label: "Plan", key: "4" },
];

const STATE_NOTE: Record<SystemState, string> = {
  READY: "Warnings active. Silence means nothing to report — never “path clear”.",
  DEGRADED: "Warnings continue with reduced input. Treat silence with extra caution.",
  UNAVAILABLE: "System off: no hazard warnings. The phone announces it on the headset; the cue board vibrates LSS.",
  STARTING: "Waiting for both boards and clock sync. No warnings yet.",
  PAUSED: "Paused by the wearer. No warnings are produced.",
};

/** `flag` rows are plain yes/no; `health` rows flag "false" as a fault. */
const DIAG_ROWS: { label: string; keys: string[]; unit: string; kind?: "flag" | "health" }[] = [
  { label: "Engine’s last RANGE", keys: ["range_mm", "range_status"], unit: "" },
  { label: "Range age", keys: ["range_age_ms"], unit: "ms" },
  { label: "Closing speed", keys: ["closing_mm_s"], unit: "mm/s" },
  { label: "Approach escalation active", keys: ["approach"], unit: "", kind: "flag" },
  { label: "Explore pulses active", keys: ["exploring"], unit: "", kind: "flag" },
  { label: "Capture → cue p50 / p95", keys: ["cap2cue_p50", "cap2cue_p95"], unit: "ms" },
  { label: "Cue → ACK p50 / p95", keys: ["ack_p50", "ack_p95"], unit: "ms" },
  { label: "Clock synced cue / cam", keys: ["cue_synced", "cam_synced"], unit: "", kind: "health" },
  { label: "Cues / ACKs", keys: ["cues", "acks"], unit: "" },
  { label: "NACKs / missed ACKs", keys: ["nacks", "missed"], unit: "" },
];

function tokenText(token: string): string {
  return token.replace(/_/g, " ");
}

function diagValue(raw: string | undefined, kind: "flag" | "health" | undefined): string {
  if (raw === undefined || raw === "-" || raw === "") return "—";
  if (raw === "true") return "yes";
  if (raw === "false") return kind === "health" ? "NO" : "no";
  const n = Number(raw);
  if (!Number.isFinite(n)) return raw;
  return Number.isInteger(n) ? String(n) : n.toFixed(1);
}

function kbd(key: string): HTMLElement {
  return el("kbd", { "aria-hidden": "true" }, key);
}

export const createHud: CreateHud = (root: HTMLElement, bus: Bus) => {
  root.classList.add("hud");

  // ------------------------------------------------------------------ live region
  const live = el("div", { class: "sr-only", "aria-live": "polite", "aria-atomic": "true" });
  let liveTimer: number | undefined;
  function announce(text: string): void {
    window.clearTimeout(liveTimer);
    live.textContent = "";
    liveTimer = window.setTimeout(() => (live.textContent = text), 60);
  }

  // ------------------------------------------------------------------ top bar
  const prevBtn = el("button", { type: "button", class: "btn icon", "aria-label": "Previous course (Left arrow)" }, "‹");
  const courseSelect = el("select", { class: "course-select", "aria-label": "Course" });
  const nextBtn = el("button", { type: "button", class: "btn icon", "aria-label": "Next course (Right arrow)" }, "›");
  const playLabel = el("span", {}, "Play");
  const playBtn = el("button", { type: "button", class: "btn primary", "aria-keyshortcuts": "Space" }, playLabel, kbd("Space"));
  const restartBtn = el("button", { type: "button", class: "btn", "aria-keyshortcuts": "R" }, "Restart course", kbd("R"));
  const camButtons = new Map<CameraMode, HTMLButtonElement>();
  const camGroup = el("div", { class: "segmented", role: "group", "aria-label": "Camera" });
  for (const c of CAMERA_MODES) {
    const b = el("button", { type: "button", class: "seg", "aria-pressed": "false", "aria-keyshortcuts": c.key }, c.label, kbd(c.key));
    b.addEventListener("click", () => bus.emit("setCameraMode", { mode: c.mode }));
    camButtons.set(c.mode, b);
    camGroup.append(b);
  }
  const muteLabel = el("span", {}, "Sound on");
  const muteBtn = el("button", { type: "button", class: "btn", "aria-pressed": "false", "aria-keyshortcuts": "M" }, muteLabel, kbd("M"));

  const top = el("header", { class: "top" },
    el("div", { class: "brand" },
      el("h1", {}, "Determine World"),
      el("p", {}, "simulation · real phone engine"),
    ),
    el("nav", { class: "transport", "aria-label": "Course" }, prevBtn, courseSelect, nextBtn, playBtn, restartBtn),
    el("div", { class: "top-right" }, el("span", { class: "label", "aria-hidden": "true" }, "Camera"), camGroup, muteBtn),
  );

  // ------------------------------------------------------------------ course card
  const markBox = el("div", { class: "rehearsal", "aria-hidden": "true" }, "–");
  const courseTitle = el("h2", { class: "course-title" }, "Loading courses…");
  const courseSummary = el("p", { class: "course-summary" });
  const courseExpect = el("span", {});
  const progressFill = el("div", { class: "progress-fill" });
  const progressTicks = el("div", { class: "progress-ticks" });
  const progress = el("div", { class: "progress", role: "progressbar", "aria-label": "Course progress", "aria-valuemin": 0, "aria-valuemax": 100, "aria-valuenow": 0 },
    progressFill, progressTicks);
  const courseTime = el("span", { class: "mono" }, "0.0 / 0.0 s");
  const courseStatus = el("span", { class: "mono muted" }, "");
  const marksList = el("ol", { class: "marks" });
  const courseCard = el("section", { class: "panel course", "aria-label": "Course" },
    el("div", { class: "course-head" }, markBox, courseTitle),
    courseSummary,
    el("p", { class: "expect" }, el("strong", {}, "Expect: "), courseExpect),
    progress,
    el("div", { class: "progress-meta" }, courseTime, courseStatus),
    marksList,
  );

  // ------------------------------------------------------------------ describe panel
  const describeBtn = el("button", { type: "button", class: "btn primary wide", "aria-keyshortcuts": "D" }, "What’s ahead?", kbd("D"));
  const describeNote = el("p", { class: "describe-note" });
  const describeText = el("p", { class: "describe-text" }, "No description requested this session.");
  const describeMeta = el("dl", { class: "kv mono" });
  const describePanel = el("section", { class: "panel describe", "aria-label": "Describe, optional cloud" },
    el("p", { class: "eyebrow" }, "Optional · cloud · never in the hazard loop"),
    describeBtn,
    describeNote,
    describeText,
    describeMeta,
  );

  // ------------------------------------------------------------------ phone panel
  const offlineReasonEl = el("p", {}, "");
  const offlineBox = el("div", { class: "offline", hidden: true },
    el("strong", {}, "ENGINE OFFLINE — no warnings are being produced"),
    offlineReasonEl,
  );

  const stateWord = el("div", { class: "state-word", "data-state": "NONE" }, "NO STATE");
  const stateReason = el("p", { class: "state-reason mono" }, "");
  const stateNote = el("p", { class: "state-note" }, "Waiting for the phone engine’s first STATE line.");

  const bandCells = new Map<Band, HTMLElement>();
  const bandLadder = el("div", { class: "band-ladder", "aria-hidden": "true" });
  for (const b of ["FAR", "MID", "NEAR"] as const) {
    const c = el("span", { class: `band band-${b.toLowerCase()}` }, b);
    bandCells.set(b, c);
    bandLadder.append(c);
  }
  const alertLabel = el("span", { class: "alert-label" }, "Ahead");
  const alertDist = el("span", { class: "alert-dist mono" }, "");
  const alertEmptyHead = el("strong", {}, "No alert");
  const alertEmptyTail = el("span", {}, " — not a clear path.");
  const alertEmpty = el("p", { class: "alert-empty" }, alertEmptyHead, alertEmptyTail);
  const alertBody = el("div", { class: "alert-body", hidden: true },
    bandLadder, el("div", { class: "alert-line" }, alertLabel, alertDist));
  const alertText = el("p", { class: "sr-only" }, "");

  const gauge = createGauge();
  const gaugeCaption = el("span", {}, "Forward range");
  const gaugeAge = el("span", { class: "mono muted" }, "—");
  const gaugeFigure = el("figure", { class: "tof" },
    el("figcaption", {}, gaugeCaption, gaugeAge),
    gauge.root,
    el("p", { class: "fine gauge-caption" }, "one forward zone · ~18° · cannot tell left from right"),
    el("ul", { class: "legend", "aria-label": "Range gauge legend" },
      el("li", {}, el("i", { class: "sw sw-near" }), "NEAR band (reference)"),
      el("li", {}, el("i", { class: "sw sw-none" }), "NO TARGET — not clear"),
      el("li", {}, el("i", { class: "sw sw-unknown" }), "UNKNOWN — unusable"),
    ),
  );

  const statusRows = {
    link: el("dd", {}, "—"),
    engine: el("dd", {}, "—"),
    cue: el("dd", {}, "—"),
    cam: el("dd", {}, "—"),
    net: el("dd", {}, "—"),
    lastCue: el("dd", { class: "mono" }, "—"),
  };
  const statusList = el("dl", { class: "kv status" },
    el("dt", {}, "Bridge link"), statusRows.link,
    el("dt", {}, "Phone engine"), statusRows.engine,
    el("dt", {}, "Cue board"), statusRows.cue,
    el("dt", {}, "Camera board"), statusRows.cam,
    el("dt", {}, "Network"), statusRows.net,
    el("dt", {}, "Last cue"), statusRows.lastCue,
  );

  function toggle(label: string): HTMLButtonElement {
    return el("button", { type: "button", class: "toggle", "aria-pressed": "false" }, el("i", { class: "box", "aria-hidden": "true" }), label);
  }
  const cueToggle = toggle("Unplug cue board");
  const camToggle = toggle("Unplug camera");
  const netToggle = toggle("Wi-Fi jitter");
  const engineBtn = el("button", { type: "button", class: "btn" }, "Restart engine");
  const controls = el("div", { class: "controls", role: "group", "aria-label": "Fault injection" }, cueToggle, camToggle, netToggle, engineBtn);

  const diagValues = new Map<string, HTMLElement>();
  const diagList = el("dl", { class: "kv mono diag-kv" });
  for (const row of DIAG_ROWS) {
    const dd = el("dd", {}, "—");
    diagValues.set(row.label, dd);
    diagList.append(el("dt", {}, row.label), dd);
  }
  const diag = el("details", { class: "diag", open: true },
    el("summary", {}, "DIAG ", el("span", { class: "muted" }, "· measured on this machine, in simulation")),
    diagList,
    el("p", { class: "fine" }, "Measured on this machine, in simulation: loopback UDP between the board simulator and phone/cli. Not device latency."),
  );

  const phone = el("section", { class: "panel phone", "aria-label": "Phone engine mirror" },
    el("header", { class: "panel-head" }, el("h2", {}, "Phone"), el("span", { class: "mono muted" }, "mirror of the real engine")),
    offlineBox,
    el("div", { class: "block state" }, el("span", { class: "label" }, "System"), stateWord, stateReason, stateNote),
    el("div", { class: "block alert" }, el("span", { class: "label" }, "Engine alert · decided by the phone"), alertEmpty, alertBody, alertText),
    gaugeFigure,
    statusList,
    controls,
    diag,
  );

  // ------------------------------------------------------------------ experiments panel
  const approachToggle = toggle("Approach escalation (TTC)");
  const exploreToggle = toggle("Explore pulse train");
  const experimentsNote = el("p", { class: "fine experiments-note" }, "");
  const experiments = el("section", { class: "panel experiments", "aria-label": "Experimental engine features" },
    el("p", { class: "eyebrow" }, "EXPERIMENTAL · restarts the engine"),
    el("div", { class: "controls", role: "group", "aria-label": "Experimental engine features" }, approachToggle, exploreToggle),
    experimentsNote,
  );

  // ------------------------------------------------------------------ score
  const scoreCanvas = el("canvas", { class: "score-canvas", role: "img", "aria-label": "Score: no cues yet." });
  const score = new Score(scoreCanvas);
  const scoreStrip = el("footer", { class: "score", "aria-label": "Score of played cues" },
    el("div", { class: "score-head" },
      el("h2", {}, "Score"),
      el("span", { class: "muted" }, "phone audio and board haptic reports, last 12 s; real-board ACK is acceptance, not measured vibration"),
      el("ul", { class: "score-legend", "aria-label": "Score legend" },
        el("li", {}, el("i", { class: "g g-oval" }), "obstacle"),
        el("li", {}, el("i", { class: "g g-diamond" }), "person"),
        el("li", {}, el("i", { class: "g g-square" }), "system off"),
        el("li", {}, el("i", { class: "g g-gain" }), "ear gain L | R"),
        el("li", {}, el("i", { class: "g g-x" }), "not applied"),
        el("li", {}, el("i", { class: "g g-bar" }), "vibration, height = strength"),
        el("li", {}, el("i", { class: "g g-near" }), "NEAR"),
        el("li", { class: "muted" }, "silence is a rest, not “all clear”"),
      ),
    ),
    scoreCanvas,
  );

  // ------------------------------------------------------------------ start overlay
  const startBtn = el("button", { type: "button", class: "btn primary start-btn" }, "Start");
  const overlay = el("div", { class: "start", role: "dialog", "aria-modal": "true", "aria-labelledby": "start-title" },
    el("div", { class: "start-card" },
      el("div", { class: "rehearsal big", "aria-hidden": "true" }, "A"),
      el("h1", { id: "start-title" }, "Determine World"),
      el("p", { class: "start-lede" },
        "A wearer walks a course while one simulated forward time-of-flight reading (VL53L4ED, ~18°, up to 1.2 m) feeds the real phone engine. Browser audio monitors the phone’s audio output; the cue board’s vibration is drawn, not sounded. This does not imply integrated wearable audio hardware."),
      el("p", { class: "fine" }, "Headphones recommended. Determine supplements the cane or guide dog and never replaces them. Silence is not “path clear”. One zone cannot tell left from right, and it cannot see glass reliably, thin wires, drop-offs, fast vehicles or anything beyond about 1.2 m."),
      el("p", { class: "fine mono" }, "Keys: Space play/pause · R restart · 1–4 camera · D what’s ahead · M mute · ←/→ course"),
      startBtn,
    ),
  );

  root.append(live, top, el("div", { class: "left" }, courseCard, describePanel, experiments), phone, scoreStrip, overlay);
  startBtn.focus();

  // ------------------------------------------------------------------ state
  let started = false;
  let courses: CourseInfo[] = [];
  let course: { id: string; tS: number; playing: boolean } | null = null;
  let renderedCourseId = "";
  let courseDirty = true;
  let muted = false;
  let linkUp = false;
  let hello: Hello | null = null;
  let bridgeStatus: BridgeStatus | null = null;
  let sysState: { state: SystemState; reason: string } | null = null;
  let alert: Alert | null = null;
  let lastTx: CueTx | null = null;
  let lastPlayed: Played | null = null;
  let lastAudio: PhoneAudio | null = null;
  /** The tuning last requested here, until the bridge reports it applied (null = nothing pending). */
  let requestedTuning: EngineTuning | null = null;
  let describePending = false;
  let engineRestarting = false;
  /** Starts false so the first renderOffline() opens the score's offline span and announces the real state. */
  let offline = false;
  const desired: { cue: boolean; cam: boolean; jitter: boolean } = { cue: false, cam: false, jitter: false };
  let hostOffset: number | null = null;
  let pendingFrame: SensorFrame | null = null;
  let lastSensorAt = 0;
  const sensorTimes: number[] = [];

  /** Maps the bridge's host clock onto performance.now(): smallest observed offset (network only adds delay). */
  function hostToPerf(hostMs: number): number {
    const sample = performance.now() - hostMs;
    if (hostOffset === null || sample < hostOffset || sample - hostOffset > 1000) hostOffset = sample;
    return hostMs + hostOffset;
  }

  // ------------------------------------------------------------------ renderers
  function renderOffline(): void {
    const reason = !linkUp
      ? "Bridge link down — reconnecting. Nothing reaches the phone engine."
      : bridgeStatus?.engine === "exited"
        ? "The phone engine process has exited. Restart it below."
        : "";
    const now = performance.now();
    const next = reason !== "";
    if (next !== offline) {
      offline = next;
      if (offline) {
        // Never leave a stale alert or state on screen once the engine cannot be heard from.
        alert = null;
        sysState = null;
        score.openSpan("offline", now);
        score.closeSpan("near", now);
        score.closeSpan("unavailable", now);
        announce(`Engine offline. No warnings are being produced. ${reason}`);
        for (const dd of diagValues.values()) setText(dd, "—");
      } else {
        score.closeSpan("offline", now);
        if (bridgeStatus) announce(`Phone engine ${bridgeStatus.engine}.`);
      }
    }
    offlineBox.hidden = !offline;
    setText(offlineReasonEl, reason);
    phone.classList.toggle("is-offline", offline);
    renderState();
    renderAlert();
    renderStatus();
    renderDescribe();
  }

  function renderState(): void {
    if (offline || !sysState) {
      stateWord.dataset.state = "NONE";
      setText(stateWord, offline ? "OFFLINE" : "NO STATE");
      setText(stateReason, "");
      setText(stateNote, offline ? "No engine, no warnings." : "Waiting for the phone engine’s first STATE line.");
      return;
    }
    stateWord.dataset.state = sysState.state;
    setText(stateWord, sysState.state);
    setText(stateReason, sysState.reason && sysState.reason !== "-" ? `reason: ${tokenText(sysState.reason)}` : "");
    setText(stateNote, STATE_NOTE[sysState.state]);
  }

  function renderAlert(): void {
    const a = offline ? null : alert;
    alertBody.hidden = a === null;
    alertEmpty.hidden = a !== null;
    setText(alertEmptyHead, offline ? "No alerts" : "No alert");
    setText(alertEmptyTail, offline ? " — the engine is offline." : " — not a clear path.");
    if (!a) {
      setText(alertText, offline ? "No alerts: engine offline." : "No alert. This does not mean the path is clear.");
      return;
    }
    for (const [b, cell] of bandCells) cell.toggleAttribute("data-on", b === a.band);
    setText(alertLabel, `${a.band} ahead`);
    setText(alertDist, a.distMm === null ? "— mm" : `${a.distMm} mm`);
    const dist = a.distMm === null ? "distance unknown" : `${a.distMm} millimetres`;
    setText(alertText, `Engine alert: ${a.band} ahead, ${dist}.`);
  }

  function boardText(board: BoardName): string {
    const s = bridgeStatus?.boards[board];
    if (!s) return "—";
    return s === "up" ? "up" : "DOWN (unplugged)";
  }

  function renderStatus(): void {
    setText(statusRows.link, linkUp ? "connected" : "DISCONNECTED — reconnecting");
    statusRows.link.dataset.ok = String(linkUp);
    const engine = bridgeStatus?.engine;
    const engineText = engineRestarting
      ? "restarting…"
      : !linkUp || !engine
        ? "unknown"
        : engine === "running"
          ? `running${hello ? ` · ${hello.phone}` : ""}`
          : engine === "external"
            ? `external process${hello ? ` · ${hello.phone}` : ""}`
            : "EXITED";
    setText(statusRows.engine, engineText);
    statusRows.engine.dataset.ok = String(linkUp && (engine === "running" || engine === "external"));
    setText(statusRows.cue, linkUp ? boardText("cue") : "—");
    statusRows.cue.dataset.ok = String(linkUp && bridgeStatus?.boards.cue === "up");
    setText(statusRows.cam, linkUp ? boardText("cam") : "—");
    statusRows.cam.dataset.ok = String(linkUp && bridgeStatus?.boards.cam === "up");
    setText(statusRows.net, linkUp && bridgeStatus ? tokenText(bridgeStatus.net) : "—");

    if (lastAudio || lastTx || lastPlayed) {
      const au = lastAudio ? `headset ${tokenText(lastAudio.cue)} ${tokenText(lastAudio.status)}` : "";
      const tx = lastTx ? `board ${lastTx.haptic} sent` : "";
      const pl = lastPlayed
        ? lastPlayed.source === "real"
          ? `board accepted ${tokenText(lastPlayed.status)} (ACK; physical onset unmeasured)`
          : `vibrated ${tokenText(lastPlayed.status)}${lastPlayed.src === "watchdog" ? " (watchdog)" : ""}, ${(lastPlayed.lateUs / 1000).toFixed(1)} ms late`
        : "";
      setText(statusRows.lastCue, [au, [tx, pl].filter(Boolean).join(" → ")].filter(Boolean).join(" · "));
    }

    const cueDown = bridgeStatus ? bridgeStatus.boards.cue === "down" : desired.cue;
    const camDown = bridgeStatus ? bridgeStatus.boards.cam === "down" : desired.cam;
    const jitter = bridgeStatus ? bridgeStatus.net === "wifi_jitter" : desired.jitter;
    cueToggle.setAttribute("aria-pressed", String(cueDown));
    camToggle.setAttribute("aria-pressed", String(camDown));
    netToggle.setAttribute("aria-pressed", String(jitter));
    for (const b of [cueToggle, camToggle, netToggle]) b.disabled = !linkUp;
    engineBtn.disabled = !linkUp || engineRestarting || hello?.engine === "external";
    engineBtn.title = hello?.engine === "external" ? "The engine runs outside the bridge; restart it there." : "";
    renderExperiments();
  }

  /** Reflects the bridge's ACTUAL tuning (bridge status, else hello), never the one requested here. */
  function renderExperiments(): void {
    const actual: EngineTuning | null | undefined = linkUp ? (bridgeStatus ? bridgeStatus.tuning : hello?.tuning) : undefined;
    const external = hello?.engine === "external" || bridgeStatus?.engine === "external";
    if (requestedTuning && actual && requestedTuning.approach === actual.approach && requestedTuning.explore === actual.explore) {
      requestedTuning = null;
    }
    let note: string;
    if (!linkUp) note = "Unavailable: the bridge link is down.";
    else if (actual === undefined) note = "Waiting for the bridge…";
    else if (actual === null || external) note = "Set on the phone: the engine runs outside the bridge, so these settings are not known or changeable here.";
    else if (requestedTuning) note = "Requested — the engine restarts; showing the bridge’s current setting until it confirms.";
    else note = "Shows the setting the running engine actually uses.";
    const usable = linkUp && !!actual && !external && !engineRestarting;
    for (const [b, on] of [[approachToggle, actual?.approach ?? false], [exploreToggle, actual?.explore ?? false]] as const) {
      b.setAttribute("aria-pressed", String(on));
      b.disabled = !usable;
    }
    setText(experimentsNote, note);
  }

  function renderDescribe(): void {
    let note = "";
    if (!linkUp) note = "Unavailable: the bridge link is down.";
    else if (!hello) note = "Waiting for the bridge…";
    else if (!hello.describe) note = "Unavailable: this bridge was started without a Describe API.";
    describeBtn.disabled = note !== "" || describePending;
    setText(describeNote, describePending ? "Asking… the hazard loop keeps running." : note);
    describeNote.hidden = describeNote.textContent === "";
  }

  function renderDescribeResult(r: DescribeResult & { pending: boolean }): void {
    describePending = r.pending;
    renderDescribe();
    if (r.pending) {
      describeText.classList.add("pending");
      setText(describeText, "Capturing the head camera and asking the Describe API…");
      describeMeta.replaceChildren();
      return;
    }
    describeText.classList.remove("pending");
    describeText.classList.toggle("failed", !r.ok);
    setText(describeText, r.ok && r.text ? `“${r.text}”` : `No description: ${r.error ?? "unknown error"}.`);
    const rows: [string, string][] = [];
    if (r.backend) rows.push(["Backend", r.backend]);
    if (r.silicon) rows.push(["Silicon", r.silicon]);
    if (r.model) rows.push(["Model", r.model]);
    for (const [k, v] of Object.entries(r.timings ?? {})) rows.push([tokenText(k), `${Math.round(v)} ms`]);
    describeMeta.replaceChildren(...rows.flatMap(([k, v]) => [el("dt", {}, k), el("dd", {}, v)]));
    announce(r.ok && r.text ? `Description: ${r.text}` : "Description unavailable.");
  }

  function renderCourses(): void {
    courseSelect.replaceChildren(
      ...courses.map((c, i) => el("option", { value: c.id }, `${String.fromCharCode(65 + i)} · ${c.title}`)),
    );
    if (course) courseSelect.value = course.id;
    courseDirty = true;
  }

  function renderCourse(): void {
    courseDirty = false;
    const cur = course;
    const idx = cur ? courses.findIndex((c) => c.id === cur.id) : -1;
    const info = idx >= 0 ? courses[idx] : null;
    if (!info || !cur) {
      setText(courseTitle, courses.length ? "Choose a course" : "Loading courses…");
      return;
    }
    if (renderedCourseId !== info.id) {
      renderedCourseId = info.id;
      setText(markBox, String.fromCharCode(65 + idx));
      setText(courseTitle, info.title);
      setText(courseSummary, info.summary);
      setText(courseExpect, info.expect);
      courseSelect.value = info.id;
      progressTicks.replaceChildren(
        ...info.marks.map((m, i) =>
          el("span", { class: "tick", style: `left:${Math.min(100, (m.atS / info.durationS) * 100)}%` }, String(i + 1))),
      );
      marksList.replaceChildren(
        ...info.marks.map((m, i) =>
          el("li", {}, el("span", { class: "num" }, String(i + 1)), el("span", { class: "mono t" }, `${m.atS.toFixed(1)} s`), el("span", { class: "lbl" }, m.label))),
      );
    }
    const frac = info.durationS > 0 ? Math.min(1, Math.max(0, cur.tS / info.durationS)) : 0;
    progressFill.style.transform = `scaleX(${frac})`;
    progress.setAttribute("aria-valuenow", String(Math.round(frac * 100)));
    setText(courseTime, `${cur.tS.toFixed(1)} / ${info.durationS.toFixed(1)} s`);
    setText(courseStatus, cur.playing ? "playing" : cur.tS >= info.durationS ? "finished" : "paused");
    marksList.querySelectorAll("li").forEach((li, i) => li.toggleAttribute("data-passed", cur.tS >= info.marks[i].atS));
    progressTicks.querySelectorAll(".tick").forEach((t, i) => t.toggleAttribute("data-passed", cur.tS >= info.marks[i].atS));
    setText(playLabel, cur.playing ? "Pause" : "Play");
  }

  // ------------------------------------------------------------------ actions
  function stepCourse(delta: number): void {
    if (!courses.length) return;
    const idx = Math.max(0, courses.findIndex((c) => c.id === course?.id));
    const next = courses[(idx + delta + courses.length) % courses.length];
    bus.emit("selectCourse", { id: next.id });
  }
  function requestDescribe(): void {
    if (describeBtn.disabled) return;
    bus.emit("describe", {});
  }
  function setMuted(m: boolean): void {
    muted = m;
    muteBtn.setAttribute("aria-pressed", String(m));
    setText(muteLabel, m ? "Muted" : "Sound on");
    bus.emit("setMuted", { muted: m });
    announce(m ? "Cue audio muted." : "Cue audio on.");
  }

  prevBtn.addEventListener("click", () => stepCourse(-1));
  nextBtn.addEventListener("click", () => stepCourse(1));
  courseSelect.addEventListener("change", () => bus.emit("selectCourse", { id: courseSelect.value }));
  playBtn.addEventListener("click", () => bus.emit("play", { playing: !(course?.playing ?? false) }));
  restartBtn.addEventListener("click", () => bus.emit("restart", {}));
  muteBtn.addEventListener("click", () => setMuted(!muted));
  describeBtn.addEventListener("click", requestDescribe);
  cueToggle.addEventListener("click", () => {
    desired.cue = cueToggle.getAttribute("aria-pressed") !== "true";
    bus.emit("setBoard", { board: "cue", down: desired.cue });
  });
  camToggle.addEventListener("click", () => {
    desired.cam = camToggle.getAttribute("aria-pressed") !== "true";
    bus.emit("setBoard", { board: "cam", down: desired.cam });
  });
  netToggle.addEventListener("click", () => {
    desired.jitter = netToggle.getAttribute("aria-pressed") !== "true";
    bus.emit("setNet", { profile: desired.jitter ? "wifi_jitter" : "clean" });
  });
  function requestTuning(change: Partial<EngineTuning>): void {
    const actual = bridgeStatus ? bridgeStatus.tuning : hello?.tuning;
    if (!actual) return;
    requestedTuning = { ...actual, ...change };
    bus.emit("setTuning", requestedTuning);
    announce("Experimental setting requested. The phone engine restarts.");
    renderExperiments();
  }
  approachToggle.addEventListener("click", () => requestTuning({ approach: approachToggle.getAttribute("aria-pressed") !== "true" }));
  exploreToggle.addEventListener("click", () => requestTuning({ explore: exploreToggle.getAttribute("aria-pressed") !== "true" }));
  engineBtn.addEventListener("click", () => {
    engineRestarting = true;
    bus.emit("restartEngine", {});
    announce("Restarting the phone engine.");
    renderStatus();
  });
  startBtn.addEventListener("click", () => {
    started = true;
    overlay.remove();
    bus.emit("play", { playing: true });
    playBtn.focus();
  });

  window.addEventListener("keydown", (e) => {
    if (!started || e.repeat || e.ctrlKey || e.metaKey || e.altKey) return;
    const target = e.target instanceof HTMLElement ? e.target : null;
    const tag = target?.tagName ?? "";
    if (tag === "INPUT" || tag === "TEXTAREA" || target?.isContentEditable) return;
    // A control focused from the keyboard keeps Space for its own activation; after a mouse click it does not.
    const keyboardOnControl = (tag === "BUTTON" || tag === "SELECT" || tag === "SUMMARY") && target?.matches(":focus-visible") === true;
    switch (e.key) {
      case " ":
        if (keyboardOnControl || tag === "SELECT") return;
        bus.emit("play", { playing: !(course?.playing ?? false) });
        break;
      case "r":
      case "R":
        bus.emit("restart", {});
        break;
      case "1":
      case "2":
      case "3":
      case "4":
        bus.emit("setCameraMode", { mode: CAMERA_MODES[Number(e.key) - 1].mode });
        break;
      case "d":
      case "D":
        requestDescribe();
        break;
      case "m":
      case "M":
        setMuted(!muted);
        break;
      case "ArrowLeft":
        if (tag === "SELECT") return;
        stepCourse(-1);
        break;
      case "ArrowRight":
        if (tag === "SELECT") return;
        stepCourse(1);
        break;
      default:
        return;
    }
    e.preventDefault();
  });

  // ------------------------------------------------------------------ bus
  bus.on("courses", (list) => {
    courses = list;
    renderCourses();
  }, true);
  bus.on("course", (c) => {
    const changed = course?.id !== c.id;
    course = c;
    if (changed && courses.length) courseSelect.value = c.id;
    courseDirty = true;
  }, true);
  bus.on("cameraMode", (mode) => {
    for (const [m, b] of camButtons) b.setAttribute("aria-pressed", String(m === mode));
  }, true);
  bus.on("sensor", (f) => {
    pendingFrame = f;
    const now = performance.now();
    lastSensorAt = now;
    sensorTimes.push(now);
    while (sensorTimes.length && sensorTimes[0] < now - 1000) sensorTimes.shift();
  }, true);
  bus.on("link", ({ connected }) => {
    if (connected === linkUp) return;
    linkUp = connected;
    if (!connected) {
      hello = null;
      bridgeStatus = null;
      engineRestarting = false;
    }
    announce(connected ? "Bridge link connected." : "Bridge link lost.");
    renderOffline();
  }, true);
  bus.on("describeResult", renderDescribeResult);

  bus.on("bridge", (msg) => {
    const now = performance.now();
    switch (msg.t) {
      case "hello":
        hello = msg;
        renderOffline();
        break;
      case "bridge":
        bridgeStatus = msg;
        engineRestarting = false;
        renderOffline();
        break;
      case "state": {
        if (offline) break;
        const prev = sysState?.state;
        sysState = { state: msg.state, reason: msg.reason };
        if (prev !== msg.state) {
          score.caption(now, "top", msg.state, tokenText(msg.reason));
          if (msg.state === "UNAVAILABLE") score.openSpan("unavailable", now);
          else score.closeSpan("unavailable", now);
          announce(`System ${msg.state}. ${STATE_NOTE[msg.state]}`);
        }
        renderState();
        break;
      }
      case "alert": {
        if (offline) break;
        const prev = alert;
        alert = msg.alert;
        if (alert?.band === "NEAR") score.openSpan("near", now);
        else score.closeSpan("near", now);
        if ((prev?.band ?? null) !== (alert?.band ?? null)) score.band(now, alert?.band ?? null);
        if (prev?.band !== alert?.band) {
          announce(alert
            ? `Engine alert: ${alert.band} ahead.`
            : "Alert cleared. No alert does not mean the path is clear.");
        }
        renderAlert();
        break;
      }
      case "cueTx":
        lastTx = msg;
        renderStatus();
        break;
      case "audio":
        // The engine clock (`ms`) is not the host clock; the line arrives as the phone plays it, so place it now.
        lastAudio = msg;
        score.sound({ t: now, cue: msg.cue, status: msg.status, glDb: msg.glDb, grDb: msg.grDb });
        renderStatus();
        break;
      case "played":
        // Haptic only: drawn, never sounded (one motor plays max(hl, hr)).
        lastPlayed = msg;
        score.vibration({
          t: hostToPerf(msg.hostMs), status: msg.status, haptic: msg.haptic, level: Math.max(msg.hl, msg.hr),
          watchdog: msg.src === "watchdog",
        });
        renderStatus();
        break;
      case "speak":
        score.caption(now, "bottom", `“${msg.text}”`, "phone speech");
        break;
      case "vibrate":
        score.caption(now, "bottom", "phone", `vibrate ${msg.pattern.join(",")} ms`);
        break;
      case "systemOff":
        score.caption(now, "top", "SYSTEM OFF", "phone alarm: tone + “System off”");
        break;
      case "diag":
        if (offline) break;
        for (const row of DIAG_ROWS) {
          const dd = diagValues.get(row.label);
          if (!dd) continue;
          const vals = row.keys.map((k) => diagValue(msg.kv[k], row.kind));
          const unit = row.unit && vals.some((v) => v !== "—") ? ` ${row.unit}` : "";
          setText(dd, vals.join(" / ") + unit);
          dd.dataset.bad = String(vals.includes("NO"));
        }
        break;
      case "log":
        break;
    }
  });

  // ------------------------------------------------------------------ frame loop
  let lastSlow = 0;
  let lastAria = 0;
  function frame(now: number): void {
    if (courseDirty) renderCourse();
    if (pendingFrame) {
      gauge.update(pendingFrame);
      pendingFrame = null;
    }
    score.frame(now);
    if (now - lastSlow > 250) {
      lastSlow = now;
      while (sensorTimes.length && sensorTimes[0] < now - 1000) sensorTimes.shift();
      const age = lastSensorAt ? now - lastSensorAt : Infinity;
      setText(gaugeCaption, linkUp ? "Forward range · raw reading sent to the phone" : "Forward range · NOT delivered (link down)");
      setText(gaugeAge, Number.isFinite(age) ? `${sensorTimes.length} Hz · ${age < 1000 ? `${Math.round(age)} ms` : `${(age / 1000).toFixed(1)} s`} old` : "no frame");
      gaugeFigure.classList.toggle("stale", !Number.isFinite(age) || age > 500);
    }
    if (now - lastAria > 3000) {
      lastAria = now;
      scoreCanvas.setAttribute("aria-label", score.summary(now));
      gauge.root.setAttribute("aria-label", gauge.describe());
    }
    requestAnimationFrame(frame);
  }
  renderOffline();
  requestAnimationFrame(frame);
};
