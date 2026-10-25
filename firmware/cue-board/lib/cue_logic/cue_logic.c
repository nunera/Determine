// Cue board pure logic. See cue_logic.h. No hardware access in this file.
#include "cue_logic.h"

#include <string.h>

/* ================================================================== haptics
 * Segment tables for the temple motors. LSS (long short short) is the watchdog /
 * SYSTEM_OFF pattern: it must still be recognisable when the phone is gone. */
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

uint8_t dt_vibro_power(uint8_t level) {
  return (uint8_t)(((unsigned)level * 50u + 254u) / 255u); /* ceiling: non-zero stays non-zero */
}

/* ================================================================== profile + CUE */

void dt_profile_default(dt_profile_state_t *p) {
  p->mode = DT_MODE_STEREO;
  p->haptic_max = 255;
}

bool dt_profile_apply(dt_profile_state_t *p, const dt_profile_t *pkt) {
  /* haptic_max 0 would silence the watchdog LSS, the only failure channel left on the
   * board now that it makes no sound (DT_HAPTIC_MAX_MIN); also rejects unknown modes. */
  if (!dt_profile_valid(pkt)) return false;
  /* The phone keeps mode for its own audio routing; the board always plays haptics. */
  p->mode = pkt->mode;
  p->haptic_max = pkt->haptic_max;
  return true;
}

dt_cue_decision_t dt_cue_decide(const dt_cue_t *cue, uint64_t now_board_us, const dt_profile_state_t *prof,
                                bool output_busy) {
  dt_cue_decision_t d = {DT_ACK_BAD_CUE, false, 0, 0};
  if (cue->cue_id >= DT_CUE_COUNT || cue->side > DT_SIDE_BOTH || cue->haptic_pattern >= DT_HAPTIC_COUNT)
    return d;
  /* Never play late, never play without sync (Invariant 5). */
  uint8_t t = dt_cue_time_check(cue, now_board_us);
  if (t != DT_ACK_APPLIED) {
    d.status = t;
    return d;
  }
  if (cue->haptic_pattern == DT_HAPTIC_NONE) {
    /* Phone-side audio only: nothing to play on the board, so never busy. */
    d.status = DT_ACK_APPLIED;
    return d;
  }
  if (output_busy && !(cue->flags & DT_CUE_FLAG_INTERRUPT)) {
    d.status = DT_ACK_BUSY_DROPPED;
    return d;
  }
  uint8_t hl = dt_clamp_haptic(cue->haptic_l, prof->haptic_max);
  uint8_t hr = dt_clamp_haptic(cue->haptic_r, prof->haptic_max);
  d.status = (hl != cue->haptic_l || hr != cue->haptic_r) ? DT_ACK_APPLIED_CLAMPED : DT_ACK_APPLIED;
  d.haptic_l = cue->side != DT_SIDE_R ? hl : 0;
  d.haptic_r = cue->side != DT_SIDE_L ? hr : 0;
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

/* ================================================================== ToF status mapping */

uint8_t dt_range_status_from_tof(uint8_t raw_status) {
  switch (raw_status) {
    case 0: return DT_RANGE_VALID;
    case 2:
    case 4: return DT_RANGE_NO_TARGET;
    default: return DT_RANGE_UNKNOWN;
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

void dt_build_range(dt_range_t *r, uint32_t seq, uint64_t t_us, uint16_t mm, uint8_t status) {
  memset(r, 0, sizeof *r);
  dt_header_init(&r->h, DT_RANGE, seq);
  r->t_us = t_us;
  r->mm = status == DT_RANGE_VALID ? mm : 0;
  r->status = status;
}

void dt_build_pong(dt_pong_t *p, uint32_t seq, uint64_t t_phone_us, uint64_t rx_us, uint64_t tx_us) {
  memset(p, 0, sizeof *p);
  dt_header_init(&p->h, DT_PONG, seq);
  p->t_phone_us = t_phone_us;
  p->t_board_rx_us = rx_us;
  p->t_board_tx_us = tx_us;
}

void dt_build_ack(dt_ack_t *a, uint32_t seq, uint32_t cue_seq, uint8_t status, uint64_t t_board_us) {
  memset(a, 0, sizeof *a);
  dt_header_init(&a->h, DT_ACK, seq);
  a->cue_seq = cue_seq;
  a->status = status;
  a->t_board_us = t_board_us;
}
