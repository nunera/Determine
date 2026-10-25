/**
 * The score: the HUD's signature element, re-scored for wire v2. Two staves, time running right-to-left over the
 * last WINDOW_MS with "now" at the right-hand double bar:
 *  - top, "Sound · phone → headset": every cue the phone engine reports it played on the headset (`audio`), engraved
 *    with its real rhythm and pitch; obstacle = filled oval, person = diamond, SYSTEM OFF = square marked "OFF";
 *    opacity follows the louder applied ear gain, and two small bars before the cue show the L (blue, first) and
 *    R (red, second) gains, because sides still exist in the headset path even though obstacle cues are BOTH;
 *  - bottom, "Vibration · cue board (one motor)": every haptic the simulated cue board reports (`played`), drawn as
 *    pulses of their real duration whose height is max(hl, hr)/255 (the board has ONE motor and cannot lateralise);
 *    the board's own watchdog LSS is marked. Anything not applied is hollow and struck through.
 * Bar lines every second; a hazard-yellow band behind both staves while the engine's alert band is NEAR.
 * Canvas 2D, redrawn every animation frame (once a second with reduced motion).
 */
import type { AckStatus, Band, CueName, HapticName } from "../contract";
import { BAND_TEMPO, CUE_GLYPHS, HAPTIC_SEGMENTS, type Family } from "./notation";

export const WINDOW_MS = 12_000;

const INK = "#131316";
const MUTED = "#4a4d55";
const RULE = "#c9cbd1";
const LEFT = "#1d4ed8";
const RIGHT = "#cf1f2a";
const HAZARD = "#ffd000";
const PAPER = "#ffffff";
const SHADE = "#eeeff2";

const MONO = '"Atkinson Hyperlegible Mono", ui-monospace, monospace';
const SANS = '"Atkinson Hyperlegible Next", system-ui, sans-serif';

const LOG_LOW = Math.log2(400);
const LOG_HIGH = Math.log2(2100);
const GAIN_MUTE = -128;

export interface SoundMark {
  t: number;
  cue: CueName;
  status: AckStatus;
  glDb: number;
  grDb: number;
}

export interface VibrationMark {
  t: number;
  status: AckStatus;
  haptic: HapticName;
  /** max(hl, hr): what the single motor plays. */
  level: number;
  watchdog: boolean;
}

type CaptionRow = "top" | "bottom";

interface Caption {
  t: number;
  row: CaptionRow;
  strong: string;
  text: string;
  color: string;
}

export type SpanKind = "near" | "unavailable" | "offline";

interface Span {
  kind: SpanKind;
  start: number;
  end: number | null;
}

interface Layout {
  s: number;
  x0: number;
  x1: number;
  capY: number;
  soundLabelY: number;
  sTop: number;
  vibLabelY: number;
  vTop: number;
  vBase: number;
  bottomY: number;
  axisY: number;
}

const APPLIED: Record<AckStatus, boolean> = {
  APPLIED: true, APPLIED_CLAMPED: true, EXPIRED: false, NO_SYNC: false, BAD_CUE: false, BUSY_DROPPED: false,
};

/** 0…1 from an applied ear gain: the −10 dBFS hard ceiling is 1, −40 dBFS and below 0.15, muted 0. */
function gainFraction(db: number): number {
  if (db === GAIN_MUTE) return 0;
  return Math.min(1, Math.max(0.15, 0.15 + ((db + 40) / 30) * 0.85));
}

export class Score {
  private readonly ctx: CanvasRenderingContext2D;
  private sounds: SoundMark[] = [];
  private vibes: VibrationMark[] = [];
  private captions: Caption[] = [];
  private spans: Span[] = [];
  private w = 0;
  private h = 0;
  private dpr = 1;
  private hatch: CanvasPattern | null = null;
  private readonly reduced = window.matchMedia("(prefers-reduced-motion: reduce)");
  private lastQuantum = -1;
  private dirty = true;

  constructor(private readonly canvas: HTMLCanvasElement) {
    const ctx = canvas.getContext("2d");
    if (!ctx) throw new Error("Canvas 2D is not available for the score");
    this.ctx = ctx;
    new ResizeObserver(() => this.resize()).observe(canvas);
    this.resize();
  }

  private resize(): void {
    const r = this.canvas.getBoundingClientRect();
    this.dpr = Math.min(2, window.devicePixelRatio || 1);
    this.w = Math.max(1, r.width);
    this.h = Math.max(1, r.height);
    this.canvas.width = Math.round(this.w * this.dpr);
    this.canvas.height = Math.round(this.h * this.dpr);
    this.hatch = this.makeHatch();
    this.dirty = true;
  }

  private makeHatch(): CanvasPattern | null {
    const c = document.createElement("canvas");
    c.width = c.height = 8;
    const g = c.getContext("2d");
    if (!g) return null;
    g.fillStyle = SHADE;
    g.fillRect(0, 0, 8, 8);
    g.strokeStyle = "#9a9ca4";
    g.lineWidth = 1;
    g.beginPath();
    g.moveTo(-2, 10);
    g.lineTo(10, -2);
    g.moveTo(6, 10);
    g.lineTo(10, 6);
    g.moveTo(-2, 2);
    g.lineTo(2, -2);
    g.stroke();
    return this.ctx.createPattern(c, "repeat");
  }

  // ------------------------------------------------------------------ input

  sound(m: SoundMark): void {
    this.sounds.push(m);
    if (m.cue === "SYSTEM_OFF") this.caption(m.t, "top", "SYSTEM OFF", "phone → headset");
    this.dirty = true;
  }

  vibration(m: VibrationMark): void {
    this.vibes.push(m);
    if (m.watchdog) this.caption(m.t, "bottom", "LSS", "cue-board watchdog: no PING from the phone");
    this.dirty = true;
  }

  caption(t: number, row: CaptionRow, strong: string, text: string, color: string = INK): void {
    this.captions.push({ t, row, strong, text, color });
    this.dirty = true;
  }

  band(t: number, band: Band | null): void {
    if (band === null) this.caption(t, "top", "rest", "no alert · not “all clear”", MUTED);
    else this.caption(t, "top", band, BAND_TEMPO[band]);
  }

  openSpan(kind: SpanKind, t: number): void {
    if (this.spans.some((s) => s.kind === kind && s.end === null)) return;
    this.spans.push({ kind, start: t, end: null });
    this.dirty = true;
  }

  closeSpan(kind: SpanKind, t: number): void {
    for (const s of this.spans) if (s.kind === kind && s.end === null) s.end = t;
    this.dirty = true;
  }

  /** Text alternative for the canvas, refreshed by the HUD every few seconds. */
  summary(now: number): string {
    const from = now - WINDOW_MS;
    const sounds = this.sounds.filter((m) => m.t >= from);
    const heard = sounds.filter((m) => APPLIED[m.status] && (m.glDb !== GAIN_MUTE || m.grDb !== GAIN_MUTE));
    const vibes = this.vibes.filter((m) => m.t >= from);
    const felt = vibes.filter((m) => APPLIED[m.status] && m.haptic !== "NONE");
    const watchdog = felt.filter((m) => m.watchdog).length;
    const failed = sounds.length - sounds.filter((m) => APPLIED[m.status]).length + vibes.length - vibes.filter((m) => APPLIED[m.status]).length;
    const offline = this.spans.some((s) => s.kind === "offline" && s.end === null);
    const parts = [
      `Score, last ${WINDOW_MS / 1000} seconds: ${heard.length} sounds played by the phone on the headset`,
      `${felt.length} vibrations played by the cue board${watchdog ? ` (${watchdog} watchdog LSS)` : ""}`,
      failed ? `${failed} not applied (expired or dropped)` : "",
      offline ? "Engine offline: no cues are being produced." : "",
    ];
    return parts.filter(Boolean).join(", ");
  }

  // ------------------------------------------------------------------ drawing

  frame(nowReal: number): void {
    const reduced = this.reduced.matches;
    const now = reduced ? Math.floor(nowReal / 1000) * 1000 : nowReal;
    if (reduced && now === this.lastQuantum && !this.dirty) return;
    this.lastQuantum = now;
    this.dirty = false;
    this.prune(now);

    const { ctx } = this;
    ctx.setTransform(this.dpr, 0, 0, this.dpr, 0, 0);
    ctx.fillStyle = PAPER;
    ctx.fillRect(0, 0, this.w, this.h);
    const L = this.layout();
    const xOf = (t: number) => L.x1 - ((now - t) / WINDOW_MS) * (L.x1 - L.x0);

    ctx.save();
    ctx.beginPath();
    ctx.rect(L.x0, 0, L.x1 - L.x0, this.h);
    ctx.clip();
    this.drawSpans(L, now, xOf);
    this.drawStaves(L);
    this.drawBarlines(L, now, xOf);
    this.drawVibrations(L, now, xOf);
    this.drawSounds(L, now, xOf);
    ctx.restore();

    this.drawCaptions(L, now, xOf);
    this.drawFrame(L);
    this.drawAxis(L);
    this.drawOfflineNotice(L);
  }

  private prune(now: number): void {
    const cut = now - WINDOW_MS - 1500;
    if (this.sounds.length && this.sounds[0].t < cut) this.sounds = this.sounds.filter((m) => m.t >= cut);
    if (this.vibes.length && this.vibes[0].t < cut) this.vibes = this.vibes.filter((m) => m.t >= cut);
    if (this.captions.length && this.captions[0].t < cut - 4000) this.captions = this.captions.filter((c) => c.t >= cut - 4000);
    this.spans = this.spans.filter((s) => s.end === null || s.end >= cut);
  }

  private layout(): Layout {
    const capH = 16;
    const labelH = 13;
    const gap = 6;
    const bottomH = 14;
    const axisH = 14;
    const fixed = 4 + capH + 2 * labelH + gap + bottomH + axisH;
    // Sound stave: 4 spaces + 1 above + 1 below; vibration lane: 4 spaces.
    const s = Math.max(4, Math.min(11, (this.h - fixed) / 10));
    const used = fixed + 10 * s;
    let y = Math.max(0, (this.h - used) / 2) + 4;
    const capY = y + 11;
    y += capH;
    const soundLabelY = y + 10;
    y += labelH;
    const sTop = y + s;
    y = sTop + 5 * s + gap;
    const vibLabelY = y + 10;
    y += labelH;
    const vTop = y;
    const vBase = y + 4 * s;
    y = vBase;
    const bottomY = y + 11;
    y += bottomH;
    const axisY = y + 2;
    return { s, x0: 18, x1: this.w - 14, capY, soundLabelY, sTop, vibLabelY, vTop, vBase, bottomY, axisY };
  }

  private drawSpans(L: Layout, now: number, xOf: (t: number) => number): void {
    const { ctx } = this;
    const top = L.sTop - L.s;
    const bottom = L.vBase + 1;
    for (const sp of this.spans) {
      const a = Math.max(L.x0, xOf(sp.start));
      const b = Math.min(L.x1, xOf(sp.end ?? now));
      if (b <= a) continue;
      if (sp.kind === "near") {
        ctx.fillStyle = HAZARD;
        ctx.fillRect(a, L.capY - 12, b - a, bottom - (L.capY - 12));
      } else {
        ctx.fillStyle = this.hatch ?? SHADE;
        ctx.fillRect(a, top, b - a, bottom - top);
      }
    }
  }

  private drawStaves(L: Layout): void {
    const { ctx } = this;
    ctx.lineWidth = 1;
    ctx.strokeStyle = INK;
    ctx.beginPath();
    for (let i = 0; i < 5; i++) {
      const y = Math.round(L.sTop + i * L.s) + 0.5;
      ctx.moveTo(L.x0, y);
      ctx.lineTo(L.x1, y);
    }
    // Vibration: a one-line stave (one motor) with a faint full-scale guide.
    const base = Math.round(L.vBase) + 0.5;
    ctx.moveTo(L.x0, base);
    ctx.lineTo(L.x1, base);
    ctx.stroke();
    ctx.strokeStyle = RULE;
    ctx.beginPath();
    const full = Math.round(L.vTop) + 0.5;
    ctx.moveTo(L.x0, full);
    ctx.lineTo(L.x1, full);
    ctx.stroke();
    // Stave labels, on paper so they stay legible over spans.
    ctx.font = `700 11px ${SANS}`;
    ctx.textBaseline = "alphabetic";
    for (const [y, strong, rest] of [
      [L.soundLabelY, "Sound", " · phone → headset"],
      [L.vibLabelY, "Vibration", " · cue board (one motor)"],
    ] as const) {
      ctx.font = `700 11px ${SANS}`;
      const ws = ctx.measureText(strong).width;
      ctx.font = `600 10px ${MONO}`;
      const wr = ctx.measureText(rest).width;
      ctx.fillStyle = PAPER;
      ctx.fillRect(L.x0, y - 10, ws + wr + 8, 13);
      ctx.fillStyle = INK;
      ctx.font = `700 11px ${SANS}`;
      ctx.fillText(strong, L.x0 + 4, y);
      ctx.fillStyle = MUTED;
      ctx.font = `600 10px ${MONO}`;
      ctx.fillText(rest, L.x0 + 4 + ws, y);
    }
  }

  private drawBarlines(L: Layout, now: number, xOf: (t: number) => number): void {
    const { ctx } = this;
    ctx.strokeStyle = INK;
    ctx.lineWidth = 1;
    ctx.beginPath();
    for (let t = Math.ceil((now - WINDOW_MS) / 1000) * 1000; t <= now; t += 1000) {
      const x = Math.round(xOf(t)) + 0.5;
      ctx.moveTo(x, L.sTop);
      ctx.lineTo(x, L.sTop + 4 * L.s);
      ctx.moveTo(x, L.vTop);
      ctx.lineTo(x, L.vBase);
    }
    ctx.stroke();
  }

  private drawVibrations(L: Layout, now: number, xOf: (t: number) => number): void {
    const { ctx } = this;
    const pxPerMs = (L.x1 - L.x0) / WINDOW_MS;
    const full = L.vBase - L.vTop;
    for (const m of this.vibes) {
      if (m.t > now) continue;
      const applied = APPLIED[m.status];
      const segs = HAPTIC_SEGMENTS[m.haptic] ?? [];
      const h = Math.max(3, (Math.min(255, Math.max(0, m.level)) / 255) * full);
      const x0 = xOf(m.t);
      if (!applied) {
        // Not played (EXPIRED, BUSY_DROPPED, …): hollow outline of the intended pulse, struck through.
        const w = Math.max(6, (segs[0] ?? 40) * pxPerMs);
        ctx.strokeStyle = INK;
        ctx.lineWidth = 1.2;
        ctx.fillStyle = PAPER;
        ctx.fillRect(x0, L.vBase - h, w, h);
        ctx.strokeRect(x0 + 0.5, L.vBase - h + 0.5, w - 1, h - 1);
        ctx.beginPath();
        ctx.moveTo(x0, L.vBase - h);
        ctx.lineTo(x0 + w, L.vBase);
        ctx.moveTo(x0, L.vBase);
        ctx.lineTo(x0 + w, L.vBase - h);
        ctx.stroke();
        continue;
      }
      if (segs.length === 0) continue;
      ctx.fillStyle = INK;
      let at = m.t;
      segs.forEach((ms, i) => {
        if (i % 2 === 0) ctx.fillRect(xOf(at), L.vBase - h, Math.max(1.5, ms * pxPerMs), h);
        at += ms;
      });
      if (m.watchdog) {
        // Watchdog LSS: the board's own failure pattern, marked above the pulses.
        ctx.font = `700 10px ${MONO}`;
        ctx.textBaseline = "alphabetic";
        ctx.fillText("LSS", x0, L.vBase - h - 2);
      }
    }
  }

  private pitchY(freqHz: number, L: Layout): number {
    const k = (Math.log2(freqHz) - LOG_LOW) / (LOG_HIGH - LOG_LOW);
    return L.sTop + 4.5 * L.s - Math.min(1, Math.max(0, k)) * 5 * L.s;
  }

  private drawSounds(L: Layout, now: number, xOf: (t: number) => number): void {
    const { ctx } = this;
    const hs = Math.max(5, L.s * 0.95); // notehead unit: legible even on the smallest staff
    const rx = hs * 0.78;
    for (const m of this.sounds) {
      if (m.t > now + 400) continue;
      const glyph = CUE_GLYPHS[m.cue];
      if (!glyph) continue;
      const applied = APPLIED[m.status];
      // Real onset times, but never closer than one notehead so a 115 ms double stays legible on a narrow score.
      const xs: number[] = [];
      for (const n of glyph.notes) {
        const x = xOf(m.t + n.offsetMs);
        xs.push(xs.length ? Math.max(x, xs[xs.length - 1] + 2.2 * rx) : x);
      }
      if (xs[0] < L.x0 - 6 * rx) continue;
      const ys = glyph.notes.map((n) => this.pitchY(n.freqHz, L));
      const loud = Math.max(gainFraction(m.glDb), gainFraction(m.grDb));
      ctx.globalAlpha = applied ? Math.max(0.3, loud) : 0.9;
      ctx.strokeStyle = INK;
      ctx.fillStyle = INK;
      this.engrave(glyph.family, xs, ys, hs, rx, L.s, applied);
      ctx.globalAlpha = 1;
      if (applied) this.drawGainBars(xs[0] - rx - 3, L, m.glDb, m.grDb);
    }
  }

  /** Two bars just before the cue: L (blue, left) then R (red, right); height = applied ear gain, a dot when muted. */
  private drawGainBars(xRight: number, L: Layout, glDb: number, grDb: number): void {
    const { ctx } = this;
    const base = L.sTop + 4 * L.s;
    const full = 4 * L.s;
    for (const [i, db, color] of [[0, glDb, LEFT], [1, grDb, RIGHT]] as const) {
      const x = xRight - 6 + i * 3;
      ctx.fillStyle = color;
      const f = gainFraction(db);
      if (f === 0) ctx.fillRect(x, base - 1, 2, 1);
      else ctx.fillRect(x, base - f * full, 2, f * full);
    }
  }

  private engrave(family: Family, xs: number[], ys: number[], hs: number, rx: number, s: number, filled: boolean): void {
    const { ctx } = this;
    ctx.lineWidth = 1.4;
    xs.forEach((x, i) => {
      const y = ys[i];
      ctx.beginPath();
      switch (family) {
        case "person":
          ctx.moveTo(x, y - hs * 0.72);
          ctx.lineTo(x + rx, y);
          ctx.lineTo(x, y + hs * 0.72);
          ctx.lineTo(x - rx, y);
          ctx.closePath();
          break;
        case "system":
          ctx.rect(x - hs * 0.75, y - hs * 0.75, hs * 1.5, hs * 1.5);
          break;
        case "ready":
          ctx.ellipse(x, y, rx * 0.6, hs * 0.38, -0.35, 0, Math.PI * 2);
          break;
        default:
          ctx.ellipse(x, y, rx, hs * 0.52, -0.35, 0, Math.PI * 2);
      }
      if (filled && family !== "calibration") ctx.fill();
      else {
        ctx.fillStyle = PAPER;
        ctx.fill();
        ctx.stroke();
        ctx.fillStyle = INK;
      }
      if (!filled) {
        // Not applied (EXPIRED, BUSY_DROPPED, NO_SYNC, BAD_CUE): strike the hollow head through.
        ctx.save();
        ctx.globalAlpha = 1;
        ctx.lineWidth = 1.5;
        ctx.beginPath();
        ctx.moveTo(x - hs * 0.8, y - hs * 0.8);
        ctx.lineTo(x + hs * 0.8, y + hs * 0.8);
        ctx.moveTo(x - hs * 0.8, y + hs * 0.8);
        ctx.lineTo(x + hs * 0.8, y - hs * 0.8);
        ctx.stroke();
        ctx.restore();
      }
    });

    if (family === "system") {
      ctx.font = `600 ${Math.max(9, Math.round(hs * 1.1))}px ${MONO}`;
      ctx.textBaseline = "alphabetic";
      ctx.fillText("OFF", xs[0] - hs * 0.75, Math.min(...ys) - hs * 1.1);
      return;
    }
    if (family === "calibration") return;

    // Stems up from the right of each head; a beam joins the notes of one cue.
    const stemLen = Math.max(3.2 * s, 2.6 * hs);
    const sx = xs.map((x) => x + rx * 0.92);
    const top = Math.min(...ys) - stemLen;
    ctx.lineWidth = 1.2;
    ctx.beginPath();
    sx.forEach((x, i) => {
      ctx.moveTo(x, ys[i] - hs * 0.2);
      ctx.lineTo(x, xs.length > 1 ? top : ys[i] - stemLen);
    });
    ctx.stroke();
    if (xs.length > 1) ctx.fillRect(sx[0], top, sx[sx.length - 1] - sx[0] + 0.6, Math.max(2, s * 0.5));
  }

  private drawCaptions(L: Layout, now: number, xOf: (t: number) => number): void {
    const { ctx } = this;
    ctx.textBaseline = "alphabetic";
    for (const row of ["top", "bottom"] as const) {
      const y = row === "top" ? L.capY : L.bottomY;
      const items = this.captions.filter((c) => c.row === row && c.t <= now + 50);
      let limit = L.x1; // newest first: each label must end before the next newer one starts
      for (let i = items.length - 1; i >= 0; i--) {
        const c = items[i];
        ctx.font = `700 11px ${SANS}`;
        const wStrong = ctx.measureText(c.strong).width;
        ctx.font = `600 10px ${MONO}`;
        const wText = c.text ? ctx.measureText(c.text).width + 6 : 0;
        const width = wStrong + wText;
        const x = Math.min(Math.max(L.x0 + 2, xOf(c.t) + 3), L.x1 - width - 4);
        if (x + width > limit - 6) continue;
        if (xOf(c.t) + width < L.x0) continue;
        ctx.fillStyle = c.color;
        ctx.font = `700 11px ${SANS}`;
        ctx.fillText(c.strong, x, y);
        if (c.text) {
          ctx.fillStyle = c.color === INK ? MUTED : c.color;
          ctx.font = `600 10px ${MONO}`;
          ctx.fillText(c.text, x + wStrong + 6, y);
        }
        limit = x;
      }
    }
  }

  private drawFrame(L: Layout): void {
    const { ctx } = this;
    const sysTop = L.sTop;
    const sysBottom = L.vBase;
    // Opening system line and "now": thin + thick double bar.
    ctx.fillStyle = INK;
    ctx.fillRect(L.x0 - 3, sysTop, 3, sysBottom - sysTop);
    ctx.fillRect(L.x1 - 5, sysTop, 1, sysBottom - sysTop);
    ctx.fillRect(L.x1 - 2, sysTop, 3, sysBottom - sysTop);
  }

  private drawAxis(L: Layout): void {
    const { ctx } = this;
    ctx.strokeStyle = RULE;
    ctx.lineWidth = 1;
    ctx.beginPath();
    ctx.moveTo(L.x0, Math.round(L.axisY) + 0.5);
    ctx.lineTo(L.x1, Math.round(L.axisY) + 0.5);
    ctx.stroke();
    ctx.font = `600 10px ${MONO}`;
    ctx.textBaseline = "top";
    for (let sAgo = 0; sAgo <= WINDOW_MS / 1000; sAgo += 2) {
      const x = L.x1 - (sAgo * 1000 * (L.x1 - L.x0)) / WINDOW_MS;
      ctx.fillStyle = INK;
      ctx.fillRect(Math.round(x), L.axisY - 2, 1, 5);
      ctx.fillStyle = MUTED;
      const label = sAgo === 0 ? "now" : `−${sAgo} s`;
      ctx.textAlign = sAgo === 0 ? "right" : sAgo === WINDOW_MS / 1000 ? "left" : "center";
      ctx.fillText(label, sAgo === 0 ? x + 1 : x, L.axisY + 4);
    }
    ctx.textAlign = "left";
    ctx.textBaseline = "alphabetic";
  }

  private drawOfflineNotice(L: Layout): void {
    if (!this.spans.some((s) => s.kind === "offline" && s.end === null)) return;
    const { ctx } = this;
    const text = "ENGINE OFFLINE — no cues are being produced";
    ctx.font = `800 13px ${SANS}`;
    const w = ctx.measureText(text).width + 20;
    const midY = (L.sTop + L.vBase) / 2;
    const x = L.x1 - 16 - w;
    ctx.fillStyle = INK;
    ctx.fillRect(x, midY - 12, w, 24);
    ctx.fillStyle = PAPER;
    ctx.textBaseline = "middle";
    ctx.fillText(text, x + 10, midY + 1);
    ctx.textBaseline = "alphabetic";
  }
}
