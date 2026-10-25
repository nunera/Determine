/**
 * How the score engraves each cue: family (notehead shape) and the rhythm and pitch of its notes. Timings and pitches
 * are the phone's note table (android …/audio/CueBank.kt CUES, mirrored in ../audio/cuebank.ts); keep in step.
 */
import type { Band, CueName, HapticName } from "../contract";

export type Family = "obstacle" | "person" | "system" | "ready" | "calibration";

export interface ScoreNote {
  /** Start of the note relative to the start of the cue. */
  offsetMs: number;
  freqHz: number;
  durMs: number;
}

export interface CueGlyph {
  family: Family;
  notes: ScoreNote[];
}

/** [freqHz, durMs, gapMs] per note, CueBank.CUES order. */
const TABLE: Record<CueName, { family: Family; notes: [number, number, number][] }> = {
  READY: { family: "ready", notes: [[2000, 85, 0]] },
  OBSTACLE_FAR: { family: "obstacle", notes: [[800, 90, 30], [800, 90, 0]] },
  OBSTACLE_MID: { family: "obstacle", notes: [[1000, 85, 25], [1000, 85, 0]] },
  OBSTACLE_NEAR: { family: "obstacle", notes: [[1600, 90, 0]] },
  PERSON_FAR: { family: "person", notes: [[523, 100, 20], [784, 100, 0]] },
  PERSON_MID: { family: "person", notes: [[659, 90, 20], [988, 90, 0]] },
  PERSON_NEAR: { family: "person", notes: [[1319, 90, 0]] },
  SYSTEM_OFF: { family: "system", notes: [[880, 120, 40], [659, 120, 40], [440, 120, 0]] },
  CAL_TONE_500: { family: "calibration", notes: [[500, 300, 0]] },
  CAL_TONE_1K: { family: "calibration", notes: [[1000, 300, 0]] },
  CAL_TONE_2K: { family: "calibration", notes: [[2000, 300, 0]] },
  CAL_OBSTACLE: { family: "obstacle", notes: [[800, 90, 30], [800, 90, 0]] },
};

export const CUE_GLYPHS = Object.fromEntries(
  Object.entries(TABLE).map(([name, { family, notes }]) => {
    let at = 0;
    const out: ScoreNote[] = notes.map(([freqHz, durMs, gapMs]) => {
      const note = { offsetMs: at, freqHz, durMs };
      at += durMs + gapMs;
      return note;
    });
    return [name, { family, notes: out }];
  }),
) as Record<CueName, CueGlyph>;

/** PROTOCOL.md v2 haptic patterns: alternating on, off, on … ms, starting with on. */
export const HAPTIC_SEGMENTS: Record<HapticName, number[]> = {
  NONE: [],
  PULSE40: [40],
  PULSE60: [60],
  BUZZ150: [150],
  LSS: [400, 100, 120, 100, 120],
};

/** Cue repetition period per band (CueBank.CUES periodMs for the obstacle cues): the score's tempo marking. */
export const BAND_TEMPO: Record<Band, string> = {
  FAR: "1/s",
  MID: "2.5/s",
  NEAR: "5/s",
};
