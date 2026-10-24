# Determine cue board firmware (Seeed XIAO ESP32-C3)

The cue board streams the VL53L5CX 8×8 depth grid (RANGE, 15 Hz) and heartbeats (BEAT, 10 Hz)
to the phone, answers PING with PONG, and plays the CUEs the phone sends on two bone-conduction
transducers and two temple haptic motors. It decides nothing about hazards (Arch §4). If the phone
stops pinging it plays SYSTEM_OFF on its own (watchdog). Wire contract: `protocol/PROTOCOL.md`.

## Layout

| Path | Contents |
|---|---|
| `src/main.cpp` | hardware: Wi-Fi/UDP, I²S audio task, haptic task, ToF task, LED |
| `lib/cue_logic/` | hardware-free logic: cue bank + timing validator, synth, gain, CUE/PROFILE decisions, watchdog, haptic patterns, ToF transform, packet builders |
| `test/test_cue_logic/` | Unity tests (host); `vectors.h` is generated |
| `tools/vectors_to_c.py` | regenerates `vectors.h` from `protocol/vectors/vectors.json` |

## Wiring (Arch §5)

| Signal | XIAO pin | GPIO | Goes to |
|---|---|---|---|
| I²C SDA | D4 | 6 | VL53L5CX (0x29), TCA9548A (0x70) |
| I²C SCL | D5 | 7 | same |
| I²S BCLK | D1 | 3 | both MAX98357A BCLK |
| I²S LRCLK | D2 | 4 | both MAX98357A LRC |
| I²S DIN | D3 | 5 | both MAX98357A DIN |
| Status LED | D10 | 10 | LED + resistor to GND |
| Haptic L (MOSFET fallback) | D6 | 21 | gate of left motor MOSFET |
| Haptic R (MOSFET fallback) | D7 | 20 | gate of right motor MOSFET |

- TCA9548A channel 0 → left DRV2605L (0x5A), channel 1 → right DRV2605L (0x5A).
- One MAX98357A is strapped to the left I²S slot, the other to the right, with their SD-pin resistors.
- Strapping pins GPIO2/8/9 are unused. Do not power the amps or motors from the C3's 3V3 pin.

## Build flags (`platformio.ini`)

| Flag | Default | Meaning |
|---|---|---|
| `DT_TOF_FLIP_X` | 0 | mirror columns after rotation |
| `DT_TOF_FLIP_Y` | 0 | mirror rows after rotation |
| `DT_TOF_ROTATE` | 0 | quarter turns clockwise, applied first |
| `DT_I2C_HZ` | 400000 | I²C clock. Use 1000000 only if the bench shows no read errors |
| `DT_HAPTIC_MOSFET` | off | drive the motors with LEDC PWM (20 kHz) on GPIO21/20 instead of the DRV2605L |

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

- **CUE**: unknown id/side/pattern → ACK `BAD_CUE`; `deadline == 0` → `NO_SYNC`; `now > deadline` →
  `EXPIRED` (never played late). If a sound or haptic is still playing and `flags` bit0 is clear,
  the CUE is dropped with `BUSY_DROPPED`. With bit0 set, the new CUE cuts off the current one.
  Gains are clamped to `min(profile ceiling, −10 dBFS)`. `PLAYED_CLAMPED` means the clamp changed a value.
  The ACK carries the gains actually applied: −128 for a side the CUE excludes, or both in HAPTIC_ONLY.
- **PROFILE**: the ceiling is stored as `min(ceiling, −10)`. Mode HAPTIC_ONLY mutes audio, and
  `haptic_max` caps intensity. The ACK has status 0 and the stored ceiling in both gain fields. A
  mode greater than 2 is rejected with `BAD_CUE`.
- **Watchdog**: it trips when no PING arrives for 800 ms, or none within 5 s of boot. It plays
  SYSTEM_OFF plus haptic LSS at once and then every 5 s at `min(ceiling, −20 dBFS)`, sets BEAT
  fault bit 7 and fast-blinks the LED. The next PING clears it.
- **Cue bank**: every earcon is synthesised, with no WAV files. Each note is ≥ 83 ms, and each cue
  plus 100 ms fits its repeat period (FAR 1000, MID 400, NEAR 200, SYSTEM_OFF 5000 ms), at ≤ 6
  notes/s. The unit tests check these rules.
- **Haptics**: DRV2605L real-time playback, with intensity 0–255 mapped to RTP 0–127. If one side
  fails, its fault bit is set and the other side keeps working. Failed drivers are retried every
  10 s while idle.
- **ToF**: if init fails, fault bit 0 is set and init is retried every 10 s. The ~90 KB firmware
  upload holds the I²C bus, so retries wait until haptics are idle. Audio never uses I²C.
  After 10 consecutive read errors the sensor is re-initialised. Zones with no target are sent
  as status 255 and mm 0.

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
| `[audio] i2s ok/FAILED` | I²S driver install (failure sets fault bit 2) |
| `[haptic] L/R ok/FAILED` | DRV2605L init or write result per side (fault bits 3/4) |
| `[tof] init ok/FAILED` | VL53L5CX state (fault bit 0); `repeated read errors` means it is re-initialising |
| `[wd] …` | watchdog entered / PINGs resumed |
| `[cue] seq=… status=…` | any CUE that was not plain PLAYED (status codes as in ACK) |
| `[profile] applied/REJECTED …` | PROFILE result |

## Bench test with `tools/sim`

`tools/sim/bench.py` stands in for the phone: it listens on UDP 47000, sends PING, CUE and PROFILE
to the board on 47001, and prints BEAT/RANGE/ACK. Put a laptop on the hotspot as the gateway, or
run the bench on a machine that the board's gateway forwards to. Run
`python3 tools/sim/bench.py --help` for the exact options. Checks:

1. Power the board with no phone. After 5 s it should play SYSTEM_OFF and LSS every 5 s, and the LED should fast-blink.
2. Start the bench. The alarm stops, the LED goes solid, and BEAT fault bit 6 (NO_CLOCK_SYNC) clears.
3. Send each cue id 0–11 with both sides, then L only, then R only. Confirm the sound and motor are on the right side.
4. Send gain 0 dBFS. The ACK should report status 2 with −10 applied.
5. Stop the bench. Within 1 s the watchdog alarm should play.
6. **Orientation check**: cover the wearer's left half of the sensor and confirm the RANGE columns 0–3
   change. Hold a card above the beam and confirm rows 0–3 change. If either is wrong, set
   `DT_TOF_FLIP_X`, `DT_TOF_FLIP_Y` or `DT_TOF_ROTATE` and reflash.
