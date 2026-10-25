/**
 * Determine World: the 3-D campus walk. Owns the renderer, the course timeline, the wearer, the ToF sensor model
 * (15 Hz, wall clock — the real sensor keeps measuring while the course is paused) and the camera board
 * (8 fps head-camera JPEGs). It emits facts on the Bus and draws what the bridge reports; it never decides a hazard.
 */
import * as THREE from "three";
import { RoomEnvironment } from "three/addons/environments/RoomEnvironment.js";
import {
  CAMERA_FPS, RANGE_HZ, type Band, type CameraMode, type CreateWorld, type WearerPose,
} from "../contract";
import { ToFSensor, type ToFResult } from "../sensor/tof";
import { CameraRig, type Subject } from "./cameras";
import { buildCourses, type Course } from "./courses";
import { SensorOverlay } from "./overlays";
import { PostFx } from "./postfx";
import { LAYER_WORLD, MAT, PAPER, Stage, groundMaterial } from "./stage";
import { Wearer } from "./wearer";

const MAX_PIXEL_RATIO = 1.5;
const dbToLin = (db: number): number => (db <= -127 ? 0 : Math.pow(10, db / 20));

export const createWorld: CreateWorld = (canvas, bus) => {
  // ------------------------------------------------------------------ renderer + scene
  Object.assign(canvas.style, { position: "fixed", inset: "0", width: "100%", height: "100%", display: "block" });
  const renderer = new THREE.WebGLRenderer({ canvas, antialias: false, powerPreference: "high-performance" });
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, MAX_PIXEL_RATIO));
  renderer.shadowMap.enabled = true;
  renderer.shadowMap.type = THREE.PCFShadowMap;
  renderer.toneMapping = THREE.NeutralToneMapping;
  renderer.toneMappingExposure = 1.0;
  renderer.outputColorSpace = THREE.SRGBColorSpace;

  const scene = new THREE.Scene();
  scene.background = PAPER.clone();
  scene.fog = new THREE.Fog(PAPER.clone(), 34, 115);
  // Reflections only where they mean something (glass); matte maquette surfaces take light from the key + fill,
  // so cast shadows and AO keep their value structure.
  const pmrem = new THREE.PMREMGenerator(renderer);
  MAT.glass.envMap = pmrem.fromScene(new RoomEnvironment(), 0.04).texture;

  const hemi = new THREE.HemisphereLight(0xffffff, 0xd2cbbf, 1.55);
  scene.add(hemi);
  const sun = new THREE.DirectionalLight(0xfff5e8, 3.1);
  sun.castShadow = true;
  sun.shadow.mapSize.set(2048, 2048);
  const sc = sun.shadow.camera;
  sc.left = -11; sc.right = 11; sc.top = 11; sc.bottom = -11; sc.near = 1; sc.far = 50;
  sun.shadow.bias = -0.0004;
  sun.shadow.normalBias = 0.03;
  sun.shadow.camera.layers.enableAll();
  scene.add(sun, sun.target);

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

  // head camera (ESP32-CAM: ~66° × 50°) — world layer only: no overlays, no own body
  const headCam = new THREE.PerspectiveCamera(50, 4 / 3, 0.05, 60);
  headCam.layers.set(LAYER_WORLD);

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
  let headBuf = new Uint8Array(0);

  /** Renders the head camera; returns top-down RGBA rows in a fresh buffer (transferred to the encoder). */
  function renderHead(w: number, h: number): ArrayBuffer {
    headTarget.setSize(w, h);
    headCam.position.copy(brow);
    headCam.rotation.set(-0.05, pose.yaw, 0, "YXZ");
    headCam.aspect = w / h;
    headCam.updateProjectionMatrix();
    renderer.setRenderTarget(headTarget);
    renderer.render(scene, headCam);
    renderer.setRenderTarget(null);
    if (headBuf.length !== w * h * 4) headBuf = new Uint8Array(w * h * 4);
    renderer.readRenderTargetPixels(headTarget, 0, 0, w, h, headBuf);
    const out = new Uint8Array(w * h * 4);
    const row = w * 4;
    for (let y = 0; y < h; y++) out.set(headBuf.subarray((h - 1 - y) * row, (h - y) * row), y * row);  // flip
    return out.buffer;
  }

  const encoder = new Worker(new URL("./jpegWorker.ts", import.meta.url), { type: "module" });
  const pending = new Map<number, { resolve: (b: Blob) => void; reject: (e: Error) => void }>();
  let nextEncodeId = 1;
  encoder.onmessage = (e: MessageEvent<{ id: number; jpeg?: Blob; error?: string }>) => {
    const p = pending.get(e.data.id);
    if (!p) return;
    pending.delete(e.data.id);
    if (e.data.jpeg) p.resolve(e.data.jpeg); else p.reject(new Error(e.data.error ?? "JPEG encode failed"));
  };

  function toJpeg(pixels: ArrayBuffer, width: number, height: number, quality: number): Promise<Blob> {
    const id = nextEncodeId++;
    const { promise, resolve, reject } = Promise.withResolvers<Blob>();
    pending.set(id, { resolve, reject });
    encoder.postMessage({ id, width, height, pixels, quality }, [pixels]);
    return promise;
  }

  let encoding = false;
  function cameraFrame(): void {
    if (encoding) return;           // never queue stale frames
    encoding = true;
    const simMs = performance.now() - t0;
    toJpeg(renderHead(320, 240), 320, 240, 0.7)
      .then((jpeg) => bus.emit("cameraJpeg", { jpeg, simMs }))
      .catch(() => undefined)        // a failed encode is a missing frame; the camera board reports CAPTURE_FAIL
      .finally(() => { encoding = false; });
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
      default:
        break;
    }
  });
  bus.on("link", ({ connected }) => { if (!connected) { setTrusted(false); alertBand = null; } });

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
    rig.resize(w, h);
    // Centre the 3-D subject in the free stage above the score strip (HUD: top bar ≈ 56 px, score ≈ 19vh).
    const score = Math.min(236, Math.max(128, h * 0.19));
    const shift = rig.mode === "wearer" ? 0 : Math.round((score - 56) / 2);
    if (shift) rig.persp.setViewOffset(w, h, 0, shift, w, h); else rig.persp.clearViewOffset();
    fx.setSize(w, h);
  }
  window.addEventListener("resize", resize);

  // ------------------------------------------------------------------ adaptive quality
  // A slow GPU must not starve the camera board (the engine reports frame_stale) or slow the ToF stream: shed
  // AO, then shadows, then resolution while frames stay over budget. Never upgrades (no oscillation mid-demo).
  // `?quality=low` starts at the lowest level.
  const FRAME_BUDGET_MS = 40;
  let quality = new URLSearchParams(location.search).get("quality") === "low" ? 3 : 0;
  let frameEma = 16, sinceChange = 0;
  function applyQuality(): void {
    fx.setAo(quality < 1);
    renderer.shadowMap.enabled = quality < 2;
    sun.castShadow = quality < 2;
    renderer.setPixelRatio(quality >= 3 ? 0.75 : Math.min(window.devicePixelRatio, MAX_PIXEL_RATIO));
    resize();
    console.info(`Determine World render quality level ${quality} (0 = full)`);
  }
  if (quality) applyQuality();

  // ------------------------------------------------------------------ loop
  let last = performance.now();
  let nextRange = 0, nextCam = 0, nextCourseEmit = 0, nextPoseEmit = 0;
  let raf = 0;
  function frame(): void {
    raf = requestAnimationFrame(frame);
    const nowMs = performance.now();
    const frameMs = nowMs - last;
    // Sim time follows wall time (the engine measures closing speed on wall time); clamp only real stalls.
    const dt = Math.min(0.5, frameMs / 1000);
    last = nowMs;
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
    if (nowMs >= nextRange) { measure(); nextRange = nowMs + 1000 / RANGE_HZ; }
    overlay.update(brow, pose.yaw, feet, lastTof, stage.colliders, alertBand);
    // the key light follows the wearer so shadows stay crisp where the story is
    sun.position.set(feet.x - 7, feet.y + 12, feet.z + 5);
    sun.target.position.copy(feet);
    rig.update(subject(), dt, now);
    if (quality >= 3) renderer.render(scene, rig.active);
    else fx.render();
    if (nowMs >= nextCam) { cameraFrame(); nextCam = nowMs + 1000 / CAMERA_FPS; }
    if (nowMs >= nextCourseEmit) { emitCourse(); nextCourseEmit = nowMs + 100; }
    if (nowMs >= nextPoseEmit) {
      bus.emit("pose", { x: pose.x, z: pose.z, yaw: pose.yaw, speedMps: pose.speedMps });
      nextPoseEmit = nowMs + 50;
    }
  }

  // ------------------------------------------------------------------ start
  bus.emit("courses", courses.map((c) => c.info));
  selectCourse(0);
  setMode("director");
  frame();

  return {
    async captureHeadJpeg(width, height, quality) {
      return toJpeg(renderHead(width, height), width, height, quality);
    },
    dispose() {
      cancelAnimationFrame(raf);
      window.removeEventListener("resize", resize);
      rig.controls.dispose();
      headTarget.dispose();
      encoder.terminate();
      pmrem.dispose();
      renderer.dispose();
    },
  };
};
