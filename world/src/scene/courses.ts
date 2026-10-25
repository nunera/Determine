/**
 * Seven courses, built as stations along one continuous limestone campus walk (inspired by UT Austin's arcades and
 * malls). Course-local coordinates: x = lateral (+ = wearer's right), s = forward along the walk; world X = x,
 * world Z = z0 − s. Every sensed set piece is a Stage block, so its mesh and collider share the same numbers.
 *
 * Envelope (wire v2): one forward zone, 18° cone, ≤ 1.2 m on a white target and less on darker ones (see
 * sensor/tof.ts maxRangeMm: grey steel ≈ 0.7 m, bark ≈ 0.5 m — datasheet-anchored, unmeasured on our module).
 * At 0.9 m/s, 1.2 m is ~1.3 s of warning. The scripted walker stops or steps aside at the distances written in each
 * course's summary — a choreography, not a user.
 */
import * as THREE from "three";
import type { CourseInfo } from "../contract";
import { BAND_MID_MM, BAND_NEAR_MM } from "../contract";
import { maxRangeMm, REFLECTANCE_BY_KIND, type Collider } from "../sensor/tof";
import { createFigure, type Figure } from "./figure";
import { Path, Trapezoid, smoothstep } from "./motion";
import { MAT, Stage, letteringTexture } from "./stage";

export const STATION_SPACING = 40;
export const START_DELAY_S = 1.0;
export const HOLD_S = 2.2;

export interface Actor {
  figure: Figure;
  collider: Collider;
  /** wearerS: the wearer's walked distance this frame (for scripted reactions). */
  update(tS: number, wearerS: number): void;
  reset(): void;
}

export interface Course {
  info: CourseInfo;
  letter: string;
  z0: number;
  floorY: number;
  path: Path;          // world XZ
  walk: Trapezoid;
  /** Distance along the walk of the hazard's near face (null when there is none to approach). */
  hazardS: number | null;
  actors: Actor[];
  /** Wearer walked distance and speed at course time tS. */
  wearerAt(tS: number): { s: number; v: number };
}

interface StationDef {
  id: string;
  title: string;
  summary: string;
  expect: string;
  floorY?: number;
  pts: [number, number][];     // course-local (x, s)
  speed: number;
  hazardS: number | null;
  build(stage: Stage, at: Local): Actor[] | void;
  marks?: (c: Course) => { atS: number; label: string }[];
}

/** Course-local helpers bound to a station origin. */
class Local {
  constructor(readonly stage: Stage, readonly z0: number) {}
  /** Box by course-local centre (x, y, s) and size (w across, h, d along the walk). */
  box(x: number, y: number, s: number, w: number, h: number, d: number, mat: THREE.Material | THREE.Material[],
    kind?: string, yaw = 0): THREE.Mesh {
    const m = this.stage.block({ x, y, z: this.z0 - s, w, h, d, yaw, mat: Array.isArray(mat) ? mat[0] : mat, kind });
    if (Array.isArray(mat)) m.material = mat;
    return m;
  }
  cylinder(x: number, s: number, y0: number, h: number, r: number, mat: THREE.Material, kind?: string): THREE.Mesh {
    const m = new THREE.Mesh(new THREE.CylinderGeometry(r, r, h, 28), mat);
    m.position.set(x, y0 + h / 2, this.z0 - s);
    this.stage.add(m);
    if (kind) this.stage.collide(kind, x, y0 + h / 2, this.z0 - s, 2 * r, h, 2 * r, 0, m);
    return m;
  }
  world(x: number, s: number): [number, number] { return [x, this.z0 - s]; }
}

// ------------------------------------------------------------------------------------------------ set dressing

function column(at: Local, x: number, s: number, h = 3.6): void {
  at.box(x, 0.15, s, 0.56, 0.3, 0.56, MAT.limestoneDark);
  at.cylinder(x, s, 0.3, h - 0.6, 0.2, MAT.limestone);
  at.box(x, h - 0.15, s, 0.58, 0.3, 0.58, MAT.limestoneDark);
}

function tree(at: Local, x: number, s: number, scale = 1, trunkKind?: string): void {
  at.cylinder(x, s, 0, 2.6 * scale, 0.14 * scale, MAT.timber, trunkKind);
  const geo = new THREE.IcosahedronGeometry(1, 1);
  const blobs: [number, number, number, number][] = [
    [0, 3.3, 0, 1.25], [0.7, 3.0, 0.3, 0.85], [-0.6, 3.1, -0.4, 0.9], [0.1, 3.9, -0.2, 0.85], [-0.3, 2.9, 0.6, 0.7],
  ];
  for (const [dx, dy, dz, r] of blobs) {
    const m = new THREE.Mesh(geo, MAT.foliage);
    m.scale.setScalar(r * scale);
    m.position.set(x + dx * scale, dy * scale, at.z0 - s + dz * scale);
    m.rotation.set(dx, dz, dy);
    at.stage.add(m);
  }
}

function lamp(at: Local, x: number, s: number): void {
  at.cylinder(x, s, 0, 3.8, 0.05, MAT.steel);
  at.box(x - Math.sign(x) * 0.25, 3.8, s, 0.6, 0.08, 0.16, MAT.steel);
}

function bench(at: Local, x: number, s: number, alongWalk = true): void {
  const [w, d] = alongWalk ? [0.5, 1.8] : [1.8, 0.5];
  at.box(x, 0.42, s, w, 0.07, d, MAT.timber);
  at.box(x, 0.2, s, w * 0.8, 0.4, d * 0.15, MAT.limestoneDark);
}

function floorMark(at: Local, letter: string, floorY: number): void {
  const tex = letteringTexture([letter], 128, 128, { border: true, size: 78, weight: 800, bg: "#f6f4ef" });
  const m = new THREE.Mesh(new THREE.PlaneGeometry(0.7, 0.7), new THREE.MeshStandardMaterial({
    map: tex, roughness: 0.95, envMapIntensity: 0.3,
  }));
  m.rotation.x = -Math.PI / 2;
  const [x, z] = at.world(-1.55, -1.1);
  m.position.set(x, floorY + 0.016, z);
  m.receiveShadow = true;
  at.stage.add(m, false);
}

function pedestrian(at: Local): Actor {
  const fig = createFigure(MAT.clayDark);
  at.stage.add(fig.root);
  const startS = 12.6, startX = 0.08, speed = 1.0;
  const collider = at.stage.collide("person", 0, 0.875, 0, 0.48, 1.75, 0.3, 0, fig.root);
  let asideAt: number | null = null;
  const place = (tS: number, wearerS: number): void => {
    const walkT = Math.max(0, tS - START_DELAY_S - 0.3);
    const s = startS - speed * walkT;
    // Scripted courtesy: step aside when the sensor-to-chest gap closes to 0.6 m (feet gap − brow 0.13 − half-depth 0.15).
    if (asideAt === null && s - wearerS - 0.28 <= 0.6 && walkT > 0) asideAt = tS;
    const k = asideAt === null ? 0 : smoothstep(0, 1.1, tS - asideAt);
    const x = startX + 1.15 * k;
    const [wx, wz] = at.world(x, s);
    fig.root.position.set(wx, 0, wz);
    fig.root.rotation.y = Math.PI - 0.5 * Math.sin(Math.PI * k);   // faces the wearer, turns while stepping aside
    fig.pose(walkT * speed / 0.68 * Math.PI, walkT > 0 ? 1 : 0, false);
    collider.cx = wx; collider.cz = wz; collider.yaw = fig.root.rotation.y;
  };
  return {
    figure: fig, collider,
    update: place,
    reset: () => { asideAt = null; place(0, 0); },
  };
}

// ------------------------------------------------------------------------------------------------ stations

/** Marks from the modelled detection range of `kind` down through the band edges the engine would cross. */
const bandMarks = (c: Course, kind: string): { atS: number; label: string }[] => {
  if (c.hazardS === null) return [];
  const detectMm = Math.round(maxRangeMm(REFLECTANCE_BY_KIND[kind]) / 10) * 10;
  const t = (gapMm: number): number => START_DELAY_S + c.walk.timeAt(c.hazardS! - 0.13 - gapMm / 1000);
  const marks = [{ atS: t(detectMm), label: `${kind} returns from ~${(detectMm / 1000).toFixed(2)} m (model)` }];
  if (detectMm > BAND_MID_MM) marks.push({ atS: t(BAND_MID_MM), label: "0.9 m · MID expected" });
  if (detectMm > BAND_NEAR_MM) marks.push({ atS: t(BAND_NEAR_MM), label: "0.5 m · NEAR expected" });
  return marks;
};

const STATIONS: StationDef[] = [
  {
    id: "head_sign",
    title: "Sign at head height",
    summary: "A hanging sign at 1.45–1.85 m: the cane sweeps under it, the head would not. The walker stops 0.45 m short.",
    expect: "FAR → MID → NEAR ahead from ~1.2 m (white sign face), before the brow reaches the sign.",
    pts: [[0, 0], [0, 7.40]],
    speed: 0.9,
    hazardS: 7.98,
    build(_stage, at) {
      for (let s = -3; s <= 18; s += 3) { column(at, -3.1, s); column(at, 3.1, s); }
      for (const side of [-1, 1]) {
        at.box(side * 4.0, 3.82, 7.5, 2.4, 0.3, 22, MAT.limestone);
        at.box(side * 5.25, 1.95, 7.5, 0.3, 3.9, 22, MAT.wall);
      }
      at.box(0, 3.5, 8, 6.4, 0.2, 0.22, MAT.limestoneDark);
      for (const x of [-0.45, 0.45]) at.box(x, 2.63, 8, 0.025, 1.56, 0.025, MAT.steel);
      const face = new THREE.MeshStandardMaterial({
        map: letteringTexture(["EAST MALL", "INFORMATION"], 330, 120, { size: 40, weight: 800 }),
        roughness: 0.8, envMapIntensity: 0.3,
      });
      at.box(0, 1.65, 8, 1.1, 0.4, 0.04,
        [MAT.white, MAT.white, MAT.white, MAT.white, face, face], "sign");
    },
    marks: (c) => [...bandMarks(c, "sign"), { atS: START_DELAY_S + c.walk.duration, label: "Walker stops 0.45 m short" }],
  },
  {
    id: "scaffold",
    title: "Scaffold: beside vs ahead",
    summary: "A grey steel bar 0.45 m to the left at head height, then one straight across. One 18° zone sees only what is ahead, and grey steel returns late.",
    expect: "Quiet for the side bar (outside the cone: a known limit). The bar ahead appears late, from ~0.7 m: MID → NEAR.",
    pts: [[0, 0], [0, 9.37]],
    speed: 0.9,
    hazardS: 9.975,
    build(_stage, at) {
      at.box(-4.1, 3, 7, 0.3, 6, 24, MAT.wall);
      for (let s = -2; s <= 17; s += 2) {
        for (const x of [-3.55, -2.55]) at.cylinder(x, s, 0, 6.2, 0.03, MAT.steel);
      }
      for (const y of [2.0, 4.0, 6.0]) for (const x of [-3.55, -2.55]) at.box(x, y, 7.5, 0.05, 0.05, 19, MAT.steel);
      for (let s = -2; s <= 17; s += 2) at.box(-3.05, 2.0, s, 1.0, 0.04, 0.04, MAT.steel);
      // side bar: tip 0.45 m left of the walker's line, at head height
      at.box(-1.5, 1.59, 4, 2.1, 0.06, 0.06, MAT.steel, "bar");
      // bar across the walk, on two standards
      for (const x of [-1.25, 1.25]) at.cylinder(x, 10.03, 0, 2.4, 0.03, MAT.steel, "bar");
      at.box(0, 1.59, 10.03, 2.6, 0.06, 0.06, MAT.steel, "bar");
      at.box(0, 2.35, 10.03, 2.6, 0.05, 0.05, MAT.steel);
      at.box(0, 0.5, 10.03, 2.4, 0.18, 0.02, MAT.white, "barrier");
    },
    marks: (c) => [
      { atS: START_DELAY_S + c.walk.timeAt(4 - 1.2), label: "Side bar 1.2 m away · outside cone" },
      ...bandMarks(c, "bar"),
    ],
  },
  {
    id: "person_ahead",
    title: "Pedestrian ahead",
    summary: "Someone in light clothing walks toward the wearer and steps aside at 0.6 m. Closing at ~1.9 m/s, ~1 m of range is half a second.",
    expect: "A brief warning at best (obstacle cues: the cli has no camera ML). The TTC approach A/B case — measure both settings.",
    pts: [[0, 0], [0, 10]],
    speed: 0.9,
    hazardS: null,
    build(_stage, at) {
      for (let s = -2; s <= 18; s += 8) { lamp(at, -3.2, s); lamp(at, 3.2, s + 4); }
      for (let s = 0; s <= 16; s += 4) {
        at.box(-2.9, 0.3, s, 0.7, 0.6, 1.6, MAT.limestoneDark);
        at.box(2.9, 0.3, s + 2, 0.7, 0.6, 1.6, MAT.limestoneDark);
      }
      at.box(-6, 2.5, 8, 0.3, 5, 26, MAT.wall);
      return [pedestrian(at)];
    },
  },
  {
    id: "branch_overhang",
    title: "Dark branch at head height",
    summary: "A live-oak branch across the line at 1.52–1.66 m. Bark is dark: by the datasheet's grey-target figure it returns only within ~0.5 m. The walker stops 0.3 m short.",
    expect: "Late: straight to NEAR about half a second before the stop, or nothing. A known limit to measure on the bench.",
    pts: [[0, 0], [0, 6.52]],
    speed: 0.8,
    hazardS: 6.95,
    build(_stage, at) {
      tree(at, 0.95, 7.2, 1.1, "trunk");
      at.box(0.39, 1.59, 7.0, 1.06, 0.12, 0.1, MAT.timber, "branch", 0.08);
      at.box(-0.05, 1.63, 6.97, 0.25, 0.05, 0.05, MAT.timber, "branch", -0.4);
      for (const [x, s] of [[2.8, 1], [-2.8, 4], [2.9, 13], [-2.7, 11], [-2.9, 17]] as [number, number][]) tree(at, x, s, 0.95);
      for (let s = -1; s <= 16; s += 5) bench(at, -2.0, s);
    },
    marks: (c) => [...bandMarks(c, "branch"), { atS: START_DELAY_S + c.walk.duration, label: "Walker stops 0.3 m short" }],
  },
  {
    id: "glass_door",
    title: "Glass door",
    summary: "A frameless glass entrance. The time-of-flight sensor cannot be trusted through glass.",
    expect: "UNKNOWN, never a distance: the phone must announce it cannot measure. The cane finds the glass.",
    pts: [[0, 0], [0, 6.94]],
    speed: 0.9,
    hazardS: 7.89,
    build(_stage, at) {
      for (const side of [-1, 1]) at.box(side * 4.1, 2.25, 8.05, 5.9, 4.5, 0.3, MAT.wall, "wall");
      for (const side of [-1, 1]) at.box(side * 1.09, 1.2, 8.0, 0.14, 2.4, 0.18, MAT.limestoneDark, "wall");
      at.box(0, 2.45, 8.0, 2.32, 0.2, 0.18, MAT.limestoneDark, "wall");
      at.box(0, 3.35, 8.05, 2.32, 1.6, 0.3, MAT.wall, "wall");
      at.box(0, 1.175, 7.9, 2.04, 2.35, 0.025, MAT.glass, "glass");
      for (const x of [-0.12, 0.12]) at.box(x, 1.1, 7.86, 0.025, 0.45, 0.025, MAT.steel);
      const sign = new THREE.MeshStandardMaterial({
        map: letteringTexture(["WELCOME CENTER"], 420, 70, { size: 40, weight: 800, bg: "#f1eee8" }),
        roughness: 0.9, envMapIntensity: 0.3,
      });
      at.box(0, 3.4, 7.89, 2.1, 0.35, 0.01, [sign, sign, sign, sign, sign, sign]);
      for (let s = -2; s <= 6; s += 4) { column(at, -3.1, s, 3.4); column(at, 3.1, s, 3.4); }
    },
    marks: (c) => [
      { atS: START_DELAY_S + c.walk.timeAt(c.hazardS! - 1.2), label: "Glass at 1.2 m · UNKNOWN expected" },
      { atS: START_DELAY_S + c.walk.duration, label: "Cane reaches the glass" },
    ],
  },
  {
    id: "open_plaza",
    title: "Open plaza",
    summary: "Nothing within 1.2 m along the line. NO_TARGET is not \"clear\": the sensor simply has nothing to report.",
    expect: "Must stay quiet. Silence here is not a promise that the path is clear.",
    pts: [[0, 0], [0, 11]],
    speed: 1.0,
    hazardS: null,
    build(stage, at) {
      const disc = new THREE.Mesh(new THREE.CylinderGeometry(13, 13, 0.04, 96), MAT.paving);
      disc.position.set(0, 0.02, at.z0 - 9);
      stage.add(disc);
      const basin = new THREE.Mesh(new THREE.TorusGeometry(2.4, 0.18, 12, 64), MAT.limestoneDark);
      basin.rotation.x = Math.PI / 2;
      basin.position.set(0, 0.35, at.z0 - 22);
      stage.add(basin);
      at.cylinder(0, 22, 0, 0.35, 2.3, MAT.limestone);
      at.cylinder(0, 22, 0.35, 1.2, 0.35, MAT.limestoneDark);
      for (const [x, s] of [[-5, 3], [5, 6], [-5, 11], [5, 14]] as [number, number][]) bench(at, x, s);
      for (let k = 0; k < 10; k++) {
        const a = (k / 10) * Math.PI * 2;
        tree(at, Math.cos(a) * 11.5, 9 + Math.sin(a) * 11.5, 0.9);
      }
    },
    marks: (c) => [{ atS: START_DELAY_S + c.walk.duration * 0.5, label: "Nothing in range · quiet ≠ clear" }],
  },
  {
    id: "stairs_down",
    title: "Stairs down",
    summary: "A terrace ends in a flight down. A forward sensor at the brow cannot see a drop-off; the cane tip finds the edge.",
    expect: "Quiet: drop-offs are outside what Determine can sense — cane territory.",
    floorY: 1.05,
    pts: [[0, 0], [0, 6.5]],
    speed: 0.8,
    hazardS: null,
    build(_stage, at) {
      at.box(0, 0.525, 0.5, 16, 1.05, 13, MAT.paving);                 // terrace s ∈ [−6, 7]
      for (let i = 0; i < 7; i++) {
        const top = 1.05 - 0.15 * (i + 1);
        if (top <= 0) break;
        at.box(0, top / 2, 7.175 + 0.35 * i, 5, top, 0.35, MAT.limestone, "stairs");
      }
      for (const side of [-1, 1]) {
        at.box(side * 5.3, 1.5, 7.0 - 0.1, 5.4, 0.9, 0.2, MAT.limestoneDark, "wall");
        at.box(side * 2.6, 0.55, 8.2, 0.2, 1.1, 2.6, MAT.limestoneDark);
      }
      at.box(0, 2.6, -4.5, 16, 5.2, 0.3, MAT.wall);
      for (const x of [-6, 6]) tree(at, x, 12, 1);
    },
    marks: (c) => [{ atS: START_DELAY_S + c.walk.duration, label: "Cane tip finds the edge" }],
  },
];

const LETTERS = "ABCDEFG";

export function buildCourses(stage: Stage): Course[] {
  return STATIONS.map((def, k) => {
    const z0 = -k * STATION_SPACING;
    const at = new Local(stage, z0);
    const floorY = def.floorY ?? 0;
    const actors = def.build(stage, at) ?? [];
    floorMark(at, LETTERS[k], floorY);
    const path = new Path(def.pts.map(([x, s]) => at.world(x, s)));
    const walk = new Trapezoid(path.length, def.speed);
    const course: Course = {
      info: {
        id: def.id, title: def.title, summary: def.summary, expect: def.expect,
        durationS: START_DELAY_S + walk.duration + HOLD_S,
        marks: [],
      },
      letter: LETTERS[k],
      z0, floorY, path, walk, hazardS: def.hazardS, actors,
      wearerAt: (tS) => walk.at(tS - START_DELAY_S),
    };
    course.info.marks = (def.marks?.(course) ?? []).filter((m) => m.atS >= 0 && m.atS <= course.info.durationS)
      .map((m) => ({ atS: Math.round(m.atS * 100) / 100, label: m.label }));
    actors.forEach((a) => a.reset());
    return course;
  });
}
