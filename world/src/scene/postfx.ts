/**
 * Maquette look: one G-buffer pass (view normals + depth, overlays excluded) feeds both GTAO and an ink-edge pass
 * (depth + normal discontinuities), then SMAA and the output transform (Neutral tone mapping, sRGB).
 * No bloom, grain, aberration or lens effects by design.
 */
import * as THREE from "three";
import { EffectComposer } from "three/addons/postprocessing/EffectComposer.js";
import { RenderPass } from "three/addons/postprocessing/RenderPass.js";
import { GTAOPass } from "three/addons/postprocessing/GTAOPass.js";
import { ShaderPass } from "three/addons/postprocessing/ShaderPass.js";
import { SMAAPass } from "three/addons/postprocessing/SMAAPass.js";
import { OutputPass } from "three/addons/postprocessing/OutputPass.js";
import { LAYER_OVERLAY } from "./stage";

const EdgeShader = {
  uniforms: {
    tDiffuse: { value: null as THREE.Texture | null },
    tDepth: { value: null as THREE.Texture | null },
    tNormal: { value: null as THREE.Texture | null },
    resolution: { value: new THREE.Vector2(1, 1) },
    cameraNear: { value: 0.1 },
    cameraFar: { value: 200 },
    ortho: { value: 0 },
    ink: { value: new THREE.Color("#131316") },
    paper: { value: new THREE.Color("#f6f4ef") },
    strength: { value: 0.92 },
    thickness: { value: 1.0 },
    fadeNear: { value: 30.0 },
    fadeFar: { value: 85.0 },
  },
  vertexShader: /* glsl */ `
    varying vec2 vUv;
    void main() { vUv = uv; gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0); }`,
  fragmentShader: /* glsl */ `
    uniform sampler2D tDiffuse, tDepth, tNormal;
    uniform vec2 resolution;
    uniform float cameraNear, cameraFar, ortho, strength, thickness, fadeNear, fadeFar;
    uniform vec3 ink, paper;
    varying vec2 vUv;
    float linearDepth(vec2 uv) {
      float d = texture2D(tDepth, uv).x;
      if (ortho > 0.5) return cameraNear + d * (cameraFar - cameraNear);
      return (cameraNear * cameraFar) / (cameraFar - d * (cameraFar - cameraNear));
    }
    vec3 normalAt(vec2 uv) { return texture2D(tNormal, uv).xyz * 2.0 - 1.0; }
    void main() {
      vec4 base = texture2D(tDiffuse, vUv);
      vec2 px = thickness / resolution;
      float dc = linearDepth(vUv);
      float dl = linearDepth(vUv - vec2(px.x, 0.0)), dr = linearDepth(vUv + vec2(px.x, 0.0));
      float du = linearDepth(vUv + vec2(0.0, px.y)), dd = linearDepth(vUv - vec2(0.0, px.y));
      // second derivative of depth, relative to depth: silhouettes and creases, scale-free
      float lap = abs(dl + dr + du + dd - 4.0 * dc) / max(dc, 1e-3);
      float depthEdge = smoothstep(0.012, 0.05, lap);
      vec3 nc = normalAt(vUv);
      float nEdge = 0.0;
      nEdge = max(nEdge, 1.0 - dot(nc, normalAt(vUv - vec2(px.x, 0.0))));
      nEdge = max(nEdge, 1.0 - dot(nc, normalAt(vUv + vec2(px.x, 0.0))));
      nEdge = max(nEdge, 1.0 - dot(nc, normalAt(vUv + vec2(0.0, px.y))));
      nEdge = max(nEdge, 1.0 - dot(nc, normalAt(vUv - vec2(0.0, px.y))));
      float normalEdge = smoothstep(0.18, 0.45, nEdge);
      float bg = step(0.99999, texture2D(tDepth, vUv).x);
      float edge = max(depthEdge, normalEdge * (1.0 - bg));
      float fade = 1.0 - smoothstep(fadeNear, fadeFar, dc);
      gl_FragColor = vec4(mix(base.rgb, ink, edge * strength * fade), base.a);
    }`,
};

export class PostFx {
  readonly composer: EffectComposer;
  private gbuffer: THREE.WebGLRenderTarget;
  private normalMat = new THREE.MeshNormalMaterial();
  private renderPass: RenderPass;
  private gtao: GTAOPass;
  private edges: ShaderPass;
  private size = new THREE.Vector2();

  constructor(private renderer: THREE.WebGLRenderer, private scene: THREE.Scene, private camera: THREE.Camera) {
    renderer.getDrawingBufferSize(this.size);
    const depth = new THREE.DepthTexture(this.size.x, this.size.y);
    depth.type = THREE.UnsignedIntType;
    this.gbuffer = new THREE.WebGLRenderTarget(this.size.x, this.size.y, {
      type: THREE.HalfFloatType, minFilter: THREE.NearestFilter, magFilter: THREE.NearestFilter, depthTexture: depth,
    });
    this.composer = new EffectComposer(renderer);
    this.renderPass = new RenderPass(scene, camera);
    this.composer.addPass(this.renderPass);
    // three r186: setGBuffer() with external textures dereferences normalRenderTarget, which only exists after the
    // default (internal) setup — so construct with defaults, then hand over the shared G-buffer.
    this.gtao = new GTAOPass(scene, camera, this.size.x, this.size.y);
    this.gtao.setGBuffer(depth, this.gbuffer.texture);
    this.gtao.updateGtaoMaterial({ radius: 0.55, distanceExponent: 1.4, thickness: 1.2, scale: 1.0, samples: 12, screenSpaceRadius: false });
    this.gtao.updatePdMaterial({ lumaPhi: 10, depthPhi: 2, normalPhi: 3, radius: 6, radiusExponent: 1, rings: 2, samples: 12 });
    this.gtao.blendIntensity = 0.9;
    this.composer.addPass(this.gtao);
    this.edges = new ShaderPass(EdgeShader);
    this.edges.uniforms.tDepth.value = depth;
    this.edges.uniforms.tNormal.value = this.gbuffer.texture;
    this.composer.addPass(this.edges);
    this.composer.addPass(new SMAAPass());
    this.composer.addPass(new OutputPass());
  }

  private aoAllowed = true;

  /** Adaptive quality: AO is the most expensive pass and the first to go on a slow GPU. */
  setAo(allowed: boolean): void {
    this.aoAllowed = allowed;
    this.setCamera(this.camera);
  }

  setCamera(camera: THREE.Camera): void {
    this.camera = camera;
    this.renderPass.camera = camera;
    const ortho = (camera as THREE.OrthographicCamera).isOrthographicCamera === true;
    // GTAO's shaders are compiled for one projection type; the plan view is a line drawing and needs no AO.
    this.gtao.enabled = !ortho && this.aoAllowed;
    if (!ortho) this.gtao.camera = camera;
    this.edges.uniforms.ortho.value = ortho ? 1 : 0;
    this.edges.uniforms.fadeNear.value = ortho ? 1e4 : 30;
    this.edges.uniforms.fadeFar.value = ortho ? 2e4 : 85;
  }

  setSize(w: number, h: number): void {
    this.composer.setSize(w, h);
    this.renderer.getDrawingBufferSize(this.size);
    this.gbuffer.setSize(this.size.x, this.size.y);
    this.gtao.setSize(this.size.x, this.size.y);
    this.edges.uniforms.resolution.value.copy(this.size);
  }

  render(): void {
    const cam = this.camera as THREE.PerspectiveCamera | THREE.OrthographicCamera;
    // G-buffer: world + wearer, no overlays
    const mask = cam.layers.mask;
    cam.layers.disable(LAYER_OVERLAY);
    const bg = this.scene.background;
    this.scene.background = null;
    this.scene.overrideMaterial = this.normalMat;
    this.renderer.setRenderTarget(this.gbuffer);
    this.renderer.setClearColor(0x7f7fff, 1);
    this.renderer.clear();
    this.renderer.render(this.scene, cam);
    this.scene.overrideMaterial = null;
    this.scene.background = bg;
    this.renderer.setRenderTarget(null);
    cam.layers.mask = mask;
    this.edges.uniforms.cameraNear.value = cam.near;
    this.edges.uniforms.cameraFar.value = cam.far;
    this.composer.render();
  }
}
