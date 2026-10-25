import { defineConfig } from "vite";
import { svelte } from "@sveltejs/vite-plugin-svelte";
import { resolve } from "node:path";
import { loadWebEnv, webDirectory } from "./server/env.mjs";

loadWebEnv();
const authData = resolve(webDirectory, process.env.AUTH_DATA_DIR || ".auth");
const authProxy = {
  "/api": {
    target: "http://127.0.0.1:8770",
    changeOrigin: false,
    xfwd: false,
  },
};
export default defineConfig({
  plugins: [svelte()],
  server: {
    host: "127.0.0.1",
    proxy: authProxy,
    fs: {
      deny: [
        ".env",
        ".env.*",
        "*.{crt,pem}",
        "**/.git/**",
        "**/.auth/**",
        "**/server/**",
        `${authData}/**`,
      ],
    },
  },
  preview: { host: "127.0.0.1", proxy: authProxy },
});
