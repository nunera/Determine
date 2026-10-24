// Cue board pure logic: no Arduino / ESP-IDF dependencies, so everything here also
// compiles and is unit-tested on the host (`pio test -e native`).
//
// Contents: cue bank + timing validator (Arch §8.1), note synthesis, dBFS gain maths,
// CUE/PROFILE decision (PROTOCOL.md CUE/ACK), watchdog state machine (PROTOCOL.md
// Watchdog), haptic pattern timing, ToF mounting transform, packet builders.
#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "determine_wire.h"

#ifdef __cplusplus
extern "C" {
#endif

/* ------------------------------------------------------------------ cue bank */

typedef enum {
  DT_WAVE_WOODBLOCK = 0, /* short resonant decaying burst (fundamental + inharmonic partial) */
  DT_WAVE_MARIMBA = 1,   /* decaying sine + 4th harmonic partial */
  DT_WAVE_TONE = 2,      /* pure sine, 5 ms linear ramps (calibration) */
  DT_WAVE_TICK = 3,      /* very fast decaying click-like note */
} dt_wave_t;

typedef struct {
  uint8_t wave;     /* dt_wave_t */
  uint16_t freq_hz; /* fundamental, 300..4000 Hz (Arch §8.1 register bounds) */
  uint16_t dur_ms;  /* note window incl. decay; >= 83 ms (Arch §8.1) */
  uint16_t gap_ms;  /* silence after this note (inside the cue) */
} dt_note_t;

#define DT_CUE_MAX_NOTES 3

typedef struct {
  const char *name;
  uint8_t note_count;
  dt_note_t notes[DT_CUE_MAX_NOTES];
  /* Repetition period the phone uses for this cue (Arch §8.2: FAR 1/s, MID 2.5/s,
   * NEAR 5/s, SYSTEM_OFF every 5 s). One-shot cues use 1000 ms. */
  uint16_t period_ms;
} dt_cue_def_t;

/* Timing rules, Arch §8.1 / PROTOCOL.md "Timing rule". */
#define DT_NOTE_MIN_MS 83
#define DT_INTER_CUE_GAP_MS 100
#define DT_MAX_NOTES_PER_S 6
#define DT_FREQ_MIN_HZ 300
#define DT_FREQ_MAX_HZ 4000

const dt_cue_def_t *dt_cue_def(uint8_t cue_id); /* NULL if unknown id */
/* Total audible length of one cue (notes + internal gaps, trailing gap excluded). */
uint32_t dt_cue_length_ms(const dt_cue_def_t *c);
/* Returns NULL when the cue obeys every timing rule, else a static reason string. */
const char *dt_cue_timing_violation(const dt_cue_def_t *c);

/* ------------------------------------------------------------------ gain */

/* dBFS -> linear amplitude in Q15 (32768 = 0 dBFS). -128 (mute) -> 0. Positive input is
 * treated as 0 dBFS: gains are never boosted above full scale. */
int32_t dt_db_to_q15(int8_t db);

/* ------------------------------------------------------------------ synth */

/* One sample of a note (normalised, |x| <= 1) at sample index n of the note window.
 * Returns 0 outside the note window. */
float dt_note_sample(const dt_note_t *note, uint32_t n, uint32_t rate_hz);

/* Scales a normalised sample by a Q15 gain into int16 with saturation. */
int16_t dt_scale_sample(float x, int32_t gain_q15);

typedef struct {
  const dt_cue_def_t *cue;
  uint32_t rate_hz;
  int32_t gain_l_q15;
  int32_t gain_r_q15;
  uint8_t note_idx;
  uint32_t pos; /* sample index within current note window + its gap */
  bool done;
} dt_player_t;

void dt_player_start(dt_player_t *p, const dt_cue_def_t *cue, uint32_t rate_hz, int32_t gain_l_q15,
                     int32_t gain_r_q15);
/* Renders up to `frames` interleaved L/R frames. Returns frames written (< frames only
 * when the cue finishes; remaining output is not touched). */
size_t dt_player_render(dt_player_t *p, int16_t *lr, size_t frames);

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
/* Requested intensity capped by the profile's haptic_max. */
static inline uint8_t dt_haptic_cap(uint8_t requested, uint8_t haptic_max) {
  return requested > haptic_max ? haptic_max : requested;
}

/* ------------------------------------------------------------------ profile + CUE decision */

typedef struct {
  int8_t ceiling_db; /* already clamped to <= DT_FW_HARD_CEILING_DB */
  uint8_t mode;      /* dt_mode */
  uint8_t haptic_max;
} dt_profile_state_t;

/* Boot default: hard cap ceiling, STEREO, full haptics. */
void dt_profile_default(dt_profile_state_t *p);
/* Applies a PROFILE packet body. Ceiling can only go down to/below the hard cap
 * (PROTOCOL.md PROFILE). Unknown modes are rejected (returns false, profile unchanged). */
bool dt_profile_apply(dt_profile_state_t *p, const dt_profile_t *pkt);

typedef struct {
  uint8_t status;        /* dt_ack_status */
  bool play;             /* output should be produced */
  int8_t gain_l_db;      /* applied (post clamp/side/mode); -128 = silent */
  int8_t gain_r_db;
  uint8_t haptic_l;      /* capped intensities, 0 when side excludes it */
  uint8_t haptic_r;
} dt_cue_decision_t;

/* Decides what to do with a CUE (PROTOCOL.md CUE/ACK):
 * BAD_CUE (unknown id/side/pattern) -> NO_SYNC / EXPIRED (dt_cue_time_check) ->
 * BUSY_DROPPED (output busy and no interrupt flag) -> PLAYED / PLAYED_CLAMPED.
 * Clamping uses dt_clamp_gain on the requested gains; PLAYED_CLAMPED iff clamping changed
 * a requested gain. Side and HAPTIC_ONLY mode then silence channels (reported as -128). */
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
/* Advances the state machine. Returns true when the SYSTEM_OFF + LSS alarm must be
 * played now (on entry and every 5 s while active). */
bool dt_wd_update(dt_watchdog_t *w, uint64_t now_us);
/* Alarm gain: min(profile ceiling, -20 dBFS), PROTOCOL.md Watchdog. */
int8_t dt_wd_gain_db(int8_t ceiling_db);

/* ------------------------------------------------------------------ ToF transform */

/* Maps raw sensor zone order to wire order (row 0 = top, col 0 = wearer's left).
 * Applied in this order: rotate `rot_quarters` x 90 deg clockwise, then mirror columns
 * (flip_x), then mirror rows (flip_y). nb_target[i] == 0 -> status 255, mm 0.
 * Negative distances (sensor artefacts) are clamped to 0. */
void dt_tof_transform(const int16_t raw_mm[DT_ZONES], const uint8_t raw_status[DT_ZONES],
                      const uint8_t raw_nb_target[DT_ZONES], bool flip_x, bool flip_y, uint8_t rot_quarters,
                      uint16_t out_mm[DT_ZONES], uint8_t out_status[DT_ZONES]);
/* Destination wire index for raw index i under the same transform. */
uint8_t dt_tof_dest_index(uint8_t raw_index, bool flip_x, bool flip_y, uint8_t rot_quarters);

/* ------------------------------------------------------------------ packet builders */

void dt_build_beat(dt_beat_t *b, uint32_t seq, uint8_t reset_reason, uint16_t faults, uint64_t t_us,
                   int8_t rssi_dbm, int8_t temp_c, uint16_t heap_kb);
void dt_build_range(dt_range_t *r, uint32_t seq, uint64_t t_us, const uint16_t mm[DT_ZONES],
                    const uint8_t status[DT_ZONES]);
void dt_build_pong(dt_pong_t *p, uint32_t seq, uint64_t t_phone_us, uint64_t rx_us, uint64_t tx_us);
void dt_build_ack(dt_ack_t *a, uint32_t seq, uint32_t cue_seq, uint8_t status, int8_t gl, int8_t gr,
                  uint64_t t_board_us);

#ifdef __cplusplus
}
#endif
