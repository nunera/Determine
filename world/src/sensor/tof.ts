/**
 * Modulino Distance (VL53L4ED) model for wire v2, matching tools/sim/scene.py:
 *  - ONE forward zone: a cone of half-angle FOV_DIAG_DEG/2 = 9° on the wearer's heading at the brow;
 *  - the reading is the nearest FORWARD distance (projection on the beam axis, scene.py's `x_m`) among objects in
 *    the cone, plus Gaussian noise σ = 4 + 0.005·d mm;
 *  - nothing in the cone, or beyond RANGE_LIMIT_MM (1.2 m) → NO_TARGET with mm 0 (not "clear");
 *  - the nearest return is glass → UNKNOWN with mm 0 (scene.py's explicit glass fixture; the sensor cannot be
 *    trusted through glass).
 * scene.py tests boxes analytically (a box must cut the beam height); here the cone is sampled with 37 rays, so a
 * head-height object slightly above or below 1.6 m is seen once it fills enough of the cone. [INFERENCE] about the
 * real sensor: it needs a minimum share of the field to return a target; we require ≥ MIN_HITS of the rays.
 * The floor is not a collider: a 9° cone at 1.6 m first meets a flat floor ≈ 10 m away, far beyond 1.2 m.
 *
 * Reflectance: ST's VL53L4CD datasheet (DS13812, 33 ms budget, indoors, 90 % detection) gives 1200 mm on an 88 %
 * white target and 450 mm on a 17 % grey one. We fit d_max = 1200 · (ρ / 0.88)^0.596 through those two anchors and
 * report NO_TARGET beyond it. [INFERENCE] Applying the L4CD figures to our L4ED module, the power-law fit and the
 * per-kind reflectances below are assumptions until bench-measured; our firmware's 20 ms budget is likely shorter.
 */
import { FOV_DIAG_DEG, RANGE_LIMIT_MM, RANGE_NO_TARGET, RANGE_UNKNOWN, RANGE_VALID } from "../contract";

/** Upright box rotated about the vertical axis. World metres, Three.js axes (Y up). */
export interface Collider {
  kind: string;
  cx: number; cy: number; cz: number;
  hx: number; hy: number; hz: number;   // half extents in the box frame
  yaw: number;                          // rotation about +Y, radians
  /** Disabled colliders are skipped (e.g. an actor that has left the course). */
  enabled: boolean;
  /** Diffuse reflectance at 940 nm, 0–1 (see REFLECTANCE_BY_KIND). */
  reflectance: number;
}

/** [INFERENCE] Typical near-IR reflectances for each set-piece kind; replace with bench measurements. */
export const REFLECTANCE_BY_KIND: Record<string, number> = {
  sign: 0.85,      // white painted sign face
  barrier: 0.85,   // white barrier board
  wall: 0.65,      // limestone / light plaster
  stairs: 0.6,
  person: 0.7,     // light clothing; dark coats are much lower
  bar: 0.35,       // galvanised scaffold tube
  trunk: 0.2,      // bark
  branch: 0.2,
  glass: 0.08,
};
export const DEFAULT_REFLECTANCE = 0.5;

/** Range at which a target of reflectance ρ is still detected (datasheet-anchored fit, capped at 1200 mm). */
export function maxRangeMm(reflectance: number): number {
  return Math.min(RANGE_LIMIT_MM, RANGE_LIMIT_MM * Math.pow(Math.max(0.01, reflectance) / 0.88, 0.596));
}

export const HALF_ANGLE_RAD = (FOV_DIAG_DEG / 2) * Math.PI / 180;
/** Ground-truth search distance for overlays and the run recorder (beyond the sensor's range on purpose). */
export const TRUTH_LIMIT_M = 4;
const RINGS = 3;
const PER_RING = 12;
const MIN_HITS = 4;

export interface SensorPose {
  x: number; y: number; z: number;  // sensor origin
  yaw: number;                      // 0 = facing −Z
}

export interface ToFResult {
  mm: number;           // 0 unless VALID
  status: number;       // RANGE_VALID | RANGE_NO_TARGET | RANGE_UNKNOWN
  kind: string;         // nearest object's kind within TRUTH_LIMIT_M, "" for none
  /** Noise-free nearest forward distance within the cone, metres, up to TRUTH_LIMIT_M; null for none. */
  truthM: number | null;
  /** World-space nearest hit (for drawing), or null. */
  hit: [number, number, number] | null;
  /** Index of the nearest collider, −1 for none. */
  collider: number;
}

/** Cone rays in the sensor frame: [forward, right, up], unit length. Centre ray first. */
export const CONE_RAYS: ReadonlyArray<readonly [number, number, number]> = (() => {
  const out: [number, number, number][] = [[1, 0, 0]];
  for (let r = 1; r <= RINGS; r++) {
    const a = (HALF_ANGLE_RAD * r) / RINGS;
    for (let k = 0; k < PER_RING; k++) {
      const phi = (2 * Math.PI * (k + (r % 2) * 0.5)) / PER_RING;
      out.push([Math.cos(a), Math.sin(a) * Math.cos(phi), Math.sin(a) * Math.sin(phi)]);
    }
  }
  return out;
})();

/** Deterministic PRNG (mulberry32) + Box–Muller, so a course replays with identical noise. */
export class Rng {
  private s: number;
  private spare: number | null = null;
  constructor(seed: number) { this.s = seed >>> 0; }
  next(): number {
    let t = (this.s = (this.s + 0x6d2b79f5) >>> 0);
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  }
  gauss(mu: number, sigma: number): number {
    if (this.spare !== null) { const s = this.spare; this.spare = null; return mu + sigma * s; }
    let u = 0, v = 0;
    while (u === 0) u = this.next();
    while (v === 0) v = this.next();
    const r = Math.sqrt(-2 * Math.log(u));
    this.spare = r * Math.sin(2 * Math.PI * v);
    return mu + sigma * r * Math.cos(2 * Math.PI * v);
  }
}

/** Ray vs yaw-rotated box (slab test in the box frame). Returns distance along the ray or −1. */
export function hitBox(c: Collider, ox: number, oy: number, oz: number, dx: number, dy: number, dz: number): number {
  const cs = Math.cos(c.yaw), sn = Math.sin(c.yaw);
  // world → box frame: rotate by −yaw about Y.
  const px = ox - c.cx, pz = oz - c.cz;
  const o0 = cs * px - sn * pz, o2 = sn * px + cs * pz, o1 = oy - c.cy;
  const d0 = cs * dx - sn * dz, d2 = sn * dx + cs * dz, d1 = dy;
  let tmin = 0, tmax = Infinity;
  const slab = (o: number, d: number, h: number): boolean => {
    if (Math.abs(d) < 1e-12) return o >= -h && o <= h;
    let t1 = (-h - o) / d, t2 = (h - o) / d;
    if (t1 > t2) { const t = t1; t1 = t2; t2 = t; }
    if (t1 > tmin) tmin = t1;
    if (t2 < tmax) tmax = t2;
    return tmin <= tmax;
  };
  if (!slab(o0, d0, c.hx) || !slab(o1, d1, c.hy) || !slab(o2, d2, c.hz)) return -1;
  return tmin > 0 ? tmin : -1;
}

export class ToFSensor {
  private rng: Rng;
  constructor(seed = 0) { this.rng = new Rng(seed); }
  reseed(seed: number): void { this.rng = new Rng(seed); }

  measure(pose: SensorPose, colliders: readonly Collider[], noise = true): ToFResult {
    // sensor frame (forward, right, up) → world: forward f = (−sin yaw, 0, −cos yaw), right r = (cos yaw, 0, −sin yaw)
    const fx = -Math.sin(pose.yaw), fz = -Math.cos(pose.yaw);
    const rx = Math.cos(pose.yaw), rz = -Math.sin(pose.yaw);
    let hits = 0, best = Infinity, bestIdx = -1, bestT = 0, bestRay = 0;
    for (let r = 0; r < CONE_RAYS.length; r++) {
      const [f, u, v] = CONE_RAYS[r];
      const dx = f * fx + u * rx, dz = f * fz + u * rz, dy = v;
      let near = Infinity, nearIdx = -1, nearT = 0;
      for (let i = 0; i < colliders.length; i++) {
        const c = colliders[i];
        if (!c.enabled) continue;
        const t = hitBox(c, pose.x, pose.y, pose.z, dx, dy, dz);
        if (t <= 0) continue;
        const fwd = t * f;                    // projection on the beam axis (scene.py reports forward x)
        if (fwd < near) { near = fwd; nearIdx = i; nearT = t; }
      }
      if (nearIdx < 0 || near > TRUTH_LIMIT_M) continue;
      hits++;
      if (near < best) { best = near; bestIdx = nearIdx; bestT = nearT; bestRay = r; }
    }
    if (bestIdx < 0 || hits < MIN_HITS) {
      return { mm: 0, status: RANGE_NO_TARGET, kind: "", truthM: null, hit: null, collider: -1 };
    }
    const [f, u, v] = CONE_RAYS[bestRay];
    const hit: [number, number, number] = [
      pose.x + (f * fx + u * rx) * bestT, pose.y + v * bestT, pose.z + (f * fz + u * rz) * bestT,
    ];
    const c = colliders[bestIdx];
    const base = { kind: c.kind, truthM: best, hit, collider: bestIdx };
    let d = best * 1000;
    if (d > RANGE_LIMIT_MM) return { ...base, mm: 0, status: RANGE_NO_TARGET };
    if (c.kind === "glass") return { ...base, mm: 0, status: RANGE_UNKNOWN };
    if (d > maxRangeMm(c.reflectance)) return { ...base, mm: 0, status: RANGE_NO_TARGET };   // too dark to return
    if (noise) d += this.rng.gauss(0, 4 + 0.005 * d);
    d = Math.max(1, Math.round(d));
    if (d > RANGE_LIMIT_MM) return { ...base, mm: 0, status: RANGE_NO_TARGET };
    return { ...base, mm: d, status: RANGE_VALID };
  }
}
