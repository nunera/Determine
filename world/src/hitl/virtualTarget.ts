/**
 * HITL head-locked virtual target: a thin box placed every frame straight ahead of the wearer's brow sensor at
 * `distanceMm` (near face), on the wearer's heading. It is one ToF collider (glass → kind "glass", which tof.ts
 * reports as UNKNOWN) and one mesh on LAYER_HITL, the layer the overlay PNG renders alone. The head camera and the
 * director cameras enable LAYER_HITL too, so the target is visible everywhere except the composite stays clean.
 * Off (the default) → collider disabled and mesh hidden: the World behaves exactly as without HITL.
 */
import * as THREE from "three";
import {
  HITL_TARGET_MAX_MM, HITL_TARGET_MIN_MM, type HitlReflectance, type HitlTargetSettings,
} from "../contract";
import type { Collider } from "../sensor/tof";
import type { Stage } from "../scene/stage";

/** Render layer of HITL virtual objects (0 world, 1 overlays, 2 wearer — see stage.ts). */
export const LAYER_HITL = 3;

/** Datasheet anchors (88 % white, 17 % grey); glass is reported UNKNOWN by kind, its ρ only matters for display. */
export const HITL_REFLECTANCE: Record<HitlReflectance, number> = { white: 0.88, grey: 0.17, glass: 0.08 };

export const DEFAULT_HITL_TARGET: HitlTargetSettings = {
  on: false, distanceMm: 500, widthMm: 300, heightMm: 300, reflectance: "white",
};

const DEPTH_M = 0.01;
const clamp = (v: number, lo: number, hi: number): number => Math.min(hi, Math.max(lo, v));

export function clampTarget(s: HitlTargetSettings): HitlTargetSettings {
  return {
    on: s.on,
    distanceMm: clamp(Math.round(s.distanceMm), HITL_TARGET_MIN_MM, HITL_TARGET_MAX_MM),
    widthMm: clamp(Math.round(s.widthMm), 20, 3000),
    heightMm: clamp(Math.round(s.heightMm), 20, 3000),
    reflectance: s.reflectance,
  };
}

export class VirtualTarget {
  readonly mesh: THREE.Mesh;
  private readonly collider: Collider;
  private settings: HitlTargetSettings = DEFAULT_HITL_TARGET;
  private readonly mats: Record<HitlReflectance, THREE.MeshStandardMaterial> = {
    white: new THREE.MeshStandardMaterial({ color: 0xf2f2ef, roughness: 0.9 }),
    grey: new THREE.MeshStandardMaterial({ color: 0x6e6e6e, roughness: 0.9 }),
    glass: new THREE.MeshStandardMaterial({ color: 0x9fc4d8, roughness: 0.1, transparent: true, opacity: 0.4 }),
  };

  constructor(scene: THREE.Scene, stage: Stage) {
    this.mesh = new THREE.Mesh(new THREE.BoxGeometry(1, 1, 1), this.mats.white);
    this.mesh.layers.set(LAYER_HITL);
    this.mesh.visible = false;
    scene.add(this.mesh);
    this.collider = stage.collide("hitl-target", 0, 0, 0, 1, 1, DEPTH_M, 0, this.mesh);
    this.collider.enabled = false;
  }

  get on(): boolean { return this.settings.on; }
  get distanceM(): number { return this.settings.distanceMm / 1000; }

  set(next: HitlTargetSettings): void {
    const s = clampTarget(next);
    this.settings = s;
    this.collider.kind = s.reflectance === "glass" ? "glass" : "hitl-target";
    this.collider.reflectance = HITL_REFLECTANCE[s.reflectance];
    this.mesh.material = this.mats[s.reflectance];
    this.collider.enabled = s.on;
    this.mesh.visible = s.on;
  }

  /** Places the box ahead of the brow sensor (yaw 0 = facing −Z, as the ToF model and the wearer use). */
  update(brow: THREE.Vector3, yaw: number): void {
    if (!this.settings.on) return;
    const s = this.settings;
    const ahead = s.distanceMm / 1000 + DEPTH_M / 2;
    const x = brow.x - Math.sin(yaw) * ahead, z = brow.z - Math.cos(yaw) * ahead;
    const w = s.widthMm / 1000, h = s.heightMm / 1000;
    this.mesh.position.set(x, brow.y, z);
    this.mesh.scale.set(w, h, DEPTH_M);
    this.mesh.rotation.set(0, yaw, 0);
    const c = this.collider;
    c.cx = x; c.cy = brow.y; c.cz = z;
    c.hx = w / 2; c.hy = h / 2; c.hz = DEPTH_M / 2;
    c.yaw = yaw;
  }

  dispose(): void {
    this.mesh.geometry.dispose();
    for (const m of Object.values(this.mats)) m.dispose();
  }
}
