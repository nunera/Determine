/**
 * Single-zone range gauge: the ONE forward ToF reading exactly as the world sends it to the phone (wire v2 RANGE:
 * Modulino Distance / VL53L4ED, ~18° diagonal cone, ≤ 1.2 m). A horizontal 0–1200 mm ruler with the proposed hazard
 * bands drawn as labelled hairline segments (NEAR < 500, MID 500–899, FAR 900–1200; Tuning defaults) and a needle
 * at the VALID distance. NO_TARGET and UNKNOWN are explicit states, never drawn as "far" or "clear".
 *
 * The bands here are a visual reference for the raw reading only: the engine decides the alert band (it may escalate
 * on approach, or drive the explore pulse train instead), and the HUD shows that separately.
 */
import {
  BAND_FAR_MM, BAND_MID_MM, BAND_NEAR_MM, RANGE_LIMIT_MM, RANGE_NO_TARGET, RANGE_STATUS_NAMES, RANGE_VALID,
  type SensorFrame,
} from "../contract";
import { svg, setText } from "./dom";

const W = 160;
const H = 44;
const X0 = 8;
const X1 = W - 8;
const BAND_Y = 9;      // band segment hairline
const RULER_Y = 26;    // ruler baseline
const INK = "#131316";
const MUTED = "#4a4d55";

const xOf = (mm: number) => X0 + (Math.min(RANGE_LIMIT_MM, Math.max(0, mm)) / RANGE_LIMIT_MM) * (X1 - X0);

export interface GaugeView {
  root: SVGSVGElement;
  update(frame: SensorFrame): void;
  /** One-sentence text alternative of the current reading. */
  describe(): string;
}

export function createGauge(): GaugeView {
  const root = svg("svg", {
    viewBox: `0 0 ${W} ${H}`,
    class: "gauge-svg",
    role: "img",
    "aria-label": "Forward range: no reading yet",
  });
  root.append(svg("defs", {},
    svg("pattern", { id: "gauge-hatch", width: 2.4, height: 2.4, patternUnits: "userSpaceOnUse", patternTransform: "rotate(45)" },
      svg("rect", { width: 2.4, height: 2.4, fill: "#eeeff2" }),
      svg("line", { x1: 0, y1: 0, x2: 0, y2: 2.4, stroke: MUTED, "stroke-width": 0.7 }),
    ),
  ));

  // Band segments: hairline brackets with labels; only NEAR carries hazard yellow, always with its text.
  const bands: [number, number, string][] = [
    [0, BAND_NEAR_MM, `NEAR <${BAND_NEAR_MM}`],
    [BAND_NEAR_MM, BAND_MID_MM, `MID ${BAND_NEAR_MM}–${BAND_MID_MM - 1}`],
    [BAND_MID_MM, BAND_FAR_MM, `FAR ${BAND_MID_MM}–${BAND_FAR_MM}`],
  ];
  const bandG = svg("g", { class: "gauge-bands" });
  for (const [a, b, label] of bands) {
    const xa = xOf(a) + 0.6;
    const xb = xOf(b) - 0.6;
    if (a === 0) bandG.append(svg("rect", { x: xa, y: BAND_Y - 6, width: xb - xa, height: 6, fill: "#ffd000" }));
    bandG.append(
      svg("path", { d: `M${xa} ${BAND_Y - 2}V${BAND_Y}H${xb}V${BAND_Y - 2}`, fill: "none", stroke: INK, "stroke-width": 0.35 }),
      svg("text", { x: (xa + xb) / 2, y: BAND_Y - 1.6, "text-anchor": "middle", class: "gauge-band" }, label),
    );
  }
  root.append(bandG);

  // Ruler: tick every 100 mm, labelled every 200 mm.
  const ruler = svg("g", { class: "gauge-ruler" });
  ruler.append(svg("line", { x1: X0, y1: RULER_Y, x2: X1, y2: RULER_Y, stroke: INK, "stroke-width": 0.4 }));
  for (let mm = 0; mm <= RANGE_LIMIT_MM; mm += 100) {
    const x = xOf(mm);
    const major = mm % 200 === 0;
    ruler.append(svg("line", { x1: x, y1: RULER_Y, x2: x, y2: RULER_Y + (major ? 2.6 : 1.5), stroke: INK, "stroke-width": 0.3 }));
    if (major) {
      ruler.append(svg("text", { x, y: RULER_Y + 6, "text-anchor": mm === 0 ? "start" : mm === RANGE_LIMIT_MM ? "end" : "middle", class: "gauge-tick" },
        mm === 0 ? "0 mm" : String(mm)));
    }
  }
  root.append(ruler);

  // Explicit non-VALID states, drawn over the whole measuring field.
  const field = svg("rect", { x: X0, y: BAND_Y + 1.5, width: X1 - X0, height: RULER_Y - BAND_Y - 1.5, fill: "none", visibility: "hidden" });
  const fieldText = svg("text", { x: (X0 + X1) / 2, y: (BAND_Y + RULER_Y) / 2 + 1.6, "text-anchor": "middle", class: "gauge-state", visibility: "hidden" });
  const fieldTextBg = svg("rect", { fill: "#ffffff", visibility: "hidden" });
  root.append(field, fieldTextBg, fieldText);

  // Needle at the VALID distance.
  const needle = svg("g", { class: "gauge-needle", visibility: "hidden" },
    svg("line", { x1: 0, y1: BAND_Y + 1.5, x2: 0, y2: RULER_Y, stroke: INK, "stroke-width": 0.9 }),
    svg("path", { d: `M-2.2 ${RULER_Y + 0.2}L0 ${RULER_Y - 3}L2.2 ${RULER_Y + 0.2}Z`, fill: INK }),
  );
  const needleText = svg("text", { x: 0, y: BAND_Y + 6.4, class: "gauge-value" });
  needle.append(needleText);
  root.append(needle);

  let summary = "Forward range: no reading yet.";

  function update(frame: SensorFrame): void {
    if (frame.status === RANGE_VALID) {
      const x = xOf(frame.mm);
      needle.setAttribute("transform", `translate(${x.toFixed(2)} 0)`);
      needle.setAttribute("visibility", "visible");
      // Keep the value label inside the ruler.
      const right = x > (X0 + X1) / 2;
      needleText.setAttribute("text-anchor", right ? "end" : "start");
      needleText.setAttribute("x", right ? "-1.6" : "1.6");
      setText(needleText, `${frame.mm} mm`);
      field.setAttribute("visibility", "hidden");
      fieldText.setAttribute("visibility", "hidden");
      fieldTextBg.setAttribute("visibility", "hidden");
      summary = `Forward range: VALID, ${frame.mm} millimetres.`;
      return;
    }
    needle.setAttribute("visibility", "hidden");
    const noTarget = frame.status === RANGE_NO_TARGET;
    field.setAttribute("fill", noTarget ? "none" : "url(#gauge-hatch)");
    field.setAttribute("stroke", INK);
    field.setAttribute("stroke-width", "0.35");
    field.setAttribute("stroke-dasharray", noTarget ? "1.2 1.2" : "none");
    field.setAttribute("visibility", "visible");
    const name = RANGE_STATUS_NAMES[frame.status] ?? `status ${frame.status}`;
    const text = noTarget ? "NO TARGET — not clear" : `${name} — sensor reading unusable`;
    setText(fieldText, text);
    fieldText.setAttribute("visibility", "visible");
    const tw = text.length * 2.25 + 4;
    fieldTextBg.setAttribute("x", String((X0 + X1) / 2 - tw / 2));
    fieldTextBg.setAttribute("y", String((BAND_Y + RULER_Y) / 2 - 2.8));
    fieldTextBg.setAttribute("width", String(tw));
    fieldTextBg.setAttribute("height", "5.6");
    fieldTextBg.setAttribute("visibility", "visible");
    summary = noTarget
      ? "Forward range: NO TARGET within 1.2 metres. This does not mean the path is clear."
      : "Forward range: UNKNOWN, the sensor reading is unusable.";
  }

  return { root, update, describe: () => summary };
}
