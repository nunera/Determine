import type { Bus, EngineTuning, Events, FromBridge, SensorFrame, WearerPose, WorldHandle } from "../contract";
import { el, setText } from "../hud/dom";

export const RECORDER_LIMITS = { completedEpisodes: 3, eventsPerEpisode: 12000, eventCharactersPerEpisode: 4_000_000 } as const;
const LIMITATIONS = {
  truthM: "Nearest distance in the single sensor cone only (up to 4 m); not truth for all visible objects.",
  sensor: "One simulated 18-degree ToF zone; nominal white-target range up to 1.2 m. NO_TARGET does not mean clear path.",
  export: "Not an ADT/VRS export, hardware recording, or validated safety metric.",
} as const;

type TuningFact = { value: EngineTuning | null; provenance: string; observedWallMs: number | null };
type Tuning = { requested: TuningFact; actual: TuningFact };
type Boundary = "recorder_started" | "course_changed" | "time_reset";
export interface EpisodeMetadata {
  schema: "determine.episode/2";
  source: "simulation";
  episodeId: string;
  courseId: string;
  startedWallMs: number;
  startedTS: number;
  boundary: Boundary;
  tuningAtStart: Tuning;
  tuningLatest: Tuning;
  engine: { mode: "spawned" | "external"; provenance: "bridge.hello"; observedWallMs: number | null } | null;
  limits: typeof RECORDER_LIMITS;
  limitations: typeof LIMITATIONS;
}

export interface EpisodeEvent {
  type: "event";
  seq: number;
  wallMs: number;
  tS: number;
  event: string;
  replayed: boolean;
  data: unknown;
}

export interface RecordedEpisode {
  metadata: EpisodeMetadata;
  /** JSON event lines, serialized on receipt so mutable Bus payloads cannot alter history. */
  events: string[];
  eventCharacters: number;
  observedEvents: number;
  droppedEvents: number;
  end: { wallMs: number; tS: number; reason: Boundary } | null;
}

/** Pure: one metadata line, retained event lines, then an explicit retention/completion summary. */
export function serializeEpisodeJsonl(episode: RecordedEpisode): string {
  return [
    JSON.stringify({ type: "metadata", ...episode.metadata }),
    ...episode.events,
    JSON.stringify({ type: "summary", episodeId: episode.metadata.episodeId, completed: episode.end !== null,
      end: episode.end, observedEvents: episode.observedEvents, retainedEvents: episode.events.length,
      droppedEvents: episode.droppedEvents, retention: "newest events; oldest evicted at configured limits" }),
    "",
  ].join("\n");
}

interface Observation<T> { wallMs: number; tS: number; episodeId: string; replayed: boolean; data: T }
export interface SnapshotMetadata {
  schema: "determine.snapshot/1";
  source: "simulation";
  episodeId: string;
  courseId: string;
  tS: number;
  captureStartedWallMs: number;
  captureFinishedWallMs: number;
  courseAtCompletion: Events["course"] | null;
  image: { file: string; mimeType: "image/jpeg"; width: number; height: number; requestedQuality: number };
  sensor: Observation<SensorFrame> | null;
  pose: Observation<WearerPose> | null;
  tuning: Tuning;
  limitations: typeof LIMITATIONS;
  alignment: string;
}

export function serializeSnapshotMetadata(metadata: SnapshotMetadata): string {
  return JSON.stringify(metadata, null, 2) + "\n";
}

function copyTuning(tuning: Tuning): Tuning {
  return {
    requested: { ...tuning.requested, value: tuning.requested.value ? { ...tuning.requested.value } : null },
    actual: { ...tuning.actual, value: tuning.actual.value ? { ...tuning.actual.value } : null },
  };
}

/** Append after createHud; pass the WorldHandle to enable actual head-camera JPEG capture. */
export function createRecorder(bus: Bus, root: HTMLElement, world?: WorldHandle): void {
  const tuning: Tuning = {
    requested: { value: null, provenance: "not_observed", observedWallMs: null },
    actual: { value: null, provenance: "not_observed", observedWallMs: null },
  };
  const completed: RecordedEpisode[] = [];
  let active: RecordedEpisode | null = null;
  let course: Events["course"] | null = null;
  let engine: EpisodeMetadata["engine"] = null;
  let sensor: Observation<SensorFrame> | null = null;
  let pose: Observation<WearerPose> | null = null;
  let episodeSequence = 0;
  let lastRestart = bus.latest("restart");
  let lastSelection = bus.latest("selectCourse");
  let connected: boolean | null = bus.latest("link")?.connected ?? null;
  const initialRequest = bus.latest("setTuning");
  if (initialRequest) {
    tuning.requested = { value: { ...initialRequest }, provenance: "bus.latest(setTuning); original receipt time unknown", observedWallMs: null };
  }

  const episodeSelect = el("select", { class: "course-select", "aria-label": "Recorded episode" });
  const download = el("button", { type: "button", class: "btn" }, "Download episode JSONL");
  const snapshot = el("button", { type: "button", class: "btn" }, "Snapshot head camera dataset");
  snapshot.disabled = !world;
  const status = el("p", { class: "fine", role: "status" }, "Waiting for a course.");
  const snapshotLinks = el("div", { class: "controls" });
  const panel = el("section", { "aria-label": "Simulation episode recorder", class: "block" },
    el("p", { class: "eyebrow" }, "Recorder · simulation, not hardware"),
    episodeSelect, el("div", { class: "controls" }, download, snapshot), snapshotLinks, status,
    el("p", { class: "fine" }, "truthM covers the sensor cone only, not all visible objects. Not ADT/VRS or a validated safety metric."),
  );
  (root.querySelector(".experiments") ?? root.querySelector(".left") ?? root).append(panel);

  function refreshOptions(): void {
    const selected = episodeSelect.value;
    episodeSelect.replaceChildren(...[...(active ? [active] : []), ...completed.slice().reverse()].map((episode) =>
      el("option", { value: episode.metadata.episodeId }, `${episode === active ? "Current" : "Completed"} · ${episode.metadata.courseId} · ${episode.metadata.episodeId}`)));
    if ([...episodeSelect.options].some((option) => option.value === selected)) episodeSelect.value = selected;
    download.disabled = !active;
  }

  function record(event: string, data: unknown, replayed = false, wallMs = Date.now()): void {
    if (!active || !course) return;
    const row: EpisodeEvent = { type: "event", seq: ++active.observedEvents, wallMs, tS: course.tS, event, replayed, data };
    const line = JSON.stringify(row);
    if (line.length > RECORDER_LIMITS.eventCharactersPerEpisode) {
      active.droppedEvents++;
      return;
    }
    while (active.events.length >= RECORDER_LIMITS.eventsPerEpisode ||
      active.eventCharacters + line.length > RECORDER_LIMITS.eventCharactersPerEpisode) {
      active.eventCharacters -= active.events.shift()!.length;
      active.droppedEvents++;
    }
    active.events.push(line);
    active.eventCharacters += line.length;
  }

  function begin(next: Events["course"], boundary: Boundary): void {
    const wallMs = Date.now();
    const followingCurrent = !active || episodeSelect.value === active.metadata.episodeId;
    if (active) {
      active.end = { wallMs, tS: course!.tS, reason: boundary };
      completed.push(active);
      if (completed.length > RECORDER_LIMITS.completedEpisodes) completed.shift();
    }
    course = { ...next };
    sensor = null;
    pose = null;
    active = {
      metadata: { schema: "determine.episode/2", source: "simulation", episodeId: `${wallMs}-${++episodeSequence}`,
        courseId: next.id, startedWallMs: wallMs, startedTS: next.tS, boundary,
        tuningAtStart: copyTuning(tuning), tuningLatest: copyTuning(tuning), engine: engine ? { ...engine } : null,
        limits: RECORDER_LIMITS, limitations: LIMITATIONS },
      events: [], eventCharacters: 0, observedEvents: 0, droppedEvents: 0, end: null,
    };
    if (connected !== null) record("link", { connected }, true, wallMs);
    record("tuning", copyTuning(tuning), true, wallMs);
    refreshOptions();
    if (followingCurrent) episodeSelect.value = active.metadata.episodeId;
    setText(status, `Recording ${next.id}. Up to ${RECORDER_LIMITS.completedEpisodes} completed episodes retained; old events evicted at limits.`);
  }

  const initialBridge = bus.latest("bridge");
  if (initialBridge) bridge(initialBridge, true);
  bus.on("course", (next) => {
    // Bus stores a command before dispatching it; world emits the resulting course synchronously.
    // This also catches an explicit restart at tS=0 without creating a duplicate boundary afterwards.
    const restart = bus.latest("restart");
    const selection = bus.latest("selectCourse");
    const resetCommand = restart !== lastRestart || selection !== lastSelection;
    lastRestart = restart;
    lastSelection = selection;
    if (!course || !active) begin(next, "recorder_started");
    else if (next.id !== course.id) begin(next, "course_changed");
    else if (next.tS < course.tS || (resetCommand && next.tS === 0)) begin(next, "time_reset");
    else course = { ...next };
    record("course", next);
  }, true);

  function observe<K extends "sensor" | "pose">(event: K, data: Events[K], replayed = false): Observation<Events[K]> | null {
    const wallMs = Date.now();
    record(event, data, replayed, wallMs);
    return active && course ? { wallMs, tS: course.tS, episodeId: active.metadata.episodeId, replayed, data: { ...data } } : null;
  }
  bus.on("sensor", (data) => { sensor = observe("sensor", data); });
  bus.on("pose", (data) => { pose = observe("pose", data); });
  const initialSensor = bus.latest("sensor");
  const initialPose = bus.latest("pose");
  if (initialSensor) sensor = observe("sensor", initialSensor, true);
  if (initialPose) pose = observe("pose", initialPose, true);

  function recordTuning(): void {
    if (active) active.metadata.tuningLatest = copyTuning(tuning);
    record("tuning", copyTuning(tuning));
  }
  bus.on("setTuning", (value) => {
    tuning.requested = { value: { ...value }, provenance: "bus.setTuning (request, not confirmation)", observedWallMs: Date.now() };
    record("setTuning", value);
    recordTuning();
  });
  bus.on("link", (value) => {
    connected = value.connected;
    if (!connected) {
      tuning.actual = { value: null, provenance: "link disconnected; actual tuning unavailable", observedWallMs: Date.now() };
      recordTuning();
    }
    record("link", value);
  });

  function bridge(message: FromBridge, replayed = false): void {
    if (message.t === "log") return;
    record(`bridge.${message.t}`, message, replayed);
    if (message.t === "hello" || message.t === "bridge") {
      tuning.actual = { value: connected && message.tuning ? { ...message.tuning } : null,
        provenance: connected ? `bridge.${message.t}${message.tuning === null ? " (external/unknown settings)" : ""}` : "link disconnected; actual tuning unavailable",
        observedWallMs: replayed ? null : Date.now() };
      if (message.t === "hello") {
        engine = { mode: message.engine, provenance: "bridge.hello", observedWallMs: replayed ? null : Date.now() };
        if (active) active.metadata.engine = { ...engine };
      }
      recordTuning();
    }
  }
  bus.on("bridge", (message) => bridge(message));
  if (initialBridge && initialBridge.t !== "log") record(`bridge.${initialBridge.t}`, initialBridge, true);

  function downloadBlob(blob: Blob, filename: string): void {
    const url = URL.createObjectURL(blob);
    const anchor = el("a", { href: url, download: filename });
    root.append(anchor);
    anchor.click();
    anchor.remove();
    window.setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
  download.addEventListener("click", () => {
    const episode = [active, ...completed].find((item) => item?.metadata.episodeId === episodeSelect.value);
    if (!episode) return;
    downloadBlob(new Blob([serializeEpisodeJsonl(episode)], { type: "application/x-ndjson" }), `determine-${episode.metadata.episodeId}.jsonl`);
    setText(status, `Downloaded ${episode.events.length} retained events (${episode.droppedEvents} evicted) from ${episode.metadata.courseId}.`);
  });

  let snapshotUrls: string[] = [];
  snapshot.addEventListener("click", async () => {
    if (!world || !active || !course) return;
    snapshot.disabled = true;
    const started = Date.now();
    const filename = `determine-head-${active.metadata.episodeId}-${started}`;
    const metadata: SnapshotMetadata = {
      schema: "determine.snapshot/1", source: "simulation", episodeId: active.metadata.episodeId,
      courseId: course.id, tS: course.tS, captureStartedWallMs: started, captureFinishedWallMs: started,
      courseAtCompletion: null, image: { file: `${filename}.jpg`, mimeType: "image/jpeg", width: 640, height: 480, requestedQuality: 0.85 },
      sensor, pose, tuning: copyTuning(tuning), limitations: LIMITATIONS,
      alignment: "sensor and pose are latest received samples, not synchronized camera truth; null means no sample in this episode. No full-camera object labels, segmentation or depth truth.",
    };
    try {
      const jpeg = await world.captureHeadJpeg(640, 480, 0.85);
      if (jpeg.size === 0) throw new Error("Head camera returned an empty JPEG");
      metadata.captureFinishedWallMs = Date.now();
      metadata.courseAtCompletion = course ? { ...course } : null;
      for (const url of snapshotUrls) URL.revokeObjectURL(url);
      snapshotUrls = [URL.createObjectURL(jpeg), URL.createObjectURL(new Blob([serializeSnapshotMetadata(metadata)], { type: "application/json" }))];
      snapshotLinks.replaceChildren(
        el("a", { class: "btn", href: snapshotUrls[0], download: `${filename}.jpg` }, "Download JPEG"),
        el("a", { class: "btn", href: snapshotUrls[1], download: `${filename}.json` }, "Download metadata JSON"),
      );
      setText(status, "Snapshot ready: save both JPEG and metadata links. Previous snapshot replaced; sensor truth covers only its cone.");
    } catch (error) {
      setText(status, `Snapshot failed: ${error instanceof Error ? error.message : String(error)}`);
    } finally {
      snapshot.disabled = false;
    }
  });
  refreshOptions();
}
