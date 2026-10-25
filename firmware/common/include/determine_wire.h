// Determine wire protocol v2 — C structs for both boards. Spec: protocol/PROTOCOL.md.
// Both ESP32 targets (and the host used for native tests) are little-endian, so packed
// structs map 1:1 onto the wire. Never add fields without bumping DT_VERSION.
//
// v2: the cue board is haptic-only. RANGE is ONE forward VL53L4CD reading (no grid, no
// resolution, no mounting transform); CUE carries no audio gains; ACK reports no applied
// gains; PROFILE is mode + haptic_max. All audio is synthesised by the phone.
#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>

#if defined(__BYTE_ORDER__) && __BYTE_ORDER__ != __ORDER_LITTLE_ENDIAN__
#error "determine_wire.h assumes a little-endian target"
#endif

#define DT_PACKED __attribute__((packed))

#ifdef __cplusplus
#define DT_STATIC_ASSERT static_assert
#else
#define DT_STATIC_ASSERT _Static_assert
#endif

#define DT_MAGIC 0x5444u
#define DT_VERSION 2u

#define DT_PORT_PHONE 47000
#define DT_PORT_CUE 47001
#define DT_PORT_CAM 47002
/* PROFILE.haptic_max minimum: with no board audio the temple haptics are the last local failure
 * channel (watchdog LSS), so 0 is never a valid ceiling — the board rejects it with ACK BAD_CUE. */
#define DT_HAPTIC_MAX_MIN 1u

#define DT_MAX_CHUNK 1400

enum dt_type {
  DT_BEAT = 0x01,
  DT_RANGE = 0x02,
  DT_FRAME = 0x03,
  DT_PING = 0x10,
  DT_PONG = 0x11,
  DT_CUE = 0x20,
  DT_ACK = 0x21,
  DT_PROFILE = 0x22,
};

enum dt_board { DT_BOARD_CAM = 1, DT_BOARD_CUE = 2 };
enum dt_side { DT_SIDE_L = 0, DT_SIDE_R = 1, DT_SIDE_BOTH = 2 };
/* Phone audio-profile semantics only: the board is always haptic-only (protocol v2). */
enum dt_mode { DT_MODE_STEREO = 0, DT_MODE_SSD = 1, DT_MODE_HAPTIC_ONLY = 2 };

/* Canonical RANGE status. The VL53L4CD board maps raw 0 -> VALID, all other statuses
 * -> UNKNOWN, including no return. It cannot positively establish NO_TARGET. */
enum dt_range_status {
  DT_RANGE_VALID = 0,
  DT_RANGE_NO_TARGET = 1,
  DT_RANGE_UNKNOWN = 2,
};

enum dt_ack_status {
  DT_ACK_APPLIED = 0,
  DT_ACK_EXPIRED = 1,
  DT_ACK_APPLIED_CLAMPED = 2,
  DT_ACK_NO_SYNC = 3,
  DT_ACK_BAD_CUE = 4,
  DT_ACK_BUSY_DROPPED = 5,
};

enum dt_fault {
  DT_FAULT_SENSOR_INIT = 1u << 0,
  DT_FAULT_SENSOR_READ = 1u << 1,
  /* bit 2 reserved: was I2S_ERR; the board makes no sound in v2 and must send 0 */
  DT_FAULT_HAPTIC_L = 1u << 3,
  DT_FAULT_HAPTIC_R = 1u << 4,
  DT_FAULT_BROWNOUT = 1u << 5,
  DT_FAULT_NO_CLOCK_SYNC = 1u << 6,
  DT_FAULT_WATCHDOG = 1u << 7,
  DT_FAULT_LOW_HEAP = 1u << 8,
  DT_FAULT_CAPTURE = 1u << 9,
};

enum dt_cue_id {
  DT_CUE_READY = 0,
  DT_CUE_OBSTACLE_FAR = 1,
  DT_CUE_OBSTACLE_MID = 2,
  DT_CUE_OBSTACLE_NEAR = 3,
  DT_CUE_PERSON_FAR = 4,
  DT_CUE_PERSON_MID = 5,
  DT_CUE_PERSON_NEAR = 6,
  DT_CUE_SYSTEM_OFF = 7,
  DT_CUE_CAL_TONE_500 = 8,
  DT_CUE_CAL_TONE_1K = 9,
  DT_CUE_CAL_TONE_2K = 10,
  DT_CUE_CAL_OBSTACLE = 11,
  DT_CUE_COUNT = 12,
};

enum dt_haptic {
  DT_HAPTIC_NONE = 0,
  DT_HAPTIC_PULSE40 = 1,
  DT_HAPTIC_PULSE60 = 2,
  DT_HAPTIC_BUZZ150 = 3,
  DT_HAPTIC_LSS = 4,
  DT_HAPTIC_COUNT = 5,
};

#define DT_CUE_FLAG_INTERRUPT 0x01u

typedef struct DT_PACKED {
  uint16_t magic;
  uint8_t ver;
  uint8_t type;
  uint32_t seq;
} dt_header_t;

typedef struct DT_PACKED {
  dt_header_t h;
  uint8_t board;
  uint8_t reset_reason;
  uint16_t faults;
  uint64_t t_us;
  int8_t rssi_dbm;
  int8_t temp_c;
  uint16_t heap_kb;
} dt_beat_t;

/* One forward ToF reading (single-zone VL53L4CD). mm is 0 unless status is DT_RANGE_VALID. */
typedef struct DT_PACKED {
  dt_header_t h;
  uint64_t t_us;
  uint16_t mm;
  uint8_t status; /* enum dt_range_status */
} dt_range_t;

typedef struct DT_PACKED {
  dt_header_t h;
  uint32_t frame_id;
  uint64_t t_cap_us;
  uint16_t w;
  uint16_t hgt;
  uint32_t total_len;
  uint32_t offset;
  uint16_t chunk_len;
  uint16_t chunk_idx;
  uint16_t chunk_count;
  /* followed by chunk_len bytes */
} dt_frame_hdr_t;

typedef struct DT_PACKED {
  dt_header_t h;
  uint64_t t_phone_us;
} dt_ping_t;

typedef struct DT_PACKED {
  dt_header_t h;
  uint64_t t_phone_us;
  uint64_t t_board_rx_us;
  uint64_t t_board_tx_us;
} dt_pong_t;

/* Haptic command; the phone owns all audio. ttl_ms is advisory: the phone already folded it
 * into deadline_board_us. haptic_l/haptic_r are clamped to the profile's haptic_max. */
typedef struct DT_PACKED {
  dt_header_t h;
  uint64_t t_issued_phone_us;
  uint64_t deadline_board_us;
  uint8_t cue_id;
  uint8_t side;
  uint8_t haptic_pattern;
  uint8_t haptic_l;
  uint8_t haptic_r;
  uint8_t flags;
  uint16_t ttl_ms;
} dt_cue_t;

typedef struct DT_PACKED {
  dt_header_t h;
  uint32_t cue_seq;
  uint8_t status; /* enum dt_ack_status */
  uint8_t reserved;
  uint64_t t_board_us;
} dt_ack_t;

typedef struct DT_PACKED {
  dt_header_t h;
  uint8_t mode;       /* enum dt_mode; phone semantics only, the board is haptic-only */
  uint8_t haptic_max; /* ceiling the board clamps CUE haptic_l/haptic_r to */
} dt_profile_t;

DT_STATIC_ASSERT(sizeof(dt_header_t) == 8, "header");
DT_STATIC_ASSERT(sizeof(dt_beat_t) == 24, "beat");
DT_STATIC_ASSERT(sizeof(dt_range_t) == 19, "range");
DT_STATIC_ASSERT(sizeof(dt_frame_hdr_t) == 38, "frame header");
DT_STATIC_ASSERT(sizeof(dt_ping_t) == 16, "ping");
DT_STATIC_ASSERT(sizeof(dt_pong_t) == 32, "pong");
DT_STATIC_ASSERT(sizeof(dt_cue_t) == 32, "cue");
DT_STATIC_ASSERT(sizeof(dt_ack_t) == 22, "ack");
DT_STATIC_ASSERT(sizeof(dt_profile_t) == 10, "profile");

static inline void dt_header_init(dt_header_t *h, uint8_t type, uint32_t seq) {
  h->magic = DT_MAGIC;
  h->ver = DT_VERSION;
  h->type = type;
  h->seq = seq;
}

/* Returns the packet type if the header is valid and the length matches that type, else 0. */
static inline uint8_t dt_validate(const uint8_t *buf, size_t len) {
  dt_header_t h;
  if (len < sizeof h) return 0;
  memcpy(&h, buf, sizeof h);
  if (h.magic != DT_MAGIC || h.ver != DT_VERSION) return 0;
  switch (h.type) {
    case DT_BEAT: return len == sizeof(dt_beat_t) ? h.type : 0;
    case DT_RANGE: return len == sizeof(dt_range_t) ? h.type : 0;
    case DT_PING: return len == sizeof(dt_ping_t) ? h.type : 0;
    case DT_PONG: return len == sizeof(dt_pong_t) ? h.type : 0;
    case DT_CUE: return len == sizeof(dt_cue_t) ? h.type : 0;
    case DT_ACK: return len == sizeof(dt_ack_t) ? h.type : 0;
    case DT_PROFILE: return len == sizeof(dt_profile_t) ? h.type : 0;
    case DT_FRAME: {
      dt_frame_hdr_t f;
      if (len < sizeof f) return 0;
      memcpy(&f, buf, sizeof f);
      return (f.chunk_len <= DT_MAX_CHUNK && len == sizeof f + f.chunk_len) ? h.type : 0;
    }
    default: return 0;
  }
}

/* Haptic intensity the board actually applies: never above the profile ceiling. */
static inline uint8_t dt_clamp_haptic(uint8_t requested, uint8_t haptic_max) {
  return requested > haptic_max ? haptic_max : requested;
}

/* A CUE may fire only with clock sync and before its deadline (board clock). */
static inline uint8_t dt_cue_time_check(const dt_cue_t *c, uint64_t now_board_us) {
  if (c->deadline_board_us == 0) return DT_ACK_NO_SYNC;
  if (now_board_us > c->deadline_board_us) return DT_ACK_EXPIRED;
  return DT_ACK_APPLIED;
}

/* A PROFILE is applicable only with a known mode and haptic_max in 1..255; otherwise ACK BAD_CUE
 * and the previous profile stays in force. */
static inline bool dt_profile_valid(const dt_profile_t *p) {
  return p->mode <= DT_MODE_HAPTIC_ONLY && p->haptic_max >= DT_HAPTIC_MAX_MIN;
}
