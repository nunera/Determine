/**
 * Image encoding off the main thread (camera-board JPEGs, Describe JPEGs, HITL overlay PNGs). Chrome encodes
 * canvas.toBlob / convertToBlob on the calling thread's idle time and only falls back after ~1 s; a continuously
 * rendering 3-D loop leaves no idle time, which delayed every camera frame by about a second and made the engine
 * report frame_stale. This worker's thread is idle.
 */
interface EncodeRequest {
  id: number; width: number; height: number; pixels: ArrayBuffer;
  type: "image/jpeg" | "image/png";
  /** JPEG only. */
  quality: number;
}

self.onmessage = async (e: MessageEvent<EncodeRequest>) => {
  const { id, width, height, pixels, type, quality } = e.data;
  try {
    const canvas = new OffscreenCanvas(width, height);
    canvas.getContext("2d")!.putImageData(new ImageData(new Uint8ClampedArray(pixels), width, height), 0, 0);
    const blob = await canvas.convertToBlob(type === "image/png" ? { type } : { type, quality });
    self.postMessage({ id, blob });
  } catch (err) {
    self.postMessage({ id, error: err instanceof Error ? err.message : String(err) });
  }
};
