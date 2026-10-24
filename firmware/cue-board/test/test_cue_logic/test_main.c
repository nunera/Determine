// Host unit tests for lib/cue_logic: `pio test -e native`.
#include <math.h>
#include <stdlib.h>
#include <string.h>
#include <unity.h>

#include "cue_logic.h"
#include "vectors.h"

void setUp(void) {}
void tearDown(void) {}

/* ------------------------------------------------------------ golden vectors */

static void test_vectors_validate_types(void) {
  TEST_ASSERT_EQUAL_UINT8(DT_BEAT, dt_validate(VEC_BEAT_CUE, sizeof VEC_BEAT_CUE));
  TEST_ASSERT_EQUAL_UINT8(DT_RANGE, dt_validate(VEC_RANGE, sizeof VEC_RANGE));
  TEST_ASSERT_EQUAL_UINT8(DT_FRAME, dt_validate(VEC_FRAME_CHUNK, sizeof VEC_FRAME_CHUNK));
  TEST_ASSERT_EQUAL_UINT8(DT_FRAME, dt_validate(VEC_FRAME_EMPTY_CHUNK, sizeof VEC_FRAME_EMPTY_CHUNK));
  TEST_ASSERT_EQUAL_UINT8(DT_PING, dt_validate(VEC_PING, sizeof VEC_PING));
  TEST_ASSERT_EQUAL_UINT8(DT_PONG, dt_validate(VEC_PONG, sizeof VEC_PONG));
  TEST_ASSERT_EQUAL_UINT8(DT_CUE, dt_validate(VEC_CUE_NEAR_LEFT, sizeof VEC_CUE_NEAR_LEFT));
  TEST_ASSERT_EQUAL_UINT8(DT_ACK, dt_validate(VEC_ACK_CLAMPED, sizeof VEC_ACK_CLAMPED));
  TEST_ASSERT_EQUAL_UINT8(DT_PROFILE, dt_validate(VEC_PROFILE_SSD, sizeof VEC_PROFILE_SSD));
}

static void test_validate_rejects_corruption(void) {
  uint8_t b[sizeof VEC_PING];
  memcpy(b, VEC_PING, sizeof b);
  TEST_ASSERT_EQUAL_UINT8(0, dt_validate(b, sizeof b - 1)); /* wrong length */
  b[0] ^= 1;
  TEST_ASSERT_EQUAL_UINT8(0, dt_validate(b, sizeof b)); /* magic */
  memcpy(b, VEC_PING, sizeof b);
  b[2] = 2;
  TEST_ASSERT_EQUAL_UINT8(0, dt_validate(b, sizeof b)); /* version */
  memcpy(b, VEC_PING, sizeof b);
  b[3] = 0x7f;
  TEST_ASSERT_EQUAL_UINT8(0, dt_validate(b, sizeof b)); /* unknown type */
  /* CUE length vs PING bytes: a CUE header with 16 bytes is rejected */
  uint8_t c[sizeof VEC_PING];
  memcpy(c, VEC_CUE_NEAR_LEFT, sizeof c);
  TEST_ASSERT_EQUAL_UINT8(0, dt_validate(c, sizeof c));
}

static void test_vector_decode_ping(void) {
  dt_ping_t p;
  memcpy(&p, VEC_PING, sizeof p);
  TEST_ASSERT_EQUAL_UINT32(VEC_PING_SEQ, p.h.seq);
  TEST_ASSERT_TRUE(p.t_phone_us == VEC_PING_T_PHONE_US);
}

static void test_vector_decode_cues(void) {
  dt_cue_t c;
  memcpy(&c, VEC_CUE_NEAR_LEFT, sizeof c);
  TEST_ASSERT_EQUAL_UINT32(VEC_CUE_NEAR_LEFT_SEQ, c.h.seq);
  TEST_ASSERT_TRUE(c.t_issued_phone_us == VEC_CUE_NEAR_LEFT_T_ISSUED_PHONE_US);
  TEST_ASSERT_TRUE(c.deadline_board_us == VEC_CUE_NEAR_LEFT_DEADLINE_BOARD_US);
  TEST_ASSERT_EQUAL_UINT8(VEC_CUE_NEAR_LEFT_CUE_ID, c.cue_id);
  TEST_ASSERT_EQUAL_UINT8(VEC_CUE_NEAR_LEFT_SIDE, c.side);
  TEST_ASSERT_EQUAL_INT8(VEC_CUE_NEAR_LEFT_GAIN_L_DB, c.gain_l_db);
  TEST_ASSERT_EQUAL_INT8(VEC_CUE_NEAR_LEFT_GAIN_R_DB, c.gain_r_db);
  TEST_ASSERT_EQUAL_UINT8(VEC_CUE_NEAR_LEFT_HAPTIC_PATTERN, c.haptic_pattern);
  TEST_ASSERT_EQUAL_UINT8(VEC_CUE_NEAR_LEFT_HAPTIC_L, c.haptic_l);
  TEST_ASSERT_EQUAL_UINT8(VEC_CUE_NEAR_LEFT_HAPTIC_R, c.haptic_r);
  TEST_ASSERT_EQUAL_UINT8(VEC_CUE_NEAR_LEFT_FLAGS, c.flags);
  TEST_ASSERT_EQUAL_UINT16(VEC_CUE_NEAR_LEFT_TTL_MS, c.ttl_ms);

  memcpy(&c, VEC_CUE_NO_SYNC, sizeof c);
  TEST_ASSERT_EQUAL_UINT32(VEC_CUE_NO_SYNC_SEQ, c.h.seq);
  TEST_ASSERT_TRUE(c.deadline_board_us == 0);
  TEST_ASSERT_EQUAL_INT8(VEC_CUE_NO_SYNC_GAIN_R_DB, c.gain_r_db);
  TEST_ASSERT_EQUAL_UINT16(VEC_CUE_NO_SYNC_TTL_MS, c.ttl_ms);
  /* No sync -> NO_SYNC regardless of time (Invariant 5). */
  dt_profile_state_t prof;
  dt_profile_default(&prof);
  dt_cue_decision_t d = dt_cue_decide(&c, 0, &prof, false);
  TEST_ASSERT_EQUAL_UINT8(DT_ACK_NO_SYNC, d.status);
  TEST_ASSERT_FALSE(d.play);
}

static void test_vector_decode_profile(void) {
  dt_profile_t p;
  memcpy(&p, VEC_PROFILE_SSD, sizeof p);
  TEST_ASSERT_EQUAL_UINT32(VEC_PROFILE_SSD_SEQ, p.h.seq);
  TEST_ASSERT_EQUAL_INT8(VEC_PROFILE_SSD_CEILING_DB, p.ceiling_db);
  TEST_ASSERT_EQUAL_UINT8(VEC_PROFILE_SSD_MODE, p.mode);
  TEST_ASSERT_EQUAL_UINT8(VEC_PROFILE_SSD_HAPTIC_MAX, p.haptic_max);
  dt_profile_state_t s;
  dt_profile_default(&s);
  TEST_ASSERT_TRUE(dt_profile_apply(&s, &p));
  TEST_ASSERT_EQUAL_INT8(-14, s.ceiling_db);
  TEST_ASSERT_EQUAL_UINT8(DT_MODE_SSD, s.mode);
  TEST_ASSERT_EQUAL_UINT8(180, s.haptic_max);
}

static void test_vector_build_beat(void) {
  dt_beat_t b;
  dt_build_beat(&b, VEC_BEAT_CUE_SEQ, VEC_BEAT_CUE_RESET_REASON, VEC_BEAT_CUE_FAULTS, VEC_BEAT_CUE_T_US,
                VEC_BEAT_CUE_RSSI_DBM, VEC_BEAT_CUE_TEMP_C, VEC_BEAT_CUE_HEAP_KB);
  TEST_ASSERT_EQUAL_HEX8_ARRAY(VEC_BEAT_CUE, &b, sizeof b);
  /* seq wrap boundary: the CAM vector uses board 1; ours always says CUE, so patch it. */
  dt_build_beat(&b, VEC_BEAT_CAM_UNKNOWN_TEMP_SEQ, VEC_BEAT_CAM_UNKNOWN_TEMP_RESET_REASON,
                VEC_BEAT_CAM_UNKNOWN_TEMP_FAULTS, VEC_BEAT_CAM_UNKNOWN_TEMP_T_US, VEC_BEAT_CAM_UNKNOWN_TEMP_RSSI_DBM,
                VEC_BEAT_CAM_UNKNOWN_TEMP_TEMP_C, VEC_BEAT_CAM_UNKNOWN_TEMP_HEAP_KB);
  TEST_ASSERT_EQUAL_UINT8(DT_BOARD_CUE, b.board);
  b.board = DT_BOARD_CAM;
  TEST_ASSERT_EQUAL_HEX8_ARRAY(VEC_BEAT_CAM_UNKNOWN_TEMP, &b, sizeof b);
}

static void test_vector_build_range(void) {
  uint16_t mm[DT_ZONES];
  uint8_t st[DT_ZONES];
  for (int i = 0; i < DT_ZONES; i++) {
    mm[i] = (uint16_t)VEC_RANGE_MM[i];
    st[i] = (uint8_t)VEC_RANGE_STATUS[i];
  }
  dt_range_t r;
  dt_build_range(&r, VEC_RANGE_SEQ, VEC_RANGE_T_US, mm, st);
  TEST_ASSERT_EQUAL_HEX8_ARRAY(VEC_RANGE, &r, sizeof r);
}

static void test_vector_build_pong(void) {
  dt_pong_t p;
  dt_build_pong(&p, VEC_PONG_SEQ, VEC_PONG_T_PHONE_US, VEC_PONG_T_BOARD_RX_US, VEC_PONG_T_BOARD_TX_US);
  TEST_ASSERT_EQUAL_HEX8_ARRAY(VEC_PONG, &p, sizeof p);
}

static void test_vector_build_ack(void) {
  dt_ack_t a;
  dt_build_ack(&a, VEC_ACK_CLAMPED_SEQ, VEC_ACK_CLAMPED_CUE_SEQ, VEC_ACK_CLAMPED_STATUS,
               VEC_ACK_CLAMPED_APPLIED_GAIN_L_DB, VEC_ACK_CLAMPED_APPLIED_GAIN_R_DB, VEC_ACK_CLAMPED_T_BOARD_US);
  TEST_ASSERT_EQUAL_HEX8_ARRAY(VEC_ACK_CLAMPED, &a, sizeof a);
}

/* The ack_clamped vector is exactly what the board must answer to cue_near_left with
 * default profile at the deadline: -12 dB passes, but side L mutes R. Check the clamp path
 * with a louder request instead. */
static void test_decide_near_left_vector(void) {
  dt_cue_t c;
  memcpy(&c, VEC_CUE_NEAR_LEFT, sizeof c);
  dt_profile_state_t prof;
  dt_profile_default(&prof);
  dt_cue_decision_t d = dt_cue_decide(&c, c.deadline_board_us, &prof, false); /* now == deadline: still ok */
  TEST_ASSERT_EQUAL_UINT8(DT_ACK_PLAYED, d.status);
  TEST_ASSERT_EQUAL_INT8(-12, d.gain_l_db);
  TEST_ASSERT_EQUAL_INT8(DT_GAIN_MUTE, d.gain_r_db);
  TEST_ASSERT_EQUAL_UINT8(200, d.haptic_l);
  TEST_ASSERT_EQUAL_UINT8(0, d.haptic_r);
  c.gain_l_db = -3;
  d = dt_cue_decide(&c, c.deadline_board_us - 1, &prof, false);
  TEST_ASSERT_EQUAL_UINT8(DT_ACK_PLAYED_CLAMPED, d.status);
  TEST_ASSERT_EQUAL_INT8(-10, d.gain_l_db);
  TEST_ASSERT_EQUAL_INT8(-128, d.gain_r_db);
  dt_ack_t a;
  dt_build_ack(&a, 11, c.h.seq, d.status, d.gain_l_db, d.gain_r_db, VEC_ACK_CLAMPED_T_BOARD_US);
  TEST_ASSERT_EQUAL_HEX8_ARRAY(VEC_ACK_CLAMPED, &a, sizeof a);
}

/* ------------------------------------------------------------ gain clamp */

static void test_clamp_boundaries(void) {
  TEST_ASSERT_EQUAL_INT8(DT_GAIN_MUTE, dt_clamp_gain(DT_GAIN_MUTE, -10));
  TEST_ASSERT_EQUAL_INT8(DT_GAIN_MUTE, dt_clamp_gain(DT_GAIN_MUTE, -30));
  TEST_ASSERT_EQUAL_INT8(-20, dt_clamp_gain(-15, -20)); /* above profile ceiling */
  TEST_ASSERT_EQUAL_INT8(-20, dt_clamp_gain(-20, -20)); /* at ceiling unchanged */
  TEST_ASSERT_EQUAL_INT8(-21, dt_clamp_gain(-21, -20));
  TEST_ASSERT_EQUAL_INT8(-10, dt_clamp_gain(0, 0));     /* hard cap when profile ceiling higher */
  TEST_ASSERT_EQUAL_INT8(-10, dt_clamp_gain(-5, -3));
  TEST_ASSERT_EQUAL_INT8(-127, dt_clamp_gain(-127, -10));
}

static void test_profile_cannot_raise_ceiling(void) {
  dt_profile_state_t s;
  dt_profile_default(&s);
  dt_profile_t p = {{0}, 0, DT_MODE_STEREO, 255, 0};
  TEST_ASSERT_TRUE(dt_profile_apply(&s, &p));
  TEST_ASSERT_EQUAL_INT8(DT_FW_HARD_CEILING_DB, s.ceiling_db);
  p.ceiling_db = -9;
  dt_profile_apply(&s, &p);
  TEST_ASSERT_EQUAL_INT8(-10, s.ceiling_db);
  p.ceiling_db = -11;
  dt_profile_apply(&s, &p);
  TEST_ASSERT_EQUAL_INT8(-11, s.ceiling_db);
  p.mode = 3; /* unknown mode rejected, state unchanged */
  p.ceiling_db = -40;
  TEST_ASSERT_FALSE(dt_profile_apply(&s, &p));
  TEST_ASSERT_EQUAL_INT8(-11, s.ceiling_db);
}

static dt_cue_t make_cue(uint8_t id, uint8_t side, int8_t gl, int8_t gr, uint8_t pattern, uint64_t deadline) {
  dt_cue_t c;
  memset(&c, 0, sizeof c);
  dt_header_init(&c.h, DT_CUE, 1);
  c.cue_id = id;
  c.side = side;
  c.gain_l_db = gl;
  c.gain_r_db = gr;
  c.haptic_pattern = pattern;
  c.haptic_l = 250;
  c.haptic_r = 100;
  c.deadline_board_us = deadline;
  c.ttl_ms = 150;
  return c;
}

static void test_decide_profile_ceiling_and_modes(void) {
  dt_profile_state_t prof = {-25, DT_MODE_STEREO, 120};
  dt_cue_t c = make_cue(DT_CUE_OBSTACLE_MID, DT_SIDE_BOTH, -20, -30, DT_HAPTIC_PULSE60, 1000);
  dt_cue_decision_t d = dt_cue_decide(&c, 500, &prof, false);
  TEST_ASSERT_EQUAL_UINT8(DT_ACK_PLAYED_CLAMPED, d.status);
  TEST_ASSERT_EQUAL_INT8(-25, d.gain_l_db);
  TEST_ASSERT_EQUAL_INT8(-30, d.gain_r_db);
  TEST_ASSERT_EQUAL_UINT8(120, d.haptic_l); /* capped by haptic_max */
  TEST_ASSERT_EQUAL_UINT8(100, d.haptic_r);
  prof.mode = DT_MODE_HAPTIC_ONLY;
  d = dt_cue_decide(&c, 500, &prof, false);
  TEST_ASSERT_TRUE(d.play);
  TEST_ASSERT_EQUAL_INT8(DT_GAIN_MUTE, d.gain_l_db);
  TEST_ASSERT_EQUAL_INT8(DT_GAIN_MUTE, d.gain_r_db);
  TEST_ASSERT_EQUAL_UINT8(120, d.haptic_l);
  prof.mode = DT_MODE_STEREO;
  c.side = DT_SIDE_R;
  d = dt_cue_decide(&c, 500, &prof, false);
  TEST_ASSERT_EQUAL_INT8(DT_GAIN_MUTE, d.gain_l_db);
  TEST_ASSERT_EQUAL_UINT8(0, d.haptic_l);
  TEST_ASSERT_EQUAL_UINT8(100, d.haptic_r);
}

static void test_decide_deadline_busy_bad(void) {
  dt_profile_state_t prof;
  dt_profile_default(&prof);
  dt_cue_t c = make_cue(DT_CUE_OBSTACLE_NEAR, DT_SIDE_BOTH, -15, -15, DT_HAPTIC_BUZZ150, 1000);
  TEST_ASSERT_EQUAL_UINT8(DT_ACK_PLAYED, dt_cue_decide(&c, 1000, &prof, false).status);
  TEST_ASSERT_EQUAL_UINT8(DT_ACK_EXPIRED, dt_cue_decide(&c, 1001, &prof, false).status);
  TEST_ASSERT_FALSE(dt_cue_decide(&c, 1001, &prof, false).play);
  c.deadline_board_us = 0;
  TEST_ASSERT_EQUAL_UINT8(DT_ACK_NO_SYNC, dt_cue_decide(&c, 0, &prof, false).status);
  c.deadline_board_us = 1000;
  TEST_ASSERT_EQUAL_UINT8(DT_ACK_BUSY_DROPPED, dt_cue_decide(&c, 10, &prof, true).status);
  c.flags = DT_CUE_FLAG_INTERRUPT;
  TEST_ASSERT_EQUAL_UINT8(DT_ACK_PLAYED, dt_cue_decide(&c, 10, &prof, true).status);
  c.cue_id = DT_CUE_COUNT;
  TEST_ASSERT_EQUAL_UINT8(DT_ACK_BAD_CUE, dt_cue_decide(&c, 10, &prof, false).status);
  c.cue_id = 0;
  c.side = 3;
  TEST_ASSERT_EQUAL_UINT8(DT_ACK_BAD_CUE, dt_cue_decide(&c, 10, &prof, false).status);
  c.side = 0;
  c.haptic_pattern = DT_HAPTIC_COUNT;
  TEST_ASSERT_EQUAL_UINT8(DT_ACK_BAD_CUE, dt_cue_decide(&c, 10, &prof, false).status);
  /* Bad cue with expired deadline still reports BAD_CUE (validated first). */
  TEST_ASSERT_EQUAL_UINT8(DT_ACK_BAD_CUE, dt_cue_decide(&c, 5000, &prof, false).status);
}

/* ------------------------------------------------------------ cue bank timing */

static void test_every_cue_obeys_timing(void) {
  for (uint8_t id = 0; id < DT_CUE_COUNT; id++) {
    const dt_cue_def_t *c = dt_cue_def(id);
    TEST_ASSERT_NOT_NULL(c);
    const char *why = dt_cue_timing_violation(c);
    TEST_ASSERT_NULL_MESSAGE(why, c->name);
  }
  TEST_ASSERT_NULL(dt_cue_def(DT_CUE_COUNT));
}

static void test_cue_bank_semantics(void) {
  /* Arch §8.2 rates and shapes. */
  TEST_ASSERT_EQUAL_UINT16(1000, dt_cue_def(DT_CUE_OBSTACLE_FAR)->period_ms);
  TEST_ASSERT_EQUAL_UINT16(400, dt_cue_def(DT_CUE_OBSTACLE_MID)->period_ms);
  TEST_ASSERT_EQUAL_UINT16(200, dt_cue_def(DT_CUE_OBSTACLE_NEAR)->period_ms);
  TEST_ASSERT_EQUAL_UINT8(1, dt_cue_def(DT_CUE_OBSTACLE_NEAR)->note_count);
  TEST_ASSERT_EQUAL_UINT8(2, dt_cue_def(DT_CUE_OBSTACLE_FAR)->note_count);
  TEST_ASSERT_EQUAL_UINT8(2, dt_cue_def(DT_CUE_OBSTACLE_MID)->note_count);
  const dt_cue_def_t *off = dt_cue_def(DT_CUE_SYSTEM_OFF);
  TEST_ASSERT_EQUAL_UINT8(3, off->note_count);
  TEST_ASSERT_TRUE(off->notes[0].freq_hz > off->notes[1].freq_hz && off->notes[1].freq_hz > off->notes[2].freq_hz);
  const dt_cue_def_t *pf = dt_cue_def(DT_CUE_PERSON_FAR);
  TEST_ASSERT_TRUE(pf->notes[1].freq_hz > pf->notes[0].freq_hz); /* rising */
  TEST_ASSERT_EQUAL_UINT16(500, dt_cue_def(DT_CUE_CAL_TONE_500)->notes[0].freq_hz);
  TEST_ASSERT_EQUAL_UINT16(1000, dt_cue_def(DT_CUE_CAL_TONE_1K)->notes[0].freq_hz);
  TEST_ASSERT_EQUAL_UINT16(2000, dt_cue_def(DT_CUE_CAL_TONE_2K)->notes[0].freq_hz);
  TEST_ASSERT_EQUAL_UINT16(300, dt_cue_def(DT_CUE_CAL_TONE_1K)->notes[0].dur_ms);
}

static void test_timing_validator_catches_violations(void) {
  dt_cue_def_t c = *dt_cue_def(DT_CUE_OBSTACLE_NEAR);
  c.notes[0].dur_ms = 82;
  TEST_ASSERT_NOT_NULL(dt_cue_timing_violation(&c));
  c.notes[0].dur_ms = 101; /* 101 + 100 > 200 */
  TEST_ASSERT_NOT_NULL(dt_cue_timing_violation(&c));
  c.notes[0].dur_ms = 100;
  TEST_ASSERT_NULL(dt_cue_timing_violation(&c));
  c.notes[0].freq_hz = 4001;
  TEST_ASSERT_NOT_NULL(dt_cue_timing_violation(&c));
  c = *dt_cue_def(DT_CUE_OBSTACLE_MID);
  c.period_ms = 333; /* 2 notes in 333 ms = 6.006 notes/s */
  c.notes[0].dur_ms = 83; c.notes[1].dur_ms = 83; c.notes[0].gap_ms = 0;
  TEST_ASSERT_NOT_NULL(dt_cue_timing_violation(&c));
}

/* ------------------------------------------------------------ watchdog */

static void test_watchdog_boot_grace(void) {
  dt_watchdog_t w;
  dt_wd_init(&w);
  TEST_ASSERT_FALSE(dt_wd_update(&w, 0));
  TEST_ASSERT_FALSE(dt_wd_update(&w, DT_WD_BOOT_GRACE_US - 1));
  TEST_ASSERT_FALSE(w.active);
  TEST_ASSERT_TRUE(dt_wd_update(&w, DT_WD_BOOT_GRACE_US)); /* alarm on entry */
  TEST_ASSERT_TRUE(w.active);
  TEST_ASSERT_FALSE(dt_wd_update(&w, DT_WD_BOOT_GRACE_US + 4999999));
  TEST_ASSERT_TRUE(dt_wd_update(&w, DT_WD_BOOT_GRACE_US + 5000000)); /* every 5 s */
  TEST_ASSERT_TRUE(w.active);
}

static void test_watchdog_ping_timeout_and_resume(void) {
  dt_watchdog_t w;
  dt_wd_init(&w);
  dt_wd_on_ping(&w, 1000000);
  TEST_ASSERT_FALSE(dt_wd_update(&w, 1000000 + 799999));
  TEST_ASSERT_FALSE(dt_wd_update(&w, 1799999));
  TEST_ASSERT_TRUE(dt_wd_update(&w, 1800000)); /* 800 ms without PING */
  TEST_ASSERT_TRUE(w.active);
  TEST_ASSERT_FALSE(dt_wd_update(&w, 1800001));
  dt_wd_on_ping(&w, 2000000); /* resume */
  TEST_ASSERT_FALSE(w.active);
  TEST_ASSERT_FALSE(dt_wd_update(&w, 2500000));
  TEST_ASSERT_FALSE(w.active);
  /* Pings keep arriving every 100 ms: never fires, even long after boot grace. */
  for (uint64_t t = 2000000; t < 20000000; t += 100000) {
    dt_wd_on_ping(&w, t);
    TEST_ASSERT_FALSE(dt_wd_update(&w, t + 50000));
  }
  /* Re-entry alarms immediately again. */
  TEST_ASSERT_TRUE(dt_wd_update(&w, 19900000 + 800000));
}

static void test_watchdog_gain(void) {
  TEST_ASSERT_EQUAL_INT8(-20, dt_wd_gain_db(-10));
  TEST_ASSERT_EQUAL_INT8(-30, dt_wd_gain_db(-30));
  TEST_ASSERT_EQUAL_INT8(-20, dt_wd_gain_db(-20));
}

/* ------------------------------------------------------------ haptics */

static void test_haptic_patterns(void) {
  TEST_ASSERT_NULL(dt_haptic_def(DT_HAPTIC_NONE));
  TEST_ASSERT_EQUAL_UINT32(40, dt_haptic_length_ms(DT_HAPTIC_PULSE40));
  TEST_ASSERT_EQUAL_UINT32(60, dt_haptic_length_ms(DT_HAPTIC_PULSE60));
  TEST_ASSERT_EQUAL_UINT32(150, dt_haptic_length_ms(DT_HAPTIC_BUZZ150));
  TEST_ASSERT_EQUAL_UINT32(840, dt_haptic_length_ms(DT_HAPTIC_LSS));
  TEST_ASSERT_TRUE(dt_haptic_on_at(DT_HAPTIC_LSS, 399));
  TEST_ASSERT_FALSE(dt_haptic_on_at(DT_HAPTIC_LSS, 400));
  TEST_ASSERT_FALSE(dt_haptic_on_at(DT_HAPTIC_LSS, 499));
  TEST_ASSERT_TRUE(dt_haptic_on_at(DT_HAPTIC_LSS, 500));
  TEST_ASSERT_TRUE(dt_haptic_on_at(DT_HAPTIC_LSS, 619));
  TEST_ASSERT_FALSE(dt_haptic_on_at(DT_HAPTIC_LSS, 620));
  TEST_ASSERT_TRUE(dt_haptic_on_at(DT_HAPTIC_LSS, 839));
  TEST_ASSERT_FALSE(dt_haptic_on_at(DT_HAPTIC_LSS, 840));
  TEST_ASSERT_FALSE(dt_haptic_on_at(DT_HAPTIC_PULSE40, 40));
  TEST_ASSERT_EQUAL_UINT8(180, dt_haptic_cap(255, 180));
  TEST_ASSERT_EQUAL_UINT8(100, dt_haptic_cap(100, 180));
}

/* ------------------------------------------------------------ ToF transform */

static void test_tof_identity_and_no_target(void) {
  int16_t mm[DT_ZONES];
  uint8_t st[DT_ZONES], nb[DT_ZONES], ost[DT_ZONES];
  uint16_t omm[DT_ZONES];
  for (int i = 0; i < DT_ZONES; i++) { mm[i] = (int16_t)(i * 10); st[i] = 5; nb[i] = 1; }
  nb[3] = 0;
  mm[4] = -7;
  st[5] = 9;
  dt_tof_transform(mm, st, nb, false, false, 0, omm, ost);
  TEST_ASSERT_EQUAL_UINT16(0, omm[3]);
  TEST_ASSERT_EQUAL_UINT8(DT_STATUS_NO_TARGET, ost[3]);
  TEST_ASSERT_EQUAL_UINT16(0, omm[4]);
  TEST_ASSERT_EQUAL_UINT8(9, ost[5]);
  TEST_ASSERT_EQUAL_UINT16(630, omm[63]);
}

static void test_tof_flips_and_rotation(void) {
  /* raw (r=0,c=1) = index 1 */
  TEST_ASSERT_EQUAL_UINT8(6, dt_tof_dest_index(1, true, false, 0));     /* (0,6) */
  TEST_ASSERT_EQUAL_UINT8(57, dt_tof_dest_index(1, false, true, 0));    /* (7,1) */
  TEST_ASSERT_EQUAL_UINT8(62, dt_tof_dest_index(1, true, true, 0));     /* (7,6) */
  TEST_ASSERT_EQUAL_UINT8(15, dt_tof_dest_index(1, false, false, 1));   /* cw: (1,7) */
  TEST_ASSERT_EQUAL_UINT8(62, dt_tof_dest_index(1, false, false, 2));   /* 180: (7,6) */
  TEST_ASSERT_EQUAL_UINT8(48, dt_tof_dest_index(1, false, false, 3));   /* ccw: (6,0) */
  TEST_ASSERT_EQUAL_UINT8(1, dt_tof_dest_index(1, false, false, 4));    /* wraps */
  /* every transform is a permutation */
  for (int f = 0; f < 16; f++) {
    uint8_t seen[DT_ZONES] = {0};
    for (uint8_t i = 0; i < DT_ZONES; i++) seen[dt_tof_dest_index(i, f & 1, f & 2, (uint8_t)(f >> 2))]++;
    for (int i = 0; i < DT_ZONES; i++) TEST_ASSERT_EQUAL_UINT8(1, seen[i]);
  }
  /* full transform moves data and status together */
  int16_t mm[DT_ZONES] = {0};
  uint8_t st[DT_ZONES] = {0}, nb[DT_ZONES], ost[DT_ZONES];
  uint16_t omm[DT_ZONES];
  memset(nb, 1, sizeof nb);
  mm[1] = 1234;
  st[1] = 9;
  dt_tof_transform(mm, st, nb, true, false, 0, omm, ost);
  TEST_ASSERT_EQUAL_UINT16(1234, omm[6]);
  TEST_ASSERT_EQUAL_UINT8(9, ost[6]);
}

/* ------------------------------------------------------------ synth + gain */

static void test_db_to_q15(void) {
  TEST_ASSERT_EQUAL_INT32(0, dt_db_to_q15(DT_GAIN_MUTE));
  TEST_ASSERT_EQUAL_INT32(32768, dt_db_to_q15(0));
  TEST_ASSERT_EQUAL_INT32(32768, dt_db_to_q15(5));
  TEST_ASSERT_INT32_WITHIN(2, 10362, dt_db_to_q15(-10)); /* 0.3162 */
  TEST_ASSERT_INT32_WITHIN(2, 3277, dt_db_to_q15(-20));
  TEST_ASSERT_TRUE(dt_db_to_q15(-90) > 0); /* below ~-96 dB Q15 rounds to 0 */
  for (int db = -127; db < 0; db++) TEST_ASSERT_TRUE(dt_db_to_q15((int8_t)db) <= dt_db_to_q15((int8_t)(db + 1)));
  for (int db = -60; db < 0; db++) TEST_ASSERT_TRUE(dt_db_to_q15((int8_t)db) < dt_db_to_q15((int8_t)(db + 1)));
}

static void test_scale_saturates(void) {
  TEST_ASSERT_EQUAL_INT16(32767, dt_scale_sample(1.5f, 32768));
  TEST_ASSERT_EQUAL_INT16(-32768, dt_scale_sample(-1.5f, 32768));
  TEST_ASSERT_EQUAL_INT16(0, dt_scale_sample(1.0f, 0));
}

static void test_synth_bounds_and_gain(void) {
  static int16_t buf[2 * 30000];
  const uint32_t rate = 22050;
  for (uint8_t id = 0; id < DT_CUE_COUNT; id++) {
    const dt_cue_def_t *c = dt_cue_def(id);
    for (uint32_t i = 0; i < c->note_count; i++)
      for (uint32_t n = 0; n < (uint32_t)c->notes[i].dur_ms * rate / 1000u; n++) {
        float s = dt_note_sample(&c->notes[i], n, rate);
        TEST_ASSERT_TRUE_MESSAGE(fabsf(s) <= 1.0f, c->name);
      }
    int32_t gl = dt_db_to_q15(-10), gr = dt_db_to_q15(-30);
    float lim_l = 32767.0f * gl / 32768.0f + 1.0f, lim_r = 32767.0f * gr / 32768.0f + 1.0f;
    dt_player_t p;
    dt_player_start(&p, c, rate, gl, gr);
    size_t n = dt_player_render(&p, buf, 30000);
    TEST_ASSERT_TRUE(p.done);
    /* rendered length == cue length (notes + internal gaps), within rounding */
    uint32_t expect = dt_cue_length_ms(c) * rate / 1000u;
    TEST_ASSERT_UINT32_WITHIN(c->note_count * 2u, expect, (uint32_t)n);
    int peak_l = 0, peak_r = 0;
    for (size_t i = 0; i < n; i++) {
      int l = abs(buf[2 * i]), r = abs(buf[2 * i + 1]);
      if (l > peak_l) peak_l = l;
      if (r > peak_r) peak_r = r;
    }
    TEST_ASSERT_TRUE_MESSAGE(peak_l <= lim_l, c->name);
    TEST_ASSERT_TRUE_MESSAGE(peak_r <= lim_r, c->name);
    TEST_ASSERT_TRUE_MESSAGE(peak_l > 500, c->name); /* audible: not silently broken */
    TEST_ASSERT_TRUE(peak_r < peak_l);
  }
  /* mute renders exact silence */
  dt_player_t p;
  dt_player_start(&p, dt_cue_def(DT_CUE_OBSTACLE_NEAR), rate, dt_db_to_q15(DT_GAIN_MUTE), 0);
  size_t n = dt_player_render(&p, buf, 30000);
  for (size_t i = 0; i < 2 * n; i++) TEST_ASSERT_EQUAL_INT16(0, buf[i]);
}

static void test_player_chunked_equals_whole(void) {
  static int16_t a[2 * 20000], b[2 * 20000];
  const dt_cue_def_t *c = dt_cue_def(DT_CUE_SYSTEM_OFF);
  dt_player_t p;
  dt_player_start(&p, c, 22050, 32768, 16384);
  size_t na = dt_player_render(&p, a, 20000);
  dt_player_start(&p, c, 22050, 32768, 16384);
  size_t nb = 0;
  while (!p.done) nb += dt_player_render(&p, b + 2 * nb, 128);
  TEST_ASSERT_EQUAL_size_t(na, nb);
  TEST_ASSERT_EQUAL_INT16_ARRAY(a, b, 2 * na);
  /* tone ramps: first sample of a calibration tone is silent (5 ms ramp) */
  TEST_ASSERT_EQUAL_FLOAT(0.0f, dt_note_sample(&dt_cue_def(DT_CUE_CAL_TONE_1K)->notes[0], 0, 22050));
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
  RUN_TEST(test_vector_build_pong);
  RUN_TEST(test_vector_build_ack);
  RUN_TEST(test_decide_near_left_vector);
  RUN_TEST(test_clamp_boundaries);
  RUN_TEST(test_profile_cannot_raise_ceiling);
  RUN_TEST(test_decide_profile_ceiling_and_modes);
  RUN_TEST(test_decide_deadline_busy_bad);
  RUN_TEST(test_every_cue_obeys_timing);
  RUN_TEST(test_cue_bank_semantics);
  RUN_TEST(test_timing_validator_catches_violations);
  RUN_TEST(test_watchdog_boot_grace);
  RUN_TEST(test_watchdog_ping_timeout_and_resume);
  RUN_TEST(test_watchdog_gain);
  RUN_TEST(test_haptic_patterns);
  RUN_TEST(test_tof_identity_and_no_target);
  RUN_TEST(test_tof_flips_and_rotation);
  RUN_TEST(test_db_to_q15);
  RUN_TEST(test_scale_saturates);
  RUN_TEST(test_synth_bounds_and_gain);
  RUN_TEST(test_player_chunked_equals_whole);
  return UNITY_END();
}
