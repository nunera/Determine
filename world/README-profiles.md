# Simulated device-profile captures

`src/data/deviceProfile.ts` exports:

```ts
createDeviceProfile(bus: Bus, root: HTMLElement, world: WorldHandle): void
```

Initialize it **after the HUD**, with the HUD root and existing world handle. It appends a compact comparison section to the left experiments column using the existing HUD classes. The parent application owns wiring in `main.ts`.

## Use

Choose **Current headband** or **Ray-Ban Meta camera route**, then press **Capture JPEG + metadata**. The control prepares a local JPEG and matching JSON sidecar and requests both downloads. Browsers may block multiple automatic downloads; the JPEG and Metadata JSON links remain available for individual downloads. A successful subsequent capture replaces those links. Capture errors are shown rather than replaced with a fake frame. This control does not require a connected bridge or manufacture a disconnected-device warning.

Both choices are labelled **SIMULATED STUDY · not DAT integrated**. Selecting a profile changes only this export, not the live camera-board feed, Describe, the engine, or audio.

| Selection | Export dimensions, configured | Image operation |
| --- | --- | --- |
| Current headband | 320 × 240 | Original JPEG from the existing simulated head camera |
| Ray-Ban Meta camera route | 720 × 1280 portrait | Center crop of that same 320 × 240 source, then resample and encode as JPEG locally |

The module always calls `captureHeadJpeg(320, 240, 0.92)`. It does **not** ask the world renderer for a portrait aspect ratio, because that changes the simulated camera projection rather than cropping the same image. It decodes the JPEG with `createImageBitmap`, retains the original JPEG for the headband, and draws the portrait crop on a canvas with asynchronous `toBlob` encoding. The bitmap is closed after processing. There is no continuous export/encoding loop or new worker.

For the portrait choice, the algorithm retains a **135 × 240 source-pixel rectangle** centered at **x = 92.5, y = 0**, then scales it to 720 × 1280. These are derived crop coordinates, **not hardware measurements**. The portrait loses peripheral source content and gains no optical detail. It shares the simulator source camera's calibration and mount; it is **not validated Ray-Ban optics** or a simulated temple-mounted optical system. Hardware FOV remains **unverified**, with no invented numeric value.

## Sidecar meaning

The JSON schema identifier is `determine.world.device-profile-capture.v1`. It records:

- `source: "simulation"`, selected profile and camera-route study identifier, `sdkIntegrated: false`, and `hardwareAccess: false`;
- configured output resolution and JPEG quality, observed decoded source dimensions and encoded Blob byte count, source crop coordinates, and whether the operation retained the original JPEG or cropped/resampled it;
- unverified hardware FOV, `hardwareDegrees: null`, and `rayBanOpticsValidated: false`;
- the documented DAT maximum portrait dimensions and allowed stream rates as **reference specifications**, not measurements or applied stream configuration;
- an unchanged bridge with the existing configured camera-board rate (8 fps), plus unchanged single-zone ToF configuration: 18° diagonal cone, up to 1200 mm for a white target, 15 Hz;
- unchanged one-motor, haptic-only board, ordinary Bluetooth phone audio rather than a DAT audio API, and unmeasured latency with zero configured artificial delay;
- request wall-clock time and the latest course event at request time. The course event is explicitly **not a synchronized image-capture timestamp**.

The route field describes the selected comparison, **not evidence that frames were sent through DAT or hardware**. The ordinary Bluetooth field describes the phone-output architecture; this module neither discovers nor changes the browser's actual audio output device. JPEG byte counts and decoded dimensions are local export observations; sensor settings and output dimensions are configuration, not bench results.

## Reference specification and deliberate limits

The research basis is [`docs/research/meta-fit.md`, §§1.3 and 3.4](../docs/research/meta-fit.md). That research documents DAT portrait output up to **720 × 1280** and accepted stream rates **{2, 7, 15, 24, 30} fps**. It also distinguishes ordinary Bluetooth A2DP/HFP audio output from DAT and leaves optical FOV unverified. This is a **single-frame interface/dataset-shape study**, so there is no frame-rate selector or claim that the unchanged 8 fps simulated camera-board feed is a permitted DAT stream.

This implementation deliberately narrows the research document's proposed simulator profile: it does not move the camera mount, set an unverified FOV, alter camera-board pacing, or implement the suggested Bluetooth latency model. **No artificial transport delay is added** and no transport/ear latency is reported as measured. Neither profile changes the ToF, haptic path, safety-critical bridge, firmware, hazard decisions, or phone cue audio.

It includes no DAT SDK application, Mock Device Kit integration, glasses pairing or permissions, physical camera access, Llama deployment, or cloud provider integration. The capture control does not upload its JPEG or sidecar; this does not disable or make privacy guarantees about the application's separate live bridge or optional Describe paths. This is not a privacy/redaction receipt. It cannot validate hardware behavior, assess navigation safety, or establish that a path is clear.
