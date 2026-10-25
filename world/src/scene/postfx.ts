/**
 * Quiet product rendering: optional contact AO, SMAA and the output transform (Neutral tone mapping, sRGB).
 * No ink outlines, bloom, grain, aberration or lens effects. Balanced/low quality skip the normal/depth pass
 * entirely but retain antialiasing, rather than falling back to a jagged unprocessed canvas.
 */
import * as THREE from "three";
import { EffectComposer } from "three/addons/postprocessing/EffectComposer.js";
import { RenderPass } from "three/addons/postprocessing/RenderPass.js";
import { GTAOPass } from "three/addons/postprocessing/GTAOPass.js";
import { SMAAPass } from "three/addons/postprocessing/SMAAPass.js";
import { OutputPass } from "three/addons/postprocessing/OutputPass.js";
import { LAYER_OVERLAY } from "./stage";


export class PostFx {
  readonly composer: EffectComposer;
  private gbuffer: THREE.WebGLRenderTarget;
  private normalMat = new THREE.MeshNormalMaterial();
  private renderPass: RenderPass;
  private gtao: GTAOPass;
  private size = new THREE.Vector2();
  private clearColor = new THREE.Color();

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
    this.gtao.updateGtaoMaterial({ radius: 0.3, distanceExponent: 1.4, thickness: 0.6, scale: 1.0, samples: 12, screenSpaceRadius: false });
    this.gtao.updatePdMaterial({ lumaPhi: 10, depthPhi: 2, normalPhi: 3, radius: 4, radiusExponent: 1, rings: 2, samples: 12 });
    this.gtao.blendIntensity = 0.55;
    this.composer.addPass(this.gtao);
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
    // GTAO's shaders are projection-specific; the clean plan view needs no contact AO.
    this.gtao.enabled = !ortho && this.aoAllowed;
    if (!ortho) this.gtao.camera = camera;
  }

  setSize(w: number, h: number): void {
    // EffectComposer caches DPR; refresh it when adaptive quality changes renderer resolution.
    this.composer.setPixelRatio(this.renderer.getPixelRatio());
    this.composer.setSize(w, h);
    this.renderer.getDrawingBufferSize(this.size);
    this.gbuffer.setSize(this.size.x, this.size.y);
    this.gtao.setSize(this.size.x, this.size.y);
  }

  render(): void {
    if (this.gtao.enabled) {
      const cam = this.camera;
      // G-buffer: world + wearer, no measurement overlays. Restore all renderer state for HITL capture.
      const mask = cam.layers.mask;
      const background = this.scene.background;
      const override = this.scene.overrideMaterial;
      const target = this.renderer.getRenderTarget();
      this.renderer.getClearColor(this.clearColor);
      const alpha = this.renderer.getClearAlpha();
      cam.layers.disable(LAYER_OVERLAY);
      this.scene.background = null;
      this.scene.overrideMaterial = this.normalMat;
      try {
        this.renderer.setRenderTarget(this.gbuffer);
        this.renderer.setClearColor(0x7f7fff, 1);
        this.renderer.clear();
        this.renderer.render(this.scene, cam);
      } finally {
        this.scene.overrideMaterial = override;
        this.scene.background = background;
        this.renderer.setRenderTarget(target);
        this.renderer.setClearColor(this.clearColor, alpha);
        cam.layers.mask = mask;
      }
    }
    this.composer.render();
  }

  dispose(): void {
    for (const pass of this.composer.passes) pass.dispose();
    this.composer.dispose();
    this.gbuffer.depthTexture?.dispose();
    this.gbuffer.dispose();
    this.normalMat.dispose();
  }
}
