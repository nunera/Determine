/**
 * Palette, materials and the Stage builder. Every sensed set piece is created through `block()`, which makes the
 * mesh and its collider from the same numbers, so what the viewer sees is exactly what the ToF model measures.
 */
import * as THREE from "three";
import { DEFAULT_REFLECTANCE, REFLECTANCE_BY_KIND, type Collider } from "../sensor/tof";

/** Render layers: 0 = world (head camera sees it), 1 = overlays (no AO/edges, never in the camera feed),
 *  2 = the wearer's own body (hidden from the head camera and first-person view). */
export const LAYER_WORLD = 0;
export const LAYER_OVERLAY = 1;
export const LAYER_WEARER = 2;

export const INK = new THREE.Color("#131316");
export const PAPER = new THREE.Color("#f6f4ef");
export const LEFT_BLUE = new THREE.Color("#1d4ed8");
export const RIGHT_RED = new THREE.Color("#cf1f2a");
export const HAZARD = new THREE.Color("#ffd000");

function matte(hex: string, roughness = 0.92): THREE.MeshStandardMaterial {
  return new THREE.MeshStandardMaterial({ color: hex, roughness, metalness: 0, envMapIntensity: 0.18 });
}

/** Warm maquette materials: value differences only, colour reserved for meaning. */
export const MAT = {
  ground: matte("#efece6"),
  paving: matte("#e7e2d8"),
  limestone: matte("#e4dccd"),
  limestoneDark: matte("#d6cdbb"),
  wall: matte("#f1eee8"),
  timber: matte("#d9d1c2"),
  steel: matte("#c9c6c0", 0.7),
  clay: matte("#ddd5c8", 0.88),
  clayDark: matte("#c7beb0", 0.88),
  foliage: new THREE.MeshStandardMaterial({ color: "#e2e0d5", roughness: 0.95, flatShading: true, envMapIntensity: 0.3 }),
  ink: new THREE.MeshStandardMaterial({ color: "#2a2a2e", roughness: 0.6 }),
  white: matte("#fbfaf6", 0.6),
  glass: new THREE.MeshStandardMaterial({
    color: "#dfe6ea", roughness: 0.08, metalness: 0, transparent: true, opacity: 0.28, depthWrite: false,
    envMapIntensity: 0.9,
  }),
} as const;

/** Floor with a faint drafting grid (1 m minor, 5 m major) that fades with distance. */
export function groundMaterial(): THREE.MeshStandardMaterial {
  const m = matte("#f3f1ec", 0.96);
  m.onBeforeCompile = (shader) => {
    shader.vertexShader = shader.vertexShader
      .replace("#include <common>", "#include <common>\nvarying vec3 vGridPos;")
      .replace("#include <worldpos_vertex>", "#include <worldpos_vertex>\nvGridPos = (modelMatrix * vec4(transformed, 1.0)).xyz;");
    shader.fragmentShader = shader.fragmentShader
      .replace("#include <common>", `#include <common>
varying vec3 vGridPos;
float gridLine(vec2 p, float spacing) {
  vec2 q = p / spacing;
  vec2 g = abs(fract(q - 0.5) - 0.5) / fwidth(q);
  return 1.0 - min(min(g.x, g.y), 1.0);
}`)
      .replace("#include <color_fragment>", `#include <color_fragment>
{
  float minor = gridLine(vGridPos.xz, 1.0);
  float major = gridLine(vGridPos.xz, 5.0);
  float fade = 1.0 - smoothstep(25.0, 70.0, length(vGridPos.xz - cameraPosition.xz));
  float ink = max(minor * 0.07, major * 0.16) * fade;
  diffuseColor.rgb = mix(diffuseColor.rgb, vec3(0.30, 0.30, 0.32), ink);
}`);
  };
  return m;
}

export interface BlockSpec {
  /** Centre position, world metres. */
  x: number; y: number; z: number;
  w: number; h: number; d: number;     // size along local X, Y, Z
  yaw?: number;
  mat: THREE.Material;
  /** Collider kind; omit for decoration the sensor should not see (above the field, or purely visual). */
  kind?: string;
  castShadow?: boolean;
}

export class Stage {
  readonly root = new THREE.Group();
  readonly colliders: Collider[] = [];
  /** Mesh drawn for each collider index (for the in-world NEAR highlight). */
  readonly colliderMesh: THREE.Object3D[] = [];
  private boxGeo = new THREE.BoxGeometry(1, 1, 1);

  constructor(scene: THREE.Scene) {
    scene.add(this.root);
  }

  block(s: BlockSpec, parent: THREE.Object3D = this.root): THREE.Mesh {
    const mesh = new THREE.Mesh(this.boxGeo, s.mat);
    mesh.scale.set(s.w, s.h, s.d);
    mesh.position.set(s.x, s.y, s.z);
    mesh.rotation.y = s.yaw ?? 0;
    mesh.castShadow = s.castShadow ?? true;
    mesh.receiveShadow = true;
    parent.add(mesh);
    if (s.kind) this.collide(s.kind, s.x, s.y, s.z, s.w, s.h, s.d, s.yaw ?? 0, mesh);
    return mesh;
  }

  /** Adds a collider for an arbitrary visual (e.g. a cylinder approximated by its bounding box). */
  collide(kind: string, x: number, y: number, z: number, w: number, h: number, d: number, yaw: number,
    visual: THREE.Object3D): Collider {
    const c: Collider = {
      kind, cx: x, cy: y, cz: z, hx: w / 2, hy: h / 2, hz: d / 2, yaw, enabled: true,
      reflectance: REFLECTANCE_BY_KIND[kind] ?? DEFAULT_REFLECTANCE,
    };
    this.colliders.push(c);
    this.colliderMesh.push(visual);
    return c;
  }

  add(obj: THREE.Object3D, shadows = true): THREE.Object3D {
    obj.traverse((o) => {
      if ((o as THREE.Mesh).isMesh) { o.castShadow = shadows; o.receiveShadow = true; }
    });
    this.root.add(obj);
    return obj;
  }
}

/** Canvas texture with ink lettering on paper (signage, floor station marks). */
export function letteringTexture(lines: string[], w: number, h: number, opts: {
  size?: number; weight?: number; bg?: string; fg?: string; border?: boolean; mono?: boolean;
} = {}): THREE.CanvasTexture {
  const scale = 4;
  const c = document.createElement("canvas");
  c.width = w * scale; c.height = h * scale;
  const g = c.getContext("2d")!;
  const size = opts.size ?? h * 0.42;
  const family = opts.mono ? "'Atkinson Hyperlegible Mono', monospace" : "'Atkinson Hyperlegible Next', sans-serif";
  const font = `${opts.weight ?? 700} ${size}px ${family}`;
  const draw = (): void => {
    g.setTransform(scale, 0, 0, scale, 0, 0);
    g.fillStyle = opts.bg ?? "#fbfaf6";
    g.fillRect(0, 0, w, h);
    if (opts.border) { g.strokeStyle = opts.fg ?? "#131316"; g.lineWidth = 3; g.strokeRect(1.5, 1.5, w - 3, h - 3); }
    g.fillStyle = opts.fg ?? "#131316";
    g.textAlign = "center";
    g.textBaseline = "middle";
    g.font = font;
    const step = size * 1.15;
    lines.forEach((t, i) => g.fillText(t, w / 2, h / 2 + (i - (lines.length - 1) / 2) * step));
  };
  draw();
  const tex = new THREE.CanvasTexture(c);
  tex.colorSpace = THREE.SRGBColorSpace;
  tex.anisotropy = 4;
  // The web fonts may still be loading: redraw once they are available.
  document.fonts.load(font).then(() => { draw(); tex.needsUpdate = true; }, () => undefined);
  return tex;
}

export function setLayer(obj: THREE.Object3D, layer: number): void {
  obj.traverse((o) => o.layers.set(layer));
}
