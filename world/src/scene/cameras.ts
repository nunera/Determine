/**
 * Camera language: director (eased 3/4-rear follow that dollies to a side profile while something is within
 * reach of the sensor, so head-height hazards read against the cane), wearer (first person at the brow),
 * orbit (free, around the wearer), plan (orthographic architectural plan, walking direction up).
 */
import * as THREE from "three";
import { OrbitControls } from "three/addons/controls/OrbitControls.js";
import type { CameraMode } from "../contract";
import { LAYER_OVERLAY, LAYER_WEARER } from "./stage";

export interface Subject {
  feet: THREE.Vector3;
  brow: THREE.Vector3;
  yaw: number;
  /** Ground truth to the nearest object ahead (m), or null. Drives the director's profile shot. */
  aheadM: number | null;
  /** Paused at the start of a course: hold the composed hero shot. */
  hero: boolean;
}

const PLAN_HEIGHT_M = 11;

export class CameraRig {
  readonly persp: THREE.PerspectiveCamera;
  readonly ortho: THREE.OrthographicCamera;
  readonly controls: OrbitControls;
  mode: CameraMode = "director";
  private pos = new THREE.Vector3();
  private look = new THREE.Vector3();
  private profile = 0;           // 0 = 3/4 rear … 1 = side profile (eased)
  private profileWanted = false;
  private lastNearAt = -1e9;
  private snapNext = true;

  constructor(dom: HTMLElement) {
    this.persp = new THREE.PerspectiveCamera(34, 1, 0.05, 220);
    this.ortho = new THREE.OrthographicCamera(-1, 1, 1, -1, 0.5, 80);
    for (const c of [this.persp, this.ortho]) { c.layers.enable(LAYER_OVERLAY); c.layers.enable(LAYER_WEARER); }
    this.controls = new OrbitControls(this.persp, dom);
    this.controls.enableDamping = true;
    this.controls.dampingFactor = 0.08;
    this.controls.minDistance = 1.2;
    this.controls.maxDistance = 30;
    this.controls.maxPolarAngle = Math.PI * 0.49;
    this.controls.enabled = false;
  }

  get active(): THREE.Camera { return this.mode === "plan" ? this.ortho : this.persp; }

  setMode(mode: CameraMode, s: Subject): void {
    this.mode = mode;
    this.controls.enabled = mode === "orbit";
    if (mode === "wearer") this.persp.layers.disable(LAYER_WEARER); else this.persp.layers.enable(LAYER_WEARER);
    this.persp.fov = mode === "wearer" ? 70 : mode === "orbit" ? 42 : 34;
    this.persp.updateProjectionMatrix();
    if (mode === "orbit") {
      this.controls.target.copy(s.brow).setY(s.brow.y - 0.5);
      this.controls.update();
    }
    this.snapNext = mode !== "orbit";
  }

  /** Next update jumps instead of easing (course change). */
  snap(): void { this.snapNext = true; }

  resize(w: number, h: number): void {
    const aspect = w / Math.max(1, h);
    this.persp.aspect = aspect;
    this.persp.updateProjectionMatrix();
    this.ortho.top = PLAN_HEIGHT_M / 2;
    this.ortho.bottom = -PLAN_HEIGHT_M / 2;
    this.ortho.left = (-PLAN_HEIGHT_M / 2) * aspect;
    this.ortho.right = (PLAN_HEIGHT_M / 2) * aspect;
    this.ortho.updateProjectionMatrix();
  }

  update(s: Subject, dt: number, now: number): void {
    const f = new THREE.Vector3(-Math.sin(s.yaw), 0, -Math.cos(s.yaw));
    const r = new THREE.Vector3(Math.cos(s.yaw), 0, -Math.sin(s.yaw));
    switch (this.mode) {
      case "wearer": {
        this.persp.position.copy(s.brow).addScaledVector(f, 0.02);
        this.persp.rotation.set(-0.07, s.yaw, 0, "YXZ");
        return;
      }
      case "orbit": {
        const target = s.brow.clone().setY(s.brow.y - 0.5);
        const delta = target.clone().sub(this.controls.target);
        this.controls.target.add(delta);
        this.persp.position.add(delta);
        this.controls.update();
        return;
      }
      case "plan": {
        const c = s.feet.clone().addScaledVector(f, 2.2);
        this.ortho.position.set(c.x, s.feet.y + 30, c.z);
        this.ortho.up.copy(f);
        this.ortho.lookAt(c.x, s.feet.y, c.z);
        return;
      }
      case "director": {
        // profile while something is within ~1.6 m ahead; release 1 s after it is gone (hysteresis)
        if (s.aheadM !== null && s.aheadM <= 1.6) { this.profileWanted = true; this.lastNearAt = now; }
        else if (s.aheadM === null || s.aheadM > 2.2) { if (now - this.lastNearAt > 1.0) this.profileWanted = false; }
        const k = 1 - Math.exp(-dt * 1.4);
        this.profile += ((this.profileWanted && !s.hero ? 1 : 0) - this.profile) * (this.snapNext ? 1 : k);
        const p = this.profile;
        let pos: THREE.Vector3, look: THREE.Vector3;
        if (s.hero) {
          pos = s.feet.clone().addScaledVector(f, -5.4).addScaledVector(r, 2.9).setY(s.feet.y + 3.1);
          look = s.brow.clone().addScaledVector(f, 1.5).setY(s.feet.y + 1.0);
        } else {
          const rear = s.feet.clone().addScaledVector(f, -4.9).addScaledVector(r, 2.1).setY(s.feet.y + 2.7);
          const side = s.feet.clone().addScaledVector(f, 0.7).addScaledVector(r, 4.4).setY(s.feet.y + 1.55);
          pos = rear.lerp(side, p);
          const lookRear = s.brow.clone().addScaledVector(f, 0.8).setY(s.feet.y + 1.05);
          const lookSide = s.brow.clone().addScaledVector(f, 0.5).setY(s.feet.y + 1.0);
          look = lookRear.lerp(lookSide, p);
        }
        const kk = this.snapNext ? 1 : 1 - Math.exp(-dt * 2.6);
        this.pos.lerp(pos, kk);
        this.look.lerp(look, kk);
        if (this.snapNext) { this.pos.copy(pos); this.look.copy(look); }
        this.snapNext = false;
        this.persp.position.copy(this.pos);
        this.persp.up.set(0, 1, 0);
        this.persp.lookAt(this.look);
        return;
      }
    }
  }
}
