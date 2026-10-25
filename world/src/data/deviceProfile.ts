import {
  CAMERA_FPS, FOV_DIAG_DEG, RANGE_HZ, RANGE_LIMIT_MM, type Bus, type WorldHandle,
} from "../contract";
import { el } from "../hud/dom";

const SOURCE_WIDTH = 320;
const SOURCE_HEIGHT = 240;
const JPEG_QUALITY = 0.92;
const DAT_ALLOWED_FPS = [2, 7, 15, 24, 30] as const;
const PROFILES = [
  {
    id: "current_headband",
    label: "Current headband",
    route: "esp32_cam_camera_board_simulation",
    width: SOURCE_WIDTH,
    height: SOURCE_HEIGHT,
    note: "320 × 240 · original simulated head-camera frame. Camera-board transport stays unchanged.",
  },
  {
    id: "rayban_meta_camera_study",
    label: "Ray-Ban Meta camera route",
    route: "rayban_meta_dat_camera_study",
    width: 720,
    height: 1280,
    note: "720 × 1280 portrait · center crop/resample of the same 320 × 240 head-camera image, not validated Ray-Ban optics. DAT is not integrated.",
  },
] as const;

function encodeCanvas(canvas: HTMLCanvasElement): Promise<Blob> {
  const { promise, resolve, reject } = Promise.withResolvers<Blob>();
  canvas.toBlob((blob) => {
    if (blob) resolve(blob);
    else reject(new Error("The browser could not encode the portrait JPEG."));
  }, "image/jpeg", JPEG_QUALITY);
  return promise;
}

/** Export-only interface study: never changes the bridge, camera feed, ToF or cue audio. */
export function createDeviceProfile(bus: Bus, root: HTMLElement, world: WorldHandle): void {
  const left = root.querySelector<HTMLElement>(".left");
  if (!left) throw new Error("Create the HUD before initializing device profiles.");

  const select = el("select", { class: "course-select", "aria-label": "Capture device profile" },
    ...PROFILES.map((profile) => el("option", { value: profile.id }, profile.label)),
  );
  select.style.maxWidth = "100%";
  select.style.minWidth = "0";
  const note = el("p", { class: "fine" }, PROFILES[0].note);
  const capture = el("button", { class: "btn", type: "button" }, "Capture JPEG + metadata");
  const status = el("p", { class: "fine", role: "status", "aria-live": "polite" });
  const downloads = el("div", { class: "controls", hidden: true });
  downloads.style.display = "none";
  const section = el("section", { class: "panel experiments", "aria-label": "Simulated device profile comparison" },
    el("p", { class: "eyebrow" }, "SIMULATED STUDY · not DAT integrated"),
    el("div", { class: "controls" }, select, capture),
    note,
    el("p", { class: "fine" },
      "DAT reference: portrait up to 720 × 1280; allowed fps 2, 7, 15, 24, 30. Single captures only here; no DAT stream. FOV unverified; latency unmeasured.",
    ),
    el("p", { class: "fine" },
      "ToF and one-motor haptics unchanged. Phone audio uses ordinary Bluetooth, not DAT; browser playback is unchanged. No artificial delay. No hardware access or clear-path claim.",
    ),
    status,
    downloads,
  );
  left.append(section);

  let objectUrls: string[] = [];
  select.addEventListener("change", () => {
    note.textContent = PROFILES[select.selectedIndex].note;
  });

  capture.addEventListener("click", async () => {
    const profile = PROFILES[select.selectedIndex];
    const requestedAt = new Date().toISOString();
    const courseAtRequest = bus.latest("course");
    capture.disabled = true;
    select.disabled = true;
    status.textContent = "Capturing the simulated head camera locally…";
    try {
      // Always preserve the existing 4:3 head-camera projection. Asking the renderer for portrait
      // dimensions would change its camera aspect/FOV rather than crop the same source image.
      const source = await world.captureHeadJpeg(SOURCE_WIDTH, SOURCE_HEIGHT, JPEG_QUALITY);
      const bitmap = await createImageBitmap(source);
      let jpeg: Blob;
      let sourceWidth: number;
      let sourceHeight: number;
      let crop: { x: number; y: number; width: number; height: number };
      try {
        sourceWidth = bitmap.width;
        sourceHeight = bitmap.height;
        if (sourceWidth !== SOURCE_WIDTH || sourceHeight !== SOURCE_HEIGHT) {
          throw new Error("The head-camera source did not match the requested 320 × 240 calibration.");
        }
        const aspect = profile.width / profile.height;
        const width = Math.min(sourceWidth, sourceHeight * aspect);
        const height = Math.min(sourceHeight, sourceWidth / aspect);
        crop = { x: (sourceWidth - width) / 2, y: (sourceHeight - height) / 2, width, height };
        if (profile.id === "current_headband") {
          jpeg = source;
        } else {
          const canvas = document.createElement("canvas");
          canvas.width = profile.width;
          canvas.height = profile.height;
          const context = canvas.getContext("2d");
          if (!context) throw new Error("The browser could not create a portrait capture canvas.");
          context.imageSmoothingEnabled = true;
          context.imageSmoothingQuality = "high";
          context.drawImage(bitmap, crop.x, crop.y, crop.width, crop.height, 0, 0, canvas.width, canvas.height);
          jpeg = await encodeCanvas(canvas);
        }
      } finally {
        bitmap.close();
      }

      const basename = `determine-${profile.id}-${requestedAt.replace(/[:.]/g, "-")}`;
      const metadata = {
        schema: "determine.world.device-profile-capture.v1",
        study: "SIMULATED STUDY",
        source: "simulation",
        profile: profile.id,
        route: profile.route,
        sdkIntegrated: false,
        hardwareAccess: false,
        requestedAt,
        courseAtRequest: courseAtRequest ?? null,
        courseTimestampMeaning: "Latest course event at request, not a synchronized capture timestamp",
        image: {
          filename: `${basename}.jpg`,
          mimeType: "image/jpeg",
          resolution: { width: profile.width, height: profile.height },
          resolutionEvidence: "configured export dimensions; not hardware sensor resolution",
          bytes: jpeg.size,
          bytesEvidence: "observed local encoded Blob size",
          jpegQualityConfigured: JPEG_QUALITY,
          sourceResolution: { width: sourceWidth, height: sourceHeight },
          sourceResolutionEvidence: "observed decoded simulated head-camera JPEG dimensions",
          sourceCalibration: "Existing simulator head camera at 320x240; unchanged source projection and mount",
          sourceCropPixels: crop,
          transform: profile.id === "current_headband" ? "original_jpeg" : "center_crop_and_resample",
          addedOpticalDetail: false,
          fov: { status: "unverified", hardwareDegrees: null, rayBanOpticsValidated: false },
        },
        captureMode: "single_frame_export_only",
        datReference: {
          maximumPortraitResolution: { width: 720, height: 1280 },
          allowedStreamFps: DAT_ALLOWED_FPS,
          evidence: "docs/research/meta-fit.md sections 1.3 and 3.4; documented specification, not observed hardware",
          appliedStreamFps: null,
        },
        bridge: { changed: false, cameraFpsConfigured: CAMERA_FPS, profileAppliedToLiveFeed: false },
        tof: {
          unchanged: true,
          zonesConfigured: 1,
          diagonalConeDegreesConfigured: FOV_DIAG_DEG,
          limitMmConfigured: RANGE_LIMIT_MM,
          limitTargetCondition: "white target",
          rateHzConfigured: RANGE_HZ,
          evidence: "simulator configuration, not new hardware measurements",
        },
        haptics: { unchanged: true, motorsConfigured: 1, boardAudio: false },
        audio: {
          path: "ordinary_bluetooth",
          datAudioApi: false,
          meaning: "Phone output route in the device comparison; actual browser audio routing is not inspected or changed",
        },
        latency: { status: "unmeasured", measuredMs: null, artificialDelayMsConfigured: 0 },
        safety: "Export does not drive hazards, replace ToF, or establish a clear path",
      };
      const sidecar = new Blob([JSON.stringify(metadata, null, 2) + "\n"], { type: "application/json" });
      for (const url of objectUrls) URL.revokeObjectURL(url);
      objectUrls = [URL.createObjectURL(jpeg), URL.createObjectURL(sidecar)];
      const jpegLink = el("a", { class: "btn", href: objectUrls[0], download: `${basename}.jpg` }, "JPEG");
      const metadataLink = el("a", { class: "btn", href: objectUrls[1], download: `${basename}.json` }, "Metadata JSON");
      downloads.replaceChildren(jpegLink, metadataLink);
      downloads.hidden = false;
      downloads.style.display = "";
      jpegLink.click();
      metadataLink.click();
      status.textContent = `${profile.label}: ${profile.width} × ${profile.height} export prepared locally. If the browser blocks two downloads, use these links. No upload by this capture control.`;
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      status.textContent = `Capture failed: ${message}${objectUrls.length ? " Previous export links remain below." : ""}`;
    } finally {
      capture.disabled = false;
      select.disabled = false;
    }
  });
}
