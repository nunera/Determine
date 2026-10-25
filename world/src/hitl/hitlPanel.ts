/**
 * HITL panel: mounted only once the bridge's hello reports `hitl.enabled` (world_bridge.py --hitl). Mode selectors
 * send `setHitl`; every readout shows what the bridge reports (`{t:'hitl'}` telemetry at 5–10 Hz), never the
 * request. Also hosts the head-locked virtual target controls and the "course objects in overlay" toggle, and the
 * 0x11 preview (the JPEG as sent to the engine).
 */
import {
  HITL_CAMERA_MODES, HITL_RANGE_MODES, HITL_TARGET_MAX_MM, HITL_TARGET_MIN_MM, RANGE_STATUS_NAMES, RANGE_VALID,
  type Bus, type FromBridge, type HitlBoardStatus, type HitlCameraMode, type HitlRangeMode, type HitlReflectance,
  type HitlTargetSettings,
} from "../contract";
import { el, setText } from "../hud/dom";
import { DEFAULT_HITL_TARGET, clampTarget } from "./virtualTarget";

const REFLECTANCES: readonly HitlReflectance[] = ["white", "grey", "glass"];

type Telemetry = Extract<FromBridge, { t: "hitl" }>;

function rangeText(mm: number | null, status: number | null): string {
  if (status === null) return "—";
  const name = RANGE_STATUS_NAMES[status] ?? `status ${status}`;
  return status === RANGE_VALID && mm !== null ? `${mm} mm` : name;
}

function boardText(b: HitlBoardStatus, extra = ""): string {
  if (b.addr === null) return "not heard";
  const age = b.ageMs === null ? "—" : `${Math.round(b.ageMs)} ms`;
  const rssi = b.rssi === null ? "—" : `${b.rssi} dBm`;
  const faults = b.faults === null ? "—" : `0x${b.faults.toString(16)}`;
  return `${b.addr} · age ${age} · rssi ${rssi} · faults ${faults}${extra}`;
}

export function createHitlPanel(bus: Bus, root: HTMLElement): void {
  let mounted = false;
  bus.on("bridge", (m) => {
    if (m.t !== "hello" || !m.hitl?.enabled || mounted) return;
    mounted = true;
    mount(bus, root, m.hitl.range, m.hitl.camera, m.hitl.compositeAvailable, m.hitl.listen);
  }, true);
}

function mount(bus: Bus, root: HTMLElement, range0: HitlRangeMode, camera0: HitlCameraMode,
  compositeAvailable: boolean, listen: string | null): void {
  const select = <T extends string>(options: readonly T[], value: T, disabled: Partial<Record<T, boolean>> = {}) =>
    el("select", {}, ...options.map((o) => el("option", { value: o, selected: o === value, disabled: disabled[o] }, o)));

  const rangeSel = select(HITL_RANGE_MODES, range0);
  const cameraSel = select(HITL_CAMERA_MODES, camera0, { composite: !compositeAvailable });
  rangeSel.addEventListener("change", () => bus.emit("setHitl", { range: rangeSel.value as HitlRangeMode }));
  cameraSel.addEventListener("change", () => bus.emit("setHitl", { camera: cameraSel.value as HitlCameraMode }));

  const realOut = el("span", { class: "mono" }, "—");
  const worldOut = el("span", { class: "mono" }, "—");
  const outOut = el("span", { class: "mono" }, "—");
  const cueOut = el("span", { class: "mono" }, "—");
  const camOut = el("span", { class: "mono" }, "—");
  const compOut = el("span", { class: "mono" }, "—");
  const errOut = el("span", { class: "mono" }, "");
  const preview = el("img", { alt: "Frame as sent to the engine", width: 320, height: 240 });
  preview.style.width = "100%";
  preview.style.height = "auto";
  preview.style.background = "#000";

  // ---------------------------------------------------------------- virtual target
  let settings: HitlTargetSettings = { ...DEFAULT_HITL_TARGET };
  const onBox = el("input", { type: "checkbox" });
  const dist = el("input", { type: "range", min: HITL_TARGET_MIN_MM, max: HITL_TARGET_MAX_MM, step: 10, value: settings.distanceMm });
  const distOut = el("span", { class: "mono" }, `${settings.distanceMm} mm`);
  const width = el("input", { type: "number", min: 20, max: 3000, step: 10, value: settings.widthMm, style: "width:5em" });
  const height = el("input", { type: "number", min: 20, max: 3000, step: 10, value: settings.heightMm, style: "width:5em" });
  const refl = select(REFLECTANCES, settings.reflectance);
  const courseBox = el("input", { type: "checkbox" });

  const pushTarget = (): void => {
    settings = clampTarget({
      on: onBox.checked, distanceMm: Number(dist.value), widthMm: Number(width.value), heightMm: Number(height.value),
      reflectance: refl.value as HitlReflectance,
    });
    setText(distOut, `${settings.distanceMm} mm`);
    bus.emit("hitlTarget", settings);
  };
  for (const input of [onBox, dist, width, height, refl]) input.addEventListener("input", pushTarget);
  courseBox.addEventListener("change", () => bus.emit("hitlOverlayCourse", { on: courseBox.checked }));
  bus.emit("hitlTarget", settings);
  bus.emit("hitlOverlayCourse", { on: false });

  const row = (label: string, ...children: (Node | string)[]): HTMLElement =>
    el("div", { style: "display:flex;gap:6px;align-items:center;flex-wrap:wrap;margin:2px 0" },
      el("span", { class: "muted", style: "min-width:5.5em" }, label), ...children);

  const panel = el("section", {
    class: "panel hitl", "aria-label": "Hardware in the loop",
    style: "position:fixed;right:12px;top:72px;width:330px;max-height:calc(100vh - 96px);overflow:auto;"
      + "pointer-events:auto;z-index:5;font-size:13px",
  },
    el("header", { class: "panel-head" }, el("h2", {}, "HITL"), el("span", { class: "mono muted" }, listen ?? "")),
    row("Range", rangeSel),
    row("Camera", cameraSel, compositeAvailable ? "" : el("span", { class: "muted" }, "(no Pillow: composite off)")),
    row("Real", realOut),
    row("World", worldOut),
    row("Out", outOut),
    row("Cue", cueOut),
    row("Cam", camOut),
    row("Composite", compOut),
    row("Error", errOut),
    preview,
    el("h3", { style: "margin:8px 0 2px;font-size:13px" }, "Virtual target (head-locked)"),
    row("On", onBox),
    row("Distance", dist, distOut),
    row("Size mm", width, "×", height),
    row("Surface", refl),
    row("Overlay", el("label", {}, courseBox, " course objects in overlay")),
  );
  root.append(panel);

  bus.on("bridge", (m) => {
    if (m.t !== "hitl") return;
    update(m);
  });

  function update(m: Telemetry): void {
    if (document.activeElement !== rangeSel) rangeSel.value = m.range.mode;
    if (document.activeElement !== cameraSel) cameraSel.value = m.camera.mode;
    const r = m.range;
    setText(realOut, rangeText(r.realMm, r.realStatus));
    const age = r.worldAgeMs === null ? "" : ` · ${Math.round(r.worldAgeMs)} ms old`;
    setText(worldOut, rangeText(r.worldMm, r.worldStatus) + age);
    setText(outOut, `${rangeText(r.outMm, r.outStatus)} · source ${r.source}`);
    setText(cueOut, boardText(m.cue));
    setText(camOut, boardText(m.cam, m.cam.fps === null ? "" : ` · ${m.cam.fps.toFixed(1)} fps`));
    setText(compOut, `${m.camera.mode} · out ${m.camera.framesOut} · composited ${m.camera.composited}`);
    setText(errOut, m.camera.lastError ?? "");
  }

  let previewUrl: string | null = null;
  bus.on("hitlPreview", ({ jpeg }) => {
    const url = URL.createObjectURL(jpeg);
    preview.src = url;
    if (previewUrl) URL.revokeObjectURL(previewUrl);
    previewUrl = url;
  });
}
