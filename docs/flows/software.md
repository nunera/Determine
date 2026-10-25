# Determine — software flowcharts

Grounded in `protocol/PROTOCOL.md` (wire **v2**), `firmware/common/include/determine_wire.h` and
`Determine-Architecture.html` §4.2, §7.1–§7.3, §8, §9. **PROPOSED** marks untuned numbers from the docs.
v2 basis: one forward ToF zone (VL53L4ED), scalar RANGE, haptic-only cue board (one Modulino Vibro, no lateral haptics), phone-synthesised cue audio on a
Bluetooth bone-conduction headset. No grid, no board audio, no lateral range.
The audio path is implemented software/bench behavior only: the separate OpenRun is not approved, and integrated bone-conduction hardware remains unresolved (Architecture §12.2–12.4).

Rendered copies: [`rendered/`](rendered/). The checked-in SVGs are regenerated from these sources with
`render.py`; regenerate after editing.

## (a) Cue-board firmware (XIAO ESP32-C3)

### Tasks

```mermaid
flowchart TB
  BOOT["Boot: record esp_reset_reason()<br/>init I2C 400 kHz, VL53L4ED (probe 0x29), Modulino Vibro (0x38)<br/>init failures set fault bits 0/3/4 (bit 2 reserved; bits 3 and 4 both reflect the single Vibro)"] --> WIFI["Join phone hotspot (station)"]
  WIFI --> TASKS
  subgraph TASKS["Concurrent tasks"]
    NET["Network loop<br/>recv UDP :47001<br/>PING: PONG + feed watchdog<br/>CUE: CUE handler<br/>PROFILE: store mode, haptic_max (1..255); 0 = BAD_CUE; ACK"]
    TOF["ToF task 15 Hz<br/>one VL53L4ED zone<br/>raw status 0: VALID, mm<br/>all other statuses or read errors: UNKNOWN, mm 0<br/>empty or out-of-range is also UNKNOWN<br/>send RANGE t_us, mm, status"]
    HAP["Haptic task<br/>pattern NONE/PULSE40/PULSE60/BUZZ150/LSS<br/>one Modulino Vibro (I2C 0x38) at max(haptic_l, haptic_r)<br/>intensity clamped to haptic_max, 0..255 maps to Vibro power 0..50<br/>each ON write carries a 1000 ms device-side auto-off<br/>flags bit0 interrupts current pattern"]
    WD["Watchdog<br/>no PING 800 ms, or none 5 s after boot<br/>haptic LSS every 5 s on the Vibro at haptic_max<br/>no sound; fault bit 7 until PINGs resume"]
    BEAT["Beat task 10 Hz<br/>BEAT board=2, faults, t_us, RSSI, temp, heap"]
  end
  NET -- "pattern + intensity" --> HAP
  NET -- "PING seen" --> WD
  WD -- "LSS" --> HAP
```

[rendered](rendered/software-1.svg)

### CUE handling decision chart

```mermaid
flowchart TD
  RX["UDP datagram"] --> V{"dt_validate:<br/>magic 0x5444, ver 2,<br/>known type, exact length?"}
  V -- no --> DROP["Drop silently"]
  V -- "yes, CUE" --> F{"cue_id <= 11, side <= 2,<br/>haptic_pattern <= 4?"}
  F -- no --> BAD["ACK BAD_CUE (4)"]
  F -- yes --> S{"deadline_board_us == 0?"}
  S -- yes --> NS["ACK NO_SYNC (3)<br/>not played"]
  S -- no --> D{"now > deadline_board_us?"}
  D -- yes --> EX["ACK EXPIRED (1)<br/>never played late"]
  D -- no --> BUSY{"Haptic pattern playing and<br/>flags bit0 not set?"}
  BUSY -- "yes, cannot queue" --> BD["ACK BUSY_DROPPED (5)"]
  BUSY -- no --> C["Clamp haptic_l and haptic_r to profile.haptic_max<br/>single Vibro runs at max(haptic_l, haptic_r); side cannot lateralise"]
  C --> PLAY["Play haptic_pattern on the Modulino Vibro<br/>(power 0..50, 1000 ms device auto-off)"]
  PLAY --> K{"haptic_max lowered a requested intensity?"}
  K -- yes --> AC["ACK APPLIED_CLAMPED (2)<br/>t_board_us"]
  K -- no --> AP["ACK APPLIED (0)"]
```

[rendered](rendered/software-2.svg) · The board makes no sound in any profile mode; `mode` (STEREO/SSD/HAPTIC_ONLY) is phone
audio-route semantics. Side exclusion (L/R zeroing the other channel) is not clamping. An unknown `mode` or `haptic_max` = 0 in a
PROFILE is also ACK BAD_CUE (haptic_max must be 1..255 so the watchdog LSS can never be configured silent). The exact BUSY policy is
the firmware's.

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
  UDP["UDP socket :47000"] --> PARSE{"Header valid?<br/>magic, ver 2, type, length"}
  PARSE -- no --> X["Drop"]
  PARSE -- yes --> ROUTE{"type"}
  ROUTE -- "BEAT" --> HEALTH["Health: last BEAT per board,<br/>fault bits, learn board IP"]
  ROUTE -- "PONG" --> SYNC["Clock sync per board<br/>offset/rtt NTP-style,<br/>lowest-RTT in window"]
  ROUTE -- "ACK" --> ACKS["Cue ACK tracker"]
  ROUTE -- "FRAME" --> ASM["Frame assembly<br/>drop if not complete 150 ms after first chunk"]
  ROUTE -- "RANGE" --> RG["Forward range<br/>mm + status: 0 VALID, 1 NO_TARGET, 2 UNKNOWN"]
  ASM --> FRAMEUSE["Frame use: Describe path and<br/>context display only, never the hazard decision"]
  RG --> FG{"Freshness gate:<br/>offset-corrected age and receive age<br/>&le; 500 ms (PROPOSED)"}
  SYNC -.-> FG
  FG -- stale --> X
  FG -- "fresh sample" --> BAND["Band the single distance<br/>NEAR under 500, MID 500-899, FAR 900-1200 mm<br/>VALID only; NO_TARGET = nothing to report;<br/>UNKNOWN never far, never none"]
  BAND --> POL["HazardPolicy<br/>at most one alert, no side, never CLEAR"]
  POL --> SCH["Cue scheduler<br/>band cadence, deadline = now + offset + ttl"]
  SCH --> AUD["Phone cue synthesis + HearingProfile<br/>calibrated level under the ceiling<br/>to the Bluetooth bone headset"]
  SCH --> OUT["Outbox.send(CUE packet)<br/>haptics only, side BOTH"]
  AUD --> RT{"Headset route active?"}
  RT -- no --> RF["Explicit route fault: UNAVAILABLE<br/>never the phone speaker"]
  HEALTH --> SM["System state machine"]
  ACKS --> SM
  RF --> SM
  SM -- "UNAVAILABLE: suppress" --> SCH
  SM -- "UNAVAILABLE" --> ALERTS["PhoneAlerts: systemOff on headset if alive,<br/>CUE SYSTEM_OFF haptics, vibrate"]
```

[rendered](rendered/software-4.svg)

## (d) HazardPolicy v0 (Arch §7.2; bands PROPOSED)

```mermaid
flowchart TD
  IN["Per range sample: mm + status<br/>head_yaw_rate"] --> BAND["band(d), VALID only: NEAR d < 500;<br/>MID d < 900; FAR d <= 1200; else NONE (mm, PROPOSED)<br/>NO_TARGET = NONE, nothing to report<br/>UNKNOWN = never far, never none"]
  BAND -- "UNKNOWN: no supported distance" --> UNKNOWN["Cancel active metric alert<br/>announce DEGRADED<br/>fresh samples required to re-enter"]
  BAND -- "known reading" --> ACT{"Alert active?"}
  ACT -- no --> ENT{"Same band >= FAR<br/>for >= 3 consecutive VALID samples<br/>(~200 ms @ 15 Hz)?"}
  ENT -- no --> Q["Quiet (silence)<br/>never CLEAR"]
  ENT -- yes --> RL{"Last onset < 700 ms ago?"}
  RL -- yes --> Q
  RL -- no --> YAW
  ACT -- yes --> EXIT{"Band NONE (valid-far or NO_TARGET)<br/>for >= 5 samples?<br/>UNKNOWN never counts toward exit"}
  EXIT -- yes --> END["End alert, Quiet<br/>never CLEAR"]
  EXIT -- no --> RC{"Band changed?"}
  RC -- yes --> RL
  RC -- no --> YAW{"head_yaw_rate high (scanning)<br/>and band FAR?"}
  YAW -- yes --> HOLD["Hold FAR cue"]
  YAW -- no --> ALERT["Alert(band, distance)<br/>generic obstacle, side BOTH<br/>repeat cadence by band:<br/>FAR 1/s, MID 2.5/s, NEAR 5/s"]
  NOTE["NEAR and MID always pass yaw suppression.<br/>No left or right. No camera object attached.<br/>Never speak distance without a VALID sample.<br/>Describe never pre-empts NEAR."]
```

[rendered](rendered/software-5.svg)

## (e) System state machine (Arch §4.2; 500 ms bounds PROPOSED)

```mermaid
stateDiagram-v2
  [*] --> STARTING
  STARTING --> READY: frames fresh AND ToF ok AND cue ack AND (HAPTIC_ONLY OR headset route confirmed)
  STARTING --> DEGRADED: links and outputs confirmed AND fresh UNKNOWN range
  READY --> DEGRADED: ToF UNKNOWN OR range stale (stop metric cues, announce once)
  READY --> UNAVAILABLE: stale frame OR missing BEAT OR repeated cue failure OR enabled audio route lost OR battery critical
  DEGRADED --> UNAVAILABLE: same conditions as READY
  STARTING --> PAUSED: user pause (spoken confirmation)
  READY --> PAUSED: user pause
  DEGRADED --> PAUSED: user pause
  UNAVAILABLE --> PAUSED: user pause
  PAUSED --> STARTING: user resume
  UNAVAILABLE --> STARTING: links recover (retry link)
  note right of UNAVAILABLE
    system-off on every surviving output:
    headset audio, board haptics (single Vibro), phone vibration;
    never silently the phone speaker;
    all obstacle cues suppressed
  end note
  note right of PAUSED
    explicit only, never automatic
  end note
```

[rendered](rendered/software-6.svg) · Arch §4.2 states "retry link" for UNAVAILABLE and does not spell out the
DEGRADED→READY and resume edges; `UNAVAILABLE → STARTING` and `PAUSED → STARTING` re-require the STARTING conditions ([INFERENCE], matches the demo "reconnect and wait for READY", Arch §11.4). The headset-route conditions follow Arch §4.2.

## (f) Calibration (Arch §9; staircase details PROPOSED)

Cue tones are synthesised on the phone and played on the Bluetooth bone headset; calibration refuses to start if that route is not the active cue output.

```mermaid
flowchart TD
  FIT["1. Fit and device: seat headset,<br/>require bone-headset route (no speaker fallback),<br/>record weaker side, warn on high ambient noise"] --> SEL["Next band x side<br/>bands 500 Hz, 1 kHz, 2 kHz; sides L, R"]
  SEL --> START["Start quiet level"]
  START --> PLAY["Phone plays CAL_TONE_500/1K/2K (ids 8-10)<br/>level clamped to the phone-side ceiling"]
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
  BAL --> VER["5. Verify: 5 random-side calibration cues,<br/>wearer names side"]
  VER --> MODE{"Mode"}
  MODE -- "wearer prefers / cannot use audio" --> HO["HAPTIC_ONLY: warning audio off<br/>no headset required"]
  MODE -- ">= 4 of 5 correct and no side at ceiling" --> ST["STEREO"]
  MODE -- "side undetectable at ceiling or naming at chance" --> SSD["SSD: mono audio<br/>(hazard cues carry no side anyway)"]
  HO --> PROF["HearingProfile (timestamped)<br/>send PROFILE mode, haptic_max (1..255); expect ACK APPLIED"]
  ST --> PROF
  SSD --> PROF
```

[rendered](rendered/software-7.svg) · Arch §9 defines STEREO (≥ 4/5, no side at ceiling) and SSD (side undetectable or chance); outcomes in between are not specified there. `haptic_max` has a floor of 1: the haptic channel cannot be configured away, but 1 is not a measured guarantee of perceptibility.

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
  CHK --> SPEAK["Speak the answer as returned"]
  UP -. "8 s timeout / error" .-> FAIL["say: Description unavailable"]
  HZ{"MID or NEAR alert<br/>or UNAVAILABLE?"} -- yes --> INT["Cancel request, stop speech;<br/>hazard cue plays immediately"]
  UP -.-> HZ
  SPEAK -.-> HZ
```

[rendered](rendered/software-8.svg)

## (h) Module / package map (phone `core`, `cli`, shared app layer, Android and desktop apps)

Matches the code in the working tree (`phone/core/src/main/kotlin/org/determine/core`, `phone/cli`,
`app-shared/src/main/kotlin/org/determine/app`, `android/app/…/org/determine/android`,
`desktop/…/org/determine/desktop`). Public API: `phone/core/API.md`.

```mermaid
flowchart LR
  subgraph core["phone/core — org.determine.core (pure Kotlin)"]
    API["Api.kt<br/>BoardId · Outbox · PhoneAlerts · EngineListener<br/>Alert · Diagnostics · CueCommand"]
    ENG["Engine.kt<br/>Engine · EngineConfig · LatencyStats"]
    WIRE["wire/Wire.kt<br/>codec for 8 packet types, ver 2"]
    CLK["clock/ClockSync.kt<br/>min-RTT offset per board"]
    ING["ingest/Ingest.kt<br/>FrameAssembler · freshness"]
    RNG["range/ForwardRange.kt<br/>one forward zone: Target · NothingSeen · Unknown"]
    POL["policy/HazardPolicy.kt<br/>bands · hysteresis, no side"]
    CUE["cue/CueScheduler.kt<br/>cadence · haptics · side BOTH"]
    SYS["system/SystemMonitor.kt<br/>STARTING/READY/DEGRADED/UNAVAILABLE/PAUSED"]
    HEAR["hearing/Hearing.kt · CalibrationSession.kt<br/>HearingProfile · Staircase · CalibrationSession"]
  end
  ENG --> WIRE & CLK & ING & RNG & POL & CUE & SYS & HEAR
  ENG --> API
  subgraph cli["phone/cli — org.determine.cli"]
    MAIN["Main.kt<br/>UDP :47000 · BoardDirectory · EventLog (stable stdout)"]
  end
  MAIN --> ENG
  subgraph shared["app-shared — org.determine.app (compiled into both apps)"]
    UI["ui/ DetermineApp · Main · Judge · Calibration · Settings"]
    SESS["runtime/DetermineSession<br/>starts/restarts EngineHost on setting changes"]
    HOST["runtime/EngineHost<br/>engine lock · 10 ms tick · HostOptions"]
    HP["runtime/HostPlatform<br/>clock · loop · alerts · cue sink · camera · redactor"]
    RT["runtime/DetermineRuntime<br/>state flows for UI"]
    UDP["net/UdpLink · BoardAddressBook<br/>implements Outbox"]
    CUES["audio/CueBank · PhoneSounds<br/>cue bank and system-off tone"]
    DSC["describe/HttpDescribeTransport"]
    AP["platform/AppPlatform · SettingsStore"]
  end
  DESC["core describe/DescribeController"]
  subgraph app["android/app — org.determine.android"]
    SVC["service/DetermineService<br/>foreground service"]
    AHP["runtime/AndroidHostPlatform<br/>HandlerThread loop"]
    ALR["alerts/AndroidPhoneAlerts<br/>TTS · Vibrator"]
    CAMS["camera/PhoneCameraSource<br/>Tier 0 CameraX"]
    PCS["audio/PhoneCueSink<br/>Bluetooth-only AudioTrack · explicit route failure"]
    RED["describe/FaceRedactor<br/>BlazeFace"]
    PWR["power/BatteryCriticalGate"]
    SET["settings/UserSettings · MainActivity"]
  end
  subgraph desk["desktop — org.determine.desktop"]
    DMAIN["Main.kt<br/>window · --sim launcher"]
    DHP["DesktopHostPlatform<br/>Java Sound · espeak-ng<br/>no verified headset · no camera or redactor"]
    DSET["DesktopSettings<br/>java.util.prefs"]
  end
  SVC --> SESS
  DMAIN --> SESS
  SESS --> HOST
  HOST --> ENG
  HOST --> DESC
  DESC --> ENG
  HOST --> HP & UDP & DSC & RT
  RT --> UI
  UI --> AP
  AHP -. implements .-> HP
  DHP -. implements .-> HP
  AHP --> ALR & CAMS & PCS & RED
  PCS --> CUES
  DHP --> CUES
  SVC --> PWR
  SET -. implements .-> AP
  DSET -. implements .-> AP
  DSC -. HTTPS .-> CLOUD["cloud/describe-api → cloud/inference"]
  SIM["tools/sim<br/>sim_boards · bench · e2e"] -. UDP .-> MAIN
```

[rendered](rendered/software-9.svg) · The module names track the working tree while the v2 cutover is in progress; re-check against
`phone/core/API.md` when regenerating.
