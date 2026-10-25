// Host unit tests for lib/cue_logic: `pio test -e native`.
//
// v2 focus: haptic decision semantics (clamp, side, busy/interrupt, deadline), profile
// haptic_max, watchdog LSS timing, single-zone RANGE builder and golden wire vectors.
#include <math.h>
#include <stdint.h>
#include <string.h>

#include "cue_logic.h"
#include "determine_wire.h"
#include <unity.h>
#include "vectors.h"

void setUp(void) {}
void tearDown(void) {}

/* ------------------------------------------------------------ golden vectors */

static void test_vectors_validate_types(void) {
  TEST_ASSERT_EQUAL(DT_BEAT, dt_validate(VEC_BEAT_CUE, sizeof VEC_BEAT_CUE));
  TEST_ASSERT_EQUAL(DT_BEAT, dt_validate(VEC_BEAT_CAM_UNKNOWN_TEMP, sizeof VEC_BEAT_CAM_UNKNOWN_TEMP));
  TEST_ASSERT_EQUAL(DT_RANGE, dt_validate(VEC_RANGE_VALID, sizeof VEC_RANGE_VALID));
  TEST_ASSERT_EQUAL(DT_RANGE, dt_validate(VEC_RANGE_NO_TARGET, sizeof VEC_RANGE_NO_TARGET));
  TEST_ASSERT_EQUAL(DT_RANGE, dt_validate(VEC_RANGE_UNKNOWN, sizeof VEC_RANGE_UNKNOWN));
  TEST_ASSERT_EQUAL(DT_FRAME, dt_validate(VEC_FRAME_CHUNK, sizeof VEC_FRAME_CHUNK));
  TEST_ASSERT_EQUAL(DT_FRAME, dt_validate(VEC_FRAME_EMPTY_CHUNK, sizeof VEC_FRAME_EMPTY_CHUNK));
  TEST_ASSERT_EQUAL(DT_PING, dt_validate(VEC_PING, sizeof VEC_PING));
  TEST_ASSERT_EQUAL(DT_PONG, dt_validate(VEC_PONG, sizeof VEC_PONG));
  TEST_ASSERT_EQUAL(DT_CUE, dt_validate(VEC_CUE_NEAR_BOTH, sizeof VEC_CUE_NEAR_BOTH));
  TEST_ASSERT_EQUAL(DT_CUE, dt_validate(VEC_CUE_NO_SYNC, sizeof VEC_CUE_NO_SYNC));
  TEST_ASSERT_EQUAL(DT_CUE, dt_validate(VEC_CUE_READY_LEFT, sizeof VEC_CUE_READY_LEFT));
  TEST_ASSERT_EQUAL(DT_ACK, dt_validate(VEC_ACK_APPLIED, sizeof VEC_ACK_APPLIED));
  TEST_ASSERT_EQUAL(DT_ACK, dt_validate(VEC_ACK_APPLIED_CLAMPED, sizeof VEC_ACK_APPLIED_CLAMPED));
  TEST_ASSERT_EQUAL(DT_ACK, dt_validate(VEC_ACK_EXPIRED, sizeof VEC_ACK_EXPIRED));
  TEST_ASSERT_EQUAL(DT_PROFILE, dt_validate(VEC_PROFILE_SSD, sizeof VEC_PROFILE_SSD));
  TEST_ASSERT_EQUAL(DT_PROFILE, dt_validate(VEC_PROFILE_HAPTIC_ONLY, sizeof VEC_PROFILE_HAPTIC_ONLY));
}

static void test_validate_rejects_corruption(void) {
  uint8_t buf[64];
  memcpy(buf, VEC_CUE_NEAR_BOTH, sizeof VEC_CUE_NEAR_BOTH);
  buf[0] = 0x99;                                   // bad magic
  TEST_ASSERT_EQUAL(0, dt_validate(buf, sizeof VEC_CUE_NEAR_BOTH));
  memcpy(buf, VEC_CUE_NEAR_BOTH, sizeof VEC_CUE_NEAR_BOTH);
  buf[2] = DT_VERSION + 1;                         // wrong version (e.g. a v1 peer)
  TEST_ASSERT_EQUAL(0, dt_validate(buf, sizeof VEC_CUE_NEAR_BOTH));
  TEST_ASSERT_EQUAL(0, dt_validate(VEC_CUE_NEAR_BOTH, sizeof VEC_CUE_NEAR_BOTH - 1)); // truncated
  TEST_ASSERT_EQUAL(0, dt_validate(VEC_CUE_NEAR_BOTH, sizeof VEC_CUE_NEAR_BOTH + 1)); // padded
  TEST_ASSERT_EQUAL(0, dt_validate(VEC_CUE_NEAR_BOTH, 0));
}

static void test_vector_decode_ping(void) {
  dt_ping_t p;
  memcpy(&p, VEC_PING, sizeof p);
  TEST_ASSERT_TRUE(p.t_phone_us == VEC_PING_T_PHONE_US);
}

static void test_vector_decode_cues(void) {
  dt_cue_t c;
  memcpy(&c, VEC_CUE_NEAR_BOTH, sizeof c);
  TEST_ASSERT_TRUE(c.t_issued_phone_us == VEC_CUE_NEAR_BOTH_T_ISSUED_PHONE_US);
  TEST_ASSERT_TRUE(c.deadline_board_us == VEC_CUE_NEAR_BOTH_DEADLINE_BOARD_US);
  TEST_ASSERT_EQUAL(VEC_CUE_NEAR_BOTH_CUE_ID, c.cue_id);
  TEST_ASSERT_EQUAL(VEC_CUE_NEAR_BOTH_SIDE, c.side);
  TEST_ASSERT_EQUAL(VEC_CUE_NEAR_BOTH_HAPTIC_PATTERN, c.haptic_pattern);
  TEST_ASSERT_EQUAL(VEC_CUE_NEAR_BOTH_HAPTIC_L, c.haptic_l);
  TEST_ASSERT_EQUAL(VEC_CUE_NEAR_BOTH_HAPTIC_R, c.haptic_r);
  TEST_ASSERT_EQUAL(VEC_CUE_NEAR_BOTH_FLAGS, c.flags);
  TEST_ASSERT_EQUAL(VEC_CUE_NEAR_BOTH_TTL_MS, c.ttl_ms);

  memcpy(&c, VEC_CUE_NO_SYNC, sizeof c);
  TEST_ASSERT_TRUE(c.deadline_board_us == 0); // 0 = phone has no clock sync
  TEST_ASSERT_EQUAL(DT_HAPTIC_LSS, c.haptic_pattern);
}

static void test_vector_decode_profile(void) {
  dt_profile_t p;
  memcpy(&p, VEC_PROFILE_SSD, sizeof p);
  TEST_ASSERT_EQUAL(DT_MODE_SSD, p.mode);
  TEST_ASSERT_EQUAL(VEC_PROFILE_SSD_HAPTIC_MAX, p.haptic_max);
  memcpy(&p, VEC_PROFILE_HAPTIC_ONLY, sizeof p);
  TEST_ASSERT_EQUAL(DT_MODE_HAPTIC_ONLY, p.mode);
  TEST_ASSERT_EQUAL(255, p.haptic_max);
}

static void test_vector_build_beat(void) {
  dt_beat_t b;
  dt_build_beat(&b, VEC_BEAT_CUE_SEQ, VEC_BEAT_CUE_RESET_REASON, VEC_BEAT_CUE_FAULTS, VEC_BEAT_CUE_T_US,
                VEC_BEAT_CUE_RSSI_DBM, VEC_BEAT_CUE_TEMP_C, VEC_BEAT_CUE_HEAP_KB);
  TEST_ASSERT_EQUAL_UINT8_ARRAY(VEC_BEAT_CUE, &b, sizeof b);
}

/* One forward zone only: mm rides alone, mm = 0 unless VALID. */
static void test_vector_build_range(void) {
  dt_range_t r;
  dt_build_range(&r, VEC_RANGE_VALID_SEQ, VEC_RANGE_VALID_T_US, VEC_RANGE_VALID_MM, DT_RANGE_VALID);
  TEST_ASSERT_EQUAL_UINT16(VEC_RANGE_VALID_MM, r.mm);
  TEST_ASSERT_EQUAL(DT_RANGE_VALID, r.status);
  TEST_ASSERT_EQUAL_UINT8_ARRAY(VEC_RANGE_VALID, &r, sizeof r);

  dt_build_range(&r, VEC_RANGE_NO_TARGET_SEQ, VEC_RANGE_NO_TARGET_T_US, 0, DT_RANGE_NO_TARGET);
  TEST_ASSERT_EQUAL_UINT8_ARRAY(VEC_RANGE_NO_TARGET, &r, sizeof r);

  dt_build_range(&r, VEC_RANGE_UNKNOWN_SEQ, VEC_RANGE_UNKNOWN_T_US, 0, DT_RANGE_UNKNOWN);
  TEST_ASSERT_EQUAL_UINT8_ARRAY(VEC_RANGE_UNKNOWN, &r, sizeof r);

  /* A distance read together with a non-VALID status must never reach the wire. */
  dt_build_range(&r, 7, 123, 999, DT_RANGE_UNKNOWN);
  TEST_ASSERT_EQUAL_UINT16(0, r.mm);
  TEST_ASSERT_EQUAL(DT_RANGE_UNKNOWN, r.status);
}

/* VL53L4ED raw range_status: 0 VALID, 2/4 NO_TARGET, everything else UNKNOWN; mm only for VALID. */
static void test_tof_status_mapping(void) {
  TEST_ASSERT_EQUAL(DT_RANGE_VALID, dt_range_status_from_tof(0));
  TEST_ASSERT_EQUAL(DT_RANGE_NO_TARGET, dt_range_status_from_tof(2));
  TEST_ASSERT_EQUAL(DT_RANGE_NO_TARGET, dt_range_status_from_tof(4));
  for (int raw = 0; raw < 256; raw++) {
    if (raw == 0 || raw == 2 || raw == 4) continue;
    TEST_ASSERT_EQUAL_MESSAGE(DT_RANGE_UNKNOWN, dt_range_status_from_tof((uint8_t)raw), "raw status");
  }
  dt_range_t r;
  dt_build_range(&r, 1, 1, 777, dt_range_status_from_tof(2));
  TEST_ASSERT_EQUAL(DT_RANGE_NO_TARGET, r.status);
  TEST_ASSERT_EQUAL_UINT16(0, r.mm);
  dt_build_range(&r, 2, 2, 777, dt_range_status_from_tof(7));
  TEST_ASSERT_EQUAL(DT_RANGE_UNKNOWN, r.status);
  TEST_ASSERT_EQUAL_UINT16(0, r.mm);
  dt_build_range(&r, 3, 3, 777, dt_range_status_from_tof(0));
  TEST_ASSERT_EQUAL_UINT16(777, r.mm);
}

static void test_vector_build_pong(void) {
  dt_pong_t p;
  dt_build_pong(&p, VEC_PONG_SEQ, VEC_PONG_T_PHONE_US, VEC_PONG_T_BOARD_RX_US, VEC_PONG_T_BOARD_TX_US);
  TEST_ASSERT_EQUAL_UINT8_ARRAY(VEC_PONG, &p, sizeof p);
}

static void test_vector_build_ack(void) {
  dt_ack_t a;
  dt_build_ack(&a, VEC_ACK_APPLIED_SEQ, VEC_ACK_APPLIED_CUE_SEQ, DT_ACK_APPLIED, VEC_ACK_APPLIED_T_BOARD_US);
  TEST_ASSERT_EQUAL_UINT8_ARRAY(VEC_ACK_APPLIED, &a, sizeof a);

  dt_build_ack(&a, VEC_ACK_APPLIED_CLAMPED_SEQ, VEC_ACK_APPLIED_CLAMPED_CUE_SEQ, DT_ACK_APPLIED_CLAMPED,
               VEC_ACK_APPLIED_CLAMPED_T_BOARD_US);
  TEST_ASSERT_EQUAL_UINT8_ARRAY(VEC_ACK_APPLIED_CLAMPED, &a, sizeof a);

  dt_build_ack(&a, VEC_ACK_EXPIRED_SEQ, VEC_ACK_EXPIRED_CUE_SEQ, DT_ACK_EXPIRED, VEC_ACK_EXPIRED_T_BOARD_US);
  TEST_ASSERT_EQUAL_UINT8_ARRAY(VEC_ACK_EXPIRED, &a, sizeof a);
  TEST_ASSERT_EQUAL_UINT(0, a.reserved);
}

/* ------------------------------------------------------------ CUE decision */

/* cue_near_both: OBSTACLE_NEAR, both sides, BUZZ150 at 200/200, deadline 9000150000. */
static void test_decide_near_both_vector(void) {
  dt_cue_t c;
  memcpy(&c, VEC_CUE_NEAR_BOTH, sizeof c);
  dt_profile_state_t prof;
  dt_profile_default(&prof); // haptic_max 255: 200 passes unclamped

  dt_cue_decision_t d = dt_cue_decide(&c, VEC_CUE_NEAR_BOTH_DEADLINE_BOARD_US - 1, &prof, false);
  TEST_ASSERT_EQUAL(DT_ACK_APPLIED, d.status);
  TEST_ASSERT_TRUE(d.play);
  TEST_ASSERT_EQUAL_UINT8(200, d.haptic_l);
  TEST_ASSERT_EQUAL_UINT8(200, d.haptic_r);
}

static dt_cue_t make_cue(uint8_t id, uint8_t side, uint8_t pattern, uint8_t hl, uint8_t hr, uint8_t flags,
                         uint64_t deadline) {
  dt_cue_t c;
  memset(&c, 0, sizeof c);
  dt_header_init(&c.h, DT_CUE, 1);
  c.deadline_board_us = deadline;
  c.cue_id = id;
  c.side = side;
  c.haptic_pattern = pattern;
  c.haptic_l = hl;
  c.haptic_r = hr;
  c.flags = flags;
  c.ttl_ms = 150;
  return c;
}

static dt_profile_t make_profile(uint8_t mode, uint8_t haptic_max) {
  dt_profile_t p;
  memset(&p, 0, sizeof p);
  dt_header_init(&p.h, DT_PROFILE, 9);
  p.mode = mode;
  p.haptic_max = haptic_max;
  return p;
}

static void test_decide_haptic_clamp_and_profile(void) {
  dt_profile_state_t prof;
  dt_profile_default(&prof);

  /* Profile haptic_max lowers both requested intensities -> APPLIED_CLAMPED. */
  dt_profile_t pk = make_profile(DT_MODE_STEREO, 120);
  TEST_ASSERT_TRUE(dt_profile_apply(&prof, &pk));
  dt_cue_t c = make_cue(DT_CUE_OBSTACLE_NEAR, DT_SIDE_BOTH, DT_HAPTIC_BUZZ150, 200, 200, 0, 9000000);
  dt_cue_decision_t d = dt_cue_decide(&c, 8999999, &prof, false);
  TEST_ASSERT_EQUAL(DT_ACK_APPLIED_CLAMPED, d.status);
  TEST_ASSERT_TRUE(d.play);
  TEST_ASSERT_EQUAL_UINT8(120, d.haptic_l);
  TEST_ASSERT_EQUAL_UINT8(120, d.haptic_r);

  /* Profile with haptic_max 180 (the profile_ssd vector) clamps 200 -> 180. */
  dt_profile_t ssd = make_profile(DT_MODE_SSD, 180);
  TEST_ASSERT_TRUE(dt_profile_apply(&prof, &ssd));
  d = dt_cue_decide(&c, 8999999, &prof, false);
  TEST_ASSERT_EQUAL(DT_ACK_APPLIED_CLAMPED, d.status);
  TEST_ASSERT_EQUAL_UINT8(180, d.haptic_l);

  /* Full-scale profile: nothing clamped -> plain APPLIED. */
  dt_profile_t full = make_profile(DT_MODE_SSD, 255);
  TEST_ASSERT_TRUE(dt_profile_apply(&prof, &full));
  d = dt_cue_decide(&c, 8999999, &prof, false);
  TEST_ASSERT_EQUAL(DT_ACK_APPLIED, d.status);
  TEST_ASSERT_EQUAL_UINT8(200, d.haptic_l);
}

static void test_decide_mode_never_mutes_haptics(void) {
  /* mode is phone audio-route semantics only: HAPTIC_ONLY still plays board haptics. */
  dt_profile_state_t prof;
  dt_profile_default(&prof);
  dt_profile_t pk = make_profile(DT_MODE_HAPTIC_ONLY, 255);
  TEST_ASSERT_TRUE(dt_profile_apply(&prof, &pk));
  dt_cue_t c = make_cue(DT_CUE_OBSTACLE_NEAR, DT_SIDE_BOTH, DT_HAPTIC_PULSE60, 200, 200, 0, 9000000);
  dt_cue_decision_t d = dt_cue_decide(&c, 8999999, &prof, false);
  TEST_ASSERT_EQUAL(DT_ACK_APPLIED, d.status);
  TEST_ASSERT_TRUE(d.play);
  TEST_ASSERT_EQUAL_UINT8(200, d.haptic_l);
}

static void test_decide_side_exclusion_is_not_clamping(void) {
  dt_profile_state_t prof;
  dt_profile_default(&prof);
  dt_cue_t c = make_cue(DT_CUE_READY, DT_SIDE_L, DT_HAPTIC_PULSE40, 180, 180, 0, 9000000);
  dt_cue_decision_t d = dt_cue_decide(&c, 8999999, &prof, false);
  TEST_ASSERT_EQUAL(DT_ACK_APPLIED, d.status); // 180 <= 255: side exclusion alone is not a clamp
  TEST_ASSERT_EQUAL_UINT8(180, d.haptic_l);
  TEST_ASSERT_EQUAL_UINT8(0, d.haptic_r);

  c = make_cue(DT_CUE_READY, DT_SIDE_R, DT_HAPTIC_PULSE40, 180, 180, 0, 9000000);
  d = dt_cue_decide(&c, 8999999, &prof, false);
  TEST_ASSERT_EQUAL(DT_ACK_APPLIED, d.status);
  TEST_ASSERT_EQUAL_UINT8(0, d.haptic_l);
  TEST_ASSERT_EQUAL_UINT8(180, d.haptic_r);
}

static void test_decide_deadline_busy_bad(void) {
  dt_profile_state_t prof;
  dt_profile_default(&prof);
  uint64_t now = 5000000;

  /* deadline 0 = phone has no clock sync estimate -> NO_SYNC (the cue_no_sync vector). */
  dt_cue_t n;
  memcpy(&n, VEC_CUE_NO_SYNC, sizeof n);
  dt_cue_decision_t d = dt_cue_decide(&n, now, &prof, false);
  TEST_ASSERT_EQUAL(DT_ACK_NO_SYNC, d.status);
  TEST_ASSERT_FALSE(d.play);

  dt_cue_t c = make_cue(DT_CUE_OBSTACLE_MID, DT_SIDE_BOTH, DT_HAPTIC_PULSE60, 100, 100, 0, 4000000);
  TEST_ASSERT_EQUAL(DT_ACK_EXPIRED, dt_cue_decide(&c, now, &prof, false).status);
  TEST_ASSERT_EQUAL(DT_ACK_APPLIED, dt_cue_decide(&c, 4000000, &prof, false).status); // at the deadline is still on time
  TEST_ASSERT_EQUAL(DT_ACK_EXPIRED, dt_cue_decide(&c, 4000001, &prof, false).status); // one tick later is late

  /* Busy without the interrupt flag drops the cue; with it, the new cue wins. */
  c = make_cue(DT_CUE_OBSTACLE_MID, DT_SIDE_BOTH, DT_HAPTIC_PULSE60, 100, 100, 0, 9000000);
  d = dt_cue_decide(&c, now, &prof, true);
  TEST_ASSERT_EQUAL(DT_ACK_BUSY_DROPPED, d.status);
  TEST_ASSERT_FALSE(d.play);
  c.flags = DT_CUE_FLAG_INTERRUPT;
  d = dt_cue_decide(&c, now, &prof, true);
  TEST_ASSERT_EQUAL(DT_ACK_APPLIED, d.status);
  TEST_ASSERT_TRUE(d.play);

  /* Unknown id / side / pattern -> BAD_CUE, nothing plays. */
  c = make_cue(DT_CUE_COUNT, DT_SIDE_BOTH, DT_HAPTIC_PULSE60, 100, 100, 0, 9000000);
  TEST_ASSERT_EQUAL(DT_ACK_BAD_CUE, dt_cue_decide(&c, now, &prof, false).status);
  c = make_cue(DT_CUE_READY, 3, DT_HAPTIC_PULSE60, 100, 100, 0, 9000000);
  TEST_ASSERT_EQUAL(DT_ACK_BAD_CUE, dt_cue_decide(&c, now, &prof, false).status);
  c = make_cue(DT_CUE_READY, DT_SIDE_BOTH, DT_HAPTIC_COUNT, 100, 100, 0, 9000000);
  TEST_ASSERT_EQUAL(DT_ACK_BAD_CUE, dt_cue_decide(&c, now, &prof, false).status);
}

static void test_decide_pattern_none_is_phone_audio_only(void) {
  /* No haptic requested: still acknowledged APPLIED and never dropped as busy. */
  dt_profile_state_t prof;
  dt_profile_default(&prof);
  dt_cue_t c = make_cue(DT_CUE_CAL_TONE_1K, DT_SIDE_BOTH, DT_HAPTIC_NONE, 0, 0, 0, 9000000);
  dt_cue_decision_t d = dt_cue_decide(&c, 8999999, &prof, true);
  TEST_ASSERT_EQUAL(DT_ACK_APPLIED, d.status);
  TEST_ASSERT_FALSE(d.play);
  TEST_ASSERT_EQUAL_UINT8(0, d.haptic_l);
  TEST_ASSERT_EQUAL_UINT8(0, d.haptic_r);
}

static void test_profile_rejects_unknown_mode(void) {
  dt_profile_state_t prof;
  dt_profile_default(&prof);
  dt_profile_t bad = make_profile(3, 100);
  TEST_ASSERT_FALSE(dt_profile_apply(&prof, &bad));
  TEST_ASSERT_EQUAL_UINT8(DT_MODE_STEREO, prof.mode); // unchanged
  TEST_ASSERT_EQUAL_UINT8(255, prof.haptic_max);
}

/* haptic_max 0 would cap the watchdog LSS to nothing; PROFILE must carry 1..255. */
static void test_profile_rejects_zero_haptic_max(void) {
  dt_profile_state_t prof;
  dt_profile_default(&prof);
  dt_profile_t ssd = make_profile(DT_MODE_SSD, 90);
  TEST_ASSERT_TRUE(dt_profile_apply(&prof, &ssd));

  dt_profile_t zero = make_profile(DT_MODE_HAPTIC_ONLY, 0);
  TEST_ASSERT_FALSE(dt_profile_apply(&prof, &zero));
  TEST_ASSERT_EQUAL_UINT8(DT_MODE_SSD, prof.mode); // previous profile untouched
  TEST_ASSERT_EQUAL_UINT8(90, prof.haptic_max);

  dt_profile_t one = make_profile(DT_MODE_STEREO, 1); // lowest accepted value
  TEST_ASSERT_TRUE(dt_profile_apply(&prof, &one));
  TEST_ASSERT_EQUAL_UINT8(1, prof.haptic_max);
}

/* The watchdog alarm is dispatched at dt_clamp_haptic(255, haptic_max) and driven through
 * dt_vibro_power(): even the minimum profile must leave a non-zero drive value. */
static void test_watchdog_alarm_survives_minimum_profile(void) {
  dt_profile_state_t prof;
  dt_profile_default(&prof);
  dt_profile_t one = make_profile(DT_MODE_HAPTIC_ONLY, 1);
  TEST_ASSERT_TRUE(dt_profile_apply(&prof, &one));
  uint8_t level = dt_clamp_haptic(255, prof.haptic_max);
  TEST_ASSERT_EQUAL_UINT8(1, level);
  TEST_ASSERT_TRUE(dt_vibro_power(level) > 0);
  TEST_ASSERT_NOT_NULL(dt_haptic_def(DT_HAPTIC_LSS));
}

static void test_vibro_power_mapping(void) {
  TEST_ASSERT_EQUAL_UINT8(0, dt_vibro_power(0)); // off stays off
  TEST_ASSERT_EQUAL_UINT8(1, dt_vibro_power(1)); // any non-zero level is driven
  TEST_ASSERT_EQUAL_UINT8(1, dt_vibro_power(5));
  TEST_ASSERT_EQUAL_UINT8(2, dt_vibro_power(6));
  TEST_ASSERT_EQUAL_UINT8(36, dt_vibro_power(180));
  TEST_ASSERT_EQUAL_UINT8(50, dt_vibro_power(255)); // never exceeds the Modulino maximum
  uint8_t prev = 0;
  for (int l = 0; l <= 255; l++) {
    uint8_t p = dt_vibro_power((uint8_t)l);
    TEST_ASSERT_TRUE(p >= prev && p <= 50);
    TEST_ASSERT_TRUE(l == 0 || p > 0);
    prev = p;
  }
}

/* ------------------------------------------------------------ watchdog */

static void test_watchdog_boot_grace(void) {
  dt_watchdog_t w;
  dt_wd_init(&w);
  TEST_ASSERT_FALSE(dt_wd_update(&w, DT_WD_BOOT_GRACE_US - 1));
  TEST_ASSERT_FALSE(w.active);
  TEST_ASSERT_TRUE(dt_wd_update(&w, DT_WD_BOOT_GRACE_US)); // no PING within 5 s of boot
  TEST_ASSERT_TRUE(w.active);
}

static void test_watchdog_ping_timeout_and_resume(void) {
  dt_watchdog_t w;
  dt_wd_init(&w);
  dt_wd_on_ping(&w, 1000000);
  TEST_ASSERT_TRUE(w.ever_pinged);
  TEST_ASSERT_FALSE(dt_wd_update(&w, 1000000 + DT_WD_PING_TIMEOUT_US - 1));
  TEST_ASSERT_TRUE(dt_wd_update(&w, 1000000 + DT_WD_PING_TIMEOUT_US)); // 800 ms of silence
  TEST_ASSERT_TRUE(w.active);

  /* LSS alarm repeats every 5 s while the phone is gone. */
  uint64_t t = 1800000;
  TEST_ASSERT_FALSE(dt_wd_update(&w, t));
  TEST_ASSERT_TRUE(dt_wd_update(&w, t + DT_WD_ALARM_PERIOD_US));

  /* A single PING clears it immediately. */
  dt_wd_on_ping(&w, t + DT_WD_ALARM_PERIOD_US + 1);
  TEST_ASSERT_FALSE(dt_wd_update(&w, t + DT_WD_ALARM_PERIOD_US + 2));
  TEST_ASSERT_FALSE(w.active);
}

/* ------------------------------------------------------------ haptics */

static void test_haptic_patterns_exist(void) {
  TEST_ASSERT_NULL(dt_haptic_def(DT_HAPTIC_NONE));
  TEST_ASSERT_NULL(dt_haptic_def(DT_HAPTIC_COUNT));
  TEST_ASSERT_NULL(dt_haptic_def(200));
  for (uint8_t p = DT_HAPTIC_NONE + 1; p < DT_HAPTIC_COUNT; p++) {
    const dt_haptic_def_t *d = dt_haptic_def(p);
    TEST_ASSERT_NOT_NULL(d);
    TEST_ASSERT_GREATER_THAN(0, d->count);
    TEST_ASSERT_LESS_OR_EQUAL(DT_HAPTIC_MAX_SEGMENTS, d->count);
  }
}

static void test_haptic_lengths_and_on_at(void) {
  TEST_ASSERT_EQUAL_UINT32(0, dt_haptic_length_ms(DT_HAPTIC_NONE));
  TEST_ASSERT_EQUAL_UINT32(40, dt_haptic_length_ms(DT_HAPTIC_PULSE40));
  TEST_ASSERT_EQUAL_UINT32(60, dt_haptic_length_ms(DT_HAPTIC_PULSE60));
  TEST_ASSERT_EQUAL_UINT32(150, dt_haptic_length_ms(DT_HAPTIC_BUZZ150));
  /* LSS = long, short, short: 400 + 120 + 120 ms of drive with 100 ms gaps. */
  TEST_ASSERT_EQUAL_UINT32(400 + 100 + 120 + 100 + 120, dt_haptic_length_ms(DT_HAPTIC_LSS));

  TEST_ASSERT_TRUE(dt_haptic_on_at(DT_HAPTIC_PULSE40, 0));
  TEST_ASSERT_TRUE(dt_haptic_on_at(DT_HAPTIC_PULSE40, 39));
  TEST_ASSERT_FALSE(dt_haptic_on_at(DT_HAPTIC_PULSE40, 40)); // pattern over

  TEST_ASSERT_TRUE(dt_haptic_on_at(DT_HAPTIC_LSS, 399));    // long segment
  TEST_ASSERT_FALSE(dt_haptic_on_at(DT_HAPTIC_LSS, 400));   // first gap
  TEST_ASSERT_TRUE(dt_haptic_on_at(DT_HAPTIC_LSS, 500));    // first short
  TEST_ASSERT_FALSE(dt_haptic_on_at(DT_HAPTIC_LSS, 620));   // second gap
  TEST_ASSERT_TRUE(dt_haptic_on_at(DT_HAPTIC_LSS, 720));    // second short
  TEST_ASSERT_FALSE(dt_haptic_on_at(DT_HAPTIC_LSS, 840));   // done
}

static void test_haptic_cap_boundaries(void) {
  TEST_ASSERT_EQUAL_UINT8(0, dt_clamp_haptic(0, 255));
  TEST_ASSERT_EQUAL_UINT8(255, dt_clamp_haptic(255, 255));
  TEST_ASSERT_EQUAL_UINT8(100, dt_clamp_haptic(200, 100));
  TEST_ASSERT_EQUAL_UINT8(100, dt_clamp_haptic(100, 100));
  TEST_ASSERT_EQUAL_UINT8(1, dt_clamp_haptic(200, 1)); // lowest accepted profile ceiling stays non-zero
}

int main(void) {
  UNITY_BEGIN();
  RUN_TEST(test_vectors_validate_types);
  RUN_TEST(test_validate_rejects_corruption);
  RUN_TEST(test_vector_decode_ping);
  RUN_TEST(test_vector_decode_cues);
  RUN_TEST(test_vector_decode_profile);
  RUN_TEST(test_vector_build_beat);
  RUN_TEST(test_vector_build_range);
  RUN_TEST(test_tof_status_mapping);
  RUN_TEST(test_vector_build_pong);
  RUN_TEST(test_vector_build_ack);
  RUN_TEST(test_decide_near_both_vector);
  RUN_TEST(test_decide_haptic_clamp_and_profile);
  RUN_TEST(test_decide_mode_never_mutes_haptics);
  RUN_TEST(test_decide_side_exclusion_is_not_clamping);
  RUN_TEST(test_decide_deadline_busy_bad);
  RUN_TEST(test_decide_pattern_none_is_phone_audio_only);
  RUN_TEST(test_profile_rejects_unknown_mode);
  RUN_TEST(test_profile_rejects_zero_haptic_max);
  RUN_TEST(test_watchdog_alarm_survives_minimum_profile);
  RUN_TEST(test_watchdog_boot_grace);
  RUN_TEST(test_vibro_power_mapping);
  RUN_TEST(test_watchdog_ping_timeout_and_resume);
  RUN_TEST(test_haptic_patterns_exist);
  RUN_TEST(test_haptic_lengths_and_on_at);
  RUN_TEST(test_haptic_cap_boundaries);
  return UNITY_END();
}
