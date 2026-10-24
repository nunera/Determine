// Determine cue board firmware (Seeed XIAO ESP32-C3).
//
// The board decides nothing about hazards (Arch §4, Invariant 3). It streams ToF ranges
// and heartbeats to the phone, answers PINGs, and plays the CUEs the phone sends. When the
// phone goes silent it plays SYSTEM_OFF on its own (PROTOCOL.md Watchdog, Invariant 2).
//
// Tasks:
//   loopTask (Arduino loop)  Wi-Fi, UDP receive, PING/CUE/PROFILE, BEAT, watchdog, LED
//   audio    (prio 5)        renders cues into I2S; never blocks the network loop
//   haptic   (prio 4)        plays haptic patterns on both sides
//   tof      (prio 2)        VL53L5CX init/retry + RANGE at 15 Hz
// Pure logic lives in lib/cue_logic (host-tested).
#include <Arduino.h>
#include <WiFi.h>
#include <WiFiUdp.h>
#include <Wire.h>
#include <driver/i2s.h>
#include <esp_system.h>
#include <esp_timer.h>

#include <SparkFun_VL53L5CX_Library.h>
#ifndef DT_HAPTIC_MOSFET
#include <Adafruit_DRV2605.h>
#endif

#include <atomic>

#include "cue_logic.h"
#include "determine_wire.h"

#if __has_include("secrets.h")
#include "secrets.h"
#else
#include "secrets.example.h"
#warning "secrets.h missing: using secrets.example.h hotspot credentials"
#endif

#ifndef DT_TOF_FLIP_X
#define DT_TOF_FLIP_X 0
#endif
#ifndef DT_TOF_FLIP_Y
#define DT_TOF_FLIP_Y 0
#endif
#ifndef DT_TOF_ROTATE
#define DT_TOF_ROTATE 0
#endif
#ifndef DT_I2C_HZ
#define DT_I2C_HZ 400000
#endif

// ---------------------------------------------------------------- pins (Arch §5)
static constexpr int PIN_SDA = 6;       // D4
static constexpr int PIN_SCL = 7;       // D5
static constexpr int PIN_I2S_BCLK = 3;  // D1
static constexpr int PIN_I2S_LRCLK = 4; // D2
static constexpr int PIN_I2S_DIN = 5;   // D3
static constexpr int PIN_LED = 10;      // D10
static constexpr int PIN_MOSFET_L = 21; // D6 (DT_HAPTIC_MOSFET only)
static constexpr int PIN_MOSFET_R = 20; // D7
static constexpr uint8_t ADDR_TOF = 0x29;
static constexpr uint8_t ADDR_MUX = 0x70;
static constexpr uint8_t MUX_CH_L = 0, MUX_CH_R = 1;

static constexpr uint32_t AUDIO_RATE = 22050;
static constexpr size_t AUDIO_CHUNK = 128; // frames per I2S write (~5.8 ms)
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

struct AudioReq { uint8_t cue_id; int8_t gain_l_db; int8_t gain_r_db; };
struct HapticReq { uint8_t pattern; uint8_t l; uint8_t r; };
static QueueHandle_t g_audio_q, g_haptic_q; // length 1: one pending request
static volatile bool g_audio_busy = false, g_haptic_busy = false;

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

static void udp_send(const void *buf, size_t len) {
  if (!g_udp_up) return;
  xSemaphoreTake(g_net, portMAX_DELAY);
  if (g_udp.beginPacket(g_phone, DT_PORT_PHONE)) {
    g_udp.write((const uint8_t *)buf, len);
    g_udp.endPacket();
  }
  xSemaphoreGive(g_net);
}

// ---------------------------------------------------------------- audio
static void audio_task(void *) {
  static int16_t buf[AUDIO_CHUNK * 2];
  i2s_config_t cfg = {};
  cfg.mode = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_TX);
  cfg.sample_rate = AUDIO_RATE;
  cfg.bits_per_sample = I2S_BITS_PER_SAMPLE_16BIT;
  cfg.channel_format = I2S_CHANNEL_FMT_RIGHT_LEFT; // left amp takes L slot, right amp R slot
  cfg.communication_format = I2S_COMM_FORMAT_STAND_I2S;
  cfg.intr_alloc_flags = 0;
  cfg.dma_buf_count = 4;
  cfg.dma_buf_len = AUDIO_CHUNK;
  cfg.use_apll = false;
  cfg.tx_desc_auto_clear = true; // silence when idle
  i2s_pin_config_t pins = {};
  pins.mck_io_num = I2S_PIN_NO_CHANGE;
  pins.bck_io_num = PIN_I2S_BCLK;
  pins.ws_io_num = PIN_I2S_LRCLK;
  pins.data_out_num = PIN_I2S_DIN;
  pins.data_in_num = I2S_PIN_NO_CHANGE;
  bool ok = i2s_driver_install(I2S_NUM_0, &cfg, 0, nullptr) == ESP_OK && i2s_set_pin(I2S_NUM_0, &pins) == ESP_OK;
  set_fault(DT_FAULT_I2S, !ok);
  Serial.printf("[audio] i2s %s\n", ok ? "ok" : "FAILED");

  AudioReq req;
  dt_player_t player;
  for (;;) {
    if (xQueueReceive(g_audio_q, &req, portMAX_DELAY) != pdTRUE) continue;
    g_audio_busy = true;
    dt_player_start(&player, dt_cue_def(req.cue_id), AUDIO_RATE, dt_db_to_q15(req.gain_l_db),
                    dt_db_to_q15(req.gain_r_db));
    while (!player.done) {
      size_t n = dt_player_render(&player, buf, AUDIO_CHUNK);
      if (n < AUDIO_CHUNK) memset(&buf[2 * n], 0, (AUDIO_CHUNK - n) * 2 * sizeof(int16_t));
      if (ok) {
        size_t written;
        if (i2s_write(I2S_NUM_0, buf, sizeof buf, &written, pdMS_TO_TICKS(100)) != ESP_OK) set_fault(DT_FAULT_I2S, true);
      } else {
        vTaskDelay(pdMS_TO_TICKS(AUDIO_CHUNK * 1000 / AUDIO_RATE + 1));
      }
      // Interrupt: a newer request replaces the current sound mid-cue (CUE flags bit0).
      if (xQueueReceive(g_audio_q, &req, 0) == pdTRUE)
        dt_player_start(&player, dt_cue_def(req.cue_id), AUDIO_RATE, dt_db_to_q15(req.gain_l_db),
                        dt_db_to_q15(req.gain_r_db));
    }
    g_audio_busy = false;
  }
}

// ---------------------------------------------------------------- haptics
#ifdef DT_HAPTIC_MOSFET
static bool haptic_init_side(int side) {
  ledcSetup(side, 20000, 8);
  ledcAttachPin(side == 0 ? PIN_MOSFET_L : PIN_MOSFET_R, side);
  ledcWrite(side, 0);
  return true;
}
static bool haptic_set(int side, uint8_t level) {
  ledcWrite(side, level);
  return true;
}
#else
static Adafruit_DRV2605 g_drv[2];
static bool g_drv_ok[2];

static bool mux_select(uint8_t ch) {
  Wire.beginTransmission(ADDR_MUX);
  Wire.write((uint8_t)(1u << ch));
  return Wire.endTransmission() == 0;
}
static bool haptic_init_side(int side) {
  xSemaphoreTake(g_i2c, portMAX_DELAY);
  bool ok = mux_select(side == 0 ? MUX_CH_L : MUX_CH_R) && g_drv[side].begin(&Wire);
  if (ok) {
    g_drv[side].useERM();
    g_drv[side].setMode(DRV2605_MODE_REALTIME); // RTP: amplitude written directly
    g_drv[side].setRealtimeValue(0);
  }
  xSemaphoreGive(g_i2c);
  return ok;
}
// level 0..255 -> RTP signed format 0..127.
static bool haptic_set(int side, uint8_t level) {
  xSemaphoreTake(g_i2c, portMAX_DELAY);
  bool ok = mux_select(side == 0 ? MUX_CH_L : MUX_CH_R);
  if (ok) g_drv[side].setRealtimeValue(level >> 1);
  xSemaphoreGive(g_i2c);
  return ok;
}
#endif

static bool g_haptic_ok[2];

static void haptic_try_init(int side) {
  bool ok = haptic_init_side(side);
  if (ok != g_haptic_ok[side]) Serial.printf("[haptic] %c %s\n", side ? 'R' : 'L', ok ? "ok" : "FAILED");
  g_haptic_ok[side] = ok;
  set_fault(side == 0 ? DT_FAULT_HAPTIC_L : DT_FAULT_HAPTIC_R, !ok);
}

// Drives one side; a failing side gets its fault bit and the other side keeps working.
static void haptic_apply(int side, uint8_t level) {
  if (!g_haptic_ok[side]) return;
  if (!haptic_set(side, level)) {
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
        haptic_apply(0, on ? req.l : 0);
        haptic_apply(1, on ? req.r : 0);
        // Segment timing; a new (interrupting) request restarts immediately.
        if (xQueueReceive(g_haptic_q, &req, pdMS_TO_TICKS(d->seg_ms[i])) == pdTRUE) restart = true;
      }
    } while (restart);
    haptic_apply(0, 0);
    haptic_apply(1, 0);
    g_haptic_busy = false;
  }
}

// ---------------------------------------------------------------- ToF
static void tof_task(void *) {
  static SparkFun_VL53L5CX imager;
  static VL53L5CX_ResultsData res;
  static int16_t raw_mm[DT_ZONES];
  static uint8_t raw_st[DT_ZONES], raw_nb[DT_ZONES], st[DT_ZONES];
  static uint16_t mm[DT_ZONES];
  static dt_range_t pkt;
  bool ok = false;
  uint32_t last_try = 0, read_errs = 0;
  bool first = true;
  for (;;) {
    if (!ok) {
      // Firmware upload (~90 KB) holds the bus for seconds: only retry when haptics are idle,
      // and never from the cue path. Audio is unaffected (no I2C).
      if ((first || millis() - last_try > 10000) && !g_haptic_busy) {
        first = false;
        last_try = millis();
        xSemaphoreTake(g_i2c, portMAX_DELAY);
        imager.setWireMaxPacketSize(128);
        ok = imager.begin(ADDR_TOF, Wire) && imager.setResolution(64) && imager.setRangingFrequency(15) &&
             imager.startRanging();
        xSemaphoreGive(g_i2c);
        set_fault(DT_FAULT_SENSOR_INIT, !ok);
        Serial.printf("[tof] init %s\n", ok ? "ok (8x8 @15Hz)" : "FAILED, retry in 10 s");
        read_errs = 0;
      }
      vTaskDelay(pdMS_TO_TICKS(100));
      continue;
    }
    xSemaphoreTake(g_i2c, portMAX_DELAY);
    bool ready = imager.isDataReady();
    bool got = ready && imager.getRangingData(&res);
    xSemaphoreGive(g_i2c);
    if (ready && !got) {
      set_fault(DT_FAULT_SENSOR_READ, true);
      if (++read_errs >= 10) {
        Serial.println("[tof] repeated read errors, re-initialising");
        ok = false;
      }
    } else if (got) {
      uint64_t t = now_us();
      read_errs = 0;
      set_fault(DT_FAULT_SENSOR_READ, false);
      for (int i = 0; i < DT_ZONES; i++) {
        raw_mm[i] = res.distance_mm[i * VL53L5CX_NB_TARGET_PER_ZONE];
        raw_st[i] = res.target_status[i * VL53L5CX_NB_TARGET_PER_ZONE];
        raw_nb[i] = res.nb_target_detected[i];
      }
      dt_tof_transform(raw_mm, raw_st, raw_nb, DT_TOF_FLIP_X, DT_TOF_FLIP_Y, DT_TOF_ROTATE, mm, st);
      dt_build_range(&pkt, g_seq_range++, t, mm, st);
      udp_send(&pkt, sizeof pkt);
    }
    vTaskDelay(pdMS_TO_TICKS(5));
  }
}

// ---------------------------------------------------------------- output dispatch
static bool output_busy() {
  return g_audio_busy || g_haptic_busy || uxQueueMessagesWaiting(g_audio_q) || uxQueueMessagesWaiting(g_haptic_q);
}

static void dispatch(uint8_t cue_id, int8_t gl, int8_t gr, uint8_t pattern, uint8_t hl, uint8_t hr, bool interrupt) {
  if (gl != DT_GAIN_MUTE || gr != DT_GAIN_MUTE) {
    AudioReq a{cue_id, gl, gr};
    if (interrupt) xQueueOverwrite(g_audio_q, &a);
    else xQueueSend(g_audio_q, &a, 0);
  }
  if (pattern != DT_HAPTIC_NONE) {
    HapticReq h{pattern, hl, hr};
    if (interrupt) xQueueOverwrite(g_haptic_q, &h);
    else xQueueSend(g_haptic_q, &h, 0);
  }
}

// ---------------------------------------------------------------- packet handling
static void send_ack(uint32_t cue_seq, uint8_t status, int8_t gl, int8_t gr) {
  dt_ack_t a;
  dt_build_ack(&a, g_seq_ack++, cue_seq, status, gl, gr, now_us());
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
        dispatch(c.cue_id, d.gain_l_db, d.gain_r_db, c.haptic_pattern, d.haptic_l, d.haptic_r,
                 (c.flags & DT_CUE_FLAG_INTERRUPT) != 0);
      send_ack(c.h.seq, d.status, d.gain_l_db, d.gain_r_db);
      if (d.status != DT_ACK_PLAYED) Serial.printf("[cue] seq=%lu id=%u status=%u\n", (unsigned long)c.h.seq, c.cue_id, d.status);
      break;
    }
    case DT_PROFILE: {
      dt_profile_t p;
      memcpy(&p, buf, sizeof p);
      portENTER_CRITICAL(&g_prof_mux);
      bool ok = dt_profile_apply(&g_profile, &p);
      dt_profile_state_t now = g_profile;
      portEXIT_CRITICAL(&g_prof_mux);
      send_ack(p.h.seq, ok ? DT_ACK_PLAYED : DT_ACK_BAD_CUE, now.ceiling_db, now.ceiling_db);
      Serial.printf("[profile] %s ceiling=%d mode=%u haptic_max=%u\n", ok ? "applied" : "REJECTED", now.ceiling_db,
                    now.mode, now.haptic_max);
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
  g_audio_q = xQueueCreate(1, sizeof(AudioReq));
  g_haptic_q = xQueueCreate(1, sizeof(HapticReq));

  Wire.begin(PIN_SDA, PIN_SCL, DT_I2C_HZ);

  WiFi.mode(WIFI_STA);
  WiFi.setSleep(false); // modem sleep adds 100+ ms latency to CUE delivery
  WiFi.setAutoReconnect(true);
  WiFi.begin(DT_WIFI_SSID, DT_WIFI_PASS);
  Serial.printf("[wifi] connecting to %s\n", DT_WIFI_SSID);

  xTaskCreate(audio_task, "audio", 4096, nullptr, 5, nullptr);
  xTaskCreate(haptic_task, "haptic", 4096, nullptr, 4, nullptr);
  xTaskCreate(tof_task, "tof", 6144, nullptr, 2, nullptr);
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
    // Failure is loud (Invariant 2): SYSTEM_OFF + LSS every 5 s at min(ceiling, -20 dBFS).
    dt_profile_state_t prof = profile_get();
    int8_t g = prof.mode == DT_MODE_HAPTIC_ONLY ? (int8_t)DT_GAIN_MUTE : dt_wd_gain_db(prof.ceiling_db);
    uint8_t h = dt_haptic_cap(255, prof.haptic_max);
    dispatch(DT_CUE_SYSTEM_OFF, g, g, DT_HAPTIC_LSS, h, h, true);
    if (!was_active) Serial.println("[wd] no PING: WATCHDOG active, playing SYSTEM_OFF every 5 s");
  }

  if (now >= next_beat) {
    next_beat = now + 100000; // 10 Hz
    send_beat();
  }
  led_update();
  delay(1);
}
