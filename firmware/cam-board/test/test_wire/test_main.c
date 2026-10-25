// Native Unity tests for the camera board's hardware-free logic.
#include <stdlib.h>
#include <string.h>
#include <unity.h>

#include "cam_logic.h"
#include "vectors_gen.h"

void setUp(void) {}
void tearDown(void) {}

/* ---------- golden vectors ---------- */

static void check_frame_vector(const uint8_t *vec, size_t vlen, uint32_t seq, uint32_t frame_id,
                               uint64_t t_cap, uint32_t w, uint32_t h, uint32_t total,
                               uint32_t off, uint32_t idx, uint32_t count, const uint8_t *data,
                               uint16_t dlen) {
  TEST_ASSERT_EQUAL_UINT8(DT_FRAME, dt_validate(vec, vlen));
  dt_frame_meta_t m = {frame_id, t_cap, (uint16_t)w, (uint16_t)h, total};
  dt_chunk_t c = {off, dlen, (uint16_t)idx, (uint16_t)count};
  uint8_t out[1500];
  size_t n = dt_build_frame(out, sizeof out, seq, &m, &c, data);
  TEST_ASSERT_EQUAL_size_t(vlen, n);
  TEST_ASSERT_EQUAL_HEX8_ARRAY(vec, out, vlen);
  /* decode direction: fields read back from the golden bytes */
  dt_frame_hdr_t f;
  memcpy(&f, vec, sizeof f);
  TEST_ASSERT_EQUAL_UINT32(frame_id, f.frame_id);
  TEST_ASSERT_EQUAL_UINT64(t_cap, f.t_cap_us);
  TEST_ASSERT_EQUAL_UINT32(total, f.total_len);
  TEST_ASSERT_EQUAL_UINT32(off, f.offset);
  TEST_ASSERT_EQUAL_UINT16(dlen, f.chunk_len);
  TEST_ASSERT_EQUAL_UINT16(count, f.chunk_count);
  TEST_ASSERT_EQUAL_UINT32(seq, f.h.seq);
}

static void test_vector_frame_chunk(void) {
  check_frame_vector(VEC_FRAME_CHUNK, VEC_FRAME_CHUNK_LEN, VEC_FRAME_CHUNK_SEQ,
                     VEC_FRAME_CHUNK_FRAME_ID, VEC_FRAME_CHUNK_T_CAP_US, VEC_FRAME_CHUNK_W,
                     VEC_FRAME_CHUNK_H, VEC_FRAME_CHUNK_TOTAL_LEN, VEC_FRAME_CHUNK_OFFSET,
                     VEC_FRAME_CHUNK_CHUNK_IDX, VEC_FRAME_CHUNK_CHUNK_COUNT, VEC_FRAME_CHUNK_DATA,
                     VEC_FRAME_CHUNK_CHUNK_LEN);
}

static void test_vector_frame_empty(void) {
  check_frame_vector(VEC_FRAME_EMPTY_CHUNK, VEC_FRAME_EMPTY_CHUNK_LEN, VEC_FRAME_EMPTY_CHUNK_SEQ,
                     VEC_FRAME_EMPTY_CHUNK_FRAME_ID, VEC_FRAME_EMPTY_CHUNK_T_CAP_US,
                     VEC_FRAME_EMPTY_CHUNK_W, VEC_FRAME_EMPTY_CHUNK_H,
                     VEC_FRAME_EMPTY_CHUNK_TOTAL_LEN, VEC_FRAME_EMPTY_CHUNK_OFFSET,
                     VEC_FRAME_EMPTY_CHUNK_CHUNK_IDX, VEC_FRAME_EMPTY_CHUNK_CHUNK_COUNT,
                     VEC_FRAME_EMPTY_CHUNK_DATA, VEC_FRAME_EMPTY_CHUNK_CHUNK_LEN);
  /* the planner produces exactly the vector's single empty chunk for a 0-byte frame */
  dt_chunk_t c;
  TEST_ASSERT_TRUE(dt_chunk_plan(0, 0, &c));
  TEST_ASSERT_EQUAL_UINT16(1, c.count);
  TEST_ASSERT_EQUAL_UINT16(0, c.len);
  TEST_ASSERT_EQUAL_UINT32(0, c.offset);
}

static void test_vector_beats(void) {
  uint8_t out[sizeof(dt_beat_t)];
  dt_beat_fields_t a = {VEC_BEAT_CUE_BOARD, VEC_BEAT_CUE_RESET_REASON, VEC_BEAT_CUE_FAULTS,
                        VEC_BEAT_CUE_T_US, VEC_BEAT_CUE_RSSI_DBM, VEC_BEAT_CUE_TEMP_C,
                        VEC_BEAT_CUE_HEAP_KB};
  TEST_ASSERT_EQUAL_size_t(VEC_BEAT_CUE_LEN, dt_build_beat(out, VEC_BEAT_CUE_SEQ, &a));
  TEST_ASSERT_EQUAL_HEX8_ARRAY(VEC_BEAT_CUE, out, VEC_BEAT_CUE_LEN);

  dt_beat_fields_t b = {VEC_BEAT_CAM_UNKNOWN_TEMP_BOARD, VEC_BEAT_CAM_UNKNOWN_TEMP_RESET_REASON,
                        VEC_BEAT_CAM_UNKNOWN_TEMP_FAULTS, VEC_BEAT_CAM_UNKNOWN_TEMP_T_US,
                        VEC_BEAT_CAM_UNKNOWN_TEMP_RSSI_DBM, VEC_BEAT_CAM_UNKNOWN_TEMP_TEMP_C,
                        VEC_BEAT_CAM_UNKNOWN_TEMP_HEAP_KB};
  TEST_ASSERT_EQUAL_size_t(VEC_BEAT_CAM_UNKNOWN_TEMP_LEN,
                           dt_build_beat(out, VEC_BEAT_CAM_UNKNOWN_TEMP_SEQ, &b));
  TEST_ASSERT_EQUAL_HEX8_ARRAY(VEC_BEAT_CAM_UNKNOWN_TEMP, out, VEC_BEAT_CAM_UNKNOWN_TEMP_LEN);
  TEST_ASSERT_EQUAL_UINT16(DT_FAULT_CAPTURE, VEC_BEAT_CAM_UNKNOWN_TEMP_FAULTS);
}

static void test_vector_ping_pong(void) {
  uint64_t t = 0;
  TEST_ASSERT_TRUE(dt_parse_ping(VEC_PING, VEC_PING_LEN, &t));
  TEST_ASSERT_EQUAL_UINT64(VEC_PING_T_PHONE_US, t);
  TEST_ASSERT_EQUAL(DT_RX_PING, dt_cam_classify(VEC_PING, VEC_PING_LEN));

  uint8_t out[sizeof(dt_pong_t)];
  TEST_ASSERT_EQUAL_size_t(VEC_PONG_LEN,
                           dt_build_pong(out, VEC_PONG_SEQ, VEC_PONG_T_PHONE_US,
                                         VEC_PONG_T_BOARD_RX_US, VEC_PONG_T_BOARD_TX_US));
  TEST_ASSERT_EQUAL_HEX8_ARRAY(VEC_PONG, out, VEC_PONG_LEN);
}

static void test_ping_rejects_corruption(void) {
  uint8_t buf[32];
  uint64_t t = 7;
  memcpy(buf, VEC_PING, VEC_PING_LEN);
  TEST_ASSERT_FALSE(dt_parse_ping(buf, VEC_PING_LEN - 1, &t)); /* short */
  TEST_ASSERT_FALSE(dt_parse_ping(buf, VEC_PING_LEN + 1, &t)); /* long */
  buf[0] ^= 0xFF;                                               /* magic */
  TEST_ASSERT_FALSE(dt_parse_ping(buf, VEC_PING_LEN, &t));
  memcpy(buf, VEC_PING, VEC_PING_LEN);
  buf[2] = 1; /* obsolete version */
  TEST_ASSERT_FALSE(dt_parse_ping(buf, VEC_PING_LEN, &t));
  TEST_ASSERT_EQUAL_UINT64(7, t); /* untouched on failure */
  /* a PONG is not a PING */
  TEST_ASSERT_FALSE(dt_parse_ping(VEC_PONG, VEC_PONG_LEN, &t));
}

static void test_cue_and_profile_ignored(void) {
  TEST_ASSERT_EQUAL(DT_RX_IGNORED, dt_cam_classify(VEC_CUE_READY_LEFT, VEC_CUE_READY_LEFT_LEN));
  TEST_ASSERT_EQUAL(DT_RX_IGNORED, dt_cam_classify(VEC_CUE_NO_SYNC, VEC_CUE_NO_SYNC_LEN));
  TEST_ASSERT_EQUAL(DT_RX_IGNORED, dt_cam_classify(VEC_PROFILE_SSD, VEC_PROFILE_SSD_LEN));
  TEST_ASSERT_EQUAL(DT_RX_INVALID, dt_cam_classify(VEC_CUE_READY_LEFT, 20));
}

/* ---------- chunk planner ---------- */

static void check_plan_covers(uint32_t total) {
  uint16_t count = dt_chunk_count(total);
  TEST_ASSERT_TRUE(count > 0);
  uint32_t expect_off = 0;
  for (uint16_t i = 0; i < count; i++) {
    dt_chunk_t c;
    TEST_ASSERT_TRUE(dt_chunk_plan(total, i, &c));
    TEST_ASSERT_EQUAL_UINT16(i, c.idx);
    TEST_ASSERT_EQUAL_UINT16(count, c.count);
    TEST_ASSERT_EQUAL_UINT32(expect_off, c.offset);
    TEST_ASSERT_TRUE(c.len <= DT_MAX_CHUNK);
    if (i + 1 < count) TEST_ASSERT_EQUAL_UINT16(DT_MAX_CHUNK, c.len);
    if (total > 0) TEST_ASSERT_TRUE(c.len > 0); /* no empty trailing chunk */
    expect_off += c.len;
  }
  TEST_ASSERT_EQUAL_UINT32(total, expect_off); /* contiguous, total coverage */
  dt_chunk_t c;
  TEST_ASSERT_FALSE(dt_chunk_plan(total, count, &c));
}

static void test_chunk_boundaries(void) {
  TEST_ASSERT_EQUAL_UINT16(1, dt_chunk_count(0));
  TEST_ASSERT_EQUAL_UINT16(1, dt_chunk_count(1));
  TEST_ASSERT_EQUAL_UINT16(1, dt_chunk_count(1400));
  TEST_ASSERT_EQUAL_UINT16(2, dt_chunk_count(1401));
  TEST_ASSERT_EQUAL_UINT16(2, dt_chunk_count(2800));
  TEST_ASSERT_EQUAL_UINT16(3, dt_chunk_count(2801));
  TEST_ASSERT_EQUAL_UINT16(43, dt_chunk_count(60000));
  dt_chunk_t c;
  TEST_ASSERT_TRUE(dt_chunk_plan(1401, 1, &c));
  TEST_ASSERT_EQUAL_UINT32(1400, c.offset);
  TEST_ASSERT_EQUAL_UINT16(1, c.len);
  TEST_ASSERT_TRUE(dt_chunk_plan(1400, 0, &c));
  TEST_ASSERT_EQUAL_UINT16(1400, c.len);
  TEST_ASSERT_FALSE(dt_chunk_plan(1400, 1, &c));
  uint32_t sizes[] = {0, 1, 1399, 1400, 1401, 2800, 2801, 60000, 61440};
  for (size_t i = 0; i < sizeof sizes / sizeof sizes[0]; i++) check_plan_covers(sizes[i]);
  /* u16 chunk_count overflow → frame refused, not silently truncated */
  TEST_ASSERT_EQUAL_UINT16(0xFFFF, dt_chunk_count(0xFFFFu * DT_MAX_CHUNK));
  TEST_ASSERT_EQUAL_UINT16(0, dt_chunk_count(0xFFFFu * DT_MAX_CHUNK + 1));
  TEST_ASSERT_FALSE(dt_chunk_plan(0xFFFFu * DT_MAX_CHUNK + 1, 0, &c));
}

/* A whole 60 KB frame, built packet by packet, reassembles to the original bytes and every
 * packet passes the receiver's validator. */
static void test_large_frame_roundtrip(void) {
  const uint32_t total = 60000;
  uint8_t *jpeg = malloc(total), *re = calloc(1, total);
  for (uint32_t i = 0; i < total; i++) jpeg[i] = (uint8_t)(i * 31u + 7u);
  dt_frame_meta_t m = {42, 123456789ull, 640, 480, total};
  uint16_t count = dt_chunk_count(total);
  uint8_t pkt[sizeof(dt_frame_hdr_t) + DT_MAX_CHUNK];
  for (uint16_t i = 0; i < count; i++) {
    dt_chunk_t c;
    TEST_ASSERT_TRUE(dt_chunk_plan(total, i, &c));
    size_t n = dt_build_frame(pkt, sizeof pkt, 1000u + i, &m, &c, jpeg + c.offset);
    TEST_ASSERT_TRUE(n <= 1438);
    TEST_ASSERT_EQUAL_UINT8(DT_FRAME, dt_validate(pkt, n));
    dt_frame_hdr_t f;
    memcpy(&f, pkt, sizeof f);
    TEST_ASSERT_EQUAL_UINT32(total, f.total_len);
    memcpy(re + f.offset, pkt + sizeof f, f.chunk_len);
  }
  TEST_ASSERT_EQUAL_MEMORY(jpeg, re, total);
  /* too-small buffer refused */
  dt_chunk_t c;
  dt_chunk_plan(total, 0, &c);
  TEST_ASSERT_EQUAL_size_t(0, dt_build_frame(pkt, sizeof pkt - 1, 0, &m, &c, jpeg));
  free(jpeg);
  free(re);
}

/* ---------- pacing ---------- */

static void test_pacer_never_exceeds_fps(void) {
  dt_pacer_t p;
  dt_pacer_init(&p, 12);
  uint32_t grants = 0;
  /* loop polled every 1 ms for 10 s */
  for (uint64_t t = 0; t < 10000000ull; t += 1000) grants += dt_pacer_due(&p, t);
  TEST_ASSERT_TRUE(grants <= 121); /* 12 fps × 10 s, +1 for the first slot */
  TEST_ASSERT_TRUE(grants >= 119);
}

static void test_pacer_drops_instead_of_backlog(void) {
  dt_pacer_t p;
  dt_pacer_init(&p, 10); /* 100 ms */
  TEST_ASSERT_TRUE(dt_pacer_due(&p, 0));
  TEST_ASSERT_FALSE(dt_pacer_due(&p, 99999));
  /* loop stalls for 1 s (e.g. Wi-Fi blocked): exactly one frame, then wait a full period */
  TEST_ASSERT_TRUE(dt_pacer_due(&p, 1100000));
  TEST_ASSERT_TRUE(p.skipped >= 9);
  TEST_ASSERT_FALSE(dt_pacer_due(&p, 1100001));
  TEST_ASSERT_FALSE(dt_pacer_due(&p, 1199999));
  TEST_ASSERT_EQUAL_UINT64(1, dt_pacer_wait_us(&p, 1199999));
  TEST_ASSERT_TRUE(dt_pacer_due(&p, 1200000));
}

static void test_pacer_jitter_keeps_grid(void) {
  dt_pacer_t p;
  dt_pacer_init(&p, 10);
  TEST_ASSERT_TRUE(dt_pacer_due(&p, 0));
  TEST_ASSERT_TRUE(dt_pacer_due(&p, 130000)); /* 30 ms late, < 1 period */
  TEST_ASSERT_FALSE(dt_pacer_due(&p, 199999)); /* still on the 100 ms grid, no extra frame */
  TEST_ASSERT_TRUE(dt_pacer_due(&p, 200000));
}

/* ---------- rate limiter ---------- */

static void test_ratelimit_admits_whole_frames_under_budget(void) {
  dt_ratelimit_t r;
  dt_ratelimit_init(&r, 480, 120000); /* 480 kB/s */
  uint32_t frame = dt_frame_wire_bytes(30000); /* typical VGA JPEG */
  uint32_t sent = 0;
  for (int i = 0; i < 120; i++) { /* 12 fps × 10 s */
    if (dt_ratelimit_admit(&r, frame, (uint64_t)i * 83333ull)) sent++;
  }
  TEST_ASSERT_EQUAL_UINT32(120, sent); /* 12 × ~31 KB = 372 kB/s < 480 */
  TEST_ASSERT_EQUAL_UINT32(0, r.dropped);
}

static void test_ratelimit_drops_over_budget(void) {
  dt_ratelimit_t r;
  dt_ratelimit_init(&r, 100, 50000); /* 100 kB/s */
  uint32_t frame = 40000;
  uint64_t bytes = 0;
  for (int i = 0; i < 120; i++) {
    if (dt_ratelimit_admit(&r, frame, (uint64_t)i * 83333ull)) bytes += frame;
  }
  /* 10 s at 100 kB/s plus the initial burst bounds what went out */
  TEST_ASSERT_TRUE(bytes <= 100000ull * 10 + 50000);
  TEST_ASSERT_TRUE(bytes >= 100000ull * 10 - 2 * frame);
  TEST_ASSERT_TRUE(r.dropped > 0);
  /* a frame larger than the burst can never go out */
  TEST_ASSERT_FALSE(dt_ratelimit_admit(&r, 50001, 100000000ull));
}

static void test_frame_wire_bytes(void) {
  TEST_ASSERT_EQUAL_UINT32(38, dt_frame_wire_bytes(0));
  TEST_ASSERT_EQUAL_UINT32(1438, dt_frame_wire_bytes(1400));
  TEST_ASSERT_EQUAL_UINT32(1401 + 76, dt_frame_wire_bytes(1401));
}

static void test_heap_kb_saturates(void) {
  TEST_ASSERT_EQUAL_UINT16(0, dt_heap_kb(1023));
  TEST_ASSERT_EQUAL_UINT16(212, dt_heap_kb(212 * 1024 + 5));
  TEST_ASSERT_EQUAL_UINT16(0xFFFF, dt_heap_kb(0xFFFFFFFFu));
}

int main(void) {
  UNITY_BEGIN();
  RUN_TEST(test_vector_frame_chunk);
  RUN_TEST(test_vector_frame_empty);
  RUN_TEST(test_vector_beats);
  RUN_TEST(test_vector_ping_pong);
  RUN_TEST(test_ping_rejects_corruption);
  RUN_TEST(test_cue_and_profile_ignored);
  RUN_TEST(test_chunk_boundaries);
  RUN_TEST(test_large_frame_roundtrip);
  RUN_TEST(test_pacer_never_exceeds_fps);
  RUN_TEST(test_pacer_drops_instead_of_backlog);
  RUN_TEST(test_pacer_jitter_keeps_grid);
  RUN_TEST(test_ratelimit_admits_whole_frames_under_budget);
  RUN_TEST(test_ratelimit_drops_over_budget);
  RUN_TEST(test_frame_wire_bytes);
  RUN_TEST(test_heap_kb_saturates);
  return UNITY_END();
}
