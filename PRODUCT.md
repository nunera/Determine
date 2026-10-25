# Product

<!-- impeccable:product-schema 1 -->

## Platform

web

## Stack

Static HTML/CSS printed to PDF with Chromium (delegated: no framework needed for print documents).

## Users

- Primary: blind and low-vision pedestrians who already travel with a white cane or guide dog and want warning of chest- and head-level obstacles the cane misses.
- Includes people with asymmetric hearing loss or single-sided deafness.
- Evaluating audience for the HackTX 26 surfaces: hackathon judges and sponsor engineers (Meta, Annapurna Labs / AWS).

## Product Purpose

Determine is a proposed compact headband prototype: one XIAO ESP32-C3 reads one Arduino Modulino Distance forward zone (the purchased module identifies as VL53L4ED, 0xECAA; earlier documents assumed VL53L4CD) and drives one Modulino Vibro haptic motor (no left/right); one ESP32-CAM supplies JPEG frames for context, with the second camera a spare, not stereo. The camera and ToF face forward, control boards sit on a rear strap, and the battery stays in a pocket. Both boards use phone Wi-Fi (wire v2). The phone decides hazards. Its implemented audio path is Bluetooth headset playback, but the separate OpenRun headset is not approved for the intended build. Integrated bone-conduction hardware is unresolved; no raw-transducer/amplifier replacement has been selected. This is an unvalidated staged near-field prototype, not a completed wearable or navigation guarantee.

## Positioning

A parking sensor for walking, not "AI glasses for the blind". Phone-side decisions, temple haptics and hearing-aware warning design; the intended integrated bone-conduction output remains a sourcing/design gap.

## Operating Context

- Built at HackTX 26 (UT Austin, 24–25 Oct 2026). Rule: all work must be done at the event.
- Demo: staged indoor course with a sighted spotter.
- Reflex loop (hazard warnings) runs offline on the phone. The optional, on-demand Describe loop targets a Graviton API plus SmolVLM on Inferentia2 via Hugging Face Optimum Neuron; Trainium2/Qwen2-VL is a more explicitly AWS-documented alternative, and Bedrock is a silicon-unspecified fallback. None is part of an urgent warning; all deployments remain unvalidated.
- The XIAO ESP32-S3 Sense is unavailable for this build. The C3 has no integrated camera; the selected Inland ESP32-CAM pair must be checked for a compatible sensor and PSRAM before committing to vision. Two powered boards and two Wi-Fi streams add mounting, battery and timing risk.
- User-confirmed Micro Center purchases, 24 Oct 2026: Modulino Distance; one XIAO ESP32-C3 3-pack (3 boards); one ESP32-CAM pair (2 boards); one DRV2605L; one Modulino Vibro (SKU 992891); TCA9548A mux; TPS62827; Inland FTDI programmer; unspecified soldering tools. No separate vibration motor, power bank, complete cable/mount set or integrated audio hardware was reported purchased. Cue firmware now drives the single Vibro (I²C 0x38) as the only haptic actuator; the DRV2605L breakout stays on the bus only for its SDA/SCL pull-ups, and the TCA9548A and TPS62827 are unused. Full current inventory: README. Architecture §12.2–12.4 and the PDFs preserve the pre-purchase two-DRV design and stock/pricing snapshot; those figures are not receipts or current inventory. No complete-build total, verified mount fit or approved integrated audio solution.

## Capabilities and Constraints

- Supplements the cane or guide dog; never replaces them. No "all clear" sound. Failure is announced, never silent.
- Cannot reliably detect glass, thin wires, drop-offs and downward stairs, fast vehicles, which side an obstacle is on, what it is, or anything outside the sensor's narrow forward view. The existing software warning envelope remains capped at 1.2 m; this is not a validated useful warning distance for the purchased L4ED module. Proposed bands, to be measured and tuned: FAR 900–1200 mm, MID 500–899 mm, NEAR under 500 mm.
- An absent or unusable return (including an empty or out-of-range view) is UNKNOWN, not proof of empty space. Announce DEGRADED and stop metric warnings until valid measurements resume.
- Calibration matches perceived loudness per side under a phone-enforced hard ceiling. One forward zone carries no direction, so hazard cues play on both ears and both temples; in single-sided deafness the audio goes mono. A single camera object is never associated with the single ToF return.
- Failure channels: if the headset route, a board or the sensor fails, the phone announces it on a surviving output (headset audio, temple haptics, phone vibration) and never silently reroutes cue audio to the phone speaker. If the phone itself dies, only the cue board's haptic-only watchdog buzz remains, with no sound; its haptic strength has a floor of 1 of 255, which is a protocol rule, not a measured guarantee the wearer can feel it.
- Thresholds and latency are proposed until measured.

## Evidence on Hand

- `Determine-Architecture.pdf` / `.html`: proposed architecture contract and pre-purchase Micro Center stock/pricing snapshot (Chapter 12), not the current purchased BOM or haptic hardware. README records the confirmed inventory and the single-Vibro haptics. Integrated audio remains unresolved.
- No user testimonials, trial data or measured latency exist yet; never fabricate them.
- Hardware diagnosis, 24 Oct 2026: purchased Distance identifies as VL53L4ED (0xECAA) and needs ST's L4ED driver. The bare four-wire bus failed without external pull-ups; with the DRV2605L breakout's 10 kΩ pull-ups, status-0 readings tracked a hand from 231 mm to 28 mm and normal firmware initialised. Bench evidence only, not stable long-run operation or verified warning performance.
- Vibro bench, 24 Oct 2026: Vibro answered at 0x38 alongside the Distance; firmware haptics initialised and the watchdog LSS ran with no I²C errors in a 30 s capture. Vibration strength, perceptibility and long-run reliability are not measured.
- User-reported bench results, 24 Oct 2026 (not independently observed): replacement C3 with its U.FL antenna fitted; all compatible iPhone app features worked on the user's iPhone; power OK; a 10-minute soak showed no resets or faults. The DRV2605L breakout stays on the bus as the I²C pull-up source (no resistors on hand). The first replacement-board test without an antenna joined at −82 to −85 dBm and dropped packets; with the antenna fitted, the user reports it works.

## Product Principles

1. Silence means "nothing to report from a working system", never "path clear".
2. The wearable decides nothing; the phone decides everything.
3. Urgent warnings never wait on the network.
4. Loudness is never the only signal.
5. Claim only what was measured or built at the event.

## Accessibility & Inclusion

Design for blind, low-vision, and unilaterally hearing-impaired users. Printed surfaces need high contrast and large type, and must still make sense in text extraction and screen readers.

## Future

Parked ideas, not part of the HackTX 26 build or any current claim.

- Vector embeddings: explore embedding-based features (scope not yet defined).
- Session record and replay: log real RANGE/CUE/state from walks and replay them through the simulator to tune warning distances. Deferred by the user (24 Oct 2026); keep the engine's timestamped-input design compatible with it.
- Describe without mandatory face blur: redaction becomes an optional setting (user decision 24 Oct 2026). Partly built: the API accepts `redacted: false` only with `unredacted_opt_in: true`, and the iPhone app sends unblurred frames only with that opt-in (default off). Android still blurs; an optional blur setting is not built.
- Lateral sensing (second Distance on the owned TCA9548A) and head-yaw input (Modulino Movement): parked, parts not owned.
- Candidate tagline: "Smart phone, dumb glasses." Matches principle 2: the wearable decides nothing; the phone decides everything.
