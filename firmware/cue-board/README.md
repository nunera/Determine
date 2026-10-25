# Determine cue board firmware (Seeed XIAO ESP32-C3)

**Hardware status (24 Oct 2026):** haptics run on one Arduino **Modulino Vibro** (SKU 992891, I²C 0x38) — a single motor, not two temple channels. The Adafruit DRV2605L breakout stays on the bus only for its 10 kΩ SDA/SCL pull-ups; the firmware does not drive it and no TCA9548A is used. Full inventory: [root README](../../README.md#hardware-in-hand--user-confirmed-purchase-24-oct-2026). Architecture §12.2–12.4 and its PDFs retain the older two-DRV/mux design and stock snapshot. Integrated bone-conduction audio remains unresolved.

The cue board streams single-zone forward ToF ranges (RANGE, from an Arduino Modulino
Distance — the purchased unit identifies as VL53L4ED, 0xECAA) and heartbeats (BEAT, 10 Hz) to the phone, answers PING with PONG,
and plays the **haptic** side of the CUEs the phone sends on one Modulino Vibro. Since
protocol v2 the board makes no sound: all cue audio is rendered by the phone and sent to a
bone-conduction Bluetooth headset (e.g. Shokz OpenRun), so the I²S amps, their wiring and
the audio task are gone. The board decides nothing about hazards (Arch §4). If the phone
stops pinging it plays the haptic LSS alarm on its own (watchdog). Wire contract:
`protocol/PROTOCOL.md` (v2).

## Layout

| Path | Contents |
|---|---|
| `src/main.cpp` | hardware: Wi-Fi/UDP, haptic task, VL53L4ED task, LED |
| `lib/cue_logic/` | hardware-free logic: CUE/PROFILE decisions, haptic patterns, watchdog, packet builders |
| `test/test_cue_logic/` | Unity tests (host); `vectors.h` is generated |
| `tools/vectors_to_c.py` | regenerates `vectors.h` from `protocol/vectors/vectors.json` |

## Wiring (Arch §5)

| Signal | XIAO pin | GPIO | Goes to |
|---|---|---|---|
| I²C SDA | D4 | 6 | Modulino Distance SDA (soldered) |
| I²C SCL | D5 | 7 | same |
| 3V3 | 3V3 | — | Modulino Distance 3V3 (soldered); feeds the whole Qwiic chain |
| GND | GND | — | common ground |
| Status LED | D10 | 10 | LED + resistor to GND |
| Haptic L (MOSFET fallback) | D6 | 21 | gate of left motor MOSFET |
| Haptic R (MOSFET fallback) | D7 | 20 | gate of right motor MOSFET |

- Bench chain: C3 → four soldered wires → **Modulino Distance** → its Qwiic cable → **DRV2605L** STEMMA QT →
  the DRV2605L's other STEMMA QT → **Modulino Vibro**. Everything runs from the C3's 3.3 V pin; never feed 5 V
  into this chain.
- Bus map: Distance 0x29 (VL53L4ED, no address-remapping MCU; XSHUT/GPIO1 pulled up on-module, left
  unconnected), Vibro 0x38, DRV2605L 0x5A (present, not driven). No duplicate addresses.
- The Distance and Vibro boards have no SDA/SCL pull-ups mounted; the DRV2605L breakout's 10 kΩ pull-ups make
  the bus work (see [bring-up](#distance-sensor-bring-up)). Removing it breaks the bus unless 2–5 kΩ resistors
  to 3.3 V replace it.
- The [Vibro datasheet](https://docs.arduino.cc/resources/datasheets/ABX00130-datasheet.pdf) lists ~67 mA
  typical / 85 mA maximum motor current at 3.3 V — vendor figures, not measured here. Never connect the
  complete Vibro module to DRV2605L motor outputs.
- The TPS62827 is unused. If added later, confirm its labels and measure its output first; do not tie it to the
  USB-powered XIAO 3V3 output without a verified power-sharing design.
- Strapping pins GPIO2/8/9 are unused. Old I²S pins D1/D2/D3 (GPIO 3/4/5) are free.

## Build flags (`platformio.ini`)

| Flag | Default | Meaning |
|---|---|---|
| `DT_I2C_HZ` | 400000 | I²C clock. Use 1000000 only if the bench shows no read errors |
| `DT_HAPTIC_MOSFET` | off | drive two motors with LEDC PWM (20 kHz) on GPIO21/20 instead of the Vibro (untested, no parts) |

The ToF mounting transform flags (`DT_TOF_FLIP_X/Y`, `DT_TOF_ROTATE`) are gone with the 8×8
grid: v2 streams one forward zone, so there is nothing to mirror or rotate.

Wi-Fi credentials come from `firmware/common/include/secrets.h` (gitignored; copy
`secrets.example.h`). If it is missing, the build warns and uses the example values.

## Build, flash, test

```sh
nix shell nixpkgs#platformio -c sh -c 'cd firmware/cue-board && pio run -e xiao_esp32c3'
# flash over USB-C (hold BOOT while plugging in if the port does not show up)
nix shell nixpkgs#platformio -c sh -c 'cd firmware/cue-board && pio run -e xiao_esp32c3 -t upload && pio device monitor'
# host unit tests
nix shell nixpkgs#platformio -c sh -c 'cd firmware/cue-board && pio test -e native'
# after protocol/vectors/vectors.json changes
python3 tools/vectors_to_c.py
```

## Behaviour

- **CUE**: unknown id/side/pattern → ACK `BAD_CUE`; `deadline_board_us == 0` → `NO_SYNC`;
  `now > deadline_board_us` → `EXPIRED` (never played late). If a haptic is still playing and
  `flags` bit0 is clear the CUE is dropped with `BUSY_DROPPED`; with bit0 set the new CUE cuts
  off the current one. Intensities are clamped to the profile's `haptic_max`, and the ACK is
  `APPLIED_CLAMPED` iff that clamp lowered a requested intensity — side exclusion (L/R) alone
  stays `APPLIED`. A CUE with `haptic_pattern == NONE` (phone-side audio only) is always
  acknowledged `APPLIED` and never counts as busy.
- **PROFILE**: stores `mode` (kept for the phone's audio-route semantics — the board always
  plays haptics, HAPTIC_ONLY does not mute them) and `haptic_max`. A mode greater than 2, or
  `haptic_max == 0`, is rejected with a `BAD_CUE` ACK and the profile is left unchanged: the
  watchdog LSS is the board's only failure announcement, so it must never be capped to zero.
  The minimum 1 is only non-zero drive, not a guarantee the motor is perceptible.
- **Watchdog**: it trips when no PING arrives for 800 ms, or none within 5 s of boot. It then
  plays the haptic **LSS** pattern (long-short-short) at `haptic_max`-capped intensity at once
  and every 5 s, sets BEAT fault bit 7 and fast-blinks the LED. There is no board sound.
  The next PING clears it.
- **ToF (VL53L4ED)**: uses the official ST L4ED 1.0.1 driver for observed model ID 0xECAA —
  probe 0x29, `InitSensor()`, 20 ms timing budget with continuous ranging, `StartRanging()`,
  then poll `CheckForDataReady` → `ClearInterrupt` → `GetResult` every 5 ms. Each ready sample
  becomes one RANGE packet (single zone, `mm` + `status`). The raw `range_status` is mapped by
  `dt_range_status_from_tof` (`lib/cue_logic`, unit-tested): `0` → `DT_RANGE_VALID`; `2` (signal
  below threshold, nothing seen) and `4` (phase out of limits, beyond range) → `DT_RANGE_NO_TARGET`;
  **every other raw status → `DT_RANGE_UNKNOWN`** (wrap-around, saturation, sigma failure, …).
  `mm = 0` unless VALID. `NO_TARGET` means "the sensor reported no target within range" — it is
  not an all-clear, and the phone says nothing for it. An empty open view is therefore quiet; glass
  or saturation stays UNKNOWN. The simulator mirrors this.
  Init failure sets fault bit 0 and is retried every 10 s; after 10 consecutive read errors
  the sensor is re-initialised. Unlike the old VL53L5CX there is no ~90 KB firmware upload,
  so retries no longer wait for idle haptics.
- **Haptics**: one Modulino Vibro at 0x38, using the command layout of Arduino's
  [`ModulinoVibro`](https://github.com/arduino-libraries/Arduino_Modulino/blob/main/src/Modulino.h). The
  single motor runs at `max(haptic_l, haptic_r)`; it cannot say left or right. Intensity 0–255 maps to Vibro
  power 0–50 (any non-zero level drives at least 1). Every ON write carries a 1000 ms device-side auto-off, so
  a stalled task cannot leave the motor running. Both haptic fault bits (3/4) reflect this one device; a failed
  write is retried every 10 s while idle.

### Ranging envelope (near-field demo)

The original design assumed VL53L4CD; the purchased module identifies as VL53L4ED. No range extension is
made by changing its driver: the phone retains FAR 900–1200 mm, MID 500–899 mm, NEAR < 500 mm.
Those thresholds remain proposed, not measured useful warning distances. A single forward zone cannot say
"left" or "right"; only proximity.

## Status LED

| Pattern | Meaning |
|---|---|
| solid | Wi-Fi up and PINGs arriving (streaming and synced) |
| slow blink (0.5 s) | no Wi-Fi |
| fast blink (0.1 s) | watchdog active (phone silent) |
| short blip every 4 s | Wi-Fi up, no PING yet |

## Serial log (115200 baud, USB CDC)

| Line | Meaning |
|---|---|
| `[boot] … reset_reason=N` | `esp_reset_reason()`; 9 = brownout (also BEAT fault bit 5) |
| `[wifi] connecting/up/down` | station state; `up` prints the board IP, the phone (gateway) IP and RSSI |
| `[haptic] L/R ok/FAILED` | Vibro init or write result per protocol side (fault bits 3/4; both map to the one Vibro) |
| `[tof] init ok/FAILED` | VL53L4ED state (fault bit 0); `repeated read errors` means it is re-initialising (fault bit 1) |
| `[wd] …` | watchdog entered / PINGs resumed |
| `[cue] seq=… status=…` | any CUE that was not plain APPLIED (status codes as in ACK) |
| `[profile] applied/REJECTED …` | PROFILE result |
| `[udp] send failed (N since last report)` | UDP sends failed; reported at most once per 5 s. Sends are skipped while Wi-Fi is down, so a drop no longer floods the log with the Arduino core's own `endPacket()` errors |

## Bench test with `tools/sim`

`tools/sim/bench.py` stands in for the phone: it listens on UDP 47000, sends PING, CUE and
PROFILE to the board on 47001, and prints BEAT/RANGE/ACK. Put a laptop on the hotspot as the
gateway, or run the bench on a machine that the board's gateway forwards to. Run
`python3 tools/sim/bench.py --help` for the exact options. Checks:

1. Power the board with no phone. After 5 s it should play the haptic LSS alarm every 5 s,
   and the LED should fast-blink. No sound anywhere — that is expected in v2.
2. Start the bench. The alarm stops, the LED goes solid, and BEAT fault bit 6 (NO_CLOCK_SYNC)
   clears.
3. Send each haptic pattern with both sides, then L only, then R only. The single Vibro should buzz
   in all three cases (it cannot lateralise), at the requested intensity.
4. Send a CUE with `haptic_l/r` above the PROFILE `haptic_max`. The ACK should report
   `APPLIED_CLAMPED` (2).
5. Stop the bench. Within ~1 s the watchdog LSS alarm should play.
6. Wave a hand in front of the Modulino Distance between ~100 and 1200 mm and confirm RANGE
   `mm` tracks; look at a far wall or open space and confirm `status 1` (NO_TARGET), `mm 0`; press a
   glass or mirror-like surface against the lens and expect `status 2` (UNKNOWN), `mm 0`.

## Distance sensor bring-up

Bench observations on the purchased module (24 Oct 2026):

- D4/GPIO6 SDA and D5/GPIO7 SCL found one responding device, 0x29, at both 100 and 400 kHz.
- Reading the model ID succeeded and returned **0xECAA**. The previous STM32duino VL53L4CD 1.0.5
  `InitSensor()` requires 0xEBAA and returned 255 for this module. Do not remove that identity check.
- The firmware now uses [ST's VL53L4ED 1.0.1 driver](https://github.com/stm32duino/VL53L4ED),
  whose identity check accepts 0xECAA. Arduino's [Modulino Distance implementation](https://github.com/arduino-libraries/Arduino_Modulino/blob/main/src/Modulino.h)
  also distinguishes L4CD and L4ED modules.
- Isolated L4ED initialization, timing setup and ranging start each returned 0 in one capture; successful
  result reads had status 0 and approximately 73–104 mm. These are observed readings, not an accuracy test.
- After soldering, the four-wire C3 ↔ Distance bus (no external pull-ups) remained intermittent: one full-firmware
  boot initialized, while later captures failed initialization with I²C errors. A standalone 1 kHz test once gave
  47/47 status-0 readings (38–86 mm); the same speed in full firmware failed with Wi-Fi on and off. Lower Wi-Fi
  TX power did not help. Results vary run to run, so no software speed profile is adopted.
- The [Distance schematic](https://docs.arduino.cc/resources/schematics/ABX00102-schematics.pdf) shows pull-ups
  only on XSHUT/GPIO1, and [Espressif](https://docs.espressif.com/projects/esp-idf/en/v5.0/esp32c3/api-reference/peripherals/i2c.html)
  states the C3's internal pull-ups (tens of kΩ) are usually insufficient. External SDA/SCL pull-ups to 3.3 V
  are required (Espressif recommends 2–5 kΩ).
- **Working bench setup (24 Oct 2026):** the Adafruit DRV2605L breakout, connected by the Distance's Qwiic cable
  to its STEMMA QT port, supplies [10 kΩ pull-ups to VIN](https://learn.adafruit.com/adafruit-drv2605-haptic-controller-breakout/pinouts);
  VIN comes from the 3.3 V Qwiic rail. Never power that board from 5 V while it shares the C3 bus. After a full
  USB power-cycle, a 100 kHz scan found 0x29 and 0x5A, every init step returned 0, and status-0 readings tracked a
  hand from 231 mm to 28 mm and back with no I²C errors in a 30 s capture. The normal 400 kHz firmware then
  initialised (`[tof] init ok`) with one isolated I²C timeout and no re-initialisation in 40 s. Bench evidence only;
  10 kΩ is above Espressif's recommended range, so proper 2–5 kΩ resistors remain the durable fix.
- If a scan ever finds 0x5A but not 0x29, fully unplug USB for 5 s: a C3 reset does not power-cycle the sensor.
- **Vibro on the same bus (24 Oct 2026):** the Modulino Vibro, chained from the DRV2605L's second STEMMA QT
  port, answered at **0x38** (Arduino's library address; the datasheet's 0x1D was not seen). A 27 s test alternating
  1.5 s Vibro ON/OFF while ranging logged one isolated I²C error and no motor-correlated failures. The Vibro
  firmware then reported `[haptic] L ok`, `R ok`, `[tof] init ok` and no I²C errors in 30 s while the watchdog LSS
  ran. Physical vibration strength and long-run reliability are not measured.
- **Vibro pulses felt too long (24 Oct 2026, user-reported):** the firmware wrote every ON with a fixed 1000 ms
  module-side duration and relied on a following 8-byte OFF write to stop the motor; 40-150 ms cues felt long and
  inconsistent, with leave time at 0 and the distance display on time, so smoothing was not the cause. A bench
  sketch that played 8 pulses in each of four modes was felt by the user: the 1000 ms window plus OFF write (the
  old firmware) was the only mode that felt long and inconsistent; a 60 ms module-side duration alone, a power-0
  stop, and a 60 ms duration plus OFF all felt like soft taps. The firmware now sets the module's duration to
  exactly the pattern segment it plays and still sends the OFF write (the last mode). Subjective single-user
  feel on one unit; the motor's real on-time and the end-to-end cue stop with the phone were not measured.
- Results are now read before the interrupt is cleared, matching the ST driver's documented
  `ClearInterrupt()` contract.
- Protocol v2, status-0-only validity, deadlines and warning thresholds are unchanged. No end-to-end phone
  warning or latency claim follows from this bench exercise.
