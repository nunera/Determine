/**
 * Palette, materials and the Stage builder. Every sensed set piece is created through `block()`, which makes the
 * mesh and its collider from the same numbers, so what the viewer sees is exactly what the ToF model measures.
 */
import * as THREE from "three";
import { DEFAULT_REFLECTANCE, REFLECTANCE_BY_KIND, type Collider } from "../sensor/tof";

/** Render layers: 0 = world (head camera sees it), 1 = overlays (no AO, never in the camera feed),
 *  2 = the wearer's own body (hidden from the head camera and first-person view). */
export const LAYER_WORLD = 0;
export const LAYER_OVERLAY = 1;
export const LAYER_WEARER = 2;

export const INK = new THREE.Color("#20252d");
export const PAPER = new THREE.Color("#f3f5f7");
export const LEFT_BLUE = new THREE.Color("#1d4ed8");
export const RIGHT_RED = new THREE.Color("#cf1f2a");
export const HAZARD = new THREE.Color("#ffd000");

function matte(hex: string, roughness = 0.76): THREE.MeshStandardMaterial {
  return new THREE.MeshStandardMaterial({ color: hex, roughness, metalness: 0, envMapIntensity: 0.3 });
}

/** Cool architectural finishes; saturated colour remains reserved for signal and warning meaning. */
export const MAT = {
  ground: matte("#edf0f3", 0.94),
  paving: matte("#c4cfda", 0.86),
  limestone: matte("#c2ced9", 0.72),
  limestoneDark: matte("#b6c2cc", 0.8),
  wall: matte("#e4e9ef", 0.7),
  timber: matte("#aeb9c1", 0.82),
  steel: new THREE.MeshStandardMaterial({ color: "#73818e", roughness: 0.34, metalness: 0.72, envMapIntensity: 0.8 }),
  clay: matte("#aebdca", 0.58),
  clayDark: matte("#718291", 0.64),
  foliage: new THREE.MeshStandardMaterial({ color: "#9daea4", roughness: 0.9, envMapIntensity: 0.25 }),
  ink: new THREE.MeshStandardMaterial({ color: "#303943", roughness: 0.48, metalness: 0.12, envMapIntensity: 0.45 }),
  white: matte("#fafcfd", 0.48),
  glass: new THREE.MeshPhysicalMaterial({
    color: "#c7dde8", roughness: 0.12, metalness: 0, transparent: true, opacity: 0.26, depthWrite: false,
    clearcoat: 1, clearcoatRoughness: 0.1, envMapIntensity: 0.85,
  }),
} as const;

/** Quiet architectural seams: world-space metres remain true, without a drafting-grid backdrop. */
export function groundMaterial(): THREE.MeshStandardMaterial {
  const m = matte("#d5dde5", 0.94);
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
  float major = gridLine(vGridPos.xz, 5.0);
  float fade = 1.0 - smoothstep(12.0, 42.0, length(vGridPos.xz - cameraPosition.xz));
  float ink = major * 0.045 * fade;
  diffuseColor.rgb = mix(diffuseColor.rgb, vec3(0.32, 0.39, 0.46), ink);
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

/** Crisp, restrained architectural signage, rasterised once at high resolution. */
export function letteringTexture(lines: string[], w: number, h: number, opts: {
  size?: number; weight?: number; bg?: string; fg?: string; border?: boolean; mono?: boolean;
} = {}): THREE.CanvasTexture {
  const scale = 4;
  const c = document.createElement("canvas");
  c.width = w * scale; c.height = h * scale;
  const g = c.getContext("2d")!;
  const size = opts.size ?? h * 0.42;
  const family = opts.mono ? "'SFMono-Regular', 'Atkinson Hyperlegible Mono', monospace" : "-apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif";
  const font = `${opts.weight ?? 600} ${size}px ${family}`;
  const draw = (): void => {
    g.setTransform(scale, 0, 0, scale, 0, 0);
    g.fillStyle = opts.bg ?? "#f7f9fb";
    g.fillRect(0, 0, w, h);
    if (opts.border) { g.strokeStyle = opts.fg ?? "#20252d"; g.lineWidth = 1; g.strokeRect(0.5, 0.5, w - 1, h - 1); }
    g.fillStyle = opts.fg ?? "#20252d";
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
