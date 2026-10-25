import { type Bus, type Events } from "./contract";
import { allowedParentOrigin, isStudioCommand, STUDIO_CHANNEL, STUDIO_VERSION, type EventName } from "./studioProtocol";

/** Attach before createWorld so initial course messages are not lost. A ready command isn't required:
 * the parent installs its listener before assigning iframe.src. iframe removal owns runtime teardown. */
export function createStudio(bus: Bus, parentOrigin: string): () => void {
  const last = new Map<string, number>();
  let logWindow = 0;
  let logCount = 0;
  let hitlEnabled = false;
  function forward<K extends EventName>(event: K, payload: Events[K]): void {
    const now = performance.now();
    let key: string = event;
    let gap = ["pose", "sensor", "course"].includes(event) ? 100 : event === "hitlPreview" ? 200 : 0;
    if (event === "bridge") {
      const msg = payload as Events["bridge"];
      if (msg.t === "hello") hitlEnabled = msg.hitl?.enabled === true;
      if (msg.t === "hitl") { key = "hitl"; gap = 100; }
      if (msg.t === "log" || msg.t === "diag") {
        if (now - logWindow >= 1000) { logWindow = now; logCount = 0; }
        if (++logCount > 20) return;
      }
    }
    if (gap && now - (last.get(key) ?? -Infinity) < gap) return;
    last.set(key, now);
    window.parent.postMessage({ channel: STUDIO_CHANNEL, version: STUDIO_VERSION, kind: "event", event, payload }, parentOrigin);
  }
  const off = (["courses", "course", "pose", "sensor", "cameraMode", "link", "bridge", "hitlPreview", "describeResult"] as const)
    .map(event => bus.on(event, payload => forward(event, payload), true));
  off.push(bus.on("cameraJpeg", ({ simMs }) => forward("cameraFrame", { simMs })));
  const audioUnlock = document.createElement("button");
  audioUnlock.textContent = "Enable browser audio monitor";
  Object.assign(audioUnlock.style, {
    position: "fixed", bottom: "16px", left: "16px", zIndex: "5", padding: "10px 14px",
    background: "#182421", color: "#f6f8f3", border: "1px solid #87988d", borderRadius: "6px",
    font: "600 12px system-ui", cursor: "pointer",
  });
  audioUnlock.addEventListener("click", () => audioUnlock.remove(), { once: true });
  document.body.append(audioUnlock);
  function receive(event: MessageEvent<unknown>): void {
    if (event.source !== window.parent || event.origin !== parentOrigin || !isStudioCommand(event.data)) return;
    const command = event.data;
    // Mode-dependent controls are also enforced here; a stale parent cannot simulate real board unplugging.
    if (command.event === "setBoard" && hitlEnabled) return;
    // The guard establishes event/payload correlation; Bus's generic emit cannot retain a union correlation.
    bus.emit(command.event, command.payload);
  }
  window.addEventListener("message", receive);
  return () => { audioUnlock.remove(); off.forEach(dispose => dispose()); window.removeEventListener("message", receive); };
}

export function studioParent(): string | null {
  return allowedParentOrigin(new URLSearchParams(location.search).get("parentOrigin"), location.origin);
}
