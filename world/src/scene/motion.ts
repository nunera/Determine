/** Polyline paths sampled by arc length, and a trapezoidal walking-speed profile. */

export interface PathPoint { x: number; z: number; tx: number; tz: number }

export class Path {
  private readonly cum: number[] = [0];
  readonly length: number;

  constructor(private readonly pts: ReadonlyArray<readonly [number, number]>) {
    for (let i = 1; i < pts.length; i++) {
      this.cum.push(this.cum[i - 1] + Math.hypot(pts[i][0] - pts[i - 1][0], pts[i][1] - pts[i - 1][1]));
    }
    this.length = this.cum[this.cum.length - 1];
  }

  /** Position and smoothed unit tangent at arc length s (clamped). Corners are rounded over ±0.6 m. */
  at(s: number): PathPoint {
    const p = this.raw(s);
    const a = this.raw(s - 0.6), b = this.raw(s + 0.6);
    let tx = a.tx + p.tx + b.tx, tz = a.tz + p.tz + b.tz;
    const n = Math.hypot(tx, tz) || 1;
    tx /= n; tz /= n;
    return { x: p.x, z: p.z, tx, tz };
  }

  private raw(s: number): PathPoint {
    const c = Math.max(0, Math.min(this.length, s));
    let i = 1;
    while (i < this.cum.length - 1 && this.cum[i] < c) i++;
    const [x0, z0] = this.pts[i - 1], [x1, z1] = this.pts[i];
    const seg = this.cum[i] - this.cum[i - 1] || 1;
    const u = (c - this.cum[i - 1]) / seg;
    return { x: x0 + (x1 - x0) * u, z: z0 + (z1 - z0) * u, tx: (x1 - x0) / seg, tz: (z1 - z0) / seg };
  }
}

/** Trapezoidal profile: accelerate at `accel` to `vmax`, cruise, decelerate to stop exactly at `length`. */
export class Trapezoid {
  readonly duration: number;
  private readonly ta: number;
  private readonly tc: number;
  private readonly vpeak: number;

  constructor(readonly length: number, vmax: number, readonly accel = 1.2) {
    const da = (vmax * vmax) / (2 * accel);
    if (2 * da >= length) {
      this.vpeak = Math.sqrt(length * accel);
      this.ta = this.vpeak / accel;
      this.tc = 0;
    } else {
      this.vpeak = vmax;
      this.ta = vmax / accel;
      this.tc = (length - 2 * da) / vmax;
    }
    this.duration = 2 * this.ta + this.tc;
  }

  /** Distance and speed at time t (seconds since the walk began). */
  at(t: number): { s: number; v: number } {
    const { ta, tc, vpeak, accel } = this;
    if (t <= 0) return { s: 0, v: 0 };
    if (t < ta) return { s: 0.5 * accel * t * t, v: accel * t };
    const da = 0.5 * accel * ta * ta;
    if (t < ta + tc) return { s: da + vpeak * (t - ta), v: vpeak };
    if (t < this.duration) {
      const r = this.duration - t;
      return { s: this.length - 0.5 * accel * r * r, v: accel * r };
    }
    return { s: this.length, v: 0 };
  }

  /** First time the walked distance reaches s (binary search). */
  timeAt(s: number): number {
    if (s <= 0) return 0;
    if (s >= this.length) return this.duration;
    let lo = 0, hi = this.duration;
    for (let k = 0; k < 40; k++) {
      const mid = (lo + hi) / 2;
      if (this.at(mid).s < s) lo = mid; else hi = mid;
    }
    return hi;
  }
}

export const smoothstep = (a: number, b: number, x: number): number => {
  const t = Math.max(0, Math.min(1, (x - a) / (b - a)));
  return t * t * (3 - 2 * t);
};
