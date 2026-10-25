/**
 * Screen-in-the-loop (HITL optical camera path). The main page is the leader: it publishes its course timeline,
 * the head-locked target and the overlay toggle on a BroadcastChannel. A second tab/window opened at `?view=camera`
 * (put it fullscreen on the monitor the real ESP32-CAM looks at) renders only the head camera at display resolution
 * and follows the leader. No UI chrome; four black corner fiducials on white and a frame counter let the bench
 * operator check framing and catch dropped/frozen frames in the real camera's JPEGs.
 */
import type { Bus, Events } from "../contract";

const CHANNEL = "determine-hitl-screen";
const FIDUCIAL_PX = 48;
const FIDUCIAL_BORDER_PX = 10;

type ScreenMessage =
  | { t: "syncCourse"; payload: Events["syncCourse"] }
  | { t: "hitlTarget"; payload: Events["hitlTarget"] }
  | { t: "hitlOverlayCourse"; payload: Events["hitlOverlayCourse"] };

/** Main page: forwards course time (the world emits it every 100 ms) with the latest HITL target settings, so a
 *  camera view opened late catches up within 100 ms. */
export function startScreenLeader(bus: Bus): void {
  const channel = new BroadcastChannel(CHANNEL);
  const post = (msg: ScreenMessage): void => channel.postMessage(msg);
  bus.on("course", (c) => {
    post({ t: "syncCourse", payload: c });
    const target = bus.latest("hitlTarget");
    if (target) post({ t: "hitlTarget", payload: target });
    const overlay = bus.latest("hitlOverlayCourse");
    if (overlay) post({ t: "hitlOverlayCourse", payload: overlay });
  });
}

/** `?view=camera` page: re-emits the leader's messages on the local Bus and draws fiducials + frame counter. */
export function mountScreenView(bus: Bus, root: HTMLElement): void {
  const channel = new BroadcastChannel(CHANNEL);
  channel.onmessage = (e: MessageEvent<ScreenMessage>) => {
    const m = e.data;
    switch (m.t) {
      case "syncCourse": bus.emit("syncCourse", m.payload); break;
      case "hitlTarget": bus.emit("hitlTarget", m.payload); break;
      case "hitlOverlayCourse": bus.emit("hitlOverlayCourse", m.payload); break;
    }
  };

  document.body.style.margin = "0";
  document.body.style.overflow = "hidden";
  document.body.style.cursor = "none";
  root.replaceChildren();
  Object.assign(root.style, { position: "fixed", inset: "0", pointerEvents: "none", zIndex: "10" });

  const corners: [string, string][] = [["top", "left"], ["top", "right"], ["bottom", "left"], ["bottom", "right"]];
  for (const [v, h] of corners) {
    const sq = document.createElement("div");
    Object.assign(sq.style, {
      position: "absolute", [v]: "0", [h]: "0", width: `${FIDUCIAL_PX}px`, height: `${FIDUCIAL_PX}px`,
      background: "#000", border: `${FIDUCIAL_BORDER_PX}px solid #fff`,
    });
    root.append(sq);
  }

  const counter = document.createElement("div");
  Object.assign(counter.style, {
    position: "absolute", top: `${FIDUCIAL_PX + 2 * FIDUCIAL_BORDER_PX + 8}px`, left: "50%", transform: "translateX(-50%)",
    font: "600 28px 'Atkinson Hyperlegible Mono', monospace", color: "#fff", background: "#000", padding: "2px 10px",
  });
  root.append(counter);

  let frame = 0;
  const tick = (): void => {
    counter.textContent = String(frame++).padStart(6, "0");
    requestAnimationFrame(tick);
  };
  requestAnimationFrame(tick);
}
