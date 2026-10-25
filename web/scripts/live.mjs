import { spawn } from "node:child_process";
import { randomBytes } from "node:crypto";
import { access } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { loadWebEnv } from "../server/env.mjs";

const web = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const root = resolve(web, "..");
const children = new Set();
let closing = false;

function stop(code = 0) {
  if (closing) return;
  closing = true;
  process.exitCode = code;
  for (const child of children) {
    try {
      if (process.platform === "win32") child.kill("SIGTERM");
      else process.kill(-child.pid, "SIGTERM");
    } catch (error) {
      if (error.code !== "ESRCH")
        console.error(`[Determine] Shutdown: ${error.message}`);
    }
  }
}

process.on("SIGINT", () => stop());
process.on("SIGTERM", () => stop());

function launch(command, args, cwd, env = {}) {
  // Provider/bridge secrets belong only to the processes that need them, never build tools or Vite.
  const childEnv = { ...process.env };
  for (const key of [
    "OPENAI_API_KEY",
    "OPENROUTER_API_KEY",
    "DESCRIBE_KEY",
    "DESCRIBE_API_KEY",
    "DETERMINE_DESCRIBE_KEY",
  ])
    delete childEnv[key];
  Object.assign(childEnv, env);
  const child = spawn(command, args, {
    cwd,
    env: childEnv,
    stdio: "inherit",
    detached: process.platform !== "win32",
  });
  children.add(child);
  child.once("exit", () => children.delete(child));
  return child;
}

async function prepare(command, args, cwd) {
  if (closing) throw new Error("Startup cancelled");
  const child = launch(command, args, cwd);
  await new Promise((resolve, reject) => {
    child.once("error", reject);
    child.once("exit", (code, signal) => {
      if (code === 0) resolve();
      else reject(new Error(`${command} failed (${signal || code})`));
    });
  });
}

function serve(command, args, cwd, env = {}) {
  const child = launch(command, args, cwd, env);
  child.once("error", (error) => {
    console.error(`[Determine] ${command}: ${error.message}`);
    stop(1);
  });
  child.once("exit", (code) => {
    if (!closing) {
      console.error(
        `[Determine] ${command} stopped (${code}); shutting down the live session.`,
      );
      stop(code || 1);
    }
  });
}

try {
  loadWebEnv();
  console.log("[Determine] Building the real phone engine and browser World…");
  await prepare("./gradlew", [":cli:installDist"], resolve(root, "phone"));
  try {
    await access(resolve(root, "world/node_modules/vite/package.json"));
  } catch {
    await prepare("npm", ["ci"], resolve(root, "world"));
  }
  await prepare("npm", ["run", "build"], resolve(root, "world"));
  if (!closing) {
    serve(process.execPath, ["server/index.mjs"], web);
    let describeUrl = "";
    let describeKey = "";
    const externalUrl = process.env.DESCRIBE_URL?.trim();
    const externalKey = process.env.DESCRIBE_KEY?.trim();
    const openrouterKey = process.env.OPENROUTER_API_KEY?.trim();
    if (externalUrl && externalKey) {
      let endpoint;
      try {
        endpoint = new URL(externalUrl);
      } catch {
        throw new Error(
          "DESCRIBE_URL must be a Describe service HTTP(S) base URL.",
        );
      }
      if (
        !["http:", "https:"].includes(endpoint.protocol) ||
        endpoint.username ||
        endpoint.password ||
        endpoint.search ||
        endpoint.hash
      )
        throw new Error(
          "DESCRIBE_URL must be an HTTP(S) base URL without credentials, query or fragment.",
        );
      describeUrl = endpoint.href.replace(/\/$/, "");
      describeKey = externalKey;
      console.log(
        "[Determine] Describe: configured external service (SIM + HITL).",
      );
    } else if (openrouterKey) {
      describeUrl = "http://127.0.0.1:8771";
      describeKey = randomBytes(32).toString("base64url");
      serve(
        "python3",
        [
          "cloud/describe-api/local_server.py",
          "--host",
          "127.0.0.1",
          "--port",
          "8771",
        ],
        root,
        {
          DESCRIBE_API_KEY: describeKey,
          DESCRIBE_BACKEND: "openrouter",
          DESCRIBE_FALLBACK: "none",
          OPENROUTER_API_KEY: openrouterKey,
          OPENROUTER_MODEL:
            process.env.OPENROUTER_MODEL?.trim() || "openai/gpt-6-luna",
        },
      );
      console.log(
        "[Determine] Describe: local OpenRouter service on loopback :8771 (SIM + HITL).",
      );
    } else {
      console.log(
        "[Determine] Describe: OFF. Set OPENROUTER_API_KEY or both DESCRIBE_URL and DESCRIBE_KEY server-side to enable.",
      );
    }
    const bridgeEnv = describeKey
      ? { DETERMINE_DESCRIBE_KEY: describeKey }
      : {};
    const common = [
      "tools/sim/world_bridge.py",
      "--serve",
      "world/dist",
      "--mode",
      "HAPTIC_ONLY",
      "--haptic-max",
      "96",
    ];
    if (describeUrl) common.push("--describe-url", describeUrl);
    serve(
      "python3",
      [...common, "--port", "8765", "--engine-port", "47000"],
      root,
      bridgeEnv,
    );
    serve(
      "python3",
      [
        ...common,
        "--port",
        "8766",
        "--engine-port",
        "47110",
        "--hitl",
        "0.0.0.0:47100",
        "--hitl-range",
        "real",
        "--hitl-camera",
        "real",
      ],
      root,
      bridgeEnv,
    );
    serve("npm", ["run", "dev", "--", "--port", "5175", "--strictPort"], web);
    console.log("[Determine] Studio: http://127.0.0.1:5175/#/studio");
    console.log(
      "[Determine] SIM bridge: :8765 · HITL bridge: :8766 · real boards send UDP to this machine on :47100",
    );
    console.log(
      "[Determine] HITL starts with real range + camera. Controls can change real hardware settings; use a supervised bench setup. Ctrl-C stops all sessions.",
    );
  }
} catch (error) {
  console.error(`[Determine] ${error.message}`);
  console.error(
    "[Determine] Requires Node 22.13+, Python 3 and JDK 17. On Nix: nix shell nixpkgs#nodejs_22 nixpkgs#jdk17 nixpkgs#python3 -c npm run live",
  );
  stop(1);
}
