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

Determine is a proposed compact headband prototype of "dumb glasses": one XIAO ESP32-C3 from a Micro Center three-pack hosts a single 12.7 × 25.4 mm VL53L5CX Mini 8×8 time-of-flight sensor, stereo bone-conduction cues and temple haptics; a separate ESP32-CAM supplies JPEG frames. Only the camera and ToF face forward; the C3, amps and haptic boards sit on a rear strap, and the power bank stays in a pocket. Both boards connect to the phone over Wi-Fi. The phone alone decides what counts as a hazard. This is a layout to test, not a demonstrated comfortable pair of glasses or a navigation guarantee.

## Positioning

A parking sensor for walking, not "AI glasses for the blind". Low-cost hardware, phone-side perception, bone conduction plus haptics, and per-side loudness calibration for unilateral hearing loss.

## Operating Context

- Built at HackTX 26 (UT Austin, 24–25 Oct 2026). Rule: all work must be done at the event.
- Demo: staged indoor course with a sighted spotter.
- Reflex loop (hazard warnings) runs offline on the phone. The optional, on-demand Describe loop targets a Graviton API plus SmolVLM on Inferentia2 via Hugging Face Optimum Neuron; Trainium2/Qwen2-VL is a more explicitly AWS-documented alternative, and Bedrock is a silicon-unspecified fallback. None is part of an urgent warning; all deployments remain unvalidated.
- The XIAO ESP32-S3 Sense is unavailable for this build. The C3 has no integrated camera; the selected Inland ESP32-CAM pair must be checked for a compatible sensor and PSRAM before committing to vision. The user reports the listed Micro Center Houston components in stock, but their checkout, prices and shelf counts were not independently observed. Two powered boards and two Wi-Fi streams add mounting, battery and timing risk.
- Houston pickup first for listed boards, amps, haptic drivers and motors, mux, regulator and FTDI adapter. One SparkFun VL53L5CX Mini plus Qwiic-to-wire harness and two Adafruit 1674 raw bone transducers from DigiKey are the two shipped exceptions unless exact local equivalents are secured; confirm checkout delivery. Three locally sold Arduino Modulino Distance boards were rejected as too bulky and too short-range, not a drop-in 8×8 alternative.

## Capabilities and Constraints

- Supplements the cane or guide dog; never replaces them. No "all clear" sound. Failure is announced, never silent.
- Cannot see: glass, thin wires, drop-offs and downward stairs, fast vehicles, anything beyond about 3 m.
- Calibration matches perceived loudness per side under a hard ceiling. In single-sided deafness, direction moves to haptics.
- Thresholds and latency are proposed until measured.

## Evidence on Hand

- `Determine-Architecture.pdf` / `.html`: sourced architecture contract and complete compact-prototype purchase list (Chapter 12), including quantities, Houston SKUs, shipped exceptions and unpriced accessories. Earlier single-board ≈$127 parts estimate is superseded; no two-board grand total is quoted without verified prices and power parts.
- No user testimonials, trial data or measured latency exist yet; never fabricate them.

## Product Principles

1. Silence means "nothing to report from a working system", never "path clear".
2. The wearable decides nothing; the phone decides everything.
3. Urgent warnings never wait on the network.
4. Loudness is never the only signal.
5. Claim only what was measured or built at the event.

## Accessibility & Inclusion

Design for blind, low-vision, and unilaterally hearing-impaired users. Printed surfaces need high contrast and large type, and must still make sense in text extraction and screen readers.
