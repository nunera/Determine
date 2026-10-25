// Cue board pure logic: no Arduino / ESP-IDF dependencies, so everything here also
// compiles and is unit-tested on the host (`pio test -e native`).
//
// v2: the board produces NO sound. The phone renders cue audio itself (bone-conduction
// Bluetooth headset); cue ids live on only to validate CUEs and for ACK semantics. The
// board's output is haptics alone, so this module now contains: haptic pattern timing,
// CUE/PROFILE decision (PROTOCOL.md CUE/ACK, wire v2), watchdog state machine (haptic
// LSS on phone loss) and packet builders.
#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "determine_wire.h"

#ifdef __cplusplus
extern "C" {
#endif

/* ------------------------------------------------------------------ haptics */

#define DT_HAPTIC_MAX_SEGMENTS 5
typedef struct {
  uint8_t count;
  uint16_t seg_ms[DT_HAPTIC_MAX_SEGMENTS]; /* alternating on, off, on, ... starting with on */
} dt_haptic_def_t;

const dt_haptic_def_t *dt_haptic_def(uint8_t pattern); /* NULL for NONE / unknown */
uint32_t dt_haptic_length_ms(uint8_t pattern);
/* True when the motor should be driven at t_ms after pattern start. */
bool dt_haptic_on_at(uint8_t pattern, uint32_t t_ms);

/* ------------------------------------------------------------------ profile + CUE decision */

typedef struct {
  uint8_t mode;      /* dt_mode; stored for the phone's audio-route semantics only */
  uint8_t haptic_max; /* caps every haptic intensity the board plays */
} dt_profile_state_t;

/* Boot default: STEREO, full haptics. The board plays haptics regardless of mode. */
void dt_profile_default(dt_profile_state_t *p);
/* Applies a PROFILE packet body. Unknown modes and haptic_max == 0 are rejected (returns
 * false, profile unchanged): the watchdog LSS is the board's only failure announcement and
 * must stay drivable. Mode is stored but never mutes board haptics (PROTOCOL.md PROFILE v2). */
bool dt_profile_apply(dt_profile_state_t *p, const dt_profile_t *pkt);

/* Modulino Vibro power (0..50, Arduino_Modulino's MAXIMUM) for a 0..255 intensity: 0 stays
 * 0, any non-zero level drives at least 1, 255 maps to 50, monotonic. Plain scaling would
 * turn the minimum profile (haptic_max 1) into 0 and silence the watchdog alarm. */
uint8_t dt_vibro_power(uint8_t level);

typedef struct {
  uint8_t status;   /* dt_ack_status */
  bool play;        /* haptic output should be produced (pattern != NONE) */
  uint8_t haptic_l; /* capped intensities, 0 when side excludes it */
  uint8_t haptic_r;
} dt_cue_decision_t;

/* Decides what to do with a CUE (PROTOCOL.md CUE/ACK v2):
 * BAD_CUE (unknown id/side/pattern) -> NO_SYNC / EXPIRED (dt_cue_time_check) ->
 * BUSY_DROPPED (haptic still playing and no interrupt flag) -> APPLIED / APPLIED_CLAMPED.
 * APPLIED_CLAMPED iff the profile haptic_max lowered a requested intensity; side
 * exclusion alone stays APPLIED. A CUE with haptic_pattern NONE (phone-side audio only)
 * is always acknowledged APPLIED and never counts as busy. */
dt_cue_decision_t dt_cue_decide(const dt_cue_t *cue, uint64_t now_board_us, const dt_profile_state_t *prof,
                                bool output_busy);

/* ------------------------------------------------------------------ watchdog */

#define DT_WD_PING_TIMEOUT_US 800000ull
#define DT_WD_BOOT_GRACE_US 5000000ull
#define DT_WD_ALARM_PERIOD_US 5000000ull

typedef struct {
  bool active;
  bool ever_pinged;
  uint64_t last_ping_us;
  uint64_t last_alarm_us;
} dt_watchdog_t;

void dt_wd_init(dt_watchdog_t *w);
void dt_wd_on_ping(dt_watchdog_t *w, uint64_t now_us); /* exits WATCHDOG immediately */
/* Advances the state machine. Returns true when the LSS haptic alarm must be
 * played now (on entry and every 5 s while active). */
bool dt_wd_update(dt_watchdog_t *w, uint64_t now_us);

/* ------------------------------------------------------------------ ToF status mapping */

/* Maps a raw VL53L4ED range_status to the wire status: 0 -> VALID; 2 (signal below threshold,
 * nothing seen) and 4 (phase out of limits, beyond range) -> NO_TARGET; every other value
 * (wrap-around, saturation, sigma/min-range failures, ...) -> UNKNOWN. NO_TARGET means the
 * sensor reported no target within range, never "clear". dt_build_range zeroes mm for
 * anything but VALID. */
uint8_t dt_range_status_from_tof(uint8_t raw_status);

/* ------------------------------------------------------------------ packet builders */

void dt_build_beat(dt_beat_t *b, uint32_t seq, uint8_t reset_reason, uint16_t faults, uint64_t t_us,
                   int8_t rssi_dbm, int8_t temp_c, uint16_t heap_kb);
/* Single forward zone (wire v2). mm = 0 when status != DT_RANGE_VALID. */
void dt_build_range(dt_range_t *r, uint32_t seq, uint64_t t_us, uint16_t mm, uint8_t status);
void dt_build_pong(dt_pong_t *p, uint32_t seq, uint64_t t_phone_us, uint64_t rx_us, uint64_t tx_us);
void dt_build_ack(dt_ack_t *a, uint32_t seq, uint32_t cue_seq, uint8_t status, uint64_t t_board_us);

#ifdef __cplusplus
}
#endif
