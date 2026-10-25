import { randomBytes } from "node:crypto";
import { chmod, mkdir, readFile, writeFile } from "node:fs/promises";
import { isAbsolute, relative, resolve } from "node:path";
import { loadWebEnv, webDirectory } from "./env.mjs";

export async function readConfig() {
  loadWebEnv();
  const production = process.env.NODE_ENV === "production";
  const url = new URL(process.env.BETTER_AUTH_URL || "http://127.0.0.1:5175");
  if (
    url.username ||
    url.password ||
    url.pathname !== "/" ||
    url.search ||
    url.hash
  ) {
    throw new Error(
      "BETTER_AUTH_URL must be an origin without credentials, path, query or fragment.",
    );
  }
  const localHost = ["localhost", "127.0.0.1", "[::1]"].includes(url.hostname);
  if (
    url.protocol !== "https:" &&
    !(url.protocol === "http:" && localHost && !production)
  ) {
    throw new Error(
      "BETTER_AUTH_URL requires HTTPS, except for local development loopback HTTP.",
    );
  }
  const dataDirectory = resolve(
    webDirectory,
    process.env.AUTH_DATA_DIR || ".auth",
  );
  const webWithinData = relative(dataDirectory, webDirectory);
  if (
    !webWithinData ||
    (webWithinData !== ".." &&
      !webWithinData.startsWith("../") &&
      !isAbsolute(webWithinData))
  ) {
    throw new Error("AUTH_DATA_DIR cannot contain the web project itself.");
  }
  for (const directory of ["public", "src", "dist"]) {
    const location = relative(resolve(webDirectory, directory), dataDirectory);
    if (
      !location ||
      (location !== ".." &&
        !location.startsWith("../") &&
        !isAbsolute(location))
    ) {
      throw new Error(
        "AUTH_DATA_DIR must be outside frontend source and served asset directories.",
      );
    }
  }
  await mkdir(dataDirectory, { recursive: true, mode: 0o700 });
  await chmod(dataDirectory, 0o700);
  let secret = process.env.BETTER_AUTH_SECRET;
  if (!secret) {
    if (production)
      throw new Error(
        "Production requires an explicit strong BETTER_AUTH_SECRET.",
      );
    const secretFile = resolve(dataDirectory, "secret");
    try {
      await writeFile(secretFile, randomBytes(48).toString("base64url"), {
        flag: "wx",
        mode: 0o600,
      });
    } catch (error) {
      if (error.code !== "EEXIST") throw error;
    }
    await chmod(secretFile, 0o600);
    secret = (await readFile(secretFile, "utf8")).trim();
  }
  if (secret.length < 32 || new Set(secret).size < 16) {
    throw new Error(
      "BETTER_AUTH_SECRET must be a strong random value of at least 32 characters.",
    );
  }
  const hasGoogleId = Boolean(process.env.GOOGLE_CLIENT_ID);
  const hasGoogleSecret = Boolean(process.env.GOOGLE_CLIENT_SECRET);
  if (hasGoogleId !== hasGoogleSecret) {
    throw new Error(
      "Google requires both GOOGLE_CLIENT_ID and GOOGLE_CLIENT_SECRET, or neither.",
    );
  }
  const trustedOrigins = production
    ? [url.origin]
    : [
        ...new Set([
          url.origin,
          "http://127.0.0.1:5175",
          "http://127.0.0.1:4175",
          "http://localhost:5175",
          "http://localhost:4175",
        ]),
      ];
  const emailAllowlist = (process.env.EMAIL_ALLOWLIST || "")
    .split(",")
    .map((value) => value.trim().toLowerCase())
    .filter(Boolean);
  const emailCodeEnabled =
    !production ||
    Boolean(
      process.env.RESEND_API_KEY &&
      process.env.EMAIL_FROM &&
      emailAllowlist.length,
    );
  return {
    production,
    secret,
    dataDirectory,
    baseURL: url.origin,
    trustedOrigins,
    googleConfigured: hasGoogleId && hasGoogleSecret,
    emailCodeEnabled,
    localEmail: !production,
    email: {
      production,
      enabled: emailCodeEnabled,
      allowlist: emailAllowlist,
      apiKey: process.env.RESEND_API_KEY,
      from: process.env.EMAIL_FROM,
    },
  };
}
