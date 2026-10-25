import { resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";

export const webDirectory = resolve(
  dirname(fileURLToPath(import.meta.url)),
  "..",
);

export function loadWebEnv() {
  const [major, minor] = process.versions.node.split(".").map(Number);
  if (major < 22 || (major === 22 && minor < 13)) {
    throw new Error("Determine authentication requires Node 22.13 or newer.");
  }
  try {
    // Node's parser preserves already-exported environment variables. No shell
    // evaluation and no VITE_ prefix: these values remain server-only.
    process.loadEnvFile(resolve(webDirectory, ".env"));
  } catch (error) {
    if (!(
      error &&
      typeof error === "object" &&
      "code" in error &&
      error.code === "ENOENT"
    ))
      throw error;
  }
}
