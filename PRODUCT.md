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

Determine is "dumb glasses": one XIAO ESP32-C3 from the selected Micro Center three-pack handles an 8×8 time-of-flight sensor, stereo bone-conduction cues and temple haptics; a separate ESP32-CAM supplies JPEG frames. Both connect to the phone over Wi-Fi. The phone alone decides what counts as a hazard and sends back short cue commands. The frame is an aid alongside a cane or guide dog, not a navigation guarantee.

## Positioning

A parking sensor for walking, not "AI glasses for the blind". Low-cost hardware, phone-side perception, bone conduction plus haptics, and per-side loudness calibration for unilateral hearing loss.

## Operating Context

- Built at HackTX 26 (UT Austin, 24–25 Oct 2026). Rule: all work must be done at the event.
- Demo: staged indoor course with a sighted spotter.
- Reflex loop (hazard warnings) runs offline on the phone. The optional, on-demand Describe loop targets a Graviton API plus SmolVLM on Inferentia2 via Hugging Face Optimum Neuron; Trainium2/Qwen2-VL is a more explicitly AWS-documented alternative, and Bedrock is a silicon-unspecified fallback. None is part of an urgent warning; all deployments remain unvalidated.
- The XIAO ESP32-S3 Sense is unavailable for this build. The C3 has no integrated camera; the separate ESP32-CAM must be checked for a compatible sensor and PSRAM before committing to vision. Exact Micro Center price and local stock for the C3 three-pack and camera board remain unverified. Two powered boards and two Wi-Fi streams add mounting, battery and timing risk.

## Capabilities and Constraints

- Supplements the cane or guide dog; never replaces them. No "all clear" sound. Failure is announced, never silent.
- Cannot see: glass, thin wires, drop-offs and downward stairs, fast vehicles, anything beyond about 3 m.
- Calibration matches perceived loudness per side under a hard ceiling. In single-sided deafness, direction moves to haptics.
- Thresholds and latency are proposed until measured.

## Evidence on Hand

- `Determine-Architecture.pdf` / `.html`: sourced architecture contract. Earlier single-board ≈$127 parts estimate is superseded; the two-board bill of materials needs verified prices and power parts before quoting a total.
- No user testimonials, trial data or measured latency exist yet; never fabricate them.

## Product Principles

1. Silence means "nothing to report from a working system", never "path clear".
2. The wearable decides nothing; the phone decides everything.
3. Urgent warnings never wait on the network.
4. Loudness is never the only signal.
5. Claim only what was measured or built at the event.

## Accessibility & Inclusion

Design for blind, low-vision, and unilaterally hearing-impaired users. Printed surfaces need high contrast and large type, and must still make sense in text extraction and screen readers.
