/**
 * JPEG encoding off the main thread. Chrome encodes canvas.toBlob / convertToBlob on the calling thread's idle
 * time and only falls back after ~1 s; a continuously rendering 3-D loop leaves no idle time, which delayed every
 * camera frame by about a second and made the engine report frame_stale. This worker's thread is idle.
 */
interface EncodeRequest { id: number; width: number; height: number; pixels: ArrayBuffer; quality: number }

self.onmessage = async (e: MessageEvent<EncodeRequest>) => {
  const { id, width, height, pixels, quality } = e.data;
  try {
    const canvas = new OffscreenCanvas(width, height);
    canvas.getContext("2d")!.putImageData(new ImageData(new Uint8ClampedArray(pixels), width, height), 0, 0);
    const jpeg = await canvas.convertToBlob({ type: "image/jpeg", quality });
    self.postMessage({ id, jpeg });
  } catch (err) {
    self.postMessage({ id, error: err instanceof Error ? err.message : String(err) });
  }
};
