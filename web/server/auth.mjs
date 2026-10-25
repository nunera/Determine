import { AsyncLocalStorage } from "node:async_hooks";
import { createHmac } from "node:crypto";
import { chmod } from "node:fs/promises";
import { resolve } from "node:path";
import { DatabaseSync } from "node:sqlite";
import { betterAuth } from "better-auth";
import { APIError, createAuthMiddleware } from "better-auth/api";
import { getMigrations } from "better-auth/db/migration";
import { revokeUnprovenAccountAccess } from "better-auth/db";
import { deleteSessionCookie } from "better-auth/cookies";
import { emailOTP } from "better-auth/plugins";
import { createDelivery } from "./email.mjs";

export async function createAccountService(config) {
  const databaseFile = resolve(config.dataDirectory, "accounts.sqlite");
  const database = new DatabaseSync(databaseFile);
  await chmod(databaseFile, 0o600);
  database.exec(
    "PRAGMA journal_mode = WAL; PRAGMA foreign_keys = ON; PRAGMA busy_timeout = 5000;",
  );
  database.exec(`CREATE TABLE IF NOT EXISTS auth_otp_rate (
    key TEXT PRIMARY KEY, started INTEGER NOT NULL, hits INTEGER NOT NULL
  ) STRICT`);
  const hitRate =
    database.prepare(`INSERT INTO auth_otp_rate (key, started, hits) VALUES (?, ?, 1)
    ON CONFLICT(key) DO UPDATE SET
      hits = CASE WHEN excluded.started - started >= 60000 THEN 1 ELSE hits + 1 END,
      started = CASE WHEN excluded.started - started >= 60000 THEN excluded.started ELSE started END
    RETURNING hits`);
  const deliver = createDelivery(config.email);
  const deliveryStatus = new AsyncLocalStorage();
  const deliveryChallenges = new WeakMap();
  const deliverForRequest = async (email, message) => {
    try {
      await deliver(email, message);
    } catch (error) {
      const status = deliveryStatus.getStore();
      if (status) status.failed = true;
      throw error;
    }
  };
  const options = {
    appName: "Determine",
    secret: config.secret,
    baseURL: config.baseURL,
    basePath: "/api/auth",
    trustedOrigins: config.trustedOrigins,
    database,
    emailAndPassword: {
      enabled: true,
      minPasswordLength: 10,
      requireEmailVerification: false,
      revokeSessionsOnPasswordReset: true,
      ...(config.emailCodeEnabled
        ? {
            sendResetPassword: async ({ user, url }) => {
              await deliverForRequest(user.email, {
                subject: "Reset your Determine password",
                text: `Set a new password using this link:\n\n${url}\n\nIf you did not request this, ignore it. This link expires in one hour.`,
              });
            },
          }
        : {}),
    },
    ...(config.emailCodeEnabled
      ? {
          emailVerification: {
            sendOnSignUp: true,
            autoSignInAfterVerification: true,
            beforeEmailVerification: async (user) => {
              // Link proof must revoke pre-registration access just like OTP proof.
              // Never let a mailbox owner inherit another person's password/session.
              await revokeUnprovenAccountAccess(
                { context: await auth.$context },
                user.id,
              );
            },
            sendVerificationEmail: async ({ user, url }) => {
              await deliverForRequest(user.email, {
                subject: "Verify your Determine email",
                text: `Verify your email using this link:\n\n${url}\n\nThis link expires in one hour. If you did not register, ignore it.`,
              });
            },
          },
        }
      : {}),
    socialProviders: config.googleConfigured
      ? {
          google: {
            clientId: process.env.GOOGLE_CLIENT_ID,
            clientSecret: process.env.GOOGLE_CLIENT_SECRET,
            disableSignUp: true,
          },
        }
      : {},
    account: {
      accountLinking: {
        enabled: true,
        trustedProviders: [],
        allowDifferentEmails: false,
        requireLocalEmailVerified: true,
      },
    },
    databaseHooks: {
      verification: {
        create: {
          after: async (verification, context) => {
            if (context?.path === "/email-otp/send-verification-otp") {
              deliveryChallenges.set(context, verification.id);
            }
          },
        },
      },
    },
    plugins: [
      emailOTP({
        disableSignUp: true,
        otpLength: 6,
        expiresIn: 300,
        allowedAttempts: 3,
        storeOTP: "hashed",
        resendStrategy: "rotate",
        rateLimit: { window: 60, max: 3 },
        async sendVerificationOTP({ email, otp }, ctx) {
          try {
            await deliverForRequest(email, {
              subject: "Your Determine sign-in code",
              text: `Your sign-in code is: ${otp}\n\nIt expires in five minutes and works once. Only the newest code works. Never share it.`,
            });
          } catch (error) {
            // Invalidate only this request's challenge, never a newer resend.
            const id = ctx && deliveryChallenges.get(ctx);
            if (ctx && id)
              await ctx.context.adapter.delete({
                model: "verification",
                where: [{ field: "id", value: id }],
              });
            throw error;
          } finally {
            if (ctx) deliveryChallenges.delete(ctx);
          }
        },
      }),
    ],
    disabledPaths: [
      "/email-otp/check-verification-otp",
      "/email-otp/verify-email",
      "/email-otp/request-password-reset",
      "/forget-password/email-otp",
      "/email-otp/reset-password",
      "/email-otp/request-email-change",
      "/email-otp/change-email",
      ...(!config.emailCodeEnabled
        ? [
            "/request-password-reset",
            "/reset-password",
            "/send-verification-email",
            "/verify-email",
          ]
        : []),
    ],
    rateLimit: { enabled: true, storage: "database" },
    advanced: {
      disableOriginCheck: false,
      disableCSRFCheck: false,
      ipAddress: { ipAddressHeaders: ["x-determine-client-ip"] },
    },
    hooks: {
      before: createAuthMiddleware(async (ctx) => {
        const sending = ctx.path === "/email-otp/send-verification-otp";
        if (!sending && ctx.path !== "/sign-in/email-otp") return;
        if (!config.emailCodeEnabled)
          throw new APIError("SERVICE_UNAVAILABLE", {
            message:
              "Email codes are not configured on this server. Use your password.",
          });
        if (sending && ctx.body?.type !== "sign-in")
          throw new APIError("BAD_REQUEST", {
            message:
              "Only existing-account sign-in codes are supported. Use the verification or password-reset link instead.",
          });
        const email =
          typeof ctx.body?.email === "string"
            ? ctx.body.email.trim().toLowerCase()
            : "";
        if (email.length > 254 || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
          throw new APIError("BAD_REQUEST", {
            message: "Enter a valid email address.",
          });
        }
        ctx.body.email = email;
        const key = createHmac("sha256", config.secret)
          .update(`${sending ? "send" : "verify"}:${email}`)
          .digest("hex");
        const { hits } = hitRate.get(key, Date.now());
        if (hits > (sending ? 3 : 10))
          throw new APIError("TOO_MANY_REQUESTS", {
            message: "Too many attempts. Wait one minute before trying again.",
          });
        if (
          sending &&
          !(await ctx.context.internalAdapter.findUserByEmail(email))
        ) {
          // Match the delivery boundary without creating an account or code.
          await deliverForRequest(email, {
            subject: "Sign in to Determine",
            text: "Someone requested a Determine sign-in code. Codes work for existing accounts only. If you have not registered, create an account first. Otherwise, ignore this message.",
          });
          return ctx.json({ success: true });
        }
      }),
      after: createAuthMiddleware(async (ctx) => {
        // Better Auth catches delivery errors in its awaited background helper.
        // Turn them back into an honest HTTP failure, never a fake sent message.
        if (deliveryStatus.getStore()?.failed) {
          if (ctx.context.newSession) {
            await ctx.context.internalAdapter.deleteSession(
              ctx.context.newSession.session.token,
            );
            deleteSessionCookie(ctx);
          }
          throw new APIError("SERVICE_UNAVAILABLE", {
            message:
              "Email delivery is unavailable. Try again later or use your password.",
          });
        }
      }),
    },
    session: { expiresIn: 60 * 60 * 24 * 30, updateAge: 60 * 60 * 24 },
  };
  const migrations = await getMigrations(options);
  if (migrations.schemaProblems.length)
    throw new Error(
      "Authentication schema migration requires operator attention.",
    );
  await migrations.runMigrations();
  const auth = betterAuth(options);
  await auth.$context;
  const handleRequest = auth.handler;
  // Better Auth clones Requests for background mail callbacks. Track their
  // outcome across the async request, not by Request-object identity.
  auth.handler = (request) =>
    deliveryStatus.run({ failed: false }, () => handleRequest(request));
  return {
    auth,
    close: () => {
      deliveryStatus.disable();
      database.close();
    },
  };
}
