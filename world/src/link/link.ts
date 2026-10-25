/**
 * Bridge link: one WebSocket to the same origin's `/ws` (Vite proxies it to tools/sim/world_bridge.py), plus the
 * Describe HTTP call. The link forwards facts both ways and decides nothing.
 *
 * Out (browser → bridge): `sensor` → `range`, `cameraJpeg` → binary frame (0x01 + JPEG), `setBoard` → `board`,
 * `setNet` → `net`, `setTuning` → `tuning` (the bridge restarts the engine with it), and engine restarts. Messages are dropped — never queued — while the socket is down or backed
 * up, so the engine never receives stale sensor data after a reconnect.
 *
 * Engine restart has no Bus event (`restart` restarts the course), so the HUD dispatches a DOM event instead:
 *   window.dispatchEvent(new CustomEvent(ENGINE_RESTART_EVENT))
 * and this module turns it into `{ t: "engine", action: "restart" }`.
 *
 * In (bridge → browser): every JSON text frame that parses as a `FromBridge` message is emitted as `bridge`.
 */
import type { Bus, CreateLink, DescribeResult, FromBridge, ToBridge, WorldHandle } from "../contract";

/** DOM event name the HUD dispatches on `window` to ask the bridge to restart the phone engine. */
export const ENGINE_RESTART_EVENT = "determine:engine-restart";

const BACKOFF_MIN_MS = 400;
const BACKOFF_MAX_MS = 8000;
/** Above this many unsent bytes the socket is backed up: drop new sensor/camera data rather than delay it. */
const MAX_BUFFERED_BYTES = 512 * 1024;
const CAMERA_FRAME_TAG = 0x01;
const DESCRIBE_TIMEOUT_MS = 20_000;
const DESCRIBE_SIZE = { width: 640, height: 480, quality: 0.85 } as const;

const FROM_BRIDGE_TYPES: Record<FromBridge["t"], true> = {
  hello: true, bridge: true, state: true, alert: true, audio: true, cueTx: true, played: true, speak: true, vibrate: true,
  systemOff: true, diag: true, log: true,
};

function parseFromBridge(text: string): FromBridge | null {
  let value: unknown;
  try {
    value = JSON.parse(text);
  } catch {
    return null;
  }
  if (typeof value !== "object" || value === null || !("t" in value)) return null;
  const t = value.t;
  if (typeof t !== "string" || !Object.hasOwn(FROM_BRIDGE_TYPES, t)) return null;
  // The bridge is our own process speaking the contract's JSON; the tag is checked, the payload trusted.
  const msg = value as FromBridge;
  return msg;
}

export const createLink: CreateLink = (bus: Bus, world: WorldHandle) => {
  let ws: WebSocket | null = null;
  let connected = false;
  let backoffMs = BACKOFF_MIN_MS;
  let reconnectTimer: number | undefined;

  bus.emit("link", { connected: false });

  function setConnected(next: boolean): void {
    if (next === connected) return;
    connected = next;
    bus.emit("link", { connected });
  }

  function scheduleReconnect(): void {
    if (reconnectTimer !== undefined) return;
    const jitter = Math.random() * 0.25 * backoffMs;
    reconnectTimer = window.setTimeout(() => {
      reconnectTimer = undefined;
      connect();
    }, backoffMs + jitter);
    backoffMs = Math.min(BACKOFF_MAX_MS, backoffMs * 2);
  }

  function connect(): void {
    let socket: WebSocket;
    try {
      socket = new WebSocket(`${location.protocol === "https:" ? "wss:" : "ws:"}//${location.host}/ws`);
    } catch {
      scheduleReconnect();
      return;
    }
    socket.binaryType = "arraybuffer";
    ws = socket;
    socket.onopen = () => {
      if (ws !== socket) return;
      backoffMs = BACKOFF_MIN_MS;
      setConnected(true);
    };
    socket.onmessage = (ev: MessageEvent) => {
      if (ws !== socket || typeof ev.data !== "string") return;
      const msg = parseFromBridge(ev.data);
      if (msg) bus.emit("bridge", msg);
    };
    socket.onclose = () => {
      if (ws !== socket) return;
      ws = null;
      setConnected(false);
      scheduleReconnect();
    };
    // An error is always followed by close; reconnecting happens there.
    socket.onerror = () => undefined;
  }

  function writable(): WebSocket | null {
    const s = ws;
    if (!s || s.readyState !== WebSocket.OPEN || s.bufferedAmount > MAX_BUFFERED_BYTES) return null;
    return s;
  }

  function send(msg: ToBridge): void {
    writable()?.send(JSON.stringify(msg));
  }

  bus.on("sensor", (f) => {
    send({ t: "range", simMs: f.simMs, mm: f.mm, status: f.status });
  });

  bus.on("cameraJpeg", ({ jpeg }) => {
    if (!writable()) return;
    void jpeg.arrayBuffer().then((buf) => {
      // Re-check after the async read: the socket may have closed or backed up meanwhile.
      const s = writable();
      if (!s) return;
      const frame = new Uint8Array(buf.byteLength + 1);
      frame[0] = CAMERA_FRAME_TAG;
      frame.set(new Uint8Array(buf), 1);
      s.send(frame);
    });
  });

  bus.on("setBoard", ({ board, down }) => send({ t: "board", board, down }));
  bus.on("setNet", ({ profile }) => send({ t: "net", profile }));
  bus.on("setTuning", (tuning) => send({ t: "tuning", tuning: { approach: tuning.approach, explore: tuning.explore } }));
  window.addEventListener(ENGINE_RESTART_EVENT, () => send({ t: "engine", action: "restart" }));

  // ------------------------------------------------------------ Describe ("What's ahead?"), optional cloud path

  let describing = false;

  function finishDescribe(result: DescribeResult): void {
    describing = false;
    bus.emit("describeResult", { ...result, pending: false });
  }

  async function describe(): Promise<void> {
    if (describing) return;
    describing = true;
    bus.emit("describeResult", { ok: false, pending: true });
    if (!connected) {
      finishDescribe({ ok: false, error: "Bridge link is down; Describe goes through the bridge." });
      return;
    }
    const controller = new AbortController();
    const timer = window.setTimeout(() => controller.abort(), DESCRIBE_TIMEOUT_MS);
    const t0 = performance.now();
    try {
      const jpeg = await world.captureHeadJpeg(DESCRIBE_SIZE.width, DESCRIBE_SIZE.height, DESCRIBE_SIZE.quality);
      const res = await fetch("/api/describe", {
        method: "POST",
        headers: { "Content-Type": "image/jpeg" },
        body: jpeg,
        signal: controller.signal,
      });
      const roundTripMs = Math.round(performance.now() - t0);
      let body: Partial<DescribeResult> | null = null;
      try {
        body = (await res.json()) as Partial<DescribeResult>;
      } catch {
        body = null;
      }
      if (!body || typeof body.ok !== "boolean") {
        finishDescribe({ ok: false, error: `HTTP ${res.status}: response was not a Describe result`, timings: { browser_round_trip: roundTripMs } });
        return;
      }
      const result: DescribeResult = {
        ok: body.ok && res.ok,
        text: body.text,
        error: body.error ?? (res.ok ? undefined : `HTTP ${res.status}`),
        backend: body.backend,
        silicon: body.silicon,
        model: body.model,
        // Measured here: capture + upload + server + download, as seen by this browser.
        timings: { ...(body.timings ?? {}), browser_round_trip: roundTripMs },
      };
      finishDescribe(result);
    } catch (err) {
      const aborted = controller.signal.aborted;
      finishDescribe({
        ok: false,
        error: aborted ? `No answer within ${DESCRIBE_TIMEOUT_MS / 1000} s` : err instanceof Error ? err.message : String(err),
        timings: { browser_round_trip: Math.round(performance.now() - t0) },
      });
    } finally {
      window.clearTimeout(timer);
    }
  }

  bus.on("describe", () => void describe());

  connect();
};
