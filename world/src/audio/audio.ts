/**
 * Phone audio. Since wire v2 the cue board is haptic-only and the phone synthesises every sound, so this module plays
 * ONLY what the phone engine reports it played on the headset: `bridge` → `audio` with status APPLIED or
 * APPLIED_CLAMPED, rendered with the phone's cue bank (./cuebank.ts, port of android …/audio/CueBank.kt) at the
 * phone's applied per-ear gains glDb/grDb (dBFS, −128 = muted ear). Output is plain stereo through a ChannelMerger
 * (L = channel 0, R = channel 1), not HRTF: the target is a Bluetooth bone-conduction headset. As in PhoneCueSink,
 * one voice: a newly applied cue replaces the one still sounding. `played` (the cue board's haptic report) is
 * vibration only and never makes a sound here; the HUD draws it.
 *
 * Also rendered, because the real phone app does so (AndroidPhoneAlerts): `speak` (spearcons, speechSynthesis at
 * 1.8×), the phone's `systemOff` alarm (off tone at alarm level + "System off" at normal rate — failure is loud), and
 * Describe answers, which follow the phone's DescribeController rules (dropped while a MID/NEAR alert is active, cut
 * off by MID/NEAR or UNAVAILABLE).
 */
import type { Band, Bus, CreateAudio, CueName, FromBridge } from "../contract";
import { CUE_NOTES, dbToLinear, renderCue, renderPhoneOffTone } from "./cuebank";

/**
 * Monitor make-up gain for laptop speakers/headphones. Applied uniformly to every headset cue, so relative levels and
 * per-ear balance are untouched; +10 dB maps the phone's hard ceiling (CueBank.HARD_CEILING_DB, −10 dBFS) to full
 * scale, so nothing clips.
 */
const MONITOR_MAKEUP_DB = 10;
/** Alarm stream level for the phone's own SYSTEM OFF tone: the tone is already at 0.8 full scale; play it unattenuated. */
const PHONE_ALARM_GAIN = 1;
/** AndroidPhoneAlerts.SPEARCON_RATE; system-off and descriptions use normal rate. */
const SPEARCON_RATE = 1.8;

type PhoneAudio = Extract<FromBridge, { t: "audio" }>;

export const createAudio: CreateAudio = (bus: Bus) => {
  let ctx: AudioContext | null = null;
  let merger: ChannelMergerNode | null = null;
  let master: GainNode | null = null;
  let muted = false;
  let voice: AudioBufferSourceNode | null = null;
  let band: Band | null = null;
  let description: SpeechSynthesisUtterance | null = null;
  const cueBuffers = new Map<CueName, AudioBuffer>();
  let phoneOffTone: AudioBuffer | null = null;

  function ensureContext(): void {
    if (!ctx) {
      ctx = new AudioContext({ latencyHint: "interactive" });
      master = ctx.createGain();
      master.gain.value = muted ? 0 : 1;
      master.connect(ctx.destination);
      merger = ctx.createChannelMerger(2);
      const makeup = ctx.createGain();
      makeup.gain.value = Math.pow(10, MONITOR_MAKEUP_DB / 20);
      merger.connect(makeup).connect(master);
    }
    if (ctx.state === "suspended") void ctx.resume();
  }
  // Browsers only start audio from a user gesture: create/resume on any pointer or key press (the Start overlay).
  window.addEventListener("pointerdown", ensureContext, { capture: true });
  window.addEventListener("keydown", ensureContext, { capture: true });

  function bufferFor(cue: CueName, c: AudioContext): AudioBuffer {
    let buf = cueBuffers.get(cue);
    if (!buf) {
      const pcm = renderCue(cue, c.sampleRate);
      buf = c.createBuffer(1, Math.max(1, pcm.length), c.sampleRate);
      buf.copyToChannel(pcm, 0);
      cueBuffers.set(cue, buf);
    }
    return buf;
  }

  /** One output voice like PhoneCueSink's player: a newly applied cue replaces the one still sounding. */
  function playHeadsetCue(p: PhoneAudio): void {
    if (p.status !== "APPLIED" && p.status !== "APPLIED_CLAMPED") return;
    if (!(p.cue in CUE_NOTES)) return;
    const gl = dbToLinear(p.glDb);
    const gr = dbToLinear(p.grDb);
    if (!ctx || !merger || ctx.state !== "running") return;
    if (voice) {
      voice.onended = null;
      voice.stop();
      voice.disconnect();
      voice = null;
    }
    if (gl === 0 && gr === 0) return; // muted link probe or haptic-only profile: the phone played no sound
    const src = ctx.createBufferSource();
    src.buffer = bufferFor(p.cue, ctx);
    const left = ctx.createGain();
    const right = ctx.createGain();
    left.gain.value = gl;
    right.gain.value = gr;
    src.connect(left).connect(merger, 0, 0);
    src.connect(right).connect(merger, 0, 1);
    src.onended = () => {
      src.disconnect();
      left.disconnect();
      right.disconnect();
      if (voice === src) voice = null;
    };
    voice = src;
    src.start();
  }

  function playPhoneOffTone(): void {
    if (!ctx || !master || ctx.state !== "running") return;
    if (!phoneOffTone) {
      const pcm = renderPhoneOffTone(ctx.sampleRate);
      phoneOffTone = ctx.createBuffer(1, pcm.length, ctx.sampleRate);
      phoneOffTone.copyToChannel(pcm, 0);
    }
    const src = ctx.createBufferSource();
    src.buffer = phoneOffTone;
    const g = ctx.createGain();
    g.gain.value = PHONE_ALARM_GAIN;
    src.connect(g).connect(master); // mono into both ears: the alarm is not lateralised
    src.onended = () => {
      src.disconnect();
      g.disconnect();
    };
    src.start();
  }

  /** Phone TTS flushes: the newest utterance replaces whatever is still being said. */
  function say(text: string, rate: number): SpeechSynthesisUtterance | null {
    if (muted || !("speechSynthesis" in window)) return null;
    const u = new SpeechSynthesisUtterance(text);
    u.rate = rate;
    u.lang = "en-US";
    speechSynthesis.cancel();
    description = null;
    speechSynthesis.speak(u);
    return u;
  }

  function stopDescription(): void {
    if (!description) return;
    description = null;
    speechSynthesis.cancel();
  }

  bus.on("bridge", (msg) => {
    switch (msg.t) {
      case "audio":
        playHeadsetCue(msg);
        break;
      case "speak":
        say(msg.text, SPEARCON_RATE);
        break;
      case "systemOff":
        playPhoneOffTone();
        say("System off", 1);
        break;
      case "alert":
        band = msg.alert?.band ?? null;
        if (band === "MID" || band === "NEAR") stopDescription();
        break;
      case "state":
        if (msg.state === "UNAVAILABLE") stopDescription();
        break;
      default:
        break;
    }
  });

  bus.on("describeResult", (r) => {
    if (r.pending) return;
    if (band === "MID" || band === "NEAR") return; // the hazard owns the audio; the answer is dropped
    if (!r.ok || !r.text) {
      say("Description unavailable.", 1);
      return;
    }
    const u = say(r.text, 1);
    if (!u) return;
    description = u;
    u.onend = () => {
      if (description === u) description = null;
    };
  });

  bus.on("link", ({ connected }) => {
    if (connected) return;
    // Nothing the bridge said is current any more.
    band = null;
  });

  bus.on("setMuted", ({ muted: m }) => {
    muted = m;
    if (master && ctx) master.gain.setValueAtTime(m ? 0 : 1, ctx.currentTime);
    if (m && "speechSynthesis" in window) {
      speechSynthesis.cancel();
      description = null;
    }
  });
};
