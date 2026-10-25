// Determine cue board firmware (Seeed XIAO ESP32-C3).
//
// The board decides nothing about hazards (Arch §4, Invariant 3). It streams single-zone
// ToF ranges and heartbeats to the phone, answers PINGs, and plays the haptic side of the
// CUEs the phone sends. All cue audio is rendered by the phone itself (bone-conduction
// Bluetooth headset); this board has no sound hardware anymore. When the phone goes
// silent it plays the haptic LSS alarm on its own (PROTOCOL.md Watchdog, Invariant 2).
//
// Tasks:
//   loopTask (Arduino loop)  Wi-Fi, UDP receive, PING/CUE/PROFILE, BEAT, watchdog, LED
//   haptic   (prio 4)        plays haptic patterns on the Modulino Vibro (one actuator)
//   tof      (prio 2)        VL53L4ED init/retry + single-zone RANGE stream
// Pure logic lives in lib/cue_logic (host-tested).
#include <Arduino.h>
#include <WiFi.h>
#include <WiFiUdp.h>
#include <Wire.h>
#include <esp_system.h>
#include <esp_timer.h>

#include <vl53l4ed_class.h> // ST driver for the purchased Modulino Distance (sensor ID 0xECAA)
#include <string.h>

#include <atomic>

#include "cue_logic.h"
#include "determine_wire.h"

#if __has_include("secrets.h")
#include "secrets.h"
#else
#include "secrets.example.h"
#warning "secrets.h missing: using secrets.example.h hotspot credentials"
#endif

#ifndef DT_I2C_HZ
#define DT_I2C_HZ 400000
#endif

// ---------------------------------------------------------------- pins (Arch §5)
static constexpr int PIN_SDA = 6;  // D4
static constexpr int PIN_SCL = 7;  // D5
static constexpr int PIN_LED = 10; // D10
static constexpr int PIN_MOSFET_L = 21; // D6 (DT_HAPTIC_MOSFET only)
static constexpr int PIN_MOSFET_R = 20; // D7
static constexpr uint8_t ADDR_TOF = 0x29;  // VL53L4ED default address (purchased Modulino Distance)

// VL53L4ED ranging: 20 ms timing budget,
// inter-measurement 0 (continuous). Poll interval bounds the stream to well under the
// 50 Hz the sensor can produce; every ready sample is sent as one RANGE.
static constexpr uint32_t TOF_TIMING_BUDGET_MS = 20;
static constexpr uint32_t TOF_POLL_MS = 5;
static constexpr uint32_t TOF_RETRY_MS = 10000;
static constexpr uint32_t TOF_READ_ERR_LIMIT = 10;
static constexpr uint16_t LOW_HEAP_KB = 20;

// ---------------------------------------------------------------- shared state
static std::atomic<uint16_t> g_faults{0};
static uint8_t g_reset_reason;

static portMUX_TYPE g_prof_mux = portMUX_INITIALIZER_UNLOCKED;
static dt_profile_state_t g_profile;

static dt_watchdog_t g_wd; // loop task only

static SemaphoreHandle_t g_i2c; // one bus shared by ToF and haptic drivers
static SemaphoreHandle_t g_net; // WiFiUDP is not thread-safe
static WiFiUDP g_udp;
static volatile bool g_udp_up = false;
static IPAddress g_phone;
static uint32_t g_seq_beat, g_seq_pong, g_seq_ack; // loop task only
static uint32_t g_seq_range;                        // tof task only

struct HapticReq { uint8_t pattern; uint8_t l; uint8_t r; };
static QueueHandle_t g_haptic_q; // length 1: one pending request
static volatile bool g_haptic_busy = false;

static void set_fault(uint16_t bit, bool on) {
  if (on) g_faults.fetch_or(bit);
  else g_faults.fetch_and((uint16_t)~bit);
}

static uint64_t now_us() { return (uint64_t)esp_timer_get_time(); }

static dt_profile_state_t profile_get() {
  portENTER_CRITICAL(&g_prof_mux);
  dt_profile_state_t p = g_profile;
  portEXIT_CRITICAL(&g_prof_mux);
  return p;
}

// The Arduino core logs every failed endPacket() itself ("could not send data") and that
// cannot be silenced per call, so a Wi-Fi drop used to flood the serial log at 200 RANGE/s.
// Never attempt a send while Wi-Fi is down, and report failures here at most once per 5 s.
static constexpr uint32_t UDP_FAIL_LOG_MS = 5000;
static uint32_t g_udp_fail_count = 0;  // guarded by g_net
static uint32_t g_udp_fail_log_ms = 0; // guarded by g_net; 0 = nothing logged yet

static void udp_send(const void *buf, size_t len) {
  if (!g_udp_up || WiFi.status() != WL_CONNECTED) return;
  xSemaphoreTake(g_net, portMAX_DELAY);
  bool sent = g_udp.beginPacket(g_phone, DT_PORT_PHONE) != 0;
  if (sent) {
    g_udp.write((const uint8_t *)buf, len);
    sent = g_udp.endPacket() != 0;
  }
  if (!sent) {
    g_udp_fail_count++;
    uint32_t now = millis();
    if (g_udp_fail_log_ms == 0 || now - g_udp_fail_log_ms >= UDP_FAIL_LOG_MS) {
      Serial.printf("[udp] send failed (%lu since last report)\n", (unsigned long)g_udp_fail_count);
      g_udp_fail_count = 0;
      g_udp_fail_log_ms = now ? now : 1;
    }
  }
  xSemaphoreGive(g_net);
}

// ---------------------------------------------------------------- haptics
#ifdef DT_HAPTIC_MOSFET
static bool haptic_init_side(int side) {
  ledcSetup(side, 20000, 8);
  ledcAttachPin(side == 0 ? PIN_MOSFET_L : PIN_MOSFET_R, side);
  ledcWrite(side, 0);
  return true;
}
static bool haptic_set(int side, uint8_t level, uint32_t /*dur_ms*/) {
  ledcWrite(side, level);
  return true;
}
#else
// Arduino Modulino Vibro (ABX00130): one I2C-controlled motor, found at 0x38 on the bench.
// It cannot lateralise, so the protocol's per-side intensities drive it at max(L, R);
// both haptic fault bits reflect this single device.
static constexpr uint8_t ADDR_VIBRO = 0x38;
// Every ON write sets the module's own timer to exactly the pattern segment it plays (40-400 ms), so the motor
// stops on time even if the OFF write that follows is lost or ignored. A fixed 1000 ms window here made 40 ms
// pulses feel long and inconsistent on the bench (24 Oct 2026: 60 ms durations felt like soft taps, the
// 1000 ms window with an OFF write did not). The OFF write is still sent as well.
static uint8_t g_side_level[2];

// Command layout from Arduino_Modulino ModulinoVibro::on()/off():
// ON  = uint32 freq (1000), uint32 duration_ms, int32 power (0..50); OFF = 8 zero bytes.
static bool vibro_write(uint8_t level, uint32_t dur_ms) {
  uint8_t buf[12] = {0};
  size_t len = 8;
  if (level) {
    uint32_t freq = 1000, ms = dur_ms ? dur_ms : 1;
    int32_t power = dt_vibro_power(level);
    memcpy(&buf[0], &freq, 4);
    memcpy(&buf[4], &ms, 4);
    memcpy(&buf[8], &power, 4);
    len = sizeof buf;
  }
  xSemaphoreTake(g_i2c, portMAX_DELAY);
  Wire.beginTransmission(ADDR_VIBRO);
  Wire.write(buf, len);
  bool ok = Wire.endTransmission() == 0;
  xSemaphoreGive(g_i2c);
  return ok;
}
static bool haptic_set(int side, uint8_t level, uint32_t dur_ms) {
  g_side_level[side] = level;
  bool ok = vibro_write(max(g_side_level[0], g_side_level[1]), dur_ms);
  // A failed side must not keep the shared motor on through the other side's writes.
  if (!ok) g_side_level[side] = 0;
  return ok;
}
static bool haptic_init_side(int side) {
  return haptic_set(side, 0, 0);
}
#endif

static bool g_haptic_ok[2];

static void haptic_try_init(int side) {
  bool ok = haptic_init_side(side);
  if (ok != g_haptic_ok[side]) Serial.printf("[haptic] %c %s\n", side ? 'R' : 'L', ok ? "ok" : "FAILED");
  g_haptic_ok[side] = ok;
  set_fault(side == 0 ? DT_FAULT_HAPTIC_L : DT_FAULT_HAPTIC_R, !ok);
}

// Drives one side for `dur_ms` (ON segments); a failing side gets its fault bit and the other side keeps working.
static void haptic_apply(int side, uint8_t level, uint32_t dur_ms) {
  if (!g_haptic_ok[side]) return;
  if (!haptic_set(side, level, dur_ms)) {
    g_haptic_ok[side] = false;
    set_fault(side == 0 ? DT_FAULT_HAPTIC_L : DT_FAULT_HAPTIC_R, true);
    Serial.printf("[haptic] %c write FAILED\n", side ? 'R' : 'L');
  }
}

static void haptic_task(void *) {
  haptic_try_init(0);
  haptic_try_init(1);
  uint32_t last_retry = millis();
  HapticReq req;
  for (;;) {
    if (xQueueReceive(g_haptic_q, &req, pdMS_TO_TICKS(1000)) != pdTRUE) {
      if (millis() - last_retry > 10000) { // retry failed drivers while idle
        last_retry = millis();
        for (int s = 0; s < 2; s++) if (!g_haptic_ok[s]) haptic_try_init(s);
      }
      continue;
    }
    g_haptic_busy = true;
    bool restart;
    do {
      restart = false;
      const dt_haptic_def_t *d = dt_haptic_def(req.pattern);
      for (uint8_t i = 0; d && i < d->count && !restart; i++) {
        bool on = (i % 2) == 0;
        uint32_t seg = d->seg_ms[i];
        haptic_apply(0, on ? req.l : 0, seg);
        haptic_apply(1, on ? req.r : 0, seg);
        // Segment timing; a new (interrupting) request restarts immediately.
        if (xQueueReceive(g_haptic_q, &req, pdMS_TO_TICKS(seg)) == pdTRUE) restart = true;
      }
    } while (restart);
    haptic_apply(0, 0, 0);
    haptic_apply(1, 0, 0);
    g_haptic_busy = false;
  }
}

// ---------------------------------------------------------------- ToF (VL53L4ED)
// The purchased Modulino Distance reports VL53L4ED model ID 0xECAA at I2C 0x29.
// The older VL53L4CD driver requires 0xEBAA and rejects this module.
// Use ST's VL53L4ED driver; do not bypass its sensor identity check.
// XSHUT remains unconnected (pulled up on the module). InitSensor(), 20 ms
// continuous timing and polling preserve the existing scalar wire-v2 contract.
// Raw range_status 0 -> VALID, 2/4 (no target / beyond range) -> NO_TARGET, anything else
// -> UNKNOWN (dt_range_status_from_tof, unit-tested); mm is 0 unless VALID.
static void tof_task(void *) {
  static VL53L4ED tof(&Wire, -1); // -1: XSHUT not wired
  static VL53L4ED_ResultsData_t res;
  static dt_range_t pkt;
  bool ok = false;
  uint32_t last_try = 0, read_errs = 0;
  bool first = true;
  for (;;) {
    if (!ok) {
      // L4CD init is a short register sweep (no firmware upload like the old L5CX), so a
      // retry only needs the bus mutex — no need to wait for idle haptics.
      if (first || millis() - last_try > TOF_RETRY_MS) {
        first = false;
        last_try = millis();
        xSemaphoreTake(g_i2c, portMAX_DELAY);
        // Cheap presence probe first, like Modulino's begin(): avoids long ULD timeouts.
        Wire.beginTransmission(ADDR_TOF);
        bool present = Wire.endTransmission() == 0;
        bool init = present && tof.InitSensor() == VL53L4ED_ERROR_NONE &&
                    tof.VL53L4ED_SetRangeTiming(TOF_TIMING_BUDGET_MS, 0) == VL53L4ED_ERROR_NONE &&
                    tof.VL53L4ED_StartRanging() == VL53L4ED_ERROR_NONE;
        xSemaphoreGive(g_i2c);
        ok = init;
        set_fault(DT_FAULT_SENSOR_INIT, !ok);
        Serial.printf("[tof] init %s\n", ok ? "ok (VL53L4ED, single zone, 20 ms budget)" : "FAILED, retry in 10 s");
        read_errs = 0;
      }
      vTaskDelay(pdMS_TO_TICKS(100));
      continue;
    }
    uint8_t ready = 0;
    xSemaphoreTake(g_i2c, portMAX_DELAY);
    bool err = tof.VL53L4ED_CheckForDataReady(&ready) != VL53L4ED_ERROR_NONE;
    bool got = false;
    if (!err && ready) {
      // Read the held result before arming the next sample (ST driver contract).
      err = tof.VL53L4ED_GetResult(&res) != VL53L4ED_ERROR_NONE ||
            tof.VL53L4ED_ClearInterrupt() != VL53L4ED_ERROR_NONE;
      got = !err;
    }
    xSemaphoreGive(g_i2c);
    if (err) {
      set_fault(DT_FAULT_SENSOR_READ, true);
      if (++read_errs >= TOF_READ_ERR_LIMIT) {
        Serial.println("[tof] repeated read errors, re-initialising");
        xSemaphoreTake(g_i2c, portMAX_DELAY);
        tof.VL53L4ED_StopRanging();
        xSemaphoreGive(g_i2c);
        ok = false;
      }
    } else if (got) {
      read_errs = 0;
      set_fault(DT_FAULT_SENSOR_READ, false);
      uint8_t status = dt_range_status_from_tof(res.range_status);
      dt_build_range(&pkt, g_seq_range++, now_us(), res.distance_mm, status);
      udp_send(&pkt, sizeof pkt);
    }
    vTaskDelay(pdMS_TO_TICKS(TOF_POLL_MS));
  }
}

// ---------------------------------------------------------------- output dispatch
static bool output_busy() {
  return g_haptic_busy || uxQueueMessagesWaiting(g_haptic_q);
}

static void dispatch(uint8_t pattern, uint8_t hl, uint8_t hr, bool interrupt) {
  if (pattern == DT_HAPTIC_NONE) return;
  HapticReq h{pattern, hl, hr};
  if (interrupt) xQueueOverwrite(g_haptic_q, &h);
  else xQueueSend(g_haptic_q, &h, 0);
}

// ---------------------------------------------------------------- packet handling
static void send_ack(uint32_t cue_seq, uint8_t status) {
  dt_ack_t a;
  dt_build_ack(&a, g_seq_ack++, cue_seq, status, now_us());
  udp_send(&a, sizeof a);
}

static void handle_packet(const uint8_t *buf, size_t len, uint64_t rx_us) {
  switch (dt_validate(buf, len)) {
    case DT_PING: {
      dt_ping_t p;
      memcpy(&p, buf, sizeof p);
      if (!g_wd.ever_pinged || g_wd.active) Serial.println("[wd] PING received: link up");
      dt_wd_on_ping(&g_wd, rx_us);
      dt_pong_t o;
      dt_build_pong(&o, g_seq_pong++, p.t_phone_us, rx_us, 0);
      o.t_board_tx_us = now_us(); // as late as possible before send
      udp_send(&o, sizeof o);
      break;
    }
    case DT_CUE: {
      dt_cue_t c;
      memcpy(&c, buf, sizeof c);
      dt_profile_state_t prof = profile_get();
      dt_cue_decision_t d = dt_cue_decide(&c, now_us(), &prof, output_busy());
      if (d.play)
        dispatch(c.haptic_pattern, d.haptic_l, d.haptic_r, (c.flags & DT_CUE_FLAG_INTERRUPT) != 0);
      send_ack(c.h.seq, d.status);
      if (d.status != DT_ACK_APPLIED)
        Serial.printf("[cue] seq=%lu id=%u status=%u\n", (unsigned long)c.h.seq, c.cue_id, d.status);
      break;
    }
    case DT_PROFILE: {
      dt_profile_t p;
      memcpy(&p, buf, sizeof p);
      portENTER_CRITICAL(&g_prof_mux);
      bool ok = dt_profile_apply(&g_profile, &p);
      dt_profile_state_t now = g_profile;
      portEXIT_CRITICAL(&g_prof_mux);
      send_ack(p.h.seq, ok ? DT_ACK_APPLIED : DT_ACK_BAD_CUE);
      Serial.printf("[profile] %s mode=%u haptic_max=%u\n", ok ? "applied" : "REJECTED", now.mode, now.haptic_max);
      break;
    }
    default: break; // wrong magic/version/type/length, or a type the cue board never receives
  }
}

// ---------------------------------------------------------------- Wi-Fi
static void wifi_manage() {
  static uint32_t down_since = 0;
  bool up = WiFi.status() == WL_CONNECTED;
  if (up && !g_udp_up) {
    g_phone = WiFi.gatewayIP(); // phone hotspot = DHCP gateway (PROTOCOL.md Transport)
    xSemaphoreTake(g_net, portMAX_DELAY);
    g_udp.begin(DT_PORT_CUE);
    xSemaphoreGive(g_net);
    g_udp_up = true;
    Serial.printf("[wifi] up ip=%s phone=%s rssi=%d\n", WiFi.localIP().toString().c_str(), g_phone.toString().c_str(),
                  WiFi.RSSI());
  } else if (!up && g_udp_up) {
    g_udp_up = false;
    xSemaphoreTake(g_net, portMAX_DELAY);
    g_udp.stop();
    xSemaphoreGive(g_net);
    down_since = millis();
    Serial.println("[wifi] down, reconnecting");
  } else if (!up && millis() - down_since > 10000) {
    down_since = millis();
    WiFi.disconnect();
    WiFi.begin(DT_WIFI_SSID, DT_WIFI_PASS);
  }
}

// ---------------------------------------------------------------- BEAT + LED
static void send_beat() {
  uint16_t faults = g_faults.load();
  if (!g_wd.ever_pinged) faults |= DT_FAULT_NO_CLOCK_SYNC;
  if (g_wd.active) faults |= DT_FAULT_WATCHDOG;
  uint16_t heap_kb = (uint16_t)(ESP.getFreeHeap() / 1024);
  if (heap_kb < LOW_HEAP_KB) faults |= DT_FAULT_LOW_HEAP;
  float tc = temperatureRead();
  int8_t temp = (isnan(tc) || tc < -100 || tc > 126) ? 127 : (int8_t)lroundf(tc);
  int rssi = WiFi.RSSI();
  dt_beat_t b;
  dt_build_beat(&b, g_seq_beat++, g_reset_reason, faults, now_us(), (int8_t)constrain(rssi, -128, 0), temp, heap_kb);
  udp_send(&b, sizeof b);
}

// solid = streaming & synced, slow blink = no Wi-Fi, fast blink = watchdog (assignment / README)
static void led_update() {
  uint32_t ms = millis();
  bool on;
  if (WiFi.status() != WL_CONNECTED) on = (ms / 500) % 2;
  else if (g_wd.active) on = (ms / 100) % 2;
  else if (g_wd.ever_pinged) on = true;
  else on = (ms / 1000) % 4 == 0; // connected, waiting for the phone
  digitalWrite(PIN_LED, on ? HIGH : LOW);
}

// ---------------------------------------------------------------- setup / loop
void setup() {
  Serial.begin(115200);
  pinMode(PIN_LED, OUTPUT);
  digitalWrite(PIN_LED, LOW);
  g_reset_reason = (uint8_t)esp_reset_reason();
  if (g_reset_reason == ESP_RST_BROWNOUT) set_fault(DT_FAULT_BROWNOUT, true);
  Serial.printf("[boot] Determine cue board, reset_reason=%u\n", g_reset_reason);

  dt_profile_default(&g_profile);
  dt_wd_init(&g_wd);
  g_i2c = xSemaphoreCreateMutex();
  g_net = xSemaphoreCreateMutex();
  g_haptic_q = xQueueCreate(1, sizeof(HapticReq));

  Wire.begin(PIN_SDA, PIN_SCL, DT_I2C_HZ);
  WiFi.mode(WIFI_STA);
  WiFi.setSleep(false); // modem sleep adds 100+ ms latency to CUE delivery
  WiFi.setAutoReconnect(true);
  WiFi.begin(DT_WIFI_SSID, DT_WIFI_PASS);
  Serial.printf("[wifi] connecting to %s\n", DT_WIFI_SSID);

  xTaskCreate(haptic_task, "haptic", 4096, nullptr, 4, nullptr);
  xTaskCreate(tof_task, "tof", 4096, nullptr, 2, nullptr);
}

void loop() {
  static uint8_t buf[256];
  static uint64_t next_beat = 0;
  wifi_manage();

  while (g_udp_up) {
    xSemaphoreTake(g_net, portMAX_DELAY);
    int len = g_udp.parsePacket();
    uint64_t rx = now_us();
    int n = len > 0 ? g_udp.read(buf, sizeof buf) : 0;
    xSemaphoreGive(g_net);
    if (len <= 0) break;
    if (len <= (int)sizeof buf && n == len) handle_packet(buf, (size_t)n, rx);
  }

  uint64_t now = now_us();
  bool was_active = g_wd.active;
  if (dt_wd_update(&g_wd, now)) {
    // Failure is loud (Invariant 2), haptic-only since v2: LSS every 5 s at haptic_max.
    dt_profile_state_t prof = profile_get();
    uint8_t h = dt_clamp_haptic(255, prof.haptic_max);
    dispatch(DT_HAPTIC_LSS, h, h, true);
    if (!was_active) Serial.println("[wd] no PING: WATCHDOG active, playing haptic LSS every 5 s");
  }

  if (now >= next_beat) {
    next_beat = now + 100000; // 10 Hz
    send_beat();
  }
  led_update();
  delay(1);
}
