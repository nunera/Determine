# Determine — hardware flow maps

Grounded in `protocol/PROTOCOL.md` (wire v1, frozen), `firmware/common/include/determine_wire.h`,
and `Determine-Architecture.html` §4–§6, §8, §13. Numbers marked **PROPOSED** are the
architecture's untuned values (tuned at Gates G6–G7); nothing here is measured.

Rendered copies: [`rendered/`](rendered/) (one SVG per diagram, linked under each block).

## (a) Physical layout / topology (Arch §5.3–§5.4)

Only the camera and the Mini ToF face forward. Control boards sit on the rear strap; the
battery stays in a pocket. Two separately powered 5 V branches, common signal ground.

```mermaid
flowchart LR
  subgraph FRONT["Front of headband (faces forward)"]
    CAM["AI-Thinker ESP32-CAM<br/>OV2640 typ. + rec. LED GPIO13"]
    TOF["SparkFun Qwiic Mini VL53L5CX<br/>8x8 ToF, pitched level"]
  end
  subgraph TEMPLES["Temples (skin contact, insulated)"]
    BL["L bone transducer<br/>Adafruit 1674, 8 ohm 1 W"]
    BR["R bone transducer<br/>Adafruit 1674, 8 ohm 1 W"]
    EL["L ERM disc motor"]
    ER["R ERM disc motor"]
  end
  subgraph REAR["Rear strap"]
    C3["Seeed XIAO ESP32-C3<br/>cue board"]
    AMPL["MAX98357A (L)"]
    AMPR["MAX98357A (R)"]
    MUX["TCA9548A mux 0x70"]
    DRVL["DRV2605L L 0x5A (ch0)"]
    DRVR["DRV2605L R 0x5A (ch1)"]
    LDO["LM3671 3.3 V / 600 mA<br/>peripheral rail"]
  end
  subgraph POCKET["Pocket"]
    BANK["5 V USB power bank<br/>(two simultaneous outputs)"]
  end
  BANK -- "5 V branch 1 (USB-C)" --> C3
  BANK -- "5 V branch 2 (USB-to-5V/GND breakout)" --> CAM
  BANK -- "5 V branch 2" --> AMPL
  BANK -- "5 V branch 2" --> AMPR
  BANK -- "5 V branch 2" --> LDO
  LDO -- "3.3 V" --> TOF
  LDO -- "3.3 V" --> MUX
  LDO -- "3.3 V" --> DRVL
  LDO -- "3.3 V" --> DRVR
  C3 --- TOF
  C3 --- MUX
  MUX --- DRVL
  MUX --- DRVR
  C3 --- AMPL
  C3 --- AMPR
  AMPL --> BL
  AMPR --> BR
  DRVL --> EL
  DRVR --> ER
  GND(["Common signal ground<br/>(all boards + branches)"])
  C3 -.- GND
  CAM -.- GND
  LDO -.- GND
```

[rendered](rendered/hardware-1.svg) · Motors and amps are never powered from the C3 3V3 pin (Arch §5.4).
Exact rail assignment of each peripheral beyond "camera, amps, LM3671 on branch 2" is a G0 bring-up decision.

## (b) Electrical buses (Arch §5.3–§5.4; shared hardware facts)

```mermaid
flowchart LR
  subgraph C3["XIAO ESP32-C3 (cue board)"]
    SDA["GPIO6 / D4 SDA"]
    SCL["GPIO7 / D5 SCL"]
    BCLK["GPIO3 / D1 BCLK"]
    LRC["GPIO4 / D2 LRCLK"]
    DIN["GPIO5 / D3 DIN"]
    LED["GPIO10 / D10 status LED"]
    PWML["GPIO21 / D6 PWM L<br/>(build flag DT_HAPTIC_MOSFET)"]
    PWMR["GPIO20 / D7 PWM R<br/>(build flag DT_HAPTIC_MOSFET)"]
  end
  subgraph I2C["I2C bus (one hardware bus)"]
    TOF["VL53L5CX 0x29<br/>~90 KB fw upload at boot"]
    MUX["TCA9548A 0x70"]
    DL["DRV2605L 0x5A"]
    DR["DRV2605L 0x5A"]
  end
  SDA --- TOF
  SCL --- TOF
  SDA --- MUX
  SCL --- MUX
  MUX -- "ch0" --- DL
  MUX -- "ch1" --- DR
  DL --> ERML["ERM L"]
  DR --> ERMR["ERM R"]
  subgraph I2S["I2S (shared 3 signals)"]
    AL["MAX98357A L<br/>SD-pin resistor selects LEFT"]
    AR["MAX98357A R<br/>SD-pin resistor selects RIGHT"]
  end
  BCLK --> AL
  BCLK --> AR
  LRC --> AL
  LRC --> AR
  DIN --> AL
  DIN --> AR
  AL --> TL["L bone transducer"]
  AR --> TR["R bone transducer"]
  LED --> SLED["Status LED"]
  PWML -. "fallback only" .-> QL["N-MOSFET + flyback diode"] -.-> ERML
  PWMR -. "fallback only" .-> QR["N-MOSFET + flyback diode"] -.-> ERMR
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
| XIAO C3 | 6 | D4 | I²C SDA | VL53L5CX 0x29, TCA9548A 0x70 |
| XIAO C3 | 7 | D5 | I²C SCL | same bus |
| XIAO C3 | 3 | D1 | I²S BCLK | both MAX98357A |
| XIAO C3 | 4 | D2 | I²S LRCLK | both MAX98357A |
| XIAO C3 | 5 | D3 | I²S DIN | both MAX98357A; channel chosen by SD-pin resistor |
| XIAO C3 | 10 | D10 | Status LED | |
| XIAO C3 | 21 | D6 | PWM haptic L | only with `DT_HAPTIC_MOSFET` |
| XIAO C3 | 20 | D7 | PWM haptic R | only with `DT_HAPTIC_MOSFET` |
| XIAO C3 | 2, 8, 9 | — | avoided | strapping pins |
| TCA9548A | ch0 / ch1 | — | DRV2605L L / R | both 0x5A, isolated by mux |
| ESP32-CAM | 13 | IO13 | Recording LED | on while streaming |
| ESP32-CAM | 4 | IO4 | Flash LED | held off |
| ESP32-CAM | 1 / 3 | U0T / U0R | FTDI UART | programming; IO0→GND to flash |

## (c) Network / data topology (PROTOCOL.md "Transport", "Rates")

Both boards are **stations** on the phone hotspot and send to their DHCP gateway; the phone
learns each board's IP from its first packet. No board-to-board bus (Arch §4, Fig 4.1).

```mermaid
flowchart LR
  subgraph HOT["Phone Wi-Fi hotspot (one channel, shared airtime)"]
    PHONE["Android phone<br/>UDP :47000<br/>(only integrator, decides hazards)"]
  end
  CUE["Cue board XIAO ESP32-C3<br/>station, UDP :47001"]
  CAM["Camera board ESP32-CAM<br/>station, UDP :47002"]
  CAM -- "BEAT 0x01, 24 B, 10 Hz*" --> PHONE
  CAM -- "FRAME 0x03, chunks of 38+&le;1400 B, 10-15 fps* JPEG QVGA-VGA" --> PHONE
  CAM -- "PONG 0x11, 32 B, reply per PING" --> PHONE
  PHONE -- "PING 0x10, 16 B, 10 Hz*" --> CAM
  CUE -- "BEAT 0x01, 24 B, 10 Hz*" --> PHONE
  CUE -- "RANGE 0x02, 212 B, 15 Hz*" --> PHONE
  CUE -- "PONG 0x11, 32 B, reply per PING" --> PHONE
  CUE -- "ACK 0x21, 24 B, per CUE/PROFILE" --> PHONE
  PHONE -- "PING 0x10, 16 B, 10 Hz* (also watchdog liveness)" --> CUE
  PHONE -- "CUE 0x20, 34 B, one per repetition, ttl 150 ms*" --> CUE
  PHONE -- "PROFILE 0x22, 12 B, on change" --> CUE
  NOTE["* PROPOSED rates (PROTOCOL.md Rates and bounds)<br/>Header 8 B: magic 0x5444, ver 1, type, seq u32 LE"]
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
  Note over CUE,CAM: Power from pocket bank (two branches)
  CUE->>CUE: init I2S amps, TCA9548A + DRV2605L L/R
  CUE->>CUE: VL53L5CX: upload ~90 KB firmware over I2C, start 8x8 @ 15 Hz
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
  PH->>CUE: PROFILE ceiling_db, mode, haptic_max
  CUE->>PH: ACK status 0 (cue_seq = PROFILE seq)
  CUE-->>PH: RANGE 15 Hz
  CAM->>CAM: LED GPIO13 on while streaming
  CAM-->>PH: FRAME chunks
  PH->>CUE: CUE READY (cue_id 0) once all of: frames fresh, ToF ok, cue ack
  CUE->>PH: ACK PLAYED
  Note over PH: STARTING to READY (Arch 4.2)
  Note over CUE: If no PING within 5 s of boot: WATCHDOG (SYSTEM_OFF + LSS every 5 s)
```

[rendered](rendered/hardware-4.svg)

### Steady-state cue round trip with timestamps

Latency budget (Arch §6.3, all PROPOSED): exposure + JPEG ≤ 60 ms, Wi-Fi ≤ 40 ms, decode + detector +
fusion ≤ 80 ms, policy + dispatch + playback start ≤ 40 ms → capture-to-cue p95 ≤ 250 ms. ToF path skips the camera stages.

```mermaid
sequenceDiagram
  autonumber
  participant CAM as Camera board
  participant CUE as Cue board (C3)
  participant PH as Phone
  CAM->>CAM: capture JPEG, t_cap_us (board clock)
  CAM->>PH: FRAME chunks (frame_id, t_cap_us, offset, chunk_idx/count)
  PH->>PH: rx time t_rx, reassemble, drop if incomplete 150 ms after first chunk
  CUE->>CUE: VL53L5CX grid ready, t_us (board clock)
  CUE->>PH: RANGE t_us, mm[64], status[64]
  PH->>PH: freshness gate: offset-corrected age and receive age <= 500 ms (PROPOSED)
  PH->>PH: detector + range corridor + fusion + HazardPolicy
  PH->>PH: deadline_board_us = now_phone + offset(CUE) + ttl (150 ms PROPOSED)
  PH->>CUE: CUE t_issued_phone_us, deadline_board_us, cue_id, side, gains, haptic, flags, ttl_ms
  alt deadline 0 (no sync)
    CUE->>PH: ACK NO_SYNC (3), not played
  else now > deadline
    CUE->>PH: ACK EXPIRED (1), not played
  else valid
    CUE->>CUE: clamp gains to min(profile ceiling, -10 dBFS), play earcon (I2S) + haptic
    CUE->>PH: ACK PLAYED (0) or PLAYED_CLAMPED (2), applied gains, t_board_us
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
  participant SPK as Phone speaker + vibration
  rect rgb(255,235,235)
  Note over CUE,PH: 1. Cue board loses power
  CUE--xPH: BEAT stops, CUE gets no ACK
  PH->>PH: no BEAT > 500 ms (PROPOSED) or missing ACK: UNAVAILABLE
  PH->>SPK: system-off on phone speaker + vibration (camera board has no audio)
  PH->>PH: suppress all obstacle cues, keep PINGing to retry link
  end
  rect rgb(235,235,255)
  Note over CUE,PH: 2. Phone silent (app killed, Wi-Fi off)
  PH--xCUE: PINGs stop
  CUE->>CUE: no PING for 800 ms: WATCHDOG, set fault bit 7
  loop every 5 s until PINGs resume
    CUE->>CUE: SYSTEM_OFF earcon at min(ceiling, -20 dBFS) + haptic LSS both sides
  end
  PH->>CUE: PING resumes
  CUE->>CUE: leave WATCHDOG, clear bit 7
  end
  rect rgb(235,255,235)
  Note over CAM,PH: 3. Camera unplugged
  CAM--xPH: BEAT and FRAME stop
  PH->>PH: frame age > 500 ms or no BEAT > 500 ms: UNAVAILABLE
  PH->>CUE: CUE SYSTEM_OFF (cue_id 7, haptic LSS) every 5 s on surviving cue board
  CUE->>PH: ACK
  PH->>SPK: system-off + vibration
  Note over PH: Obstacle cues suppressed, recovery requires STARTING conditions again
  end
```

[rendered](rendered/hardware-6.svg) · Target: unavailable cue ≤ 1 s after a fault (G7, PROPOSED).
