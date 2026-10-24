// See cam_logic.h. No hardware, no allocation.
#include "cam_logic.h"

#include <string.h>

uint16_t dt_chunk_count(uint32_t total_len) {
  if (total_len == 0) return 1; /* one empty chunk carries the frame metadata */
  uint32_t n = (total_len + DT_MAX_CHUNK - 1) / DT_MAX_CHUNK;
  return n > 0xFFFFu ? 0 : (uint16_t)n;
}

bool dt_chunk_plan(uint32_t total_len, uint16_t idx, dt_chunk_t *out) {
  uint16_t count = dt_chunk_count(total_len);
  if (count == 0 || idx >= count) return false;
  uint32_t off = (uint32_t)idx * DT_MAX_CHUNK;
  uint32_t rem = total_len - off;
  out->offset = off;
  out->len = (uint16_t)(rem > DT_MAX_CHUNK ? DT_MAX_CHUNK : rem);
  out->idx = idx;
  out->count = count;
  return true;
}

size_t dt_build_frame(uint8_t *out, size_t cap, uint32_t seq, const dt_frame_meta_t *m,
                      const dt_chunk_t *c, const uint8_t *chunk_data) {
  if (c->len > DT_MAX_CHUNK) return 0;
  size_t n = sizeof(dt_frame_hdr_t) + c->len;
  if (cap < n) return 0;
  dt_frame_hdr_t f;
  dt_header_init(&f.h, DT_FRAME, seq);
  f.frame_id = m->frame_id;
  f.t_cap_us = m->t_cap_us;
  f.w = m->w;
  f.hgt = m->h;
  f.total_len = m->total_len;
  f.offset = c->offset;
  f.chunk_len = c->len;
  f.chunk_idx = c->idx;
  f.chunk_count = c->count;
  memcpy(out, &f, sizeof f);
  if (c->len) memcpy(out + sizeof f, chunk_data, c->len);
  return n;
}

size_t dt_build_beat(uint8_t *out, uint32_t seq, const dt_beat_fields_t *b) {
  dt_beat_t p;
  dt_header_init(&p.h, DT_BEAT, seq);
  p.board = b->board;
  p.reset_reason = b->reset_reason;
  p.faults = b->faults;
  p.t_us = b->t_us;
  p.rssi_dbm = b->rssi_dbm;
  p.temp_c = b->temp_c;
  p.heap_kb = b->heap_kb;
  memcpy(out, &p, sizeof p);
  return sizeof p;
}

bool dt_parse_ping(const uint8_t *buf, size_t len, uint64_t *t_phone_us) {
  if (dt_validate(buf, len) != DT_PING) return false;
  dt_ping_t p;
  memcpy(&p, buf, sizeof p);
  *t_phone_us = p.t_phone_us;
  return true;
}

size_t dt_build_pong(uint8_t *out, uint32_t seq, uint64_t t_phone_us, uint64_t t_board_rx_us,
                     uint64_t t_board_tx_us) {
  dt_pong_t p;
  dt_header_init(&p.h, DT_PONG, seq);
  p.t_phone_us = t_phone_us;
  p.t_board_rx_us = t_board_rx_us;
  p.t_board_tx_us = t_board_tx_us;
  memcpy(out, &p, sizeof p);
  return sizeof p;
}

uint16_t dt_heap_kb(uint32_t free_bytes) {
  uint32_t kb = free_bytes / 1024u;
  return kb > 0xFFFFu ? 0xFFFFu : (uint16_t)kb;
}

void dt_pacer_init(dt_pacer_t *p, uint32_t fps) {
  p->period_us = 1000000ull / (fps ? fps : 1);
  p->next_us = 0;
  p->started = false;
  p->skipped = 0;
}

bool dt_pacer_due(dt_pacer_t *p, uint64_t now_us) {
  if (!p->started) {
    p->started = true;
    p->next_us = now_us + p->period_us;
    return true;
  }
  if (now_us < p->next_us) return false;
  uint64_t late = now_us - p->next_us;
  if (late >= p->period_us) {
    /* Missed whole slots: skip them instead of bursting to catch up. */
    p->skipped += (uint32_t)(late / p->period_us);
    p->next_us = now_us + p->period_us;
  } else {
    /* Small jitter: keep the grid so the average rate stays at target, never above it. */
    p->next_us += p->period_us;
  }
  return true;
}

uint64_t dt_pacer_wait_us(const dt_pacer_t *p, uint64_t now_us) {
  if (!p->started || now_us >= p->next_us) return 0;
  return p->next_us - now_us;
}

void dt_ratelimit_init(dt_ratelimit_t *r, uint32_t max_kbytes_per_s, uint32_t burst_bytes) {
  r->rate_bps = (uint64_t)max_kbytes_per_s * 1000u;
  r->burst = burst_bytes;
  r->tokens_x1e6 = (uint64_t)burst_bytes * 1000000ull;
  r->last_us = 0;
  r->started = false;
  r->dropped = 0;
}

bool dt_ratelimit_admit(dt_ratelimit_t *r, uint32_t bytes, uint64_t now_us) {
  uint64_t cap = r->burst * 1000000ull;
  if (r->started && now_us > r->last_us) {
    uint64_t add = (now_us - r->last_us) * r->rate_bps; /* bytes*1e6 */
    r->tokens_x1e6 = (cap - r->tokens_x1e6 < add) ? cap : r->tokens_x1e6 + add;
  }
  if (!r->started || now_us > r->last_us) r->last_us = now_us;
  r->started = true;
  uint64_t need = (uint64_t)bytes * 1000000ull;
  if (r->tokens_x1e6 < need) {
    r->dropped++;
    return false;
  }
  r->tokens_x1e6 -= need;
  return true;
}

uint32_t dt_frame_wire_bytes(uint32_t total_len) {
  return total_len + (uint32_t)dt_chunk_count(total_len) * (uint32_t)sizeof(dt_frame_hdr_t);
}

dt_rx_kind_t dt_cam_classify(const uint8_t *buf, size_t len) {
  uint8_t t = dt_validate(buf, len);
  if (t == 0) return DT_RX_INVALID;
  if (t == DT_PING) return DT_RX_PING;
  return DT_RX_IGNORED; /* CUE/PROFILE etc.: camera board decides and plays nothing */
}
