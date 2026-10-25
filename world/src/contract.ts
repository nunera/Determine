/**
 * Shared contract between the world (scene/, sensor/), the HUD (hud/), the cue audio (audio/) and the bridge link
 * (link/). Every module talks through one typed Bus; none imports another module's internals.
 *
 * Wire v2 truth chain (protocol/PROTOCOL.md): the world measures ONE forward ToF reading (Modulino Distance,
 * VL53L4ED: ~18° diagonal cone, ≤ 1.2 m) and renders the ESP32-CAM view → link → tools/sim/world_bridge.py
 * (simulated cue + camera boards over UDP) → the real phone engine (phone/cli) decides → the phone's AUDIO half
 * (what the phone would play on the Bluetooth bone-conduction headset) and the haptic CUE packets → the simulated
 * cue board applies/ACKs the haptic → bridge reports both → audio plays the phone's sound, the HUD shows both.
 * The browser never decides a hazard. One zone cannot tell left from right: obstacle cues are always BOTH.
 */

// ---------------------------------------------------------------- protocol enums (protocol/PROTOCOL.md v2)

export const CUE_NAMES = [
  "READY", "OBSTACLE_FAR", "OBSTACLE_MID", "OBSTACLE_NEAR",
  "PERSON_FAR", "PERSON_MID", "PERSON_NEAR", "SYSTEM_OFF",
  "CAL_TONE_500", "CAL_TONE_1K", "CAL_TONE_2K", "CAL_OBSTACLE",
] as const;
export type CueName = (typeof CUE_NAMES)[number];

export const HAPTIC_NAMES = ["NONE", "PULSE40", "PULSE60", "BUZZ150", "LSS"] as const;
export type HapticName = (typeof HAPTIC_NAMES)[number];

export type Side = "L" | "R" | "BOTH";
export type AckStatus = "APPLIED" | "EXPIRED" | "APPLIED_CLAMPED" | "NO_SYNC" | "BAD_CUE" | "BUSY_DROPPED";
export type SystemState = "STARTING" | "READY" | "DEGRADED" | "UNAVAILABLE" | "PAUSED";
export type Band = "FAR" | "MID" | "NEAR";
export type BoardName = "cue" | "cam";

/** Canonical RANGE status (wire v2). NO_TARGET is never "clear"; UNKNOWN is an announced failure. */
export const RANGE_VALID = 0;
export const RANGE_NO_TARGET = 1;
export const RANGE_UNKNOWN = 2;
export type RangeStatusName = "VALID" | "NO_TARGET" | "UNKNOWN";
export const RANGE_STATUS_NAMES: readonly RangeStatusName[] = ["VALID", "NO_TARGET", "UNKNOWN"];

// ---------------------------------------------------------------- sensor geometry (tools/sim/scene.py v2)

export const SENSOR_HEIGHT_M = 1.6;
export const FOV_DIAG_DEG = 18;
export const RANGE_LIMIT_MM = 1200;
export const RANGE_HZ = 15;
export const CAMERA_FPS = 8;
/** Proposed near-field bands (phone/core Tuning defaults; PROTOCOL.md "Hazard bands"). Visual reference only:
 *  the engine decides the band, the world never does. */
export const BAND_NEAR_MM = 500;
export const BAND_MID_MM = 900;
export const BAND_FAR_MM = 1200;

// ---------------------------------------------------------------- wire between browser and bridge (WebSocket /ws)

/** EXPERIMENTAL engine features (phone/core Tuning) the simulator can A/B. Changing them restarts the engine. */
export interface EngineTuning {
  /** approachEnabled: escalate the band by one when time-to-contact is short (TTC 1.5 s, ≥ 300 mm/s). */
  approach: boolean;
  /** exploreEnabled: distance-proportional haptic pulse train instead of band cues. */
  explore: boolean;
}

/** Browser → bridge, JSON text frames. Camera JPEGs go as binary frames: byte 0 = 0x01, then the JPEG bytes. */
export type ToBridge =
  | { t: "range"; simMs: number; mm: number; status: number }     // mm 0 unless status = RANGE_VALID
  | { t: "board"; board: BoardName; down: boolean }                // simulate unplugging a board
  | { t: "net"; profile: "clean" | "wifi_jitter" }                 // tools/sim/netem.py profiles
  | { t: "engine"; action: "restart" }
  | { t: "tuning"; tuning: EngineTuning };

/** Bridge → browser, JSON text frames. `ms` is ms since the engine started (cli clock) unless noted. */
export type FromBridge =
  /** `tuning` is null when the engine is an external phone: the bridge cannot know its settings. */
  | { t: "hello"; engine: "spawned" | "external"; phone: string; describe: boolean; tuning: EngineTuning | null }
  | { t: "bridge"; engine: "running" | "exited" | "external"; boards: Record<BoardName, "up" | "down">; net: string;
      tuning: EngineTuning | null }
  | { t: "state"; ms: number; state: SystemState; reason: string }
  | { t: "alert"; ms: number; alert: { band: Band; distMm: number | null } | null }
  /** The phone's audio half of a cue (cli `AUDIO` line): what the phone plays on the headset. */
  | { t: "audio"; ms: number; cue: CueName; side: Side; glDb: number; grDb: number; status: AckStatus }
  /** The haptic half the phone sent to the cue board (cli `CUE-TX`). */
  | { t: "cueTx"; ms: number; seq: number; cue: CueName; side: Side; haptic: HapticName; hl: number; hr: number;
      flags: number; ttlMs: number }
  /** What the simulated cue board did with a CUE, or its own watchdog LSS (`src: "watchdog"`). The bench board has
   *  ONE Vibro motor and plays max(hl, hr): it cannot lateralise. */
  | { t: "played"; hostMs: number; seq: number | null; cue: CueName; side: Side; status: AckStatus;
      haptic: HapticName; hl: number; hr: number; hapticMax: number; src: "phone" | "watchdog"; lateUs: number }
  | { t: "speak"; ms: number; text: string }
  | { t: "vibrate"; ms: number; pattern: number[] }
  | { t: "systemOff"; ms: number }
  | { t: "diag"; ms: number; kv: Record<string, string> }
  | { t: "log"; line: string };

/** POST /api/describe (proxied by the bridge to cloud/describe-api). Body: image/jpeg. */
export interface DescribeResult {
  ok: boolean;
  text?: string;          // the API's safety-filtered text, spoken as returned (no prefix is added)
  error?: string;
  backend?: string;       // e.g. "inferentia2" | "cpu" | "bedrock"
  silicon?: string;       // e.g. "Inferentia2" | "Graviton2" | "unspecified"
  model?: string;
  timings?: Record<string, number>;  // ms
}

// ---------------------------------------------------------------- in-browser state and events

export type CameraMode = "director" | "wearer" | "orbit" | "plan";

export interface CourseInfo {
  id: string;
  title: string;        // short, e.g. "Sign at head height"
  summary: string;      // one sentence: the situation and what the single forward sensor can and cannot do
  durationS: number;
  /** Honest expectation shown in the HUD, e.g. "FAR → MID → NEAR before contact" or "must stay quiet". */
  expect: string;
  /** Named moments (seconds from course start) the director camera and HUD timeline mark. */
  marks: { atS: number; label: string }[];
}

export interface WearerPose {
  /** Three.js world, metres, Y up. Feet position on the floor. */
  x: number; z: number;
  /** Radians; 0 = facing −Z (Three.js forward). */
  yaw: number;
  speedMps: number;
}

export interface SensorFrame {
  simMs: number;
  mm: number;           // 0 unless status = RANGE_VALID
  status: number;       // RANGE_VALID | RANGE_NO_TARGET | RANGE_UNKNOWN
  /** Kind of the nearest object in the cone ("sign", "person", "glass", …), "" for none — HUD/visuals only. */
  kind: string;
  /** Ground truth for overlays and the run recorder: nearest object distance along the beam, metres (null = none
   *  within the cone up to 4 m). Never sent to the engine. */
  truthM: number | null;
}

export interface Events {
  // world → everyone
  courses: CourseInfo[];
  course: { id: string; tS: number; playing: boolean };
  pose: WearerPose;
  sensor: SensorFrame;
  cameraMode: CameraMode;
  // link → everyone (bridge facts)
  link: { connected: boolean };
  bridge: FromBridge;
  // UI → world / link
  selectCourse: { id: string };
  play: { playing: boolean };
  restart: Record<string, never>;
  setCameraMode: { mode: CameraMode };
  setBoard: { board: BoardName; down: boolean };
  setNet: { profile: "clean" | "wifi_jitter" };
  setTuning: EngineTuning;
  setMuted: { muted: boolean };
  describe: Record<string, never>;            // user pressed "What's ahead?"
  describeResult: DescribeResult & { pending: boolean };
  // world → link
  cameraJpeg: { jpeg: Blob; simMs: number };
}

type Handler<T> = (payload: T) => void;

export class Bus {
  private handlers = new Map<keyof Events, Set<Handler<never>>>();
  private last = new Map<keyof Events, unknown>();

  on<K extends keyof Events>(type: K, fn: Handler<Events[K]>, replay = false): () => void {
    let set = this.handlers.get(type);
    if (!set) this.handlers.set(type, (set = new Set()));
    set.add(fn as Handler<never>);
    if (replay && this.last.has(type)) fn(this.last.get(type) as Events[K]);
    return () => set!.delete(fn as Handler<never>);
  }

  emit<K extends keyof Events>(type: K, payload: Events[K]): void {
    this.last.set(type, payload);
    this.handlers.get(type)?.forEach((fn) => (fn as Handler<Events[K]>)(payload));
  }

  latest<K extends keyof Events>(type: K): Events[K] | undefined {
    return this.last.get(type) as Events[K] | undefined;
  }
}

// ---------------------------------------------------------------- module entry points (main.ts wires these)

export interface WorldHandle {
  /** Renders the head-mounted camera view as a JPEG (used by Describe and the simulated camera board). */
  captureHeadJpeg(width: number, height: number, quality: number): Promise<Blob>;
  dispose(): void;
}
export type CreateWorld = (canvas: HTMLCanvasElement, bus: Bus) => WorldHandle;
export type CreateHud = (root: HTMLElement, bus: Bus) => void;
export type CreateAudio = (bus: Bus) => void;
export type CreateLink = (bus: Bus, world: WorldHandle) => void;
