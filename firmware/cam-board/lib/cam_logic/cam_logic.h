// Hardware-free camera-board logic: FRAME chunk planning, packet builders, frame pacing and
// the byte-rate limiter. Compiled for the ESP32-CAM and for [env:native] unit tests.
// Wire contract: protocol/PROTOCOL.md (v2).
#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "determine_wire.h"

#ifdef __cplusplus
extern "C" {
#endif

/* ---- Chunk planner --------------------------------------------------------------------- */

typedef struct {
  uint32_t offset;
  uint16_t len;
  uint16_t idx;
  uint16_t count;
} dt_chunk_t;

/* Number of FRAME chunks for a JPEG of total_len bytes. An empty frame still produces one
 * zero-length chunk (golden vector frame_empty_chunk). Returns 0 if the frame would need more
 * than 65535 chunks (u16 chunk_count overflow); such a frame must be dropped. */
uint16_t dt_chunk_count(uint32_t total_len);

/* Fills *out for chunk idx of a total_len-byte frame. Returns false if idx is out of range. */
bool dt_chunk_plan(uint32_t total_len, uint16_t idx, dt_chunk_t *out);

/* ---- Packet builders (byte-exact with protocol/vectors/vectors.json) ------------------- */

typedef struct {
  uint32_t frame_id;
  uint64_t t_cap_us;
  uint16_t w;
  uint16_t h;
  uint32_t total_len;
} dt_frame_meta_t;

/* Writes header + chunk data into out. Returns the packet length, or 0 if cap is too small or
 * the chunk is invalid (len > DT_MAX_CHUNK). */
size_t dt_build_frame(uint8_t *out, size_t cap, uint32_t seq, const dt_frame_meta_t *m,
                      const dt_chunk_t *c, const uint8_t *chunk_data);

typedef struct {
  uint8_t board;
  uint8_t reset_reason;
  uint16_t faults;
  uint64_t t_us;
  int8_t rssi_dbm;
  int8_t temp_c;
  uint16_t heap_kb;
} dt_beat_fields_t;

/* Always writes sizeof(dt_beat_t) bytes. */
size_t dt_build_beat(uint8_t *out, uint32_t seq, const dt_beat_fields_t *f);

/* Parses a received datagram as PING. Returns true and sets *t_phone_us only for a valid PING. */
bool dt_parse_ping(const uint8_t *buf, size_t len, uint64_t *t_phone_us);

/* Always writes sizeof(dt_pong_t) bytes. */
size_t dt_build_pong(uint8_t *out, uint32_t seq, uint64_t t_phone_us, uint64_t t_board_rx_us,
                     uint64_t t_board_tx_us);

/* Heap in kB for BEAT, saturating at 65535. */
uint16_t dt_heap_kb(uint32_t free_bytes);

/* ---- Frame pacer ----------------------------------------------------------------------- */
/* Grants at most one capture per period. When the loop runs late it never "catches up" with
 * a burst: the schedule re-anchors to now, so stale frames are never queued (drop, don't
 * backlog). */
typedef struct {
  uint64_t period_us;
  uint64_t next_us;
  bool started;
  uint32_t skipped; /* periods missed because the loop was late */
} dt_pacer_t;

void dt_pacer_init(dt_pacer_t *p, uint32_t fps);
/* True if a frame may be captured now; on true the next slot is scheduled. */
bool dt_pacer_due(dt_pacer_t *p, uint64_t now_us);
/* Microseconds until the next slot (0 if due). */
uint64_t dt_pacer_wait_us(const dt_pacer_t *p, uint64_t now_us);

/* ---- Byte-rate limiter (token bucket) -------------------------------------------------- */
/* Caps the camera's UDP payload rate so the shared hotspot airtime leaves room for the cue
 * board. A frame is admitted whole or dropped whole: a partial frame is useless to the phone. */
typedef struct {
  uint64_t rate_bps;  /* bytes per second */
  uint64_t burst;     /* bucket capacity, bytes */
  uint64_t tokens_x1e6; /* tokens scaled by 1e6 for integer accuracy */
  uint64_t last_us;
  bool started;
  uint32_t dropped;
} dt_ratelimit_t;

void dt_ratelimit_init(dt_ratelimit_t *r, uint32_t max_kbytes_per_s, uint32_t burst_bytes);
/* Admits bytes if the bucket holds them (and consumes), else counts a drop and returns false. */
bool dt_ratelimit_admit(dt_ratelimit_t *r, uint32_t bytes, uint64_t now_us);

/* Wire bytes for a whole frame (headers + data) — what the rate limiter charges. */
uint32_t dt_frame_wire_bytes(uint32_t total_len);

/* ---- Inbound classification ------------------------------------------------------------ */
typedef enum {
  DT_RX_INVALID = 0, /* bad magic/version/type/length */
  DT_RX_PING,
  DT_RX_IGNORED,     /* valid packet the camera board never acts on (CUE, PROFILE, …) */
} dt_rx_kind_t;

dt_rx_kind_t dt_cam_classify(const uint8_t *buf, size_t len);

#ifdef __cplusplus
}
#endif
