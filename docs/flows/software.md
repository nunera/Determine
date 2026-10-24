# Determine — software flowcharts

Grounded in `protocol/PROTOCOL.md` (wire v1, frozen), `firmware/common/include/determine_wire.h` and
`Determine-Architecture.html` §4.2, §7.1–§7.3, §8, §9. **PROPOSED** marks untuned numbers from the docs.

Rendered copies: [`rendered/`](rendered/).

## (a) Cue-board firmware (XIAO ESP32-C3)

### Tasks

```mermaid
flowchart TB
  BOOT["Boot: record esp_reset_reason()<br/>init I2S, mux + DRV2605L L/R, VL53L5CX fw upload<br/>init failures set fault bits 0/2/3/4"] --> WIFI["Join phone hotspot (station)"]
  WIFI --> TASKS
  subgraph TASKS["Concurrent tasks"]
    NET["Network loop<br/>recv UDP :47001<br/>PING: PONG + feed watchdog<br/>CUE: CUE handler<br/>PROFILE: store ceiling/mode/haptic_max, ACK 0"]
    TOF["ToF task 15 Hz<br/>read 8x8 grid, mounting transform<br/>no target: status 255, mm 0<br/>send RANGE t_us; read error: fault bit 1"]
    AUD["Audio render task<br/>synthesise earcon per cue_id<br/>per-side gain, I2S stereo frames<br/>flags bit0 interrupts current sound"]
    HAP["Haptic task<br/>pattern NONE/PULSE40/PULSE60/BUZZ150/LSS<br/>DRV2605L via mux ch0/ch1<br/>or MOSFET PWM if DT_HAPTIC_MOSFET"]
    WD["Watchdog<br/>no PING 800 ms, or none 5 s after boot<br/>SYSTEM_OFF + LSS every 5 s at min(ceiling, -20 dBFS)<br/>fault bit 7 until PINGs resume"]
    BEAT["Beat task 10 Hz<br/>BEAT board=2, faults, t_us, RSSI, temp, heap"]
  end
  NET -- "play request" --> AUD
  NET -- "pattern + intensity" --> HAP
  NET -- "PING seen" --> WD
  WD -- "SYSTEM_OFF" --> AUD
  WD -- "LSS" --> HAP
```

[rendered](rendered/software-1.svg)

### CUE handling decision chart

```mermaid
flowchart TD
  RX["UDP datagram"] --> V{"dt_validate:<br/>magic 0x5444, ver 1,<br/>known type, exact length?"}
  V -- no --> DROP["Drop silently"]
  V -- "yes, CUE" --> F{"cue_id <= 11, side <= 2,<br/>haptic_pattern <= 4,<br/>gains <= 0 or -128?"}
  F -- no --> BAD["ACK BAD_CUE (4)"]
  F -- yes --> S{"deadline_board_us == 0?"}
  S -- yes --> NS["ACK NO_SYNC (3)<br/>not played"]
  S -- no --> D{"now > deadline_board_us?"}
  D -- yes --> EX["ACK EXPIRED (1)<br/>never played late"]
  D -- no --> C["Clamp each gain to<br/>min(profile.ceiling_db, DT_FW_HARD_CEILING_DB = -10)"]
  C --> M{"Profile mode"}
  M -- "HAPTIC_ONLY" --> MUTE["Mute audio, haptic only"]
  M -- "SSD / STEREO" --> BUSY{"Sound playing and<br/>flags bit0 not set?"}
  BUSY -- "yes, cannot queue" --> BD["ACK BUSY_DROPPED (5)"]
  BUSY -- no --> PLAY["Render earcon at clamped L/R gains<br/>+ haptic pattern at min(haptic, haptic_max)"]
  MUTE --> PLAY
  PLAY --> K{"Clamp changed a gain?"}
  K -- yes --> AC["ACK PLAYED_CLAMPED (2)<br/>applied gains, t_board_us"]
  K -- no --> AP["ACK PLAYED (0)"]
```

[rendered](rendered/software-2.svg) · In SSD mode the phone sends identical L/R gains (Arch §9); the board plays what it is sent within the ceiling.
Haptic intensity limited by `haptic_max` follows PROFILE semantics; the exact BUSY policy is the firmware's.

## (b) Camera board (ESP32-CAM) capture / chunk / pace loop

```mermaid
flowchart TD
  B["Boot: LED GPIO13 off, flash LED GPIO4 off"] --> P{"PSRAM detected at runtime?"}
  P -- yes --> CFG1["JPEG QVGA-VGA, fb_count 2+<br/>(10-15 fps target, PROPOSED)"]
  P -- no --> CFG2["JPEG at CIF or lower, fb_count 1<br/>(driver limit without PSRAM)"]
  CFG1 --> INIT{"esp_camera_init ok?"}
  CFG2 --> INIT
  INIT -- no --> FAULT["Set fault bit 0 / 9<br/>keep BEAT + PONG running"]
  INIT -- yes --> J["Join hotspot, start BEAT 10 Hz + PING/PONG"]
  FAULT --> J
  J --> L["LED GPIO13 on while streaming"]
  L --> CAP["Grab frame, t_cap_us = esp_timer_get_time()"]
  CAP --> OK{"Frame ok?"}
  OK -- no --> F9["Fault bit 9 CAPTURE_FAIL"] --> PACE
  OK -- yes --> CH["Split JPEG into chunks of &le; 1400 B<br/>FRAME: frame_id, t_cap_us, w, h, total_len,<br/>offset, chunk_len, chunk_idx, chunk_count"]
  CH --> SEND["sendto phone :47000 per chunk"]
  SEND --> RET["Return frame buffer, frame_id++"]
  RET --> PACE["Pace to target period"]
  PACE --> CAP
```

[rendered](rendered/software-3.svg)

## (c) Phone pipeline (Arch §7.1)

```mermaid
flowchart LR
  UDP["UDP socket :47000"] --> PARSE{"Header valid?<br/>magic, ver, type, length"}
  PARSE -- no --> X["Drop"]
  PARSE -- yes --> ROUTE{"type"}
  ROUTE -- "BEAT" --> HEALTH["Health: last BEAT per board,<br/>fault bits, learn board IP"]
  ROUTE -- "PONG" --> SYNC["Clock sync per board<br/>offset/rtt NTP-style,<br/>lowest-RTT in window"]
  ROUTE -- "ACK" --> ACKS["Cue ACK tracker"]
  ROUTE -- "FRAME" --> ASM["Frame assembly<br/>drop if not complete 150 ms after first chunk"]
  ROUTE -- "RANGE" --> RG["Range grid"]
  ASM --> FG{"Freshness gate:<br/>offset-corrected age and receive age<br/>&le; 500 ms (PROPOSED)"}
  RG --> FG
  SYNC -.-> FG
  FG -- stale --> X
  FG -- "fresh JPEG" --> DET["Detector (MediaPipe, allowlist + threshold)<br/>labels + bearing only"]
  FG -- "fresh grid" --> COR["Range corridor<br/>centre cols x head-chest rows + L/R flanks<br/>min valid mm; status 5/9 VALID,<br/>255 NO_TARGET, else UNKNOWN"]
  DET --> FUS["Fusion: match detections to ToF regions by bearing;<br/>unmatched corridor return = obstacle"]
  COR --> FUS
  FUS --> POL["HazardPolicy<br/>at most one alert"]
  POL --> SCH["Cue scheduler<br/>band cadence, profile gains + ceiling,<br/>deadline = now + offset + ttl"]
  SCH --> OUT["Outbox.send(CUE, CUE packet)"]
  HEALTH --> SM["System state machine"]
  ACKS --> SM
  SM -- "UNAVAILABLE: suppress" --> SCH
  SM -- "UNAVAILABLE" --> ALERTS["PhoneAlerts: systemOff, vibrate"]
```

[rendered](rendered/software-4.svg)

## (d) HazardPolicy v0 (Arch §7.2; bands PROPOSED)

```mermaid
flowchart TD
  IN["Per ToF sample: corridor_min_mm, left_min_mm, right_min_mm<br/>(valid flags), detections, head_yaw_rate"] --> BAND["band(d): NEAR d < 1000; MID d < 2000;<br/>FAR d < 3000; else NONE (mm, PROPOSED)<br/>invalid region = UNKNOWN, never NONE/far"]
  BAND --> PRI["Pick top region by priority:<br/>NEAR corridor > NEAR flank > MID corridor<br/>> MID flank > FAR corridor"]
  PRI --> ACT{"Alert active?"}
  ACT -- no --> ENT{"Same region, band >= FAR<br/>for >= 3 consecutive samples<br/>(~200 ms @ 15 Hz)?"}
  ENT -- no --> Q["Quiet (silence)<br/>never CLEAR"]
  ENT -- yes --> RL{"Last onset < 700 ms ago?"}
  RL -- yes --> Q
  RL -- no --> YAW
  ACT -- yes --> EXIT{"Band NONE for >= 5 samples?"}
  EXIT -- yes --> END["End alert, Quiet<br/>never CLEAR"]
  EXIT -- no --> RC{"Region changed?"}
  RC -- yes --> RL
  RC -- no --> YAW{"head_yaw_rate high (scanning)<br/>and band FAR?"}
  YAW -- yes --> HOLD["Hold FAR cue"]
  YAW -- no --> LAB["Label: matched detection class if confident,<br/>else obstacle"]
  LAB --> ALERT["Alert(region, band, label)<br/>repeat cadence by band:<br/>FAR 1/s, MID 2.5/s, NEAR 5/s"]
  NOTE["NEAR always passes yaw suppression.<br/>Never speak distance without valid ToF.<br/>Describe never pre-empts NEAR."]
```

[rendered](rendered/software-5.svg)

## (e) System state machine (Arch §4.2; 500 ms bounds PROPOSED)

```mermaid
stateDiagram-v2
  [*] --> STARTING
  STARTING --> READY: frames fresh AND ToF ok AND cue ack
  READY --> DEGRADED: ToF invalid OR low light OR confidence low (announce once)
  READY --> UNAVAILABLE: frame age > 500 ms OR no BEAT > 500 ms from either board OR cue NACK OR phone battery critical
  DEGRADED --> UNAVAILABLE: same conditions as READY
  STARTING --> PAUSED: user pause (spoken confirmation)
  READY --> PAUSED: user pause
  DEGRADED --> PAUSED: user pause
  UNAVAILABLE --> PAUSED: user pause
  PAUSED --> STARTING: user resume
  UNAVAILABLE --> STARTING: links recover (retry link)
  note right of UNAVAILABLE
    system-off on any surviving output
    plus phone speaker/vibration;
    all obstacle cues suppressed
  end note
  note right of PAUSED
    explicit only, never automatic
  end note
```

[rendered](rendered/software-6.svg) · Arch §4.2 states "retry link" for UNAVAILABLE and does not spell out the
DEGRADED→READY and resume edges; `UNAVAILABLE → STARTING` and `PAUSED → STARTING` re-require the STARTING conditions ([INFERENCE], matches the demo "reconnect and wait for READY", Arch §11.4).

## (f) Calibration (Arch §9; staircase details PROPOSED)

```mermaid
flowchart TD
  FIT["1. Fit and device: seat frame,<br/>record output device + weaker side,<br/>warn on high ambient noise"] --> SEL["Next band x side<br/>bands 500 Hz, 1 kHz, 2 kHz; sides L, R"]
  SEL --> START["Start quiet level"]
  START --> PLAY["CUE CAL_TONE_500/1K/2K (ids 8-10)<br/>ttl 1000 ms, gain clamped to ceiling"]
  PLAY --> ANS{"Wearer taps heard?"}
  ANS -- "heard" --> TWO{"2nd consecutive heard?"}
  TWO -- yes --> DOWN["Level down one step"]
  TWO -- no --> PLAY
  ANS -- "not heard" --> UP{"Already at ceiling?"}
  UP -- yes --> UNUSE["Mark side unusable for band<br/>(never exceed ceiling)"]
  UP -- no --> UPS["Level up one step"]
  DOWN --> REV{"6-8 reversals reached?"}
  UPS --> REV
  REV -- no --> PLAY
  REV -- yes --> THR["Threshold (converges ~70.7%)"]
  THR --> MORE{"More band x side?"}
  UNUSE --> MORE
  MORE -- yes --> SEL
  MORE -- no --> LVL["3. Level = threshold + SL 10-20 dB<br/>(wearer choice), clamped to ceiling"]
  LVL --> BAL["4. Balance: alternate CAL_OBSTACLE (id 11) L/R,<br/>wearer nudges balance; repeat once"]
  BAL --> VER["5. Verify: 5 random-side cues,<br/>wearer names side"]
  VER --> MODE{"Mode"}
  MODE -- "wearer prefers / cannot use audio" --> HO["HAPTIC_ONLY"]
  MODE -- ">= 4 of 5 correct and no side at ceiling" --> ST["STEREO"]
  MODE -- "side undetectable at ceiling or naming at chance" --> SSD["SSD: mono audio,<br/>direction via haptics + spearcons"]
  HO --> PROF["HearingProfile (timestamped)<br/>send PROFILE ceiling_db, mode, haptic_max; expect ACK 0"]
  ST --> PROF
  SSD --> PROF
```

[rendered](rendered/software-7.svg) · Arch §9 defines STEREO (≥ 4/5, no side at ceiling) and SSD (side undetectable or chance); outcomes in between are not specified there.

## (g) Describe loop (Arch §7.3) — as implemented in `phone/core/describe` + `cloud/`

```mermaid
flowchart TD
  ASK["User long-press 'What's ahead?'<br/>or TalkBack action (never automatic)"] --> EN{"Opted in?"}
  EN -- no --> R1["DISABLED"]
  EN -- yes --> UN{"System UNAVAILABLE?"}
  UN -- yes --> R2["SYSTEM_UNAVAILABLE"]
  UN -- no --> BZ{"Request already in flight?"}
  BZ -- yes --> R3["BUSY"]
  BZ -- no --> FR{"Latest frame ≤ 500 ms old?"}
  FR -- no --> R4["NO_FRESH_FRAME"]
  FR -- yes --> RED["FaceRedactor on phone<br/>MediaPipe BlazeFace → pixelate faces"]
  RED -- "fails / no model" --> R5["REDACTION_FAILED<br/>say: Description unavailable · nothing uploaded"]
  RED -- ok --> UP["HTTPS POST /v1/describe<br/>describe-api on Lambda arm64 (Graviton2)"]
  UP --> INF["POST /v1/generate<br/>SmolVLM-256M on inf2.xlarge (Inferentia2)<br/>fallback: Bedrock Nova Lite"]
  INF --> FIL["Safety filter in the API:<br/>≤ 2 sentences, no 'clear/safe', no guesses about people"]
  FIL --> CHK["Phone re-checks: drop reassurance sentences"]
  CHK --> SPEAK["Speak: 'May be wrong.' + answer"]
  UP -. "8 s timeout / error" .-> FAIL["say: Description unavailable"]
  HZ{"MID or NEAR alert<br/>or UNAVAILABLE?"} -- yes --> INT["Cancel request, stop speech;<br/>hazard cue plays immediately"]
  UP -.-> HZ
  SPEAK -.-> HZ
```

[rendered](rendered/software-8.svg)

## (h) Module / package map (phone `core`, `cli`, Android app)

Matches the code as of this commit (`phone/core/src/main/kotlin/org/determine/core`, `phone/cli`,
`android/app/src/main/kotlin/org/determine/android`). Public API: `phone/core/API.md`.

```mermaid
flowchart LR
  subgraph core["phone/core — org.determine.core (pure Kotlin)"]
    API["Api.kt<br/>BoardId · Outbox · PhoneAlerts · EngineListener<br/>Detection · Alert · Diagnostics · CueCommand"]
    ENG["Engine.kt<br/>Engine · EngineConfig · LatencyStats"]
    WIRE["wire/Wire.kt<br/>codec for 8 packet types"]
    CLK["clock/ClockSync.kt<br/>min-RTT offset per board"]
    ING["ingest/Ingest.kt<br/>FrameAssembler · freshness"]
    RNG["range/RangeCorridor.kt<br/>corridor + flanks, UNKNOWN"]
    POL["policy/HazardPolicy.kt<br/>bands · hysteresis · priority"]
    CUE["cue/CueScheduler.kt<br/>cadence · side · gains · haptics"]
    SYS["system/SystemMonitor.kt<br/>STARTING/READY/DEGRADED/UNAVAILABLE/PAUSED"]
    HEAR["hearing/Hearing.kt · CalibrationSession.kt<br/>HearingProfile · Staircase · CalibrationSession"]
  end
  ENG --> WIRE & CLK & ING & RNG & POL & CUE & SYS & HEAR
  ENG --> API
  subgraph cli["phone/cli — org.determine.cli"]
    MAIN["Main.kt<br/>UDP :47000 · BoardDirectory · EventLog (stable stdout)"]
  end
  MAIN --> ENG
  subgraph app["android/app — org.determine.android"]
    SVC["service/DetermineService<br/>foreground service, restarts on fallback change"]
    HOST["runtime/EngineHost<br/>engine lock · 10 ms tick · HostOptions"]
    RT["runtime/DetermineRuntime<br/>state flows for UI"]
    UDP["net/UdpLink · BoardAddressBook<br/>implements Outbox"]
    DET["detect/DetectorRunner · FramePacer<br/>MediaPipe EfficientDet-Lite0"]
    ALR["alerts/AndroidPhoneAlerts<br/>TTS · Vibrator · system-off · DescribeSpeech"]
    CAMS["camera/PhoneCameraSource<br/>Tier 0 CameraX fallback"]
    CUES["audio/PhoneCueSink · CueBank<br/>phone-audio cue fallback"]
    DSC["describe/FaceRedactor · HttpDescribeTransport"]
    PWR["power/BatteryCriticalGate"]
    UI["ui/ Main · Judge · Calibration · Settings screens"]
    SET["settings/UserSettings"]
  end
  DESC["core describe/DescribeController"]
  SVC --> HOST
  HOST --> ENG
  HOST --> DESC
  DESC --> ENG
  HOST --> UDP & DET & ALR & PWR & CAMS & CUES & DSC
  HOST --> RT
  RT --> UI
  UI --> SET
  DSC -. HTTPS .-> CLOUD["cloud/describe-api → cloud/inference"]
  SIM["tools/sim<br/>sim_boards · bench · e2e"] -. UDP .-> MAIN
```

[rendered](rendered/software-9.svg)
