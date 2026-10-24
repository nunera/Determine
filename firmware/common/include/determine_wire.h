// Determine wire protocol v1 — C structs for both boards. Spec: protocol/PROTOCOL.md.
// Both ESP32 targets (and the host used for native tests) are little-endian, so packed
// structs map 1:1 onto the wire. Never add fields without bumping DT_VERSION.
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
#define DT_VERSION 1u

#define DT_PORT_PHONE 47000
#define DT_PORT_CUE 47001
#define DT_PORT_CAM 47002

#define DT_MAX_CHUNK 1400
#define DT_ZONES 64
#define DT_STATUS_NO_TARGET 255
#define DT_GAIN_MUTE (-128)
#define DT_FW_HARD_CEILING_DB (-10) /* PROPOSED until transducers are characterised */
#define DT_WATCHDOG_GAIN_DB (-20)

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
enum dt_mode { DT_MODE_STEREO = 0, DT_MODE_SSD = 1, DT_MODE_HAPTIC_ONLY = 2 };

enum dt_ack_status {
  DT_ACK_PLAYED = 0,
  DT_ACK_EXPIRED = 1,
  DT_ACK_PLAYED_CLAMPED = 2,
  DT_ACK_NO_SYNC = 3,
  DT_ACK_BAD_CUE = 4,
  DT_ACK_BUSY_DROPPED = 5,
};

enum dt_fault {
  DT_FAULT_SENSOR_INIT = 1u << 0,
  DT_FAULT_SENSOR_READ = 1u << 1,
  DT_FAULT_I2S = 1u << 2,
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

typedef struct DT_PACKED {
  dt_header_t h;
  uint64_t t_us;
  uint8_t res;
  uint8_t reserved0;
  uint16_t reserved1;
  uint16_t mm[DT_ZONES];
  uint8_t status[DT_ZONES];
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

typedef struct DT_PACKED {
  dt_header_t h;
  uint64_t t_issued_phone_us;
  uint64_t deadline_board_us;
  uint8_t cue_id;
  uint8_t side;
  int8_t gain_l_db;
  int8_t gain_r_db;
  uint8_t haptic_pattern;
  uint8_t haptic_l;
  uint8_t haptic_r;
  uint8_t flags;
  uint16_t ttl_ms;
} dt_cue_t;

typedef struct DT_PACKED {
  dt_header_t h;
  uint32_t cue_seq;
  uint8_t status;
  uint8_t reserved;
  int8_t applied_gain_l_db;
  int8_t applied_gain_r_db;
  uint64_t t_board_us;
} dt_ack_t;

typedef struct DT_PACKED {
  dt_header_t h;
  int8_t ceiling_db;
  uint8_t mode;
  uint8_t haptic_max;
  uint8_t reserved;
} dt_profile_t;

DT_STATIC_ASSERT(sizeof(dt_header_t) == 8, "header");
DT_STATIC_ASSERT(sizeof(dt_beat_t) == 24, "beat");
DT_STATIC_ASSERT(sizeof(dt_range_t) == 212, "range");
DT_STATIC_ASSERT(sizeof(dt_frame_hdr_t) == 38, "frame header");
DT_STATIC_ASSERT(sizeof(dt_ping_t) == 16, "ping");
DT_STATIC_ASSERT(sizeof(dt_pong_t) == 32, "pong");
DT_STATIC_ASSERT(sizeof(dt_cue_t) == 34, "cue");
DT_STATIC_ASSERT(sizeof(dt_ack_t) == 24, "ack");
DT_STATIC_ASSERT(sizeof(dt_profile_t) == 12, "profile");

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

/* Gain the board actually applies: never above min(profile ceiling, firmware hard cap). */
static inline int8_t dt_clamp_gain(int8_t requested_db, int8_t profile_ceiling_db) {
  int8_t cap = profile_ceiling_db < DT_FW_HARD_CEILING_DB ? profile_ceiling_db : (int8_t)DT_FW_HARD_CEILING_DB;
  if (requested_db == DT_GAIN_MUTE) return DT_GAIN_MUTE;
  return requested_db > cap ? cap : requested_db;
}

/* A CUE may play only with clock sync and before its deadline (board clock). */
static inline uint8_t dt_cue_time_check(const dt_cue_t *c, uint64_t now_board_us) {
  if (c->deadline_board_us == 0) return DT_ACK_NO_SYNC;
  if (now_board_us > c->deadline_board_us) return DT_ACK_EXPIRED;
  return DT_ACK_PLAYED;
}
