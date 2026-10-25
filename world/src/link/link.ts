/**
 * Bridge link: one WebSocket to the same origin's `/ws` (Vite proxies it to tools/sim/world_bridge.py), plus the
 * Describe HTTP call. The link forwards facts both ways and decides nothing.
 *
 * Out (browser → bridge): `sensor` → `range`, `cameraJpeg` → binary frame (0x01 + JPEG), `hitlOverlayPng` → binary
 * frame (0x02 + PNG; the world only produces it while HITL composite mode is on), `setBoard` → `board`,
 * `setNet` → `net`, `setTuning` → `tuning` (the bridge restarts the engine with it), `setHitl` → `hitl`,
 * `setProfile` → `profile`, and `restartEngine` → engine restart. Messages are dropped — never queued — while the socket is down or backed
 * up, so the engine never receives stale sensor data after a reconnect.
 *
 * `restartEngine` is distinct from `restart`, which resets the browser course.
 *
 * In (bridge → browser): every JSON text frame that parses as a `FromBridge` message is emitted as `bridge`; binary
 * frames tagged 0x11 (HITL: the JPEG as sent to the engine) are emitted as `hitlPreview`.
 */
import {
  WS_TAG_CAMERA_JPEG, WS_TAG_HITL_OVERLAY, WS_TAG_HITL_PREVIEW,
  type Bus, type CreateLink, type DescribeResult, type FromBridge, type ToBridge, type WorldHandle,
} from "../contract";
import { isDescribeResult } from "../studioProtocol";

const BACKOFF_MIN_MS = 400;
const BACKOFF_MAX_MS = 8000;
/** Above this many unsent bytes the socket is backed up: drop new sensor/camera data rather than delay it. */
const MAX_BUFFERED_BYTES = 512 * 1024;
const DESCRIBE_TIMEOUT_MS = 20_000;
const DESCRIBE_SIZE = { width: 640, height: 480, quality: 0.85 } as const;

const FROM_BRIDGE_TYPES: Record<FromBridge["t"], true> = {
  hello: true, bridge: true, state: true, alert: true, audio: true, cueTx: true, played: true, speak: true, vibrate: true,
  systemOff: true, diag: true, log: true, hitl: true, profile: true,
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
  let describeEnabled = false;
  let describeController: AbortController | null = null;

  bus.emit("link", { connected: false });

  function setConnected(next: boolean): void {
    if (next === connected) return;
    connected = next;
    if (!next) {
      describeEnabled = false;
      describeController?.abort();
      describeController = null;
    }
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
      if (ws !== socket) return;
      if (typeof ev.data === "string") {
        const msg = parseFromBridge(ev.data);
        if (msg?.t === "hello") describeEnabled = msg.describe === true;
        if (msg) bus.emit("bridge", msg);
        return;
      }
      if (!(ev.data instanceof ArrayBuffer) || ev.data.byteLength < 2) return;
      if (new Uint8Array(ev.data, 0, 1)[0] === WS_TAG_HITL_PREVIEW) {
        bus.emit("hitlPreview", { jpeg: new Blob([ev.data.slice(1)], { type: "image/jpeg" }) });
      }
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

  /** Sends `tag` + the blob's bytes as one binary frame; dropped when the socket is down or backed up. */
  function sendTagged(tag: number, blob: Blob): void {
    if (!writable()) return;
    void blob.arrayBuffer().then((buf) => {
      // Re-check after the async read: the socket may have closed or backed up meanwhile.
      const s = writable();
      if (!s) return;
      const frame = new Uint8Array(buf.byteLength + 1);
      frame[0] = tag;
      frame.set(new Uint8Array(buf), 1);
      s.send(frame);
    });
  }

  bus.on("cameraJpeg", ({ jpeg }) => sendTagged(WS_TAG_CAMERA_JPEG, jpeg));
  bus.on("hitlOverlayPng", ({ png }) => sendTagged(WS_TAG_HITL_OVERLAY, png));
  bus.on("setHitl", ({ range, camera }) => send({ t: "hitl", range, camera }));

  bus.on("setBoard", ({ board, down }) => send({ t: "board", board, down }));
  bus.on("setNet", ({ profile }) => send({ t: "net", profile }));
  bus.on("setTuning", (tuning) => send({ t: "tuning", tuning: { approach: tuning.approach, explore: tuning.explore } }));
  bus.on("setProfile", (profile) => send({ t: "profile", profile }));
  bus.on("restartEngine", () => send({ t: "engine", action: "restart" }));

  // ------------------------------------------------------------ Describe ("What's ahead?"), optional cloud path

  function finishDescribe(controller: AbortController, result: DescribeResult): void {
    // A disconnected request must not repopulate the inspector after the next hello.
    if (describeController !== controller) return;
    describeController = null;
    bus.emit("describeResult", { ...result, pending: false });
  }

  async function describe(): Promise<void> {
    if (describeController) return;
    const controller = new AbortController();
    describeController = controller;
    bus.emit("describeResult", { ok: false, pending: true });
    if (!connected || !describeEnabled) {
      finishDescribe(controller, {
        ok: false,
        error: !connected
          ? "Bridge link is down; Describe goes through the bridge."
          : "This bridge has not advertised Describe. Configure the server-side service and reconnect.",
      });
      return;
    }
    const timer = window.setTimeout(() => controller.abort(), DESCRIBE_TIMEOUT_MS);
    // The timeout includes the JPEG encoder, not only fetch. No second camera producer is started.
    let cancel = () => {};
    const cancelled = new Promise<never>((_, reject) => {
      cancel = () => reject(new DOMException("Describe cancelled", "AbortError"));
      controller.signal.addEventListener("abort", cancel, { once: true });
    });
    const t0 = performance.now();
    let stage = "Rendered World view capture";
    try {
      const jpeg = await Promise.race([
        world.captureHeadJpeg(DESCRIBE_SIZE.width, DESCRIBE_SIZE.height, DESCRIBE_SIZE.quality),
        cancelled,
      ]);
      controller.signal.throwIfAborted();
      stage = "Describe service";
      const res = await fetch("/api/describe", {
        method: "POST",
        headers: { "Content-Type": "image/jpeg" },
        body: jpeg,
        signal: controller.signal,
      });
      let body: unknown;
      try {
        body = await Promise.race([res.json(), cancelled]);
      } catch (cause) {
        controller.signal.throwIfAborted();
        body = null;
      }
      const roundTripMs = Math.round(performance.now() - t0);
      if (typeof body !== "object" || body === null || Array.isArray(body)) {
        finishDescribe(controller, { ok: false, error: `HTTP ${res.status}: response was not a Describe result`, timings: { browser_round_trip: roundTripMs } });
        return;
      }
      const data = body as Partial<DescribeResult>;
      const result = {
        ok: data.ok === true && res.ok,
        text: data.text,
        error: data.error ?? (res.ok ? undefined : `HTTP ${res.status}`),
        backend: data.backend,
        silicon: data.silicon,
        model: data.model,
        // Measured here: capture + upload + server + download, as seen by this browser.
        timings: typeof data.timings === "object" && data.timings !== null && !Array.isArray(data.timings)
          ? { ...data.timings, browser_round_trip: roundTripMs }
          : data.timings === undefined ? { browser_round_trip: roundTripMs } : data.timings,
        pending: false,
      };
      if (!isDescribeResult(result)) {
        finishDescribe(controller, { ok: false, error: `HTTP ${res.status}: response was not a valid, bounded Describe result`, timings: { browser_round_trip: roundTripMs } });
        return;
      }
      finishDescribe(controller, result);
    } catch (err) {
      finishDescribe(controller, {
        ok: false,
        error: controller.signal.aborted
          ? `No answer within ${DESCRIBE_TIMEOUT_MS / 1000} s`
          : `${stage} failed: ${err instanceof Error ? err.message : String(err)}`.slice(0, 2048),
        timings: { browser_round_trip: Math.round(performance.now() - t0) },
      });
    } finally {
      window.clearTimeout(timer);
      controller.signal.removeEventListener("abort", cancel);
    }
  }

  bus.on("describe", () => void describe());

  connect();
};
