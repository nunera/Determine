/**
 * TypeScript port of the phone's cue sound bank, android/app/src/main/kotlin/org/determine/android/audio/CueBank.kt
 * (wire v2: the cue board is haptic-only, so the phone synthesises every warning sound and PhoneCueSink plays it on
 * the Bluetooth bone-conduction headset). Same note table, waveforms, envelopes, integer window maths and
 * dBFS → Q15 gain maths, rendered at the AudioContext rate. Keep in step with CueBank.kt.
 */
import type { CueName } from "../contract";

type Wave = "WOODBLOCK" | "MARIMBA" | "TONE" | "TICK";

interface Note {
  wave: Wave;
  freqHz: number;
  durMs: number;
  gapMs: number;
}

const W: Wave = "WOODBLOCK";
const M: Wave = "MARIMBA";
const T: Wave = "TONE";
const K: Wave = "TICK";

const n = (wave: Wave, freqHz: number, durMs: number, gapMs: number): Note => ({ wave, freqHz, durMs, gapMs });

/** Values copied from CueBank.CUES (index = cue id, PROTOCOL.md "Cue IDs"). */
export const CUE_NOTES: Record<CueName, Note[]> = {
  READY: [n(K, 2000, 85, 0)],
  OBSTACLE_FAR: [n(W, 800, 90, 30), n(W, 800, 90, 0)],
  OBSTACLE_MID: [n(W, 1000, 85, 25), n(W, 1000, 85, 0)],
  OBSTACLE_NEAR: [n(W, 1600, 90, 0)],
  PERSON_FAR: [n(M, 523, 100, 20), n(M, 784, 100, 0)],
  PERSON_MID: [n(M, 659, 90, 20), n(M, 988, 90, 0)],
  PERSON_NEAR: [n(M, 1319, 90, 0)],
  SYSTEM_OFF: [n(M, 880, 120, 40), n(M, 659, 120, 40), n(M, 440, 120, 0)],
  CAL_TONE_500: [n(T, 500, 300, 0)],
  CAL_TONE_1K: [n(T, 1000, 300, 0)],
  CAL_TONE_2K: [n(T, 2000, 300, 0)],
  CAL_OBSTACLE: [n(W, 800, 90, 30), n(W, 800, 90, 0)],
};

export const GAIN_MUTE = -128;

/** CueBank.dbToQ15 expressed as a linear amplitude (Q15 / 32768): −128 mutes, ≥ 0 dBFS never boosts. */
export function dbToLinear(db: number): number {
  if (db === GAIN_MUTE) return 0;
  if (db >= 0) return 1;
  return Math.round(32768 * Math.pow(10, db / 20)) / 32768;
}

const SINE_N = 1 << 10;
const SINE = Float32Array.from({ length: SINE_N }, (_, i) => Math.sin((2 * Math.PI * i) / SINE_N));

/** Phase from the absolute sample index with integer maths, masked to the table (CueBank.sineAt). */
function sineAt(i: number, freqHz: number, rateHz: number): number {
  return SINE[Math.floor((i * freqHz * SINE_N) / rateHz) & (SINE_N - 1)];
}

/** Samples in a note window of `durMs` at `rateHz` (CueBank.windowSamples integer maths). */
function windowSamples(durMs: number, rateHz: number): number {
  return Math.floor((durMs * rateHz) / 1000);
}

/** One normalised sample (|x| ≤ 1) at index `i` of the note window (CueBank.noteSample). */
function noteSample(note: Note, i: number, rateHz: number): number {
  const total = windowSamples(note.durMs, rateHz);
  if (total === 0 || i < 0 || i >= total) return 0;
  const rest = 1 - i / total;
  const tMs = (i * 1000) / rateHz;
  const f = note.freqHz;
  switch (note.wave) {
    case "WOODBLOCK": {
      const att = tMs < 1 ? tMs : 1;
      // Kotlin `(f * 2.76f).toInt()` is float32 maths; emulate it so the overtone matches CueBank.kt exactly.
      const f2 = Math.trunc(Math.fround(f * Math.fround(2.76)));
      return att * (0.7 * rest ** 4 * sineAt(i, f, rateHz) + 0.3 * rest ** 8 * sineAt(i, f2, rateHz));
    }
    case "MARIMBA": {
      const att = tMs < 2 ? tMs / 2 : 1;
      return att * (0.75 * rest ** 3 * sineAt(i, f, rateHz) + 0.25 * rest ** 8 * sineAt(i, f * 4, rateHz));
    }
    case "TONE": {
      const dur = note.durMs;
      let ramp = tMs < 5 ? tMs / 5 : dur - tMs < 5 ? (dur - tMs) / 5 : 1;
      if (ramp < 0) ramp = 0;
      return 0.9 * ramp * sineAt(i, f, rateHz);
    }
    case "TICK": {
      const att = tMs < 0.5 ? tMs / 0.5 : 1;
      return 0.6 * att * rest ** 12 * sineAt(i, f, rateHz);
    }
  }
}

/**
 * The whole cue as one normalised mono stream, exactly as CueBank.Player walks it: each note window, then its gap
 * (silence) unless it is the last note. Per-ear gain is applied afterwards (CueBank.scaleSample scales the same
 * sample for L and R). Includes the 32767/32768 int16 scale.
 */
export function renderCue(cue: CueName, rateHz: number): Float32Array<ArrayBuffer> {
  const notes = CUE_NOTES[cue];
  let frames = 0;
  notes.forEach((note, idx) => {
    frames += windowSamples(note.durMs, rateHz) + (idx + 1 < notes.length ? windowSamples(note.gapMs, rateHz) : 0);
  });
  const out = new Float32Array(frames);
  let at = 0;
  notes.forEach((note, idx) => {
    const win = windowSamples(note.durMs, rateHz);
    for (let i = 0; i < win; i++) out[at + i] = noteSample(note, i, rateHz) * (32767 / 32768);
    at += win + (idx + 1 < notes.length ? windowSamples(note.gapMs, rateHz) : 0);
  });
  return out;
}

/**
 * The phone's own failure alarm (AndroidPhoneAlerts.buildSystemOffTone): three descending 150 ms notes
 * (988, 784, 587 Hz) with 100 ms gaps, 5 ms linear attack/release, amplitude 0.8, on the alarm stream.
 */
export function renderPhoneOffTone(rateHz: number): Float32Array<ArrayBuffer> {
  const noteSamples = Math.floor((rateHz * 150) / 1000);
  const gapSamples = Math.floor((rateHz * 100) / 1000);
  const edge = Math.floor((rateHz * 5) / 1000);
  const freqs = [988, 784, 587];
  const out = new Float32Array(freqs.length * (noteSamples + gapSamples));
  let at = 0;
  for (const f of freqs) {
    for (let i = 0; i < noteSamples; i++) {
      const env = Math.min(1, i / edge, (noteSamples - i) / edge);
      out[at++] = Math.sin((2 * Math.PI * f * i) / rateHz) * env * 0.8;
    }
    at += gapSamples;
  }
  return out;
}
