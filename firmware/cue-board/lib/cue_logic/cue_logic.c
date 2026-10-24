// Cue board pure logic. See cue_logic.h. No hardware access in this file.
#include "cue_logic.h"

#include <math.h>
#include <string.h>

/* ================================================================== cue bank
 * Arch §8 cue language. Timbre + rhythm carry meaning: obstacle = wood-block double
 * (NEAR: one sharper note), person = rising two-tone marimba (NEAR: one higher note),
 * system off = three descending notes, ready = one soft tick. Every note window is
 * >= 83 ms (includes the decay tail); the phone repeats cues at period_ms and every cue
 * leaves >= 100 ms of silence before the next repetition (Arch §8.1). */
#define W DT_WAVE_WOODBLOCK
#define M DT_WAVE_MARIMBA
#define T DT_WAVE_TONE
#define K DT_WAVE_TICK
static const dt_cue_def_t k_cues[DT_CUE_COUNT] = {
    [DT_CUE_READY] = {"READY", 1, {{K, 2000, 85, 0}}, 1000},
    [DT_CUE_OBSTACLE_FAR] = {"OBSTACLE_FAR", 2, {{W, 800, 90, 30}, {W, 800, 90, 0}}, 1000},
    [DT_CUE_OBSTACLE_MID] = {"OBSTACLE_MID", 2, {{W, 1000, 85, 25}, {W, 1000, 85, 0}}, 400},
    [DT_CUE_OBSTACLE_NEAR] = {"OBSTACLE_NEAR", 1, {{W, 1600, 90, 0}}, 200},
    [DT_CUE_PERSON_FAR] = {"PERSON_FAR", 2, {{M, 523, 100, 20}, {M, 784, 100, 0}}, 1000},
    [DT_CUE_PERSON_MID] = {"PERSON_MID", 2, {{M, 659, 90, 20}, {M, 988, 90, 0}}, 400},
    [DT_CUE_PERSON_NEAR] = {"PERSON_NEAR", 1, {{M, 1319, 90, 0}}, 200},
    [DT_CUE_SYSTEM_OFF] = {"SYSTEM_OFF", 3, {{M, 880, 120, 40}, {M, 659, 120, 40}, {M, 440, 120, 0}}, 5000},
    [DT_CUE_CAL_TONE_500] = {"CAL_TONE_500", 1, {{T, 500, 300, 0}}, 1000},
    [DT_CUE_CAL_TONE_1K] = {"CAL_TONE_1K", 1, {{T, 1000, 300, 0}}, 1000},
    [DT_CUE_CAL_TONE_2K] = {"CAL_TONE_2K", 1, {{T, 2000, 300, 0}}, 1000},
    [DT_CUE_CAL_OBSTACLE] = {"CAL_OBSTACLE", 2, {{W, 800, 90, 30}, {W, 800, 90, 0}}, 1000},
};
#undef W
#undef M
#undef T
#undef K

const dt_cue_def_t *dt_cue_def(uint8_t cue_id) { return cue_id < DT_CUE_COUNT ? &k_cues[cue_id] : NULL; }

uint32_t dt_cue_length_ms(const dt_cue_def_t *c) {
  uint32_t ms = 0;
  for (uint8_t i = 0; i < c->note_count; i++) {
    ms += c->notes[i].dur_ms;
    if (i + 1 < c->note_count) ms += c->notes[i].gap_ms;
  }
  return ms;
}

const char *dt_cue_timing_violation(const dt_cue_def_t *c) {
  if (c == NULL || c->note_count == 0 || c->note_count > DT_CUE_MAX_NOTES) return "note count";
  for (uint8_t i = 0; i < c->note_count; i++) {
    const dt_note_t *n = &c->notes[i];
    if (n->dur_ms < DT_NOTE_MIN_MS) return "note shorter than 83 ms";
    if (n->freq_hz < DT_FREQ_MIN_HZ || n->freq_hz > DT_FREQ_MAX_HZ) return "pitch outside 300-4000 Hz";
    if (n->wave > DT_WAVE_TICK) return "unknown waveform";
  }
  if (dt_cue_length_ms(c) + DT_INTER_CUE_GAP_MS > c->period_ms) return "cue + 100 ms gap exceeds period";
  if ((uint32_t)c->note_count * 1000u > (uint32_t)DT_MAX_NOTES_PER_S * c->period_ms) return "more than 6 notes/s";
  return NULL;
}

/* ================================================================== gain */

int32_t dt_db_to_q15(int8_t db) {
  if (db == DT_GAIN_MUTE) return 0;
  if (db >= 0) return 32768;
  return (int32_t)lroundf(32768.0f * powf(10.0f, (float)db / 20.0f));
}

/* ================================================================== synth */

#define SINE_BITS 10
#define SINE_N (1u << SINE_BITS)
static float s_sine[SINE_N];
static bool s_sine_ready;

static float sine_at(uint64_t n, uint32_t freq_hz, uint32_t rate_hz) {
  if (!s_sine_ready) {
    for (uint32_t i = 0; i < SINE_N; i++) s_sine[i] = sinf(6.28318530718f * (float)i / (float)SINE_N);
    s_sine_ready = true;
  }
  /* Phase computed from the absolute sample index: no accumulator drift. */
  uint64_t idx = (n * (uint64_t)freq_hz * SINE_N) / rate_hz;
  return s_sine[idx & (SINE_N - 1)];
}

static float powi(float b, int e) {
  float r = 1.0f;
  while (e-- > 0) r *= b;
  return r;
}

float dt_note_sample(const dt_note_t *note, uint32_t n, uint32_t rate_hz) {
  uint32_t total = (uint32_t)note->dur_ms * rate_hz / 1000u;
  if (total == 0 || n >= total) return 0.0f;
  float x = (float)n / (float)total;   /* 0..1 position in the note window */
  float rest = 1.0f - x;
  float t_ms = (float)n * 1000.0f / (float)rate_hz;
  float f = note->freq_hz;
  switch (note->wave) {
    case DT_WAVE_WOODBLOCK: {
      /* 1 ms attack, fast decay; inharmonic partial at 2.76 f decays faster. */
      float att = t_ms < 1.0f ? t_ms : 1.0f;
      uint32_t f2 = (uint32_t)(f * 2.76f);
      return att * (0.7f * powi(rest, 4) * sine_at(n, note->freq_hz, rate_hz) +
                    0.3f * powi(rest, 8) * sine_at(n, f2, rate_hz));
    }
    case DT_WAVE_MARIMBA: {
      float att = t_ms < 2.0f ? t_ms / 2.0f : 1.0f;
      return att * (0.75f * powi(rest, 3) * sine_at(n, note->freq_hz, rate_hz) +
                    0.25f * powi(rest, 8) * sine_at(n, note->freq_hz * 4u, rate_hz));
    }
    case DT_WAVE_TONE: {
      /* 5 ms linear ramps on both ends (calibration tones, Arch §9). */
      float dur = (float)note->dur_ms;
      float ramp = 1.0f;
      if (t_ms < 5.0f) ramp = t_ms / 5.0f;
      else if (dur - t_ms < 5.0f) ramp = (dur - t_ms) / 5.0f;
      if (ramp < 0.0f) ramp = 0.0f;
      return 0.9f * ramp * sine_at(n, note->freq_hz, rate_hz);
    }
    case DT_WAVE_TICK: {
      float att = t_ms < 0.5f ? t_ms / 0.5f : 1.0f;
      return 0.6f * att * powi(rest, 12) * sine_at(n, note->freq_hz, rate_hz);
    }
    default: return 0.0f;
  }
}

int16_t dt_scale_sample(float x, int32_t gain_q15) {
  float v = x * (float)gain_q15 * (32767.0f / 32768.0f);
  if (v > 32767.0f) return 32767;
  if (v < -32768.0f) return -32768;
  return (int16_t)lroundf(v);
}

void dt_player_start(dt_player_t *p, const dt_cue_def_t *cue, uint32_t rate_hz, int32_t gain_l_q15,
                     int32_t gain_r_q15) {
  memset(p, 0, sizeof *p);
  p->cue = cue;
  p->rate_hz = rate_hz;
  p->gain_l_q15 = gain_l_q15;
  p->gain_r_q15 = gain_r_q15;
  p->done = (cue == NULL || cue->note_count == 0);
}

size_t dt_player_render(dt_player_t *p, int16_t *lr, size_t frames) {
  size_t i = 0;
  while (i < frames && !p->done) {
    const dt_note_t *note = &p->cue->notes[p->note_idx];
    bool last = (p->note_idx + 1u == p->cue->note_count);
    uint32_t win = (uint32_t)note->dur_ms * p->rate_hz / 1000u;
    uint32_t gap = last ? 0u : (uint32_t)note->gap_ms * p->rate_hz / 1000u;
    if (p->pos >= win + gap) {
      p->pos = 0;
      if (last) p->done = true;
      else p->note_idx++;
      continue;
    }
    float s = dt_note_sample(note, p->pos, p->rate_hz); /* 0 in the gap */
    lr[2 * i] = dt_scale_sample(s, p->gain_l_q15);
    lr[2 * i + 1] = dt_scale_sample(s, p->gain_r_q15);
    p->pos++;
    i++;
  }
  return i;
}

/* ================================================================== haptics */

static const dt_haptic_def_t k_haptics[DT_HAPTIC_COUNT] = {
    [DT_HAPTIC_NONE] = {0, {0}},
    [DT_HAPTIC_PULSE40] = {1, {40}},
    [DT_HAPTIC_PULSE60] = {1, {60}},
    [DT_HAPTIC_BUZZ150] = {1, {150}},
    [DT_HAPTIC_LSS] = {5, {400, 100, 120, 100, 120}},
};

const dt_haptic_def_t *dt_haptic_def(uint8_t pattern) {
  if (pattern == DT_HAPTIC_NONE || pattern >= DT_HAPTIC_COUNT) return NULL;
  return &k_haptics[pattern];
}

uint32_t dt_haptic_length_ms(uint8_t pattern) {
  const dt_haptic_def_t *d = dt_haptic_def(pattern);
  uint32_t ms = 0;
  if (d) for (uint8_t i = 0; i < d->count; i++) ms += d->seg_ms[i];
  return ms;
}

bool dt_haptic_on_at(uint8_t pattern, uint32_t t_ms) {
  const dt_haptic_def_t *d = dt_haptic_def(pattern);
  if (!d) return false;
  uint32_t start = 0;
  for (uint8_t i = 0; i < d->count; i++) {
    if (t_ms < start + d->seg_ms[i]) return (i % 2u) == 0u;
    start += d->seg_ms[i];
  }
  return false;
}

/* ================================================================== profile + CUE */

void dt_profile_default(dt_profile_state_t *p) {
  p->ceiling_db = DT_FW_HARD_CEILING_DB;
  p->mode = DT_MODE_STEREO;
  p->haptic_max = 255;
}

bool dt_profile_apply(dt_profile_state_t *p, const dt_profile_t *pkt) {
  if (pkt->mode > DT_MODE_HAPTIC_ONLY) return false;
  /* The phone can only lower the ceiling below the firmware hard cap (Invariant 4). */
  p->ceiling_db = pkt->ceiling_db < DT_FW_HARD_CEILING_DB ? pkt->ceiling_db : (int8_t)DT_FW_HARD_CEILING_DB;
  p->mode = pkt->mode;
  p->haptic_max = pkt->haptic_max;
  return true;
}

dt_cue_decision_t dt_cue_decide(const dt_cue_t *cue, uint64_t now_board_us, const dt_profile_state_t *prof,
                                bool output_busy) {
  dt_cue_decision_t d = {DT_ACK_BAD_CUE, false, DT_GAIN_MUTE, DT_GAIN_MUTE, 0, 0};
  if (cue->cue_id >= DT_CUE_COUNT || cue->side > DT_SIDE_BOTH || cue->haptic_pattern >= DT_HAPTIC_COUNT) return d;
  /* Never play late, never play without sync (Invariant 5). */
  uint8_t t = dt_cue_time_check(cue, now_board_us);
  if (t != DT_ACK_PLAYED) {
    d.status = t;
    return d;
  }
  if (output_busy && !(cue->flags & DT_CUE_FLAG_INTERRUPT)) {
    d.status = DT_ACK_BUSY_DROPPED;
    return d;
  }
  int8_t gl = dt_clamp_gain(cue->gain_l_db, prof->ceiling_db);
  int8_t gr = dt_clamp_gain(cue->gain_r_db, prof->ceiling_db);
  d.status = (gl != cue->gain_l_db || gr != cue->gain_r_db) ? DT_ACK_PLAYED_CLAMPED : DT_ACK_PLAYED;
  bool left = cue->side != DT_SIDE_R;
  bool right = cue->side != DT_SIDE_L;
  bool audio = prof->mode != DT_MODE_HAPTIC_ONLY; /* HAPTIC_ONLY mutes audio (PROTOCOL.md PROFILE) */
  d.gain_l_db = (left && audio) ? gl : (int8_t)DT_GAIN_MUTE;
  d.gain_r_db = (right && audio) ? gr : (int8_t)DT_GAIN_MUTE;
  if (cue->haptic_pattern != DT_HAPTIC_NONE) {
    d.haptic_l = left ? dt_haptic_cap(cue->haptic_l, prof->haptic_max) : 0;
    d.haptic_r = right ? dt_haptic_cap(cue->haptic_r, prof->haptic_max) : 0;
  }
  d.play = true;
  return d;
}

/* ================================================================== watchdog */

void dt_wd_init(dt_watchdog_t *w) { memset(w, 0, sizeof *w); }

void dt_wd_on_ping(dt_watchdog_t *w, uint64_t now_us) {
  w->ever_pinged = true;
  w->last_ping_us = now_us;
  w->active = false;
}

bool dt_wd_update(dt_watchdog_t *w, uint64_t now_us) {
  bool starved = w->ever_pinged ? (now_us - w->last_ping_us >= DT_WD_PING_TIMEOUT_US)
                                : (now_us >= DT_WD_BOOT_GRACE_US);
  if (!starved) {
    w->active = false;
    return false;
  }
  if (!w->active) {
    w->active = true;
    w->last_alarm_us = now_us;
    return true;
  }
  if (now_us - w->last_alarm_us >= DT_WD_ALARM_PERIOD_US) {
    w->last_alarm_us = now_us;
    return true;
  }
  return false;
}

int8_t dt_wd_gain_db(int8_t ceiling_db) {
  int8_t g = ceiling_db < DT_WATCHDOG_GAIN_DB ? ceiling_db : (int8_t)DT_WATCHDOG_GAIN_DB;
  return dt_clamp_gain(g, ceiling_db);
}

/* ================================================================== ToF transform */

uint8_t dt_tof_dest_index(uint8_t raw_index, bool flip_x, bool flip_y, uint8_t rot_quarters) {
  uint8_t r = raw_index / 8u, c = raw_index % 8u;
  for (uint8_t q = 0; q < (rot_quarters & 3u); q++) {
    uint8_t nr = c, nc = (uint8_t)(7u - r); /* 90 deg clockwise */
    r = nr;
    c = nc;
  }
  if (flip_x) c = (uint8_t)(7u - c);
  if (flip_y) r = (uint8_t)(7u - r);
  return (uint8_t)(r * 8u + c);
}

void dt_tof_transform(const int16_t raw_mm[DT_ZONES], const uint8_t raw_status[DT_ZONES],
                      const uint8_t raw_nb_target[DT_ZONES], bool flip_x, bool flip_y, uint8_t rot_quarters,
                      uint16_t out_mm[DT_ZONES], uint8_t out_status[DT_ZONES]) {
  for (uint8_t i = 0; i < DT_ZONES; i++) {
    uint8_t d = dt_tof_dest_index(i, flip_x, flip_y, rot_quarters);
    if (raw_nb_target[i] == 0) {
      out_mm[d] = 0;
      out_status[d] = DT_STATUS_NO_TARGET;
    } else {
      out_mm[d] = raw_mm[i] < 0 ? 0 : (uint16_t)raw_mm[i];
      /* 255 is reserved for NO_TARGET on the wire; the sensor never reports it. */
      out_status[d] = raw_status[i];
    }
  }
}

/* ================================================================== packet builders */

void dt_build_beat(dt_beat_t *b, uint32_t seq, uint8_t reset_reason, uint16_t faults, uint64_t t_us,
                   int8_t rssi_dbm, int8_t temp_c, uint16_t heap_kb) {
  memset(b, 0, sizeof *b);
  dt_header_init(&b->h, DT_BEAT, seq);
  b->board = DT_BOARD_CUE;
  b->reset_reason = reset_reason;
  b->faults = faults;
  b->t_us = t_us;
  b->rssi_dbm = rssi_dbm;
  b->temp_c = temp_c;
  b->heap_kb = heap_kb;
}

void dt_build_range(dt_range_t *r, uint32_t seq, uint64_t t_us, const uint16_t mm[DT_ZONES],
                    const uint8_t status[DT_ZONES]) {
  memset(r, 0, sizeof *r);
  dt_header_init(&r->h, DT_RANGE, seq);
  r->t_us = t_us;
  r->res = DT_ZONES;
  memcpy(r->mm, mm, sizeof r->mm);
  memcpy(r->status, status, sizeof r->status);
}

void dt_build_pong(dt_pong_t *p, uint32_t seq, uint64_t t_phone_us, uint64_t rx_us, uint64_t tx_us) {
  memset(p, 0, sizeof *p);
  dt_header_init(&p->h, DT_PONG, seq);
  p->t_phone_us = t_phone_us;
  p->t_board_rx_us = rx_us;
  p->t_board_tx_us = tx_us;
}

void dt_build_ack(dt_ack_t *a, uint32_t seq, uint32_t cue_seq, uint8_t status, int8_t gl, int8_t gr,
                  uint64_t t_board_us) {
  memset(a, 0, sizeof *a);
  dt_header_init(&a->h, DT_ACK, seq);
  a->cue_seq = cue_seq;
  a->status = status;
  a->applied_gain_l_db = gl;
  a->applied_gain_r_db = gr;
  a->t_board_us = t_board_us;
}
