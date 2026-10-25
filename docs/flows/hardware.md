# Determine — hardware flow maps

Grounded in `protocol/PROTOCOL.md` (wire **v2**), `firmware/common/include/determine_wire.h`,
and `Determine-Architecture.html` §4–§6, §8, §12–§13. Numbers marked **PROPOSED** are the
architecture's untuned values (tuned at Gates G6–G8); nothing here is measured.

Design basis (updated 24 Oct 2026 from the bench): ONE Arduino Modulino Distance (VL53L4ED) as a single forward ToF zone, one
ESP32-CAM from the pair (the second is a spare, not a stereo partner), and the XIAO ESP32-C3 driving ONE Arduino Modulino Vibro
(a single I2C-controlled vibration motor) as the haptic output. The firmware cannot lateralise: it drives the Vibro at
max(haptic_l, haptic_r). An Adafruit DRV2605L breakout is in the Qwiic chain only as the source of the 10 kΩ SDA/SCL pull-ups; no motor is
attached to it and the firmware does not drive it. There is no I2C mux. Phone-to-Bluetooth-headset audio
is the implemented software/bench path, not approved integrated hardware. The separate OpenRun is not
approved; integrated bone-conduction output is unresolved. No wearable performance or latency is validated. Houston stock snapshot and unadopted regulator/motor
candidates: Architecture §12.2 (24 Oct 2026 ~1:40 PM CDT). Micro Center-only; no shipped fallback.

Rendered copies: [`rendered/`](rendered/) (one SVG per diagram, linked under each block). The checked-in SVGs
are regenerated from these sources with `render.py`; regenerate after editing.

## (a) Physical layout / topology (Arch §5.3–§5.4)

Only the camera and the Modulino Distance face forward. The C3 and the I2C haptic chain are bench-wired; the battery
stays in a pocket. The phone and the headset are separate finished products. Two separately powered 5 V
branches, common signal ground.

```mermaid
flowchart LR
  subgraph FRONT["Front of headband (faces forward)"]
    CAM["AI-Thinker ESP32-CAM<br/>OV2640 typ. + rec. LED GPIO13"]
    TOF["Arduino Modulino Distance<br/>VL53L4ED, ONE forward zone<br/>spec 1200 mm indoors, 18 deg diag FOV"]
  end
  subgraph HAPTIC["I2C haptic chain (Qwiic cables)"]
    DRV["Adafruit DRV2605L breakout 0x5A<br/>10 kohm SDA/SCL pull-up source only<br/>no motor, not driven by firmware"]
    VIB["Arduino Modulino Vibro 0x38<br/>ONE I2C vibration motor, own MCU<br/>driven at max(haptic_l, haptic_r)"]
  end
  subgraph REAR["Rear strap"]
    C3["Seeed XIAO ESP32-C3<br/>cue board, haptics only"]
  end
  subgraph POCKET["Pocket"]
    BANK["5 V USB power bank<br/>(two simultaneous outputs)"]
  end
  subgraph PHONEHEAD["Carried / worn finished products"]
    PHONE["Android phone<br/>decides, synthesises cue audio"]
    HEAD["Bluetooth headset bench path<br/>integrated audio unresolved"]
  end
  BANK -- "5 V branch 1 (USB-C)" --> C3
  BANK -- "5 V branch 2 (USB-to-5V/GND breakout)" --> CAM
  C3 -- "4 soldered wires: 3V3, GND, SDA, SCL" --- TOF
  TOF -- "Qwiic" --- DRV
  DRV -- "Qwiic" --- VIB
  C3 -. "Wi-Fi: RANGE, BEAT, ACK" .-> PHONE
  CAM -. "Wi-Fi: FRAME, BEAT" .-> PHONE
  PHONE -. "Wi-Fi: CUE, PROFILE, PING" .-> C3
  PHONE -- "Bluetooth A2DP audio<br/>route loss announced, never phone speaker" --> HEAD
  GND(["Common signal ground<br/>(C3 + camera + branches)"])
  C3 -.- GND
  CAM -.- GND
```

[rendered](rendered/hardware-1.svg) · Rail assignment beyond "C3 on branch 1, camera on branch 2": the Modulino Distance, the DRV2605L breakout and the Modulino Vibro all
sit on the 3.3 V rail from the C3 3V3 pin (bench wiring). No separate regulator is adopted (LM3671 sold out, TPS62827 candidate not adopted). The Vibro's motor
draws about 67 mA typ / 85 mA max per the Arduino datasheet; that is not measured on this rail. This departs from the Arch §5.4 rule that motors are not powered from
the C3 3V3 pin. There are no amplifiers in this revision.

## (b) Electrical buses (Arch §5.3–§5.4; shared hardware facts)

```mermaid
flowchart LR
  subgraph C3["XIAO ESP32-C3 (cue board)"]
    SDA["GPIO6 / D4 SDA"]
    SCL["GPIO7 / D5 SCL"]
    LED["GPIO10 / D10 status LED"]
  end
  subgraph I2C["I2C bus (one hardware bus, 400 kHz)"]
    TOF["Modulino Distance VL53L4ED 0x29<br/>(4 soldered wires: 3V3, GND, SDA, SCL)"]
    DRV["DRV2605L breakout 0x5A<br/>10 kohm SDA/SCL pull-ups only<br/>no motor, not driven by firmware"]
    VIB["Modulino Vibro 0x38<br/>one vibration motor, own MCU"]
  end
  SDA --- TOF
  SCL --- TOF
  TOF -- "Qwiic" --- DRV
  DRV -- "Qwiic" --- VIB
  RAIL["3.3 V rail from C3 3V3 pin<br/>(Distance, DRV2605L, Vibro)"] -.- I2C
  LED --> SLED["Status LED"]
  subgraph CAMB["AI-Thinker ESP32-CAM (camera board)"]
    DVP["DVP camera bus (on-module)"]
    G13["GPIO13 recording LED<br/>on while streaming"]
    G4["GPIO4 flash LED<br/>held OFF"]
    UART["U0TXD GPIO1 / U0RXD GPIO3<br/>+ IO0 flash jumper"]
  end
  DVP --- OV["OV2640 typ.<br/>(sensor + PSRAM detected at runtime)"]
  G13 --> RLED["Front recording LED"]
  FTDI["FTDI USB-UART (3.3 V logic)"] -- "programming only" --- UART
  PC["Laptop"] -- "USB" --- FTDI
  PC -- "USB-C (flash + power)" --- C3
```

[rendered](rendered/hardware-2.svg)

| Board | GPIO | Pin | Function | Notes |
|---|---|---|---|---|
| XIAO C3 | 6 | D4 | I²C SDA | Modulino Distance 0x29, Modulino Vibro 0x38, DRV2605L 0x5A (pull-ups only) |
| XIAO C3 | 7 | D5 | I²C SCL | same bus |
| XIAO C3 | — | 3V3 | 3.3 V rail | Distance (4 soldered wires), DRV2605L and Vibro via Qwiic chain |
| XIAO C3 | 10 | D10 | Status LED | |
| XIAO C3 | 2, 8, 9 | — | avoided | strapping pins |
| Qwiic chain | — | — | Distance → DRV2605L → Vibro | DRV2605L only supplies 10 kΩ SDA/SCL pull-ups; Vibro power 0–50 from intensity 0–255 |
| ESP32-CAM | 13 | IO13 | Recording LED | on while streaming |
| ESP32-CAM | 4 | IO4 | Flash LED | held off |
| ESP32-CAM | 1 / 3 | U0T / U0R | FTDI UART | programming; IO0→GND to flash |

The v1 I²S pins (D1–D3) are unused in v2. The firmware keeps an untested `DT_HAPTIC_MOSFET` build flag (GPIO21/20 PWM) from an earlier
revision; the architecture's shopping list contains no parts for it, so treat it as unsupported.

## (c) Network / data topology (PROTOCOL.md "Transport", "Rates")

Both boards are **stations** on the phone hotspot and send to their DHCP gateway; the phone
learns each board's IP from its first packet. No board-to-board bus (Arch §4, Fig 4.1). Header is 8 B:
magic 0x5444, **ver 2**, type, seq u32 LE. Audio never travels on this network: it goes phone → headset over Bluetooth.

```mermaid
flowchart LR
  subgraph HOT["Phone Wi-Fi hotspot (one channel, shared airtime)"]
    PHONE["Android phone<br/>UDP :47000<br/>(only integrator, decides hazards)"]
  end
  CUE["Cue board XIAO ESP32-C3<br/>station, UDP :47001"]
  CAM["Camera board ESP32-CAM<br/>station, UDP :47002"]
  HEAD["Bluetooth headset bench path<br/>not approved integrated hardware"]
  CAM -- "BEAT 0x01, 24 B, 10 Hz*" --> PHONE
  CAM -- "FRAME 0x03, chunks of 38+&le;1400 B, 10-15 fps* JPEG QVGA-VGA" --> PHONE
  CAM -- "PONG 0x11, 32 B, reply per PING" --> PHONE
  PHONE -- "PING 0x10, 16 B, 10 Hz*" --> CAM
  CUE -- "BEAT 0x01, 24 B, 10 Hz*" --> PHONE
  CUE -- "RANGE 0x02, 19 B (mm u16 + status u8), 15 Hz*" --> PHONE
  CUE -- "PONG 0x11, 32 B, reply per PING" --> PHONE
  CUE -- "ACK 0x21, 22 B, per CUE/PROFILE" --> PHONE
  PHONE -- "PING 0x10, 16 B, 10 Hz* (also watchdog liveness)" --> CUE
  PHONE -- "CUE 0x20, 32 B, one per repetition, ttl 150 ms*, haptics only" --> CUE
  PHONE -- "PROFILE 0x22, 10 B (mode, haptic_max 1..255), on change" --> CUE
  PHONE -. "cue audio (A2DP)" .-> HEAD
  NOTE["* PROPOSED rates (PROTOCOL.md Rates and bounds)<br/>RANGE status: 0 VALID, 1 NO_TARGET, 2 UNKNOWN"]
```

[rendered](rendered/hardware-3.svg)

## (d) Sequences

### Boot and join

```mermaid
sequenceDiagram
  autonumber
  participant CUE as Cue board (C3)
  participant CAM as Camera board
  participant PH as Phone :47000
  participant HEAD as Bone headset (BT)
  Note over CUE,CAM: Power from pocket bank (two branches)
  CUE->>CUE: init I2C 400 kHz, Distance VL53L4ED (probe 0x29, continuous ranging), Vibro (0x38)
  CUE->>PH: join hotspot as station (DHCP), gateway = phone
  CAM->>CAM: camera init, detect PSRAM, LED GPIO13 off
  CAM->>PH: join hotspot as station (DHCP)
  CUE->>PH: first BEAT (board=2, reset_reason, faults incl. bit6 NO_CLOCK_SYNC)
  PH->>PH: learn cue board IP from first packet
  CAM->>PH: first BEAT (board=1)
  PH->>PH: learn camera IP
  loop 10 Hz per board (PROPOSED)
    PH->>CUE: PING t_phone_us
    CUE->>PH: PONG echo, t_board_rx_us, t_board_tx_us
    PH->>CAM: PING t_phone_us
    CAM->>PH: PONG echo, rx, tx
    PH->>PH: offset = ((rx - t_phone) + (tx - t4)) / 2, keep lowest-RTT sample in window
  end
  PH->>CUE: PROFILE mode, haptic_max (1..255)
  CUE->>PH: ACK status 0 APPLIED (cue_seq = PROFILE seq)
  CUE-->>PH: RANGE 15 Hz
  CAM->>CAM: LED GPIO13 on while streaming
  CAM-->>PH: FRAME chunks
  PH->>PH: confirm headset route is the active cue output (no silent speaker fallback)
  PH->>HEAD: synthesise + play READY (cue_id 0)
  PH->>CUE: CUE READY (cue_id 0, haptic tick)
  CUE->>PH: ACK APPLIED
  Note over PH: STARTING to READY only when frames fresh, ToF ok, cue ack and headset route confirmed (Arch 4.2)
  Note over CUE: If no PING within 5 s of boot: WATCHDOG (haptic LSS on the Vibro every 5 s, no sound)
```

[rendered](rendered/hardware-4.svg)

### Steady-state cue round trip with timestamps

Latency budget (Arch §6.3, all PROPOSED): exposure + JPEG ≤ 60 ms, Wi-Fi ≤ 40 ms, band + policy ≤ 80 ms,
CUE dispatch + haptic start ≤ 40 ms → range-sample-to-haptic p95 ≤ 250 ms. Headset audio adds 100–200 ms of
Bluetooth A2DP latency; report the audio and haptic legs separately. The ToF path does not use the camera stages.

```mermaid
sequenceDiagram
  autonumber
  participant CAM as Camera board
  participant CUE as Cue board (C3)
  participant PH as Phone
  participant HEAD as Bone headset (BT)
  CAM->>CAM: capture JPEG, t_cap_us (board clock)
  CAM->>PH: FRAME chunks (frame_id, t_cap_us, offset, chunk_idx/count)
  PH->>PH: rx time t_rx, reassemble, drop if incomplete 150 ms after first chunk (Describe and diagnostics only)
  CUE->>CUE: VL53L4ED sample ready, t_us (board clock)
  CUE->>PH: RANGE t_us, mm, status (0 VALID / 1 NO_TARGET / 2 UNKNOWN)
  PH->>PH: freshness gate: offset-corrected age and receive age <= 500 ms (PROPOSED)
  PH->>PH: band: NEAR under 500, MID 500-899, FAR 900-1200 mm (PROPOSED), one alert, never CLEAR
  PH->>PH: deadline_board_us = now_phone + offset(CUE) + ttl (150 ms PROPOSED)
  PH->>HEAD: cue audio synthesised on phone, played at calibrated level under the ceiling
  PH->>CUE: CUE t_issued_phone_us, deadline_board_us, cue_id, side, haptic_pattern, haptic_l, haptic_r, flags, ttl_ms
  alt deadline 0 (no sync)
    CUE->>PH: ACK NO_SYNC (3), not played
  else now > deadline
    CUE->>PH: ACK EXPIRED (1), not played
  else invalid CUE (side > 2, unknown pattern)
    CUE->>PH: ACK BAD_CUE (4)
  else valid
    CUE->>CUE: clamp haptic_l/r to PROFILE haptic_max, drive Vibro at max(haptic_l, haptic_r) (1000 ms device auto-off)
    CUE->>PH: ACK APPLIED (0) or APPLIED_CLAMPED (2), t_board_us
  end
  PH->>PH: log stage timestamps (Gate G6), next repetition = new CUE at band period
```

[rendered](rendered/hardware-5.svg)

### Failure paths (Arch §4.2, §13 G7; PROTOCOL.md Watchdog)

```mermaid
sequenceDiagram
  autonumber
  participant CAM as Camera board
  participant CUE as Cue board (C3)
  participant PH as Phone
  participant HEAD as Bone headset (BT)
  participant VIB as Phone vibration
  rect rgb(255,235,235)
  Note over CUE,PH: 1. Cue board loses power
  CUE--xPH: BEAT stops, CUE gets no ACK
  PH->>PH: no BEAT > 500 ms (PROPOSED) or missing ACK: UNAVAILABLE
  PH->>HEAD: system-off on headset audio
  PH->>VIB: phone vibration (the camera board has no haptics)
  PH->>PH: suppress all obstacle cues, keep PINGing to retry link
  end
  rect rgb(235,235,255)
  Note over CUE,PH: 2. Phone silent (app killed, Wi-Fi off)
  PH--xCUE: PINGs stop
  CUE->>CUE: no PING for 800 ms: WATCHDOG, set fault bit 7
  loop every 5 s until PINGs resume
    CUE->>CUE: haptic LSS on the single Vibro at haptic_max (>= 1). No sound: the phone owns audio and is gone
  end
  PH->>CUE: PING resumes
  CUE->>CUE: leave WATCHDOG, clear bit 7
  end
  rect rgb(235,255,235)
  Note over CAM,PH: 3. Camera unplugged
  CAM--xPH: BEAT and FRAME stop
  PH->>PH: frame age > 500 ms or no BEAT > 500 ms: UNAVAILABLE
  PH->>HEAD: system-off cue
  PH->>CUE: CUE SYSTEM_OFF (cue_id 7, haptic LSS) every 5 s on surviving cue board
  CUE->>PH: ACK
  PH->>VIB: phone vibration
  Note over PH: Obstacle cues suppressed, recovery requires STARTING conditions again
  end
  rect rgb(255,250,225)
  Note over PH,HEAD: 4. Headset route lost (disconnect, multipoint steal)
  HEAD--xPH: Bluetooth route gone
  PH->>PH: route check fails: UNAVAILABLE, never fall back to the phone speaker
  PH->>CUE: CUE SYSTEM_OFF haptic LSS
  PH->>VIB: phone vibration
  end
```

[rendered](rendered/hardware-6.svg) · Target: unavailable cue ≤ 1 s after a fault (G7, PROPOSED). A phone that dies leaves only the board's haptic buzz.
