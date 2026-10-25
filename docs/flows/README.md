# Determine flow maps

Mermaid diagrams (GitHub renders them inline). Pre-rendered SVGs live in [`rendered/`](rendered/).
Sources of truth: `protocol/PROTOCOL.md` (wire v2), `firmware/common/include/determine_wire.h`,
`Determine-Architecture.html`. Numbers marked PROPOSED are untuned design values, not measurements.

Design basis: one forward single-zone ToF (Arduino Modulino Distance, VL53L4CD), a haptic-only cue board, cue audio
synthesised on the phone and played on a Bluetooth bone-conduction headset. No 8×8 grid, no board audio, no lateral range.

The headset path is implemented software/bench behavior, not approved final hardware. Separate OpenRun not approved; integrated bone conduction unresolved. Houston's snapshot shows one of two motors and sold-out LM3671; candidates are not adopted (Architecture §12.2).

| File | Diagram | SVG |
|---|---|---|
| [hardware.md](hardware.md) | (a) Physical layout and power | [hardware-1](rendered/hardware-1.svg) |
| | (b) Electrical buses + pin table | [hardware-2](rendered/hardware-2.svg) |
| | (c) Network / data topology | [hardware-3](rendered/hardware-3.svg) |
| | (d) Boot and join sequence | [hardware-4](rendered/hardware-4.svg) |
| | (d) Steady-state cue round trip | [hardware-5](rendered/hardware-5.svg) |
| | (d) Failure paths | [hardware-6](rendered/hardware-6.svg) |
| [software.md](software.md) | (a) Cue-board tasks | [software-1](rendered/software-1.svg) |
| | (a) CUE handling decision chart | [software-2](rendered/software-2.svg) |
| | (b) Camera capture / chunk / pace loop | [software-3](rendered/software-3.svg) |
| | (c) Phone pipeline | [software-4](rendered/software-4.svg) |
| | (d) HazardPolicy v0 | [software-5](rendered/software-5.svg) |
| | (e) System state machine | [software-6](rendered/software-6.svg) |
| | (f) Calibration | [software-7](rendered/software-7.svg) |
| | (g) Describe loop | [software-8](rendered/software-8.svg) |
| | (h) Phone core / Android module map | [software-9](rendered/software-9.svg) |

The `.md` files are the source. The checked-in SVGs are generated artifacts: re-render every diagram after editing
(writes `rendered/<file>-<n>.svg` with plain SVG text labels, so they show in any viewer and in GitHub image tags):

```sh
nix shell nixpkgs#mermaid-cli -c python3 docs/flows/render.py
```
