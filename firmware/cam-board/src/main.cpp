// Determine camera board — AI-Thinker ESP32-CAM.
// Streams JPEG FRAME chunks, BEAT (10 Hz) and PONG to the phone (hotspot gateway:47000).
// The board decides nothing about hazards (Arch §4); it ignores CUE/PROFILE.
// Pure logic (chunking, packet builders, pacing, rate limit) lives in lib/cam_logic.
#include <Arduino.h>
#include <WiFi.h>
#include <esp_camera.h>
#include <esp_system.h>
#include <esp_timer.h>
#include <lwip/sockets.h>

#include "cam_logic.h"
#include "determine_wire.h"

#if __has_include("secrets.h")
#include "secrets.h"
#else
#warning "secrets.h missing: using secrets.example.h hotspot credentials"
#include "secrets.example.h"
#endif

#ifndef DT_CAM_FRAMESIZE
#define DT_CAM_FRAMESIZE FRAMESIZE_VGA
#endif
#ifndef DT_CAM_FPS
#define DT_CAM_FPS 12
#endif
#ifndef DT_CAM_MAX_KBPS
#define DT_CAM_MAX_KBPS 480 /* 12 fps × ~30 KB VGA JPEG ≈ 372 kB/s, leaves margin */
#endif
#ifndef DT_CAM_JPEG_QUALITY
#define DT_CAM_JPEG_QUALITY 14
#endif

// AI-Thinker ESP32-CAM pin map.
#define PWDN_GPIO 32
#define RESET_GPIO -1
#define XCLK_GPIO 0
#define SIOD_GPIO 26
#define SIOC_GPIO 27
#define Y9_GPIO 35
#define Y8_GPIO 34
#define Y7_GPIO 39
#define Y6_GPIO 36
#define Y5_GPIO 21
#define Y4_GPIO 19
#define Y3_GPIO 18
#define Y2_GPIO 5
#define VSYNC_GPIO 25
#define HREF_GPIO 23
#define PCLK_GPIO 22

#define REC_LED_GPIO 13   // front recording indicator, Arch §10.1 bystander privacy
#define FLASH_LED_GPIO 4  // onboard flash LED: always off

static const uint32_t kLowHeapBytes = 24 * 1024;
static const uint64_t kCaptureFaultHoldUs = 1000000;  // keep CAPTURE bit visible ≥ 1 s
static const uint64_t kRecLedHoldUs = 300000;         // LED on while frames flow
static const uint64_t kSensorRetryUs = 2000000;

static int g_sock = -1;
static volatile uint32_t g_gateway = 0;  // network order, 0 = no link
static uint8_t g_reset_reason;

static volatile bool g_cam_ok = false;
static volatile bool g_got_ping = false;
static volatile uint64_t g_capture_fault_until = 0;
static volatile uint64_t g_last_frame_sent_us = 0;
static volatile uint32_t g_ignored_rx = 0, g_invalid_rx = 0;
static uint32_t g_seq_beat = 0, g_seq_pong = 0, g_seq_frame = 0;

static inline uint64_t now_us() { return (uint64_t)esp_timer_get_time(); }

// sendto on an lwIP socket is thread-safe; each sender uses its own seq counter.
static bool send_to_phone(const uint8_t *buf, size_t len) {
  uint32_t gw = g_gateway;
  if (g_sock < 0 || gw == 0) return false;
  sockaddr_in a = {};
  a.sin_family = AF_INET;
  a.sin_port = htons(DT_PORT_PHONE);
  a.sin_addr.s_addr = gw;
  return sendto(g_sock, buf, len, 0, (sockaddr *)&a, sizeof a) == (int)len;
}

static void flag_capture_fault() { g_capture_fault_until = now_us() + kCaptureFaultHoldUs; }

// ---------------- camera ----------------

static framesize_t g_framesize;

static const char *sensor_name(uint16_t pid) {
  switch (pid) {
    case OV2640_PID: return "OV2640";
    case OV3660_PID: return "OV3660";
    case OV5640_PID: return "OV5640";
    case OV7725_PID: return "OV7725";
    case OV7670_PID: return "OV7670";
    default: return "unknown";
  }
}

static bool camera_init() {
  camera_config_t c = {};
  c.pin_pwdn = PWDN_GPIO;
  c.pin_reset = RESET_GPIO;
  c.pin_xclk = XCLK_GPIO;
  c.pin_sccb_sda = SIOD_GPIO;
  c.pin_sccb_scl = SIOC_GPIO;
  c.pin_d7 = Y9_GPIO;
  c.pin_d6 = Y8_GPIO;
  c.pin_d5 = Y7_GPIO;
  c.pin_d4 = Y6_GPIO;
  c.pin_d3 = Y5_GPIO;
  c.pin_d2 = Y4_GPIO;
  c.pin_d1 = Y3_GPIO;
  c.pin_d0 = Y2_GPIO;
  c.pin_vsync = VSYNC_GPIO;
  c.pin_href = HREF_GPIO;
  c.pin_pclk = PCLK_GPIO;
  c.xclk_freq_hz = 20000000;
  c.ledc_timer = LEDC_TIMER_0;
  c.ledc_channel = LEDC_CHANNEL_0;
  c.pixel_format = PIXFORMAT_JPEG;  // Arch §5: JPEG only (PSRAM bandwidth with Wi-Fi on)
  c.jpeg_quality = DT_CAM_JPEG_QUALITY;
  if (psramFound()) {
    c.frame_size = DT_CAM_FRAMESIZE;
    c.fb_count = 2;
    c.fb_location = CAMERA_FB_IN_PSRAM;
    c.grab_mode = CAMERA_GRAB_LATEST;  // never hand out a stale queued frame
  } else {
    // esp32-camera: without PSRAM only JPEG at CIF or lower, one buffer in DRAM (Arch §5, [13]).
    c.frame_size = DT_CAM_FRAMESIZE > FRAMESIZE_CIF ? FRAMESIZE_CIF : DT_CAM_FRAMESIZE;
    c.fb_count = 1;
    c.fb_location = CAMERA_FB_IN_DRAM;
    c.grab_mode = CAMERA_GRAB_WHEN_EMPTY;
  }
  g_framesize = c.frame_size;
  esp_err_t err = esp_camera_init(&c);
  if (err != ESP_OK) {
    Serial.printf("[cam] esp_camera_init failed 0x%x\n", err);
    return false;
  }
  sensor_t *s = esp_camera_sensor_get();
  uint16_t pid = s ? s->id.PID : 0;
  Serial.printf("[cam] sensor PID 0x%04x (%s), framesize %d, psram %s\n", pid, sensor_name(pid),
                (int)g_framesize, psramFound() ? "yes" : "no");
  // JPEG-capable sensors only; anything else is a SENSOR_INIT fault (Gate G0).
  if (pid != OV2640_PID && pid != OV3660_PID && pid != OV5640_PID) {
    Serial.println("[cam] unsupported sensor for JPEG streaming");
    esp_camera_deinit();
    return false;
  }
  return true;
}

static void send_frame(camera_fb_t *fb, uint32_t frame_id, uint64_t t_cap) {
  static uint8_t pkt[sizeof(dt_frame_hdr_t) + DT_MAX_CHUNK];
  dt_frame_meta_t m = {frame_id, t_cap, (uint16_t)fb->width, (uint16_t)fb->height,
                       (uint32_t)fb->len};
  uint16_t count = dt_chunk_count(m.total_len);
  if (count == 0) {
    flag_capture_fault();
    return;
  }
  for (uint16_t i = 0; i < count; i++) {
    dt_chunk_t c;
    dt_chunk_plan(m.total_len, i, &c);
    size_t n = dt_build_frame(pkt, sizeof pkt, g_seq_frame++, &m, &c, fb->buf + c.offset);
    // Brief retry for lwIP ENOMEM; give up on the frame rather than send it late
    // (phone drops frames incomplete within 150 ms, PROTOCOL.md FRAME).
    bool ok = false;
    for (int tries = 0; tries < 5 && !(ok = send_to_phone(pkt, n)); tries++) delay(2);
    if (!ok) {
      flag_capture_fault();
      return;
    }
  }
  g_last_frame_sent_us = now_us();
}

static void camera_task(void *) {
  dt_pacer_t pacer;
  dt_pacer_init(&pacer, DT_CAM_FPS);
  dt_ratelimit_t rl;
  // Burst covers ~3 frames at the configured budget so normal JPEG size variance passes.
  dt_ratelimit_init(&rl, DT_CAM_MAX_KBPS, DT_CAM_MAX_KBPS * 1000u * 3u / DT_CAM_FPS);
  uint64_t next_init = 0;
  uint32_t frame_id = 0;
  for (;;) {
    uint64_t now = now_us();
    if (!g_cam_ok) {
      if (now >= next_init) {
        g_cam_ok = camera_init();
        next_init = now + kSensorRetryUs;
      }
      vTaskDelay(pdMS_TO_TICKS(50));
      continue;
    }
    if (g_gateway == 0) {  // nowhere to send: don't capture
      vTaskDelay(pdMS_TO_TICKS(50));
      continue;
    }
    uint64_t wait = dt_pacer_wait_us(&pacer, now);
    if (wait > 0) {
      vTaskDelay(pdMS_TO_TICKS(wait / 1000 ? wait / 1000 : 1));
      continue;
    }
    if (!dt_pacer_due(&pacer, now)) continue;
    camera_fb_t *fb = esp_camera_fb_get();
    uint64_t t_cap = now_us();  // t_cap_us at grab (GRAB_LATEST: freshest frame)
    if (!fb) {
      flag_capture_fault();
      continue;
    }
    if (fb->format != PIXFORMAT_JPEG || fb->len < 2 || fb->buf[0] != 0xFF || fb->buf[1] != 0xD8) {
      flag_capture_fault();  // corrupt JPEG: drop
    } else if (dt_ratelimit_admit(&rl, dt_frame_wire_bytes(fb->len), t_cap)) {
      send_frame(fb, frame_id++, t_cap);
    }
    esp_camera_fb_return(fb);
  }
}

// ---------------- network rx (PING → PONG) ----------------

static void rx_task(void *) {
  uint8_t buf[64];
  uint8_t out[sizeof(dt_pong_t)];
  for (;;) {
    if (g_sock < 0) {
      vTaskDelay(pdMS_TO_TICKS(100));
      continue;
    }
    int n = recv(g_sock, buf, sizeof buf, 0);  // blocking with SO_RCVTIMEO
    uint64_t t_rx = now_us();
    if (n <= 0) continue;
    uint64_t t_phone;
    switch (dt_cam_classify(buf, (size_t)n)) {
      case DT_RX_PING:
        dt_parse_ping(buf, (size_t)n, &t_phone);
        g_got_ping = true;
        dt_build_pong(out, g_seq_pong++, t_phone, t_rx, now_us());
        send_to_phone(out, sizeof out);
        break;
      case DT_RX_IGNORED: g_ignored_rx++; break;  // CUE/PROFILE are not for this board
      default: g_invalid_rx++; break;
    }
  }
}

static void open_socket() {
  int s = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
  if (s < 0) return;
  sockaddr_in a = {};
  a.sin_family = AF_INET;
  a.sin_port = htons(DT_PORT_CAM);
  a.sin_addr.s_addr = htonl(INADDR_ANY);
  timeval tv = {0, 200000};
  setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof tv);
  if (bind(s, (sockaddr *)&a, sizeof a) != 0) {
    close(s);
    return;
  }
  g_sock = s;
}

// ---------------- setup / loop ----------------

void setup() {
  pinMode(FLASH_LED_GPIO, OUTPUT);
  digitalWrite(FLASH_LED_GPIO, LOW);
  pinMode(REC_LED_GPIO, OUTPUT);
  digitalWrite(REC_LED_GPIO, LOW);
  Serial.begin(115200);
  g_reset_reason = (uint8_t)esp_reset_reason();
  Serial.printf("\n[boot] Determine cam board, reset reason %u\n", g_reset_reason);
  Serial.printf("[boot] PSRAM %s, size %u bytes, free heap %u\n", psramFound() ? "found" : "NOT found",
                (unsigned)ESP.getPsramSize(), (unsigned)ESP.getFreeHeap());

  WiFi.mode(WIFI_STA);
  WiFi.setSleep(false);  // power save off: latency (Arch §6.3)
  WiFi.setAutoReconnect(true);
  WiFi.begin(DT_WIFI_SSID, DT_WIFI_PASS);
  open_socket();

  xTaskCreatePinnedToCore(rx_task, "dt_rx", 4096, nullptr, 5, nullptr, 0);
  xTaskCreatePinnedToCore(camera_task, "dt_cam", 6144, nullptr, 4, nullptr, 1);
}

void loop() {
  static uint64_t next_beat = 0, last_wifi_log = 0;
  static bool was_connected = false;
  uint64_t now = now_us();

  bool connected = WiFi.status() == WL_CONNECTED;
  if (connected && !was_connected) {
    g_gateway = (uint32_t)WiFi.gatewayIP();
    Serial.printf("[wifi] connected %s gw %s rssi %d\n", WiFi.localIP().toString().c_str(),
                  WiFi.gatewayIP().toString().c_str(), WiFi.RSSI());
  } else if (!connected && was_connected) {
    g_gateway = 0;
    Serial.println("[wifi] lost link, auto-reconnecting");
  } else if (!connected && now - last_wifi_log > 5000000) {
    last_wifi_log = now;
    Serial.println("[wifi] waiting for hotspot");
    WiFi.reconnect();
  }
  was_connected = connected;
  if (g_sock < 0) open_socket();

  // Recording LED tracks actual transmission (Arch §10.1).
  bool streaming = g_last_frame_sent_us && now - g_last_frame_sent_us < kRecLedHoldUs;
  digitalWrite(REC_LED_GPIO, streaming ? HIGH : LOW);

  if (now >= next_beat) {
    next_beat = now + 100000;  // 10 Hz
    uint32_t heap = ESP.getFreeHeap();
    uint16_t faults = 0;
    if (!g_cam_ok) faults |= DT_FAULT_SENSOR_INIT;
    if (!g_got_ping) faults |= DT_FAULT_NO_CLOCK_SYNC;
    if (now < g_capture_fault_until) faults |= DT_FAULT_CAPTURE;
    if (heap < kLowHeapBytes) faults |= DT_FAULT_LOW_HEAP;
    if (g_reset_reason == ESP_RST_BROWNOUT) faults |= DT_FAULT_BROWNOUT;
    dt_beat_fields_t b = {DT_BOARD_CAM, g_reset_reason, faults, now,
                          (int8_t)(connected ? WiFi.RSSI() : 0), 127 /* unknown */,
                          dt_heap_kb(heap)};
    uint8_t out[sizeof(dt_beat_t)];
    dt_build_beat(out, g_seq_beat++, &b);
    send_to_phone(out, sizeof out);
  }
  delay(5);
}
