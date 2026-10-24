# Determine camera board (AI-Thinker ESP32-CAM)

Streams JPEG frames to the phone as `FRAME` chunks, sends `BEAT` at 10 Hz and answers `PING`
with `PONG` (wire contract: `protocol/PROTOCOL.md`). It makes no hazard decisions and ignores
`CUE`/`PROFILE` packets (counted only).

## Build / test

```sh
nix shell nixpkgs#platformio -c sh -c 'cd firmware/cam-board && pio run -e esp32cam && pio test -e native'
```

Hotspot credentials: copy `firmware/common/include/secrets.example.h` to `secrets.h` (gitignored)
and fill them in. Without it the build uses the example values and warns.

Build flags (`platformio.ini`): `DT_CAM_FRAMESIZE` (default `FRAMESIZE_VGA`; set
`FRAMESIZE_QVGA` as fallback), `DT_CAM_FPS` (12), `DT_CAM_MAX_KBPS` (480 kB/s camera budget so
the cue board's traffic is not starved), `DT_CAM_JPEG_QUALITY` (14).

After changing `protocol/vectors/vectors.json`, regenerate the test header:
`python3 firmware/cam-board/tools/vectors_to_c.py`.

## Flashing with an FTDI adapter

1. Set the FTDI adapter to **3.3 V logic**. Power the ESP32-CAM from **5 V** on its 5V pin
   (the 3.3 V pin of most FTDI adapters cannot supply the camera + Wi-Fi peaks → brown-outs).
2. Wire: FTDI TX → U0R (GPIO3), FTDI RX → U0T (GPIO1), GND → GND (common ground).
3. Bridge **IO0 to GND**, then press RST (or power-cycle) to enter the bootloader.
4. `pio run -e esp32cam -t upload --upload-port /dev/ttyUSB0`
5. Remove the IO0–GND bridge, press RST, then `pio device monitor -b 115200`.

## Gate G0 PSRAM / sensor check

On boot the serial log prints:

```
[boot] PSRAM found, size 4194304 bytes, free heap ...
[cam] sensor PID 0x0026 (OV2640), framesize 10, psram yes
```

Record both lines for the module under test (Arch §13 G0).
- PSRAM found → JPEG `DT_CAM_FRAMESIZE` (VGA), 2 frame buffers in PSRAM, grab-latest.
- PSRAM **NOT found** → firmware automatically drops to JPEG ≤ CIF with one DRAM buffer (the
  only mode esp32-camera supports without PSRAM). Note this; if CIF is too poor, fall back to
  Tier 0 (phone camera).
- Sensor other than OV2640/OV3660/OV5640, or init failure → `SENSOR_INIT` fault bit in BEAT,
  init retried every 2 s; BEATs continue so the phone reports the camera as faulty (fail loud).
- G0 pass: 10 minutes streaming with no resets (reset reason in BEAT stays constant), no corrupt
  JPEGs (`CAPTURE` fault never set) and no brown-outs (`BROWNOUT` fault bit) with both boards on.

## LEDs

| LED | Meaning |
|---|---|
| Front recording indicator, GPIO13 (active high) | On while frames are being transmitted (within the last 300 ms). Bystander privacy, Arch §10.1. |
| Onboard flash, GPIO4 | Always off. |

## BEAT faults set by this board

`SENSOR_INIT` (camera not initialised), `NO_CLOCK_SYNC` (no PING received since boot),
`CAPTURE` (grab failure, corrupt JPEG or send failure in the last second; the frame is dropped,
never queued), `LOW_HEAP` (< 24 KB free), `BROWNOUT` (last reset was a brown-out).
Temperature is always reported as 127 (unknown).

## Bench test

With the board on the hotspot (or a laptop hotspot whose gateway runs the tool), run the bench
tool from the repo root, `python3 tools/sim/bench.py` (see its `--help` for options). It listens
on UDP 47000, sends PINGs to the board on 47002, and reports BEAT faults, PONG RTT, frame rate,
chunk completeness and JPEG validity. Expect: `NO_CLOCK_SYNC` clears after the first PING, ~12 fps
complete frames, and the front LED lit while streaming.
