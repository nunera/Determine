import type { Events, FromBridge, LiveProfile } from "./contract";

/** Studio v1: postMessage envelopes only; no second WebSocket or sensor producer in the parent.
 * Parent -> iframe: {channel,version,kind:'command',event,payload}; iframe -> parent: kind:'event'.
 * Both ends MUST check origin AND source before using the guards below. No wildcard target origins.
 * courses/course/cameraMode replay at attachment; pose/sensor/course and HITL <=10 Hz; preview <=5 Hz;
 * diagnostic/log forwarding <=20/s. The host bounds its event log separately.
 */
export const STUDIO_CHANNEL = "determine.studio";
export const STUDIO_VERSION = 1;
export type CommandName = "selectCourse" | "play" | "restart" | "setCameraMode" | "setBoard" | "setNet" |
  "setTuning" | "setProfile" | "restartEngine" | "setMuted" | "setHitl" | "hitlTarget" | "hitlOverlayCourse" | "describe";
export type EventName = "courses" | "course" | "pose" | "sensor" | "cameraMode" | "link" | "bridge" | "hitlPreview" | "cameraFrame" | "describeResult";
type Envelope<K extends keyof Events, Kind extends string> = K extends keyof Events ? {
  channel: typeof STUDIO_CHANNEL; version: typeof STUDIO_VERSION; kind: Kind; event: K; payload: Events[K];
} : never;
export type StudioCommand = Envelope<CommandName, "command">;
export type StudioEvent = Envelope<EventName, "event">;
const record = (v: unknown): v is Record<string, unknown> => typeof v === "object" && v !== null && !Array.isArray(v);
const finite = (v: unknown): v is number => typeof v === "number" && Number.isFinite(v);
const integer = (v: unknown, min: number, max: number) => finite(v) && Number.isInteger(v) && v >= min && v <= max;
const oneOf = (v: unknown, values: readonly string[]) => typeof v === "string" && values.includes(v);
export function isLiveProfile(v: unknown): v is LiveProfile {
  return record(v) && oneOf(v.mode, ["HAPTIC_ONLY", "STEREO", "SSD"]) && integer(v.ceilingDb, -60, -10) && integer(v.hapticMax, 1, 255);
}
const boundedText = (v: unknown, max: number): v is string => typeof v === "string" && v.length <= max;
/** Bound provider-controlled text and timing maps before they cross the iframe boundary. */
export function isDescribeResult(v: unknown): v is Events["describeResult"] {
  if (!record(v) || typeof v.ok !== "boolean" || typeof v.pending !== "boolean" ||
    Object.keys(v).some(k => !["ok", "pending", "text", "error", "backend", "silicon", "model", "timings"].includes(k))) return false;
  if ((v.text !== undefined && !boundedText(v.text, 8000)) ||
    (v.error !== undefined && !boundedText(v.error, 2048)) ||
    [v.backend, v.silicon, v.model].some(value => value !== undefined && !boundedText(value, 512))) return false;
  if (v.timings !== undefined && (!record(v.timings) || Object.keys(v.timings).length > 16 ||
    Object.entries(v.timings).some(([key, ms]) => !key.length || key.length > 64 || !finite(ms) || ms < 0 || ms > 3_600_000))) return false;
  if (v.pending) return !v.ok && Object.keys(v).every(k => k === "ok" || k === "pending");
  return v.ok ? typeof v.text === "string" && !!v.text.trim() : typeof v.error === "string" && !!v.error.trim();
}
function envelope(v: unknown, kind: string): v is Record<string, unknown> {
  return record(v) && v.channel === STUDIO_CHANNEL && v.version === STUDIO_VERSION && v.kind === kind && typeof v.event === "string";
}
export function isStudioCommand(v: unknown): v is StudioCommand {
  if (!envelope(v, "command") || !record(v.payload)) return false;
  const p = v.payload;
  switch (v.event) {
    case "selectCourse": return typeof p.id === "string" && p.id.length < 150;
    case "play": return typeof p.playing === "boolean";
    case "restart": case "restartEngine": case "describe": return Object.keys(p).length === 0;
    case "setCameraMode": return oneOf(p.mode, ["director", "wearer", "orbit", "plan"]);
    case "setBoard": return oneOf(p.board, ["cue", "cam"]) && typeof p.down === "boolean";
    case "setNet": return oneOf(p.profile, ["clean", "wifi_jitter"]);
    case "setMuted": return typeof p.muted === "boolean";
    case "setTuning": return typeof p.approach === "boolean" && typeof p.explore === "boolean";
    case "setProfile": return isLiveProfile(p);
    case "setHitl": return (p.range === undefined || oneOf(p.range, ["real", "world", "fuse"])) &&
      (p.camera === undefined || oneOf(p.camera, ["real", "composite"])) && (p.range !== undefined || p.camera !== undefined);
    case "hitlTarget": return typeof p.on === "boolean" && integer(p.distanceMm, 30, 1300) && integer(p.widthMm, 20, 3000) &&
      integer(p.heightMm, 20, 3000) && oneOf(p.reflectance, ["white", "grey", "glass"]);
    case "hitlOverlayCourse": return typeof p.on === "boolean";
    default: return false;
  }
}
function board(v: unknown): boolean {
  return record(v) && (v.addr === null || typeof v.addr === "string") && (v.ageMs === null || finite(v.ageMs)) &&
    (v.rssi === null || finite(v.rssi)) && (v.faults === null || finite(v.faults));
}
function bridge(v: unknown): v is FromBridge {
  if (!record(v)) return false;
  switch (v.t) {
    case "hello": return oneOf(v.engine, ["spawned", "external"]) && typeof v.phone === "string" &&
      (v.describe === undefined || typeof v.describe === "boolean") &&
      (!Object.hasOwn(v, "profile") || v.profile === null || isLiveProfile(v.profile)) &&
      (v.hitl === undefined || (record(v.hitl) && typeof v.hitl.enabled === "boolean" && typeof v.hitl.compositeAvailable === "boolean"));
    case "bridge": return oneOf(v.engine, ["running", "exited", "external"]) && record(v.boards) &&
      oneOf(v.boards.cue, ["up", "down"]) && oneOf(v.boards.cam, ["up", "down"]) && typeof v.net === "string" &&
      (!Object.hasOwn(v, "profile") || v.profile === null || isLiveProfile(v.profile));
    case "profile": return oneOf(v.status, ["applied", "rejected"]) && (v.profile === null || isLiveProfile(v.profile)) &&
      (v.error === undefined || typeof v.error === "string");
    case "hitl": return board(v.cue) && board(v.cam) && record(v.range) && record(v.camera) &&
      oneOf(v.range.mode, ["real", "world", "fuse"]) && oneOf(v.camera.mode, ["real", "composite"]);
    case "state": return typeof v.state === "string" && typeof v.reason === "string";
    case "alert": return v.alert === null || (record(v.alert) && oneOf(v.alert.band, ["FAR", "MID", "NEAR"]) && (v.alert.distMm === null || finite(v.alert.distMm)));
    case "played": return typeof v.status === "string" && typeof v.haptic === "string" && finite(v.hapticMax);
    case "cueTx": return typeof v.cue === "string" && finite(v.seq) && finite(v.hl) && finite(v.hr);
    case "audio": return typeof v.cue === "string" && finite(v.glDb) && finite(v.grDb) && typeof v.status === "string";
    case "log": return typeof v.line === "string";
    case "diag": return record(v.kv);
    case "speak": return typeof v.text === "string";
    case "vibrate": return Array.isArray(v.pattern) && v.pattern.every(finite);
    case "systemOff": return finite(v.ms);
    default: return false;
  }
}
export function isStudioEvent(v: unknown): v is StudioEvent {
  if (!envelope(v, "event")) return false;
  const p = v.payload;
  switch (v.event) {
    case "courses": return Array.isArray(p) && p.length <= 100 && p.every(c => record(c) && typeof c.id === "string" && typeof c.title === "string" && typeof c.summary === "string" && typeof c.expect === "string" && finite(c.durationS));
    case "course": return record(p) && typeof p.id === "string" && finite(p.tS) && typeof p.playing === "boolean";
    case "pose": return record(p) && [p.x, p.z, p.yaw, p.speedMps].every(finite);
    case "sensor": return record(p) && finite(p.simMs) && finite(p.mm) && integer(p.status, 0, 2) && typeof p.kind === "string";
    case "cameraFrame": return record(p) && finite(p.simMs);
    case "cameraMode": return oneOf(p, ["director", "wearer", "orbit", "plan"]);
    case "link": return record(p) && typeof p.connected === "boolean";
    case "bridge": return bridge(p);
    case "hitlPreview": return record(p) && p.jpeg instanceof Blob && p.jpeg.type === "image/jpeg" && p.jpeg.size <= 2_000_000;
    case "describeResult": return isDescribeResult(p);
    default: return false;
  }
}
/** Only the current origin or explicit local development/preview website ports are embedders. */
export function allowedParentOrigin(raw: string | null, ownOrigin: string): string | null {
  if (!raw) return null;
  try {
    const u = new URL(raw);
    if (u.origin !== raw || u.username || u.password || !["http:", "https:"].includes(u.protocol)) return null;
    if (u.origin === ownOrigin) return u.origin;
    return ["localhost", "127.0.0.1", "[::1]"].includes(u.hostname) && ["5175", "4175"].includes(u.port) ? u.origin : null;
  } catch { return null; }
}
