/**
 * Determine World: the 3-D campus walk. Owns the renderer, the course timeline, the wearer, the ToF sensor model
 * (15 Hz, wall clock — the real sensor keeps measuring while the course is paused) and the camera board
 * (8 fps head-camera JPEGs). It emits facts on the Bus and draws what the bridge reports; it never decides a hazard.
 *
 * HITL (bridge hello.hitl.enabled): the head-locked virtual target joins the ToF colliders and LAYER_HITL; while the
 * bridge's camera mode is `composite` the head camera renders LAYER_HITL alone (plus the course's sensed pieces when
 * `hitlOverlayCourse` is on) over a transparent clear and emits `hitlOverlayPng` ≤ HITL_OVERLAY_FPS.
 * `options.view === "camera"` (screen-in-the-loop page): the canvas shows only the head camera at display
 * resolution; no ToF stream, no camera board; course time follows `syncCourse`.
 */
import * as THREE from "three";
import { RoomEnvironment } from "three/addons/environments/RoomEnvironment.js";
import {
  CAMERA_FPS, HITL_OVERLAY_FPS, HITL_OVERLAY_HEIGHT, HITL_OVERLAY_WIDTH, RANGE_HZ,
  type Band, type CameraMode, type CreateWorld, type HitlCameraMode, type WearerPose,
} from "../contract";
import { LAYER_HITL, VirtualTarget } from "../hitl/virtualTarget";
import { ToFSensor, type ToFResult } from "../sensor/tof";
import { CameraRig, type Subject } from "./cameras";
import { buildCourses, type Course } from "./courses";
import { SensorOverlay } from "./overlays";
import { PostFx } from "./postfx";
import { LAYER_WORLD, MAT, PAPER, Stage, groundMaterial } from "./stage";
import { Wearer } from "./wearer";

const MAX_PIXEL_RATIO = 1.5;
const dbToLin = (db: number): number => (db <= -127 ? 0 : Math.pow(10, db / 20));

export const createWorld: CreateWorld = (canvas, bus, options) => {
  const cameraView = options?.view === "camera";
  // ------------------------------------------------------------------ renderer + scene
  Object.assign(canvas.style, { position: "fixed", inset: "0", width: "100%", height: "100%", display: "block" });
  const renderer = new THREE.WebGLRenderer({ canvas, antialias: cameraView, powerPreference: "high-performance" });
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, MAX_PIXEL_RATIO));
  renderer.shadowMap.enabled = true;
  renderer.shadowMap.type = THREE.PCFSoftShadowMap;
  renderer.toneMapping = THREE.NeutralToneMapping;
  renderer.toneMappingExposure = 0.9;
  renderer.outputColorSpace = THREE.SRGBColorSpace;

  const scene = new THREE.Scene();
  scene.background = PAPER.clone();
  scene.fog = new THREE.Fog(PAPER.clone(), 34, 115);
  // A small procedural studio environment gives metal/glass a readable response, without downloaded assets.
  const pmrem = new THREE.PMREMGenerator(renderer);
  const room = new RoomEnvironment();
  const environment = pmrem.fromScene(room, 0.04);
  scene.environment = environment.texture;
  scene.environmentIntensity = 0.5;
  room.dispose();
  pmrem.dispose();

  const hemi = new THREE.HemisphereLight(0xf4f8ff, 0xb5c1cd, 1.0);
  hemi.layers.enable(LAYER_HITL);
  scene.add(hemi);
  const sun = new THREE.DirectionalLight(0xfffaf5, 2.2);
  sun.layers.enable(LAYER_HITL);
  sun.castShadow = true;
  sun.shadow.mapSize.set(2048, 2048);
  const sc = sun.shadow.camera;
  sc.left = -11; sc.right = 11; sc.top = 11; sc.bottom = -11; sc.near = 1; sc.far = 50;
  sun.shadow.bias = -0.0004;
  sun.shadow.normalBias = 0.03;
  sun.shadow.camera.layers.enableAll();
  scene.add(sun, sun.target);
  const fill = new THREE.DirectionalLight(0xe0ecff, 0.35);
  fill.position.set(6, 5, -8);
  fill.layers.enable(LAYER_HITL);
  scene.add(fill);

  const ground = new THREE.Mesh(new THREE.PlaneGeometry(900, 900), groundMaterial());
  ground.rotation.x = -Math.PI / 2;
  ground.position.z = -150;
  ground.receiveShadow = true;
  scene.add(ground);

  const stage = new Stage(scene);
  // promenade linking every station
  stage.block({ x: 0, y: 0.006, z: -125, w: 4.6, h: 0.012, d: 300, mat: MAT.paving, castShadow: false });
  const courses: Course[] = buildCourses(stage);
  const wearer = new Wearer(scene);
  const overlay = new SensorOverlay(scene);
  const rig = new CameraRig(canvas);
  const fx = new PostFx(renderer, scene, rig.active);
  const sensor = new ToFSensor(1);
  const target = new VirtualTarget(scene, stage);
  for (const c of [rig.persp, rig.ortho]) c.layers.enable(LAYER_HITL);

  // head camera (ESP32-CAM: ~66° × 50°) — world + HITL layers only: no overlays, no own body
  const HEAD_NEAR_M = 0.05;
  const headCam = new THREE.PerspectiveCamera(50, 4 / 3, HEAD_NEAR_M, 60);
  headCam.layers.set(LAYER_WORLD);
  headCam.layers.enable(LAYER_HITL);

  // ------------------------------------------------------------------ state
  let idx = 0;
  let tS = 0;
  let playing = false;
  let alertBand: Band | null = null;
  let trusted = false;
  let lastTof: ToFResult | null = null;
  const t0 = performance.now();
  const course = (): Course => courses[idx];
  const brow = new THREE.Vector3();
  const feet = new THREE.Vector3();
  const pose: WearerPose & { s: number; floorY: number } = { x: 0, z: 0, yaw: 0, speedMps: 0, s: 0, floorY: 0 };

  const emitCourse = (): void => bus.emit("course", { id: course().info.id, tS, playing });

  function placeWearer(now: number): void {
    const c = course();
    const { s, v } = c.wearerAt(tS);
    const p = c.path.at(s);
    pose.x = p.x; pose.z = p.z; pose.s = s; pose.speedMps = v; pose.floorY = c.floorY;
    pose.yaw = Math.atan2(-p.tx, -p.tz);
    for (const a of c.actors) a.update(tS, s);
    wearer.update(p.x, c.floorY, p.z, pose.yaw, s, v, c.walk.at(c.walk.duration / 2).v || 1, now);
    wearer.browWorld(brow);
    feet.set(p.x, c.floorY, p.z);
  }

  function selectCourse(i: number): void {
    idx = (i + courses.length) % courses.length;
    tS = 0;
    for (const a of course().actors) a.reset();
    sensor.reseed(idx + 1);
    rig.snap();
    placeWearer((performance.now() - t0) / 1000);
    emitCourse();
  }

  // ------------------------------------------------------------------ sensor + camera board
  function measure(): void {
    const tof = sensor.measure({ x: brow.x, y: brow.y, z: brow.z, yaw: pose.yaw }, stage.colliders);
    lastTof = tof;
    bus.emit("sensor", { simMs: performance.now() - t0, mm: tof.mm, status: tof.status, kind: tof.kind, truthM: tof.truthM });
  }

  // 8-bit sRGB target: the GPU does the colour encoding, read-back is a byte copy (no per-pixel JS maths).
  // Render targets skip tone mapping, so the camera feed is slightly brighter than the screen.
  const headTarget = new THREE.WebGLRenderTarget(320, 240);
  headTarget.texture.colorSpace = THREE.SRGBColorSpace;
  const overlayTarget = new THREE.WebGLRenderTarget(HITL_OVERLAY_WIDTH, HITL_OVERLAY_HEIGHT);
  overlayTarget.texture.colorSpace = THREE.SRGBColorSpace;
  let headBuf = new Uint8Array(0);

  /** Puts the head camera at the brow; the near plane shrinks so a virtual target closer than 5 cm still shows. */
  function aimHead(aspect: number): void {
    headCam.position.copy(brow);
    headCam.rotation.set(-0.05, pose.yaw, 0, "YXZ");
    headCam.aspect = aspect;
    headCam.near = target.on ? Math.min(HEAD_NEAR_M, target.distanceM * 0.5) : HEAD_NEAR_M;
    headCam.updateProjectionMatrix();
  }

  /** Renders the head camera into `rt`; returns top-down RGBA rows in a fresh buffer (transferred to the encoder). */
  function renderInto(rt: THREE.WebGLRenderTarget, w: number, h: number): ArrayBuffer {
    renderer.setRenderTarget(rt);
    renderer.render(scene, headCam);
    renderer.setRenderTarget(null);
    if (headBuf.length !== w * h * 4) headBuf = new Uint8Array(w * h * 4);
    renderer.readRenderTargetPixels(rt, 0, 0, w, h, headBuf);
    const out = new Uint8Array(w * h * 4);
    const row = w * 4;
    for (let y = 0; y < h; y++) out.set(headBuf.subarray((h - 1 - y) * row, (h - y) * row), y * row);  // flip
    return out.buffer;
  }

  function renderHead(w: number, h: number): ArrayBuffer {
    headTarget.setSize(w, h);
    aimHead(w / h);
    return renderInto(headTarget, w, h);
  }

  /** HITL overlay: LAYER_HITL only, transparent background, no fog. */
  const clearColor = new THREE.Color();
  function renderOverlay(): ArrayBuffer {
    const w = HITL_OVERLAY_WIDTH, h = HITL_OVERLAY_HEIGHT;
    aimHead(w / h);
    const mask = headCam.layers.mask;
    const background = scene.background, fog = scene.fog;
    renderer.getClearColor(clearColor);
    const clearAlpha = renderer.getClearAlpha();
    headCam.layers.set(LAYER_HITL);
    scene.background = null;
    scene.fog = null;
    renderer.setClearColor(0x000000, 0);
    try {
      return renderInto(overlayTarget, w, h);
    } finally {
      headCam.layers.mask = mask;
      scene.background = background;
      scene.fog = fog;
      renderer.setClearColor(clearColor, clearAlpha);
    }
  }

  const encoder = new Worker(new URL("./jpegWorker.ts", import.meta.url), { type: "module" });
  const pending = new Map<number, { resolve: (b: Blob) => void; reject: (e: Error) => void }>();
  let nextEncodeId = 1;
  encoder.onmessage = (e: MessageEvent<{ id: number; blob?: Blob; error?: string }>) => {
    const p = pending.get(e.data.id);
    if (!p) return;
    pending.delete(e.data.id);
    if (e.data.blob) p.resolve(e.data.blob); else p.reject(new Error(e.data.error ?? "image encode failed"));
  };

  function encode(pixels: ArrayBuffer, width: number, height: number, type: "image/jpeg" | "image/png",
    quality: number): Promise<Blob> {
    const id = nextEncodeId++;
    const { promise, resolve, reject } = Promise.withResolvers<Blob>();
    pending.set(id, { resolve, reject });
    encoder.postMessage({ id, width, height, pixels, type, quality }, [pixels]);
    return promise;
  }

  let encoding = false;
  function cameraFrame(): void {
    if (encoding) return;           // never queue stale frames
    encoding = true;
    const simMs = performance.now() - t0;
    encode(renderHead(320, 240), 320, 240, "image/jpeg", 0.7)
      .then((jpeg) => bus.emit("cameraJpeg", { jpeg, simMs }))
      .catch(() => undefined)        // a failed encode is a missing frame; the camera board reports CAPTURE_FAIL
      .finally(() => { encoding = false; });
  }

  // HITL state: enabled from the bridge hello, camera mode from hello then live telemetry.
  let hitlEnabled = false;
  let hitlCamera: HitlCameraMode = "real";
  let overlayEncoding = false;
  function overlayFrame(): void {
    if (overlayEncoding) return;
    overlayEncoding = true;
    const simMs = performance.now() - t0;
    encode(renderOverlay(), HITL_OVERLAY_WIDTH, HITL_OVERLAY_HEIGHT, "image/png", 1)
      .then((png) => bus.emit("hitlOverlayPng", { png, simMs }))
      .catch(() => undefined)        // a missing overlay: the bridge forwards the real frame once its PNG is > 1 s old
      .finally(() => { overlayEncoding = false; });
  }

  /** Course set pieces (every collider's mesh except the target itself) join or leave the HITL layer. */
  function setCourseInOverlay(on: boolean): void {
    for (const visual of stage.colliderMesh) {
      if (visual === target.mesh) continue;
      visual.traverse((o) => { if (on) o.layers.enable(LAYER_HITL); else o.layers.disable(LAYER_HITL); });
    }
  }

  // ------------------------------------------------------------------ bus
  bus.on("selectCourse", ({ id }) => {
    const i = courses.findIndex((c) => c.info.id === id);
    if (i >= 0) selectCourse(i);
  });
  bus.on("play", ({ playing: p }) => {
    if (p && tS >= course().info.durationS) { tS = 0; for (const a of course().actors) a.reset(); sensor.reseed(idx + 1); }
    playing = p;
    emitCourse();
  });
  bus.on("restart", () => {
    tS = 0;
    for (const a of course().actors) a.reset();
    sensor.reseed(idx + 1);
    emitCourse();
  });
  bus.on("setCameraMode", ({ mode }) => setMode(mode));
  bus.on("hitlTarget", (s) => target.set(s), true);
  bus.on("hitlOverlayCourse", ({ on }) => setCourseInOverlay(on), true);
  bus.on("syncCourse", ({ id, tS: t, playing: p }) => {
    const i = courses.findIndex((c) => c.info.id === id);
    if (i < 0) return;
    if (i !== idx) selectCourse(i);
    else if (t < tS - 0.25) for (const a of course().actors) a.reset();   // leader rewound
    tS = t;
    playing = p;
  });
  bus.on("bridge", (m) => {
    const now = (performance.now() - t0) / 1000;
    switch (m.t) {
      case "audio":
        if (m.status === "APPLIED" || m.status === "APPLIED_CLAMPED") wearer.audioPulse(dbToLin(m.glDb), dbToLin(m.grDb), now);
        break;
      case "played":
        if (m.status === "APPLIED" || m.status === "APPLIED_CLAMPED") wearer.hapticPulse(Math.max(m.hl, m.hr) / 255, now);
        break;
      case "state":
        setTrusted(m.state === "READY");
        if (m.state !== "READY" && m.state !== "DEGRADED") alertBand = null;
        break;
      case "alert":
        alertBand = m.alert?.band ?? null;
        break;
      case "hello":
        hitlEnabled = m.hitl?.enabled ?? false;
        hitlCamera = m.hitl?.camera ?? "real";
        break;
      case "hitl":
        hitlCamera = m.camera.mode;
        break;
      default:
        break;
    }
  });
  bus.on("link", ({ connected }) => { if (!connected) { setTrusted(false); alertBand = null; hitlEnabled = false; } });

  function setTrusted(t: boolean): void {
    if (t === trusted) return;
    trusted = t;
    overlay.setTrusted(t);
    wearer.setDeviceTrusted(t);
  }

  function setMode(mode: CameraMode): void {
    rig.setMode(mode, subject());
    fx.setCamera(rig.active);
    resize();
    bus.emit("cameraMode", mode);
  }

  function subject(): Subject {
    return { feet, brow, yaw: pose.yaw, aheadM: lastTof?.truthM ?? null, hero: !playing && tS === 0 };
  }

  // ------------------------------------------------------------------ layout
  function resize(): void {
    const w = canvas.clientWidth || window.innerWidth, h = canvas.clientHeight || window.innerHeight;
    renderer.setSize(w, h, false);
    if (cameraView) return;         // the head camera's aspect is set every frame from the canvas size
    rig.resize(w, h);
    // Centre the 3-D subject in the free stage above the score strip (HUD: top bar ≈ 56 px, score ≈ 19vh).
    const score = Math.min(236, Math.max(128, h * 0.19));
    const shift = rig.mode === "wearer" ? 0 : Math.round((score - 56) / 2);
    if (shift) rig.persp.setViewOffset(w, h, 0, shift, w, h); else rig.persp.clearViewOffset();
    fx.setSize(w, h);
  }
  window.addEventListener("resize", resize);

  // ------------------------------------------------------------------ adaptive quality
  // Shed contact AO, then shadows, then resolution. Even the fallback keeps SMAA and the output transform.
  // Embedded `?quality=balanced` starts without AO; `?quality=low` also omits shadow rendering.
  const FRAME_BUDGET_MS = 40;
  const requestedQuality = new URLSearchParams(location.search).get("quality");
  let quality = requestedQuality === "low" ? 2 : requestedQuality === "balanced" ? 1 : 0;
  let frameEma = 16, sinceChange = 0;
  function applyQuality(): void {
    fx.setAo(quality < 1);
    renderer.shadowMap.enabled = quality < 2;
    sun.castShadow = quality < 2;
    renderer.setPixelRatio(Math.min(window.devicePixelRatio, quality >= 3 ? 0.85 : quality >= 2 ? 1.25 : MAX_PIXEL_RATIO));
    resize();
    console.info(`Determine World render quality level ${quality} (0 = full)`);
  }
  if (cameraView) renderer.setPixelRatio(window.devicePixelRatio);  // display resolution for the real camera
  else if (quality) applyQuality();

  // ------------------------------------------------------------------ loop
  let last = performance.now();
  let nextRange = 0, nextCam = 0, nextOverlay = 0, nextCourseEmit = 0, nextPoseEmit = 0;
  let raf = 0;
  function frame(): void {
    raf = requestAnimationFrame(frame);
    const nowMs = performance.now();
    const frameMs = nowMs - last;
    // Sim time follows wall time (the engine measures closing speed on wall time); clamp only real stalls.
    const dt = Math.min(0.5, frameMs / 1000);
    last = nowMs;
    if (cameraView) {
      if (playing) tS = Math.min(course().info.durationS, tS + dt);   // between leader syncs
      placeWearer((nowMs - t0) / 1000);
      target.update(brow, pose.yaw);
      sun.position.set(feet.x - 7, feet.y + 12, feet.z + 5);
      sun.target.position.copy(feet);
      aimHead(canvas.clientWidth / Math.max(1, canvas.clientHeight));
      renderer.render(scene, headCam);
      return;
    }
    frameEma += (frameMs - frameEma) * 0.1;
    if (++sinceChange > 45 && frameEma > FRAME_BUDGET_MS && quality < 3) {
      quality++;
      sinceChange = 0;
      applyQuality();
    }
    const now = (nowMs - t0) / 1000;
    if (playing) {
      tS += dt;
      if (tS >= course().info.durationS) { tS = course().info.durationS; playing = false; emitCourse(); }
    }
    placeWearer(now);
    target.update(brow, pose.yaw);
    if (nowMs >= nextRange) { measure(); nextRange = nowMs + 1000 / RANGE_HZ; }
    overlay.update(brow, pose.yaw, feet, lastTof, stage.colliders, alertBand);
    // the key light follows the wearer so shadows stay crisp where the story is
    sun.position.set(feet.x - 7, feet.y + 12, feet.z + 5);
    sun.target.position.copy(feet);
    rig.update(subject(), dt, now);
    fx.render();
    if (nowMs >= nextCam) { cameraFrame(); nextCam = nowMs + 1000 / CAMERA_FPS; }
    if (hitlEnabled && hitlCamera === "composite" && nowMs >= nextOverlay) {
      overlayFrame();
      nextOverlay = nowMs + 1000 / HITL_OVERLAY_FPS;
    }
    if (nowMs >= nextCourseEmit) { emitCourse(); nextCourseEmit = nowMs + 100; }
    if (nowMs >= nextPoseEmit) {
      bus.emit("pose", { x: pose.x, z: pose.z, yaw: pose.yaw, speedMps: pose.speedMps });
      nextPoseEmit = nowMs + 50;
    }
  }

  // ------------------------------------------------------------------ start
  bus.emit("courses", courses.map((c) => c.info));
  selectCourse(0);
  if (cameraView) resize();
  else setMode("director");
  frame();

  return {
    async captureHeadJpeg(width, height, quality) {
      return encode(renderHead(width, height), width, height, "image/jpeg", quality);
    },
    dispose() {
      cancelAnimationFrame(raf);
      window.removeEventListener("resize", resize);
      rig.controls.dispose();
      headTarget.dispose();
      overlayTarget.dispose();
      target.dispose();
      encoder.terminate();
      fx.dispose();
      scene.environment = null;
      environment.dispose();
      renderer.dispose();
    },
  };
};
