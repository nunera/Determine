---
name: Determine — The Score
description: Eight-page landscape pitch brief for a proposed accessible mobility aid.
colors:
  paper: "#ffffff"
  ink: "#131316"
  muted-ink: "#4a4d55"
  rule: "#c9cbd1"
  left-ear: "#1d4ed8"
  right-ear: "#cf1f2a"
  left-wash: "#a9c0ff"
  right-wash: "#ffa9ad"
  hazard: "#ffd000"
typography:
  display:
    fontFamily: "Atkinson Hyperlegible Next, sans-serif"
    fontSize: "132px"
    fontWeight: 800
    lineHeight: 0.86
    letterSpacing: "-0.04em"
  heading:
    fontFamily: "Atkinson Hyperlegible Next, sans-serif"
    fontSize: "38px"
    fontWeight: 800
    lineHeight: 1.04
    letterSpacing: "-0.025em"
  body:
    fontFamily: "Atkinson Hyperlegible Next, sans-serif"
    fontSize: "14px"
    lineHeight: 1.42
  measurement:
    fontFamily: "Atkinson Hyperlegible Mono, monospace"
    fontSize: "11px"
    fontWeight: 600
---

## Overview

The pitch brief treats Determine's cue language as a two-stave score. Notes accelerate as a hazard draws nearer; left and right channels are legible without sound by color, position, and text. This is a presentation system for the proposed product, not an image of a finished prototype. It replaces the dense architecture report's report-like visual world without changing its technical claims.

## Colors

White paper and near-black engraving ink keep print contrast high. Audiology's blue-left and red-right convention distinguishes the channels. A yellow field identifies the near-hazard measure; color never carries direction or urgency by itself. Light channel washes may overlap to indicate both ears, with explicit text labels.

## Typography

Atkinson Hyperlegible Next carries every reading surface, including the cover display. Atkinson Hyperlegible Mono is for timings, distances, footers, and source numbering, not decorative technical styling. Musical rests are Noto Music glyphs; noteheads, stems, beams, and staff rules are precise SVG geometry. Source text stays selectable and linked in the PDF.

## Layout

Each page is fixed at 11 × 8.5 inches in landscape orientation with a .62-inch horizontal inset. One idea owns each spread; explanatory text follows its figure rather than surrounding it with cards. The cover's two-stave score maps distance to tempo. The last page houses limitations and compact linked sources. `Determine-Brief.html` is the editable print source; `Determine-Brief.pdf` is the tagged, compressed deliverable.

## Shapes

Square rehearsal marks, straight rules, barlines, noteheads, and rhythm beams form the recurring geometry. No rounded cards, decorative blur, glass, or shadows. Thin black or channel-colored lines connect annotations to the hardware diagram.

## Components

A boxed rehearsal letter anchors each interior page title. Staff diagrams use a five-line grid with left and right channel labels; each graphic also has a text alternative in its SVG `aria-label`. A footer gives the document name, chapter cue, and page count. The on-request scene-description example is explicitly labelled illustrative, not a model result.

## Do's and Don'ts

- Do keep the cane and the user in the story; show the cloud path as optional and interruptible.
- Do distinguish measured facts, vendor specifications, illustrative scenes, and proposed performance targets.
- Do preserve high contrast, selectable text, links, and a tagged PDF when recompressing.
- Don't depict silence as proof that a path is safe or bone conduction as a cure for single-sided deafness.
- Don't name-drop Annapurna Labs without identifying what runs on Graviton, Trainium2, or Inferentia2 and what remains untested.
