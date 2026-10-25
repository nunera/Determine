<script lang="ts">
  import { onMount } from "svelte";
  import * as THREE from "three";
  import { STLLoader } from "three/addons/loaders/STLLoader.js";
  import { RoomEnvironment } from "three/addons/environments/RoomEnvironment.js";

  type Theme = "paper" | "midnight" | "cobalt";
  type Part = { name: string; file: string; role: string };
  let { theme, paused = false }: { theme: Theme; paused?: boolean } = $props();
  let host: HTMLDivElement;
  let status = $state<"loading" | "ready" | "unavailable">("loading");
  let applyTheme: (value: Theme) => void = () => {};
  let updatePlayback: () => void = () => {};

  $effect(() => applyTheme(theme));
  $effect(() => {
    paused;
    updatePlayback();
  });

  onMount(() => {
    const abort = new AbortController();
    const geometries = new Set<THREE.BufferGeometry>();
    const materials = new Map<string, THREE.MeshPhysicalMaterial>();
    const scene = new THREE.Scene();
    const assembly = new THREE.Group();
    const model = new THREE.Group();
    const camera = new THREE.OrthographicCamera(-120, 120, 70, -70, 1, 2000);
    const motion = window.matchMedia("(prefers-reduced-motion: reduce)");
    const loader = new STLLoader();
    const pointer = new THREE.Vector2();
    const smoothedPointer = new THREE.Vector2();
    const bounds = new THREE.Box3();
    const lensBounds = new THREE.Box3();
    const corner = new THREE.Vector3();
    const projectedMin = new THREE.Vector3();
    const projectedMax = new THREE.Vector3();
    const textCenter = new THREE.Vector3();
    const textOrigin = new THREE.Vector3();
    const cameraRight = new THREE.Vector3();
    const cameraUp = new THREE.Vector3();
    const textCanvas = document.createElement("canvas");
    textCanvas.width = 2048;
    textCanvas.height = 512;
    const textContext = textCanvas.getContext("2d");
    const textTexture = new THREE.CanvasTexture(textCanvas);
    textTexture.colorSpace = THREE.SRGBColorSpace;
    // The opaque queue is intentional: Three's transmission pass does not see
    // transparent text. Alpha testing removes the plane, leaving only glyphs.
    const textMaterial = new THREE.MeshBasicMaterial({
      map: textTexture,
      alphaTest: 0.45,
      transparent: false,
      toneMapped: false,
    });
    const textGeometry = new THREE.PlaneGeometry(1, 1);
    geometries.add(textGeometry);
    const lettering = new THREE.Mesh(textGeometry, textMaterial);
    lettering.name = "Determine — optical typography";
    lettering.visible = false;
    scene.add(lettering);
    let renderer: THREE.WebGLRenderer | undefined;
    let environment: THREE.WebGLRenderTarget | undefined;
    let observer: ResizeObserver | undefined;
    let viewportObserver: IntersectionObserver | undefined;
    let key: THREE.DirectionalLight | undefined;
    let rim: THREE.DirectionalLight | undefined;
    let frame = 0;
    let disposed = false;
    let loaded = false;
    let inViewport = true;
    let idleTime = 0;
    let lastTime = 0;
    let width = 1;
    let height = 1;
    let fittedHeight = 160;
    let activeTheme = theme;

    // The original millimetre CAD uses Z-up and positive Y toward the ears.
    assembly.rotation.x = -Math.PI / 2;
    model.add(assembly);
    scene.add(model);
    camera.position.set(86, 49, 330);
    camera.lookAt(0, 0, 0);
    camera.updateMatrixWorld();
    lettering.quaternion.copy(camera.quaternion);
    cameraRight.setFromMatrixColumn(camera.matrixWorld, 0);
    cameraUp.setFromMatrixColumn(camera.matrixWorld, 1);

    const palette = {
      paper: {
        frame: "#687c86",
        lens: "#edf7ff",
        trim: "#a5b1b9",
        text: "#1b2126",
      },
      midnight: {
        frame: "#bed2e5",
        lens: "#e5f1ff",
        trim: "#dde9f5",
        text: "#ecf0ee",
      },
      cobalt: {
        frame: "#d1e3f4",
        lens: "#ecf6ff",
        trim: "#f2f6ff",
        text: "#f5f6f5",
      },
    };

    function drawLettering() {
      if (disposed || !textContext) return;
      textContext.clearRect(0, 0, textCanvas.width, textCanvas.height);
      textContext.font = '700 400px "Atkinson Hyperlegible Next", sans-serif';
      textContext.textAlign = "center";
      textContext.textBaseline = "middle";
      textContext.fillStyle = palette[activeTheme].text;
      textContext.fillText("Determine", 1024, 268, 1950);
      textTexture.needsUpdate = true;
      render();
    }

    function render() {
      if (!disposed && inViewport && !document.hidden && renderer)
        renderer.render(scene, camera);
    }

    function pose() {
      model.rotation.y =
        Math.sin(idleTime * 0.28) * 0.032 + smoothedPointer.x * 0.16;
      model.rotation.x =
        Math.sin(idleTime * 0.23) * 0.012 + smoothedPointer.y * 0.065;
      model.position.y = Math.sin(idleTime * 0.38) * 0.9;
      lettering.position
        .copy(textOrigin)
        .addScaledVector(cameraRight, Math.sin(idleTime * 0.27) * 8)
        .addScaledVector(cameraUp, Math.sin(idleTime * 0.21) * 5);
      // A travelling studio reflection, not extra objects or invented hardware.
      if (key) key.position.x = -100 + Math.sin(idleTime * 0.19) * 95;
      if (rim) rim.intensity = 2.4 + Math.sin(idleTime * 0.23) * 0.45;
    }

    function animate(time: number) {
      frame = 0;
      if (disposed || !loaded || !inViewport || document.hidden) return;
      const delta = lastTime ? Math.min((time - lastTime) / 1000, 0.05) : 0;
      lastTime = time;
      idleTime += delta;
      smoothedPointer.lerp(pointer, 1 - Math.exp(-delta * 7));
      pose();
      render();
      if (!paused && !motion.matches) frame = requestAnimationFrame(animate);
    }

    function playback() {
      if (frame) cancelAnimationFrame(frame);
      frame = 0;
      lastTime = 0;
      if (disposed || !loaded || !inViewport || document.hidden) return;
      render();
      if (!paused && !motion.matches) frame = requestAnimationFrame(animate);
    }

    function projectBounds() {
      projectedMin.set(Infinity, Infinity, Infinity);
      projectedMax.set(-Infinity, -Infinity, -Infinity);
      for (let index = 0; index < 8; index++) {
        corner
          .set(
            index & 1 ? bounds.max.x : bounds.min.x,
            index & 2 ? bounds.max.y : bounds.min.y,
            index & 4 ? bounds.max.z : bounds.min.z,
          )
          .applyMatrix4(camera.matrixWorldInverse);
        projectedMin.min(corner);
        projectedMax.max(corner);
      }
    }

    function fit() {
      // Include the actual text extents and reserve space for pointer rotation.
      // Neither lens coverage nor the optical effect uses screen-space boxes.
      bounds.setFromObject(assembly);
      projectBounds();
      const minX = Math.min(
        projectedMin.x,
        textCenter.x - lettering.scale.x / 2,
      );
      const maxX = Math.max(
        projectedMax.x,
        textCenter.x + lettering.scale.x / 2,
      );
      const minY = Math.min(
        projectedMin.y,
        textCenter.y - lettering.scale.y / 2,
      );
      const maxY = Math.max(
        projectedMax.y,
        textCenter.y + lettering.scale.y / 2,
      );
      const aspect = width / height;
      fittedHeight =
        Math.max(
          Math.max(Math.abs(minY), Math.abs(maxY)) * 2,
          (Math.max(Math.abs(minX), Math.abs(maxX)) * 2) / aspect,
        ) * 1.16;
      camera.left = (-fittedHeight * aspect) / 2;
      camera.right = (fittedHeight * aspect) / 2;
      camera.top = fittedHeight / 2;
      camera.bottom = -fittedHeight / 2;
      camera.updateProjectionMatrix();
    }

    function resize() {
      if (!renderer || disposed) return;
      const rect = host.getBoundingClientRect();
      width = Math.max(1, rect.width);
      height = Math.max(1, rect.height);
      if (loaded) fit();
      renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 1.75));
      renderer.setSize(width, height, false);
      render();
    }

    function move(event: PointerEvent) {
      if (motion.matches || event.pointerType === "touch" || !loaded) return;
      const rect = host.getBoundingClientRect();
      pointer.set(
        THREE.MathUtils.clamp(
          ((event.clientX - rect.left) / rect.width) * 2 - 1,
          -1,
          1,
        ),
        THREE.MathUtils.clamp(
          ((event.clientY - rect.top) / rect.height) * 2 - 1,
          -1,
          1,
        ),
      );
      if (paused) {
        smoothedPointer.copy(pointer);
        pose();
        render();
      }
    }

    function leave() {
      pointer.set(0, 0);
      if (paused || motion.matches) {
        smoothedPointer.set(0, 0);
        pose();
        render();
      }
    }

    function motionChange() {
      if (motion.matches) {
        pointer.set(0, 0);
        smoothedPointer.set(0, 0);
        idleTime = 0;
        pose();
      }
      playback();
    }

    function contextLost(event: Event) {
      event.preventDefault();
      status = "unavailable";
      dispose();
    }

    function dispose() {
      if (disposed) return;
      disposed = true;
      abort.abort();
      if (frame) cancelAnimationFrame(frame);
      observer?.disconnect();
      viewportObserver?.disconnect();
      document.removeEventListener("visibilitychange", playback);
      motion.removeEventListener("change", motionChange);
      host.removeEventListener("pointermove", move);
      host.removeEventListener("pointerleave", leave);
      renderer?.domElement.removeEventListener("webglcontextlost", contextLost);
      geometries.forEach((geometry) => geometry.dispose());
      materials.forEach((material) => material.dispose());
      textMaterial.dispose();
      textTexture.dispose();
      environment?.dispose();
      scene.clear();
      renderer?.dispose();
      renderer?.forceContextLoss();
      renderer?.domElement.remove();
      applyTheme = () => {};
      updatePlayback = () => {};
    }

    try {
      if (!textContext) throw new Error("Typography canvas unavailable");
      renderer = new THREE.WebGLRenderer({
        alpha: true,
        premultipliedAlpha: false,
        antialias: true,
        powerPreference: "low-power",
      });
      // The page itself remains the backdrop, in every theme.
      renderer.setClearColor(0x000000, 0);
      renderer.outputColorSpace = THREE.SRGBColorSpace;
      renderer.toneMapping = THREE.ACESFilmicToneMapping;
      renderer.toneMappingExposure = 1.05;
      renderer.domElement.setAttribute("aria-hidden", "true");
      renderer.domElement.addEventListener("webglcontextlost", contextLost);
      host.prepend(renderer.domElement);

      const studio = new RoomEnvironment();
      const pmrem = new THREE.PMREMGenerator(renderer);
      try {
        environment = pmrem.fromScene(studio, 0.04);
        scene.environment = environment.texture;
      } finally {
        studio.dispose();
        pmrem.dispose();
      }
      scene.add(new THREE.HemisphereLight(0xeaf4ff, 0x52647b, 1.9));
      key = new THREE.DirectionalLight(0xffffff, 3.1);
      key.position.set(-100, 180, 240);
      scene.add(key);
      rim = new THREE.DirectionalLight(0x9cc6ff, 2.4);
      rim.position.set(160, 30, -120);
      scene.add(rim);

      materials.set(
        "shell",
        new THREE.MeshPhysicalMaterial({
          metalness: 0.48,
          roughness: 0.18,
          clearcoat: 1,
          clearcoatRoughness: 0.07,
          envMapIntensity: 1.65,
        }),
      );
      materials.set(
        "lens_window",
        new THREE.MeshPhysicalMaterial({
          metalness: 0,
          roughness: 0.32,
          transmission: 1,
          // An illustrative optical path: the supplied CAD is a plano surrogate,
          // not a prescription lens. Thickness makes its refraction legible.
          thickness: 10,
          ior: 1.49,
          clearcoat: 0.32,
          clearcoatRoughness: 0.08,
          envMapIntensity: 0.65,
          // No blending with the crisp word underneath. The transmission shader
          // writes its own sampled alpha, preserving the transparent page outside.
          transparent: false,
          depthWrite: true,
          side: THREE.FrontSide,
        }),
      );
      // Preserve the original hollow chamber and rear shield without turning
      // them into a second bulky, opaque optical volume.
      materials.set(
        "lens_chamber",
        new THREE.MeshPhysicalMaterial({
          metalness: 0,
          roughness: 0.11,
          clearcoat: 0.7,
          transparent: true,
          opacity: 0.055,
          depthWrite: false,
          envMapIntensity: 0.8,
        }),
      );
      materials.set(
        "metal",
        new THREE.MeshPhysicalMaterial({
          metalness: 0.95,
          roughness: 0.16,
        }),
      );
      materials.set(
        "silicone",
        new THREE.MeshPhysicalMaterial({
          color: "#aab7c0",
          roughness: 0.7,
          transparent: true,
          opacity: 0.6,
        }),
      );
      materials.set(
        "gasket",
        new THREE.MeshPhysicalMaterial({
          color: "#243342",
          roughness: 0.68,
        }),
      );

      applyTheme = (value) => {
        if (disposed) return;
        activeTheme = value;
        const colors = palette[value];
        materials.get("shell")!.color.set(colors.frame);
        materials.get("lens_window")!.color.set(colors.lens);
        materials.get("lens_chamber")!.color.set(colors.lens);
        materials.get("metal")!.color.set(colors.trim);
        drawLettering();
      };
      updatePlayback = playback;
      applyTheme(theme);
      // Refresh after the actual Atkinson face loads, rather than freezing the
      // initial fallback glyphs into a CanvasTexture.
      void document.fonts
        .load('700 400px "Atkinson Hyperlegible Next"', "Determine")
        .then(() => document.fonts.ready)
        .then(() => drawLettering())
        .catch(() => {
          /* The system-font canvas remains a usable fallback. */
        });
      observer = new ResizeObserver(resize);
      observer.observe(host);
      viewportObserver = new IntersectionObserver((entries) => {
        inViewport = entries[0]?.isIntersecting ?? false;
        playback();
      });
      viewportObserver.observe(host);
      document.addEventListener("visibilitychange", playback);
      motion.addEventListener("change", motionChange);
      host.addEventListener("pointermove", move);
      host.addEventListener("pointerleave", leave);
      resize();

      void (async () => {
        try {
          const base = `${import.meta.env.BASE_URL}model/`;
          const response = await fetch(`${base}manifest.json`, {
            signal: abort.signal,
          });
          if (!response.ok) throw new Error("CAD manifest unavailable");
          const manifest = (await response.json()) as { parts: Part[] };
          await Promise.all(
            manifest.parts.map(async (part) => {
              const response = await fetch(`${base}${part.file}`, {
                signal: abort.signal,
              });
              if (!response.ok) throw new Error("CAD mesh unavailable");
              const buffer = await response.arrayBuffer();
              if (disposed) return;
              const geometry = loader.parse(buffer);
              geometries.add(geometry);
              const role = part.role === "lid" ? "shell" : part.role;
              const material = materials.get(role);
              if (!material) throw new Error("Unknown CAD material");
              const mesh = new THREE.Mesh(geometry, material);
              mesh.name = part.name;
              assembly.add(mesh);
            }),
          );
          if (disposed) return;
          bounds.setFromObject(assembly).getCenter(corner);
          assembly.position.sub(corner);
          model.updateMatrixWorld(true);
          bounds.setFromObject(assembly);
          projectBounds();
          const projectedWidth = projectedMax.x - projectedMin.x;
          const rearDepth = projectedMin.z - 35;
          // Position the camera-facing word using the actual lens centroid,
          // behind the entire CAD assembly, with crisp letters beyond its rims.
          lensBounds.makeEmpty();
          assembly.children.forEach((part) => {
            if ((part as THREE.Mesh).material === materials.get("lens_window"))
              lensBounds.expandByObject(part);
          });
          lensBounds
            .getCenter(textCenter)
            .applyMatrix4(camera.matrixWorldInverse);
          textCenter.z = rearDepth;
          lettering.position.copy(textCenter).applyMatrix4(camera.matrixWorld);
          textOrigin.copy(lettering.position);
          lettering.scale.set(projectedWidth * 1.12, projectedWidth * 0.28, 1);
          lettering.visible = true;
          loaded = true;
          status = "ready";
          resize();
          playback();
        } catch {
          if (disposed) return;
          status = "unavailable";
          dispose();
        }
      })();
    } catch {
      status = "unavailable";
      dispose();
    }

    return dispose;
  });
</script>

<div
  class="glasses-scene"
  class:ready={status === "ready"}
  bind:this={host}
  role="img"
  aria-label="Interactive three-dimensional Determine slim-frame CAD design study. Determine typography floats behind the original lens geometry, with illustrative refraction through the lenses. Not production hardware."
>
  {#if status !== "ready"}
    <div class="scene-fallback" class:failed={status === "unavailable"}>
      <svg viewBox="0 0 600 210" fill="none" aria-hidden="true">
        <defs>
          <linearGradient
            id="determine-lens"
            x1="160"
            y1="55"
            x2="440"
            y2="180"
            gradientUnits="userSpaceOnUse"
          >
            <stop stop-color="currentColor" stop-opacity="0.03" />
            <stop offset="0.5" stop-color="#7cafff" stop-opacity="0.25" />
            <stop offset="1" stop-color="currentColor" stop-opacity="0.04" />
          </linearGradient>
        </defs>
        <path
          d="m85 77 47-48h45M515 77l-47-48h-45"
          stroke="currentColor"
          stroke-width="7"
          stroke-linecap="round"
        />
        <path
          d="M90 75c32-15 110-17 148-1 11 5 13 18 10 42l-7 29c-5 18-20 26-65 26-46 0-65-9-72-28L88 99c-4-10-4-19 2-24Zm420 0c-32-15-110-17-148-1-11 5-13 18-10 42l7 29c5 18 20 26 65 26 46 0 65-9 72-28l16-44c4-10 4-19-2-24Z"
          fill="url(#determine-lens)"
          stroke="currentColor"
          stroke-width="7"
        />
        <path
          d="M249 90c30-16 72-16 102 0M75 80h15m420 0h15"
          stroke="currentColor"
          stroke-width="7"
          stroke-linecap="round"
        />
      </svg>
      <p role="status">
        {status === "loading"
          ? "Loading the CAD design study…"
          : "3D preview unavailable · slim-frame illustration shown"}
      </p>
    </div>
  {/if}
</div>

<style>
  .glasses-scene {
    position: relative;
    width: 100%;
    height: 100%;
    isolation: isolate;
    color: var(--ink, #22334b);
    touch-action: pan-y;
  }

  .glasses-scene :global(canvas) {
    display: block;
    width: 100%;
    height: 100%;
    opacity: 0;
    transition: opacity 600ms ease;
  }

  .glasses-scene.ready :global(canvas) {
    opacity: 1;
  }

  .scene-fallback {
    position: absolute;
    inset: 0;
    display: flex;
    align-items: center;
    justify-content: center;
    flex-direction: column;
    pointer-events: none;
  }

  .scene-fallback svg {
    width: min(78%, 750px);
    max-height: 76%;
    opacity: 0.55;
  }

  .scene-fallback.failed svg {
    opacity: 0.9;
  }

  .scene-fallback p {
    margin: 0.4rem 1rem 1.5rem;
    color: var(--muted, #5c6674);
    font-family: var(--mono, monospace);
    font-size: 0.75rem;
    text-align: center;
  }

  @media (max-width: 600px) {
    .scene-fallback p {
      max-width: 27ch;
      font-size: 0.6875rem;
    }
  }

  @media (prefers-reduced-motion: reduce) {
    .glasses-scene :global(canvas) {
      transition: none;
    }
  }
</style>
