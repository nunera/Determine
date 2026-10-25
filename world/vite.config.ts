import { defineConfig } from "vite";

// The bridge (tools/sim/world_bridge.py) serves /ws and /api on 127.0.0.1:8765.
const BRIDGE = process.env.DETERMINE_BRIDGE ?? "http://127.0.0.1:8765";

export default defineConfig({
  server: {
    host: "127.0.0.1",
    port: 5173,
    proxy: {
      "/ws": { target: BRIDGE.replace(/^http/, "ws"), ws: true },
      "/api": { target: BRIDGE },
    },
  },
  build: { target: "es2022", chunkSizeWarningLimit: 2000 },
});
