#!/usr/bin/env python3
"""Build the self-contained Determine visual technical brief.

Audience: project owner, engineers and judges. Bedrock: basic software, geometry,
units and rates; no prior Neuron, DAT or ranging knowledge assumed.
Capability: trace a warning, identify sensor blind spots, distinguish simulated
transport from physical latency, and scope a truthful glasses integration.
Concept homes: interface p2; physical hardware p3; software/clocks p4; numerical
sensor p5; recorded evidence p6; TTC/latency p7; failure semantics p8; glasses p9;
crop/export p10; Describe/privacy/cloud p11; validation p12; sources/glossary p13.
Figures are drawn at final print size. All drawing coordinates use top-left origin.
The sign trace and screenshot are portable snapshots of prior observed runs.

Requires reportlab and Pillow. Run: python3 docs/visual-guide/build.py
"""
from __future__ import annotations
import hashlib
import json
import math
from pathlib import Path
import subprocess
from xml.sax.saxutils import escape

from PIL import Image
from reportlab.pdfgen import canvas
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.lib.colors import HexColor, Color, white
from reportlab.lib.styles import ParagraphStyle
from reportlab.platypus import Paragraph
from reportlab.lib.utils import ImageReader

HERE = Path(__file__).resolve().parent
ASSETS = HERE / "assets"
OUT = HERE / "Determine-Simulation-and-Glasses.pdf"
W, H = 612, 792
M, CW = 48, 516
INK = HexColor("#152A38")
MUTED = HexColor("#506371")
TEAL = HexColor("#087F80")
BLUE = HexColor("#315EAD")
AMBER = HexColor("#B56F0B")
RED = HexColor("#AE3E38")
BORDER = HexColor("#CBD4D7")
PAPER = HexColor("#FAFAF6")
PALE = HexColor("#EDF3F3")
BAND_COLORS = {"FAR": BLUE, "MID": AMBER, "NEAR": RED}


def font_path(family: str) -> str:
    return subprocess.check_output(["fc-match", "-f", "%{file}", family], text=True).strip()


for name, family in [("Body", "DejaVu Sans"), ("Bold", "DejaVu Sans:style=Bold"), ("Mono", "DejaVu Sans Mono")]:
    pdfmetrics.registerFont(TTFont(name, font_path(family)))
pdfmetrics.registerFontFamily("Body", normal="Body", bold="Bold", italic="Body", boldItalic="Bold")

TRACE = json.loads((ASSETS / "sign-trace.json").read_text())
STATS = {
    "coneWidthM": 2 * 1.2 * math.tan(math.radians(9)),
    "greyLimitMm": 1200 * (.17 / .88) ** .596,
    "barkLimitMm": 1200 * (.2 / .88) ** .596,
    "cropWidthPx": 240 * 9 / 16,
    "cropX": (320 - 240 * 9 / 16) / 2,
    "retainedWidthPct": 100 * (240 * 9 / 16) / 320,
    "resampleScale": 1280 / 240,
    "decodedRgbMBps": 720 * 1280 * 3 * 30 / 1e6,
}
FIRST_VALID = next(x for x in TRACE["sensors"] if x["status"] == 0)
FIRST_FAR = next(x for x in TRACE["alerts"] if x["band"] == "FAR")
STATS["receiptIntervalMs"] = FIRST_FAR["wallMs"] - FIRST_VALID["wallMs"]

C = canvas.Canvas(str(OUT), pagesize=(W, H), pageCompression=1)
C.setTitle("Determine — Inside the simulation: glasses and technical implications")
C.setAuthor("Determine project • technical research and implementation brief")
C.setSubject("Wire-v2 simulation, real-engine evidence, wearable integration and validation boundaries")
C.setKeywords("Determine, simulation, smart glasses, ToF, TTC, DAT, accessibility, Neuron")
PAGE = 0
AUDIT = []


def line(x1, y1, x2, y2, color=BORDER, width=1, dashed=False):
    C.setStrokeColor(color)
    C.setLineWidth(width)
    C.setDash(3, 3) if dashed else C.setDash()
    C.line(x1, H-y1, x2, H-y2)
    C.setDash()


def box(x, y, w, h, fill=PALE, stroke=None, radius=5, dashed=False):
    C.setFillColor(fill or white)
    C.setStrokeColor(stroke or fill or white)
    C.setLineWidth(.8)
    if dashed:
        C.setDash(4, 3)
    C.roundRect(x, H-y-h, w, h, radius, stroke=bool(stroke), fill=bool(fill))
    C.setDash()


def text(s, x, y, size=10, font="Body", color=INK):
    C.setFillColor(color)
    C.setFont(font, size)
    C.drawString(x, H-y-size, s)
    AUDIT.append((PAGE, y, y+size, s))


def para(s, x, y, w, size=10.2, color=INK, leading=None, font="Body"):
    style = ParagraphStyle("p", fontName=font, fontSize=size, leading=leading or size*1.43, textColor=color,
                           spaceAfter=0, allowWidows=0, allowOrphans=0)
    p = Paragraph(s, style)
    _, h = p.wrap(w, H)
    p.drawOn(C, x, H-y-h)
    AUDIT.append((PAGE, y, y+h, s))
    if y+h > 742:
        raise ValueError(f"Page {PAGE} paragraph exceeds body: bottom {y+h:.1f}: {s[:80]}")
    return y+h


def heading(s, x, y, w=CW, size=14):
    return para(s, x, y, w, size=size, font="Bold", leading=size*1.2)


def note(s, y, color=TEAL, x=M, w=CW):
    p = Paragraph(s, ParagraphStyle("n", fontName="Body", fontSize=9.1, leading=13.2, textColor=INK))
    _, h = p.wrap(w-28, H)
    box(x, y, w, h+22, fill=PALE)
    box(x, y, 3, h+22, fill=color, radius=0)
    p.drawOn(C, x+14, H-y-11-h)
    AUDIT.append((PAGE, y, y+h+22, s))
    assert y+h+22 < 743, (PAGE, s)
    return y+h+22


def caption(n, s, y, x=M, w=CW):
    return para(f"<b>Fig. {n}.</b> {s}", x, y, w, size=8.1, color=MUTED, leading=11.4)


def circle(x, y, r, fill=white, stroke=INK, width=1):
    C.setFillColor(fill)
    C.setStrokeColor(stroke)
    C.setLineWidth(width)
    C.circle(x, H-y, r, fill=1, stroke=1)


def polygon(points, fill, stroke=None, width=1):
    p = C.beginPath()
    p.moveTo(points[0][0], H-points[0][1])
    for x, y in points[1:]:
        p.lineTo(x, H-y)
    p.close()
    C.setFillColor(fill)
    C.setStrokeColor(stroke or fill)
    C.setLineWidth(width)
    C.drawPath(p, fill=1, stroke=bool(stroke))


def arrow(x1, y1, x2, y2, color=TEAL, width=1.4, dashed=False):
    line(x1, y1, x2, y2, color, width, dashed)
    a = math.atan2(y2-y1, x2-x1)
    pts = [(x2, y2), (x2-6*math.cos(a-.45), y2-6*math.sin(a-.45)),
           (x2-6*math.cos(a+.45), y2-6*math.sin(a+.45))]
    polygon(pts, color)


def node(x, y, w, h, title, body, color=TEAL, proposed=False):
    box(x, y, w, h, fill=white, stroke=color, dashed=proposed)
    para(title, x+12, y+10, w-24, size=10, font="Bold", color=color)
    if body:
        para(body, x+12, y+29, w-24, size=8.8, leading=12, color=INK)


def bullet(s, y, x=M, w=CW, color=TEAL):
    circle(x+3, y+7, 2, fill=color, stroke=color)
    return para(s, x+15, y, w-15)+10


def table(headers, rows, x, y, widths, size=9):
    top = y
    for idx, row in enumerate([headers, *rows]):
        cells = []
        for s, w in zip(row, widths):
            p = Paragraph(s, ParagraphStyle("cell", fontName="Bold" if idx == 0 else "Body",
                        fontSize=size, leading=size*1.35, textColor=INK))
            _, h = p.wrap(w-16, H)
            cells.append((p, h))
        rh = max(h for _, h in cells)+16
        box(x, y, sum(widths), rh, fill=PALE if idx == 0 else (white if idx%2 else PAPER), radius=0)
        xx = x
        for w, (p, h) in zip(widths, cells):
            p.drawOn(C, xx+8, H-y-8-h)
            xx += w
        y += rh
        line(x, y, x+sum(widths), y)
    AUDIT.append((PAGE, top, y, "table"))
    assert y < 742, (PAGE, "table", y)
    return y


TITLES = [
    "Inside the simulation", "Read the demo", "What is actually worn?", "How a warning crosses the software",
    "How the virtual sensor sees", "A real engine trace, not a scripted warning", "Urgency and the latency budget",
    "Failure is a state, not silence", "What changes with smart glasses?", "Pixels are not optical evidence",
    "Describe, privacy and cloud placement", "Turn the demo into research", "Sources, glossary and reproduction",
]


def page(title, deck=None, section="TECHNICAL BRIEF"):
    global PAGE
    if PAGE:
        C.showPage()
    PAGE += 1
    C.setFillColor(PAPER)
    C.rect(0, 0, W, H, fill=1, stroke=0)
    text("DETERMINE", M, 24, 9, "Bold", TEAL)
    text(section, 345, 25, 7.8, "Mono", MUTED)
    line(M, 45, W-M, 45)
    C.bookmarkPage(f"p{PAGE}")
    C.addOutlineEntry(title, f"p{PAGE}", level=0)
    title_size = 24 if len(title) < 38 else 21
    while pdfmetrics.stringWidth(title, "Bold", title_size) > CW:
        title_size -= .5
    para(title, M, 65, CW, size=title_size, font="Bold", leading=27)
    if deck:
        para(deck, M, 108, CW, size=10.2, color=MUTED, leading=14.2)
    line(M, 752, W-M, 752)
    text("Wire v2 · upstream 65cfa0c · research snapshot 24 Oct 2026", M, 763, 7.2, color=MUTED)
    C.setFont("Mono", 8)
    C.setFillColor(MUTED)
    C.drawRightString(W-M, H-772, f"{PAGE:02d} / 13")


# 1 — Cover, navigation and the evidence contract.
page(TITLES[0], section="SIMULATION + GLASSES / VISUAL GUIDE")
para("A virtual environment with a real warning engine.<br/>A glasses pathway with explicit engineering limits.", M, 118, CW,
     size=18, leading=25, font="Bold")
box(M, 203, CW, 101, fill=INK)
text("MODEL THE WORLD.", M+20, 219, 21, "Bold", white)
text("DO NOT MODEL AWAY THE LIMITS.", M+20, 252, 17, "Bold", white)
para("This brief explains how the browser turns geometry into one range reading, how the phone engine turns readings into cues, and what smart glasses would change. It assumes basic software and distance/time arithmetic, not specialist wearable knowledge.", M, 324, CW, size=10.6)
heading("Use this as a map", M, 412)
left = [(2, "Read the running demo"), (3, "Current wearable hardware"), (4, "Software + clock boundaries"), (5, "Sensor model and blind spots"), (6, "Recorded engine evidence"), (7, "TTC and latency")]
right = [(8, "Failure-state semantics"), (9, "Glasses integration architecture"), (10, "Crop/resample versus real optics"), (11, "Privacy + cloud implications"), (12, "Experiments and demo plan"), (13, "Sources + reproduction")]
for xx, entries in [(M, left), (315, right)]:
    for k, (n, label) in enumerate(entries):
        yy = 445+k*24
        text(f"{n:02}", xx, yy, 9.3, "Mono", TEAL)
        text(label, xx+27, yy, 9.1)
        C.linkAbsolute(label, f"p{n}", Rect=(xx, H-yy-17, xx+246, H-yy+2), thickness=0)
heading("How to read the evidence", M, 614)
para("<b>CODE</b> = implemented behaviour; <b>OBSERVED</b> = recorded local run; <b>MODEL</b> = calculation or sensor assumption; <b>PROPOSED</b> = not integrated. Blue denotes transport, teal local processing, amber optional ML/research. Dashed outlines mark proposed routes. Sources [1–10] are on page 13.", M, 642, CW, size=9.5)

# 2 — Actual surface, then the course catalogue.
page(TITLES[1], "The scene illustrates a head-height obstacle. The gauge and score expose what the engine actually did—not what the animation author wanted it to do.")
im = Image.open(ASSETS / "simulation.png")
# Keep the meaningful scene large; tiny dashboard text is not used as explanatory text.
sw, sh = im.size
crop = im.crop((int(sw*.215), int(sh*.17), int(sw*.785), int(sh*.815)))
C.drawImage(ImageReader(crop), M, H-170-236, width=330, height=236, preserveAspectRatio=True, anchor="c")
box(392, 170, 172, 236, fill=white, stroke=BORDER)
text("OBSERVED UI", 406, 184, 8.1, "Mono", TEAL)
text("READY", 406, 207, 20, "Bold")
text("NEAR ahead", 406, 241, 13, "Bold", RED)
para("1  Course + expected limit<br/><br/>2  Real-engine state and alert<br/><br/>3  Raw range versus smoothed urgency<br/><br/>4  Separate sound and vibration staves", 406, 275, 144, size=8.7, leading=12)
caption(1, "The cane can pass below the sign while the brow-mounted sensor faces it. Scene crop from the actual production-demo screenshot; the enlarged status at right transcribes the observed state. This is software rendering, not a physical walk. [1, 3]", 418)
heading("Seven courses make different claims", M, 474)
table(["Course", "Question it makes visible"], [
    ["Sign", "Can the range policy warn about a head-height object the cane passes under?"],
    ["Scaffold / pedestrian", "What is missed beside the cone? What changes with relative closing speed?"],
    ["Dark branch", "What happens when the assumed material return range is short?"],
    ["Glass", "Does an explicitly unknown measurement remove the stale alert and degrade state?"],
    ["Plaza / stairs", "Can we explain silence without claiming that the path is clear?"],
], M, 502, [128, 388], size=8.7)

# 3 — Physical arrangement and its engineering consequences.
page(TITLES[2], "Current headband architecture—not glasses hardware. The drawing is a functional placement sketch, not a measured assembly or wiring CAD. [1, 2]")
# Side profile, facing right.
circle(233, 262, 47, fill=HexColor("#E5E8E4"), stroke=MUTED)
polygon([(272, 251), (287, 266), (273, 273)], HexColor("#E5E8E4"), MUTED)
box(217, 309, 29, 38, fill=HexColor("#E5E8E4"), stroke=MUTED)
box(180, 342, 101, 88, fill=HexColor("#E5E8E4"), stroke=MUTED, radius=21)
line(189, 239, 274, 239, INK, 7)
box(270, 228, 17, 18, TEAL)
box(269, 254, 16, 14, BLUE)
box(179, 232, 19, 27, INK)
circle(184, 270, 10, fill=white, stroke=AMBER, width=3)
line(187, 284, 214, 299, BLUE, 3)
arrow(286, 235, 355, 212, TEAL)
para("<b>Forward ToF</b><br/>One zone; distance/status only.<br/>No object identity or left/right.", 363, 191, 195, size=9.2)
arrow(285, 263, 354, 287, BLUE)
para("<b>ESP32-CAM</b><br/>Context frames and optional Describe.<br/>Not the hazard-distance source.", 363, 270, 195, size=9.2)
arrow(179, 244, 113, 213, INK)
para("<b>Cue board + Vibro</b><br/>One motor.<br/>No board speaker.", M, 177, 137, size=9.2)
arrow(182, 276, 117, 315, AMBER)
para("<b>Headset</b><br/>Phone-synthesized audio.<br/>Bluetooth bone-conduction<br/>route in the current design.", M, 307, 145, size=9.2)
node(361, 363, 197, 68, "PHONE", "Runs the Kotlin engine; schedules sound and board vibration.", TEAL)
arrow(283, 389, 352, 389, BLUE, dashed=True)
caption(2, "Sensing, deciding and actuating live on different devices. Losing the cue board cannot be repaired by telling that unpowered board to vibrate; phone-side failure audio and the powered board watchdog cover different failures. Sketch not to scale. [1, 2, 4]", 449)
heading("Why keep the cane?", M, 514)
para("The sensor looks near head height through a narrow cone. A drop-off or low obstacle may never intersect it. The cane samples a different part of the environment, so this design supplements it rather than establishing obstacle-free travel.", M, 544, CW)
heading("The one-motor consequence", M, 607)
para("Wire fields named <font name='Mono'>hl</font> and <font name='Mono'>hr</font> do not create two actuators: the v2 board applies their maximum to one Vibro motor. The range is forward-only, so a cue must not invent a safe steering direction.", M, 636, CW)

# 4 — Flow plus clock/transport mechanics.
page(TITLES[3], "The browser supplies a scene and simulated measurements. The existing phone engine remains the authority for warnings. [1, 2, 4]")
node(48, 167, 238, 74, "1  VIRTUAL ENVIRONMENT", "Geometry + actor pose → cone samples.<br/>A separate head camera renders JPEG.", TEAL)
node(326, 167, 238, 74, "2  PYTHON BRIDGE", "WebSocket in; UDP board protocol out.<br/>Simulates cue/camera boards and ACKs.", BLUE)
arrow(287, 204, 320, 204, BLUE)
text("/ws", 294, 184, 8, "Mono", BLUE)
node(326, 303, 238, 79, "3  REAL KOTLIN ENGINE", "Freshness + status + persistence → alert.<br/>Produces phone AUDIO and board CUE.", TEAL)
arrow(445, 243, 445, 295, BLUE)
text("RANGE 15 Hz target + FRAME 8 fps target", 330, 265, 7.6, "Mono", BLUE)
node(48, 303, 238, 79, "4  OBSERVABLE OUTPUT", "Phone audio plays in the browser.<br/>Accepted board vibration is drawn separately.", TEAL)
arrow(318, 343, 294, 343, BLUE)
text("events", 290, 324, 7.4, "Mono", BLUE)
caption(3, "The simulator exercises the same warning policy through real protocol boundaries. Its loopback ACK is evidence of software/protocol behaviour, not transducer onset or Bluetooth audio delay. Nominal rates are not guaranteed under render load. [1, 4]", 405)
heading("Three clocks must not be silently subtracted", M, 467)
table(["Clock", "What it means / how to use it"], [
    ["Course tS", "Choreography position; may pause or reset. Sensor rows carry the latest course event time, not a synchronized capture instant."],
    ["wallMs", "Recorder receipt time (epoch ms). Same-receiver intervals are useful, but they include transport/scheduling."],
    ["Board / engine time", "Source timestamps used for freshness and policy timing. Compare across devices only after clock-offset handling."],
], M, 497, [133, 383], size=8.8)
para("<b>Where to inspect:</b> <font name='Mono'>scene/world.ts</font> → <font name='Mono'>link/link.ts</font> → <font name='Mono'>tools/sim/world_bridge.py</font> → <font name='Mono'>HazardPolicy.kt</font>. The bus carries observations and commands; it is not a second hazard policy.", M, 689, CW, size=8.5)

# 5 — Sensor construction and reflectance equation.
page(TITLES[4], "Time-of-flight (ToF) estimates distance from reflected light. Here it is approximated with geometric rays and assumed material response—not a physical optical simulator.")
text("TOP VIEW · ONE FORWARD ZONE", M, 165, 8, "Mono", TEAL)
ox, oy, d = 86, 247, 292
half = d*math.tan(math.radians(9))
polygon([(ox, oy), (ox+d, oy-half), (ox+d, oy+half)], HexColor("#DEEEEC"), TEAL)
circle(ox, oy, 6, TEAL, TEAL)
for k in range(-3, 4):
    line(ox, oy, ox+d, oy+(k/3)*half, HexColor("#90BABB"), .5)
box(376, 186, 12, 124, fill=INK)
arrow(ox, 327, ox+d, 327, TEAL)
text("1.2 m nominal white-target limit", 140, 339, 8.5)
text("18° full cone", 111, 211, 9, "Bold", TEAL)
para(f"At 1.2 m, width ≈<br/><b>{STATS['coneWidthM']:.2f} m</b><br/>2 × 1.2 × tan(9°)", 410, 217, 147, size=9.1)
caption(4, "A circular 18° cone is narrow even at its outer range. The browser samples a centre ray plus three rings of twelve rays (37 total); these are numerical samples of one zone, not 37 hardware pixels. Model geometry, not a measured field of view. [1, 2]", 365)
para("For each ray the code intersects yaw-rotated boxes, retains the nearest forward projection, and requires at least four rays to hit geometry. This coverage rule is an assumption. No target produces status 1 with mm = 0; it does not mean a surface is at zero distance.", M, 419, CW, size=9.7)
# Reflectance chart, final print size.
x0, y0, gw, gh = 87, 488, 337, 125
line(x0, y0+gh, x0+gw, y0+gh, MUTED)
line(x0, y0, x0, y0+gh, MUTED)
for v in [0, .6, 1.2]:
    yy=y0+gh*(1-v/1.2)
    line(x0-4, yy, x0+gw, yy, BORDER, .5)
    text(f"{v:.1f}", 57, yy-5, 8, "Mono", MUTED)
for rho in [.17, .5, .88]:
    xx=x0+gw*rho
    text(f"{rho:.2f}", xx-10, y0+gh+8, 8, "Mono", MUTED)
prev=None
for j in range(101):
    rho=j/100
    dm=min(1.2, 1.2*(max(.01,rho)/.88)**.596)
    pt=(x0+gw*rho,y0+gh*(1-dm/1.2))
    if prev: line(*prev,*pt, TEAL, 1.8)
    prev=pt
for rho, dm in [(.17,.45),(.88,1.2)]:
    circle(x0+gw*rho, y0+gh*(1-dm/1.2), 3, TEAL, TEAL)
text("range (m)", 48, 468, 8, color=MUTED)
text("assumed reflectance ρ", 175, 638, 8, color=MUTED)
para("<b>MODEL</b><br/>dmax = 1200 ×<br/>(ρ / 0.88)<super>0.596</super> mm<br/><br/>Capped at 1200 mm.<br/>Noise σ = 4 + 0.005d mm.", 440, 483, 122, size=8.6, leading=12)
caption(5, "The fit reproduces two VL53L4CD datasheet anchors: 0.45 m grey and 1.20 m white. Applying that fit to VL53L4ED, assigning reflectance by object kind, and using a four-ray threshold are uncalibrated assumptions. σ is 10 mm at d = 1200 mm. Bench data must replace them. [2, 8]", 671)

# 6 — Recorded data, causal evidence, not repeated test counts.
page(TITLES[5], "The sign episode below came from the production browser → bridge → installed Kotlin CLI. Approach escalation was OFF. Dots and band changes are loaded from the saved event log. [3]")
x0,y0,gw,gh=88,192,462,204
xmin,xmax=7.8,12.3
px=lambda t:x0+(t-xmin)/(xmax-xmin)*gw
py=lambda mm:y0+gh*(1-mm/1400)
for mm in [0,500,900,1200]:
    yy=py(mm)
    line(x0,yy,x0+gw,yy,BORDER,.6)
    text(str(mm),48,yy-5,8,"Mono",MUTED)
for t in [8,9,10,11,12]:
    xx=px(t)
    line(xx,y0,xx,y0+gh,BORDER,.4)
    text(str(t),xx-3,y0+gh+9,8,"Mono",MUTED)
text("range (mm)",M,167,8,color=MUTED)
text("latest reported course time tS (s)",181,425,8,color=MUTED)
for r in TRACE["sensors"]:
    if xmin <= r["tS"] <= xmax and r["status"] == 0:
        circle(px(r["tS"]),py(r["mm"]),1.6,TEAL,TEAL,.3)
text("VALID readings",420,179,8,color=TEAL)
# Non-valid readings are categorical, never plotted at zero range.
text("sensor",M,457,8,"Mono",MUTED)
for r in TRACE["sensors"]:
    if xmin <= r["tS"] <= xmax:
        line(px(r["tS"]),454,px(r["tS"]),465,TEAL if r["status"]==0 else BORDER,1)
text("alert",M,490,8,"Mono",MUTED)
changes=[]
for a in TRACE["alerts"]:
    if not changes or a["band"]!=changes[-1]["band"]:
        changes.append(a)
for k,a in enumerate(changes):
    end=changes[k+1]["tS"] if k+1<len(changes) else xmax
    xa,xb=px(max(xmin,a["tS"])),px(min(xmax,end))
    box(xa,486,xb-xa,22,fill=BAND_COLORS[a["band"]],radius=0)
    text(a["band"],xa+4,491,8,"Bold",white)
caption(6, f"{len(TRACE['sensors'])} sensor rows and {len(TRACE['alerts'])} non-null alert rows were recorded; the plot shows the 7.8–12.3 s approach/stop window. NO_TARGET is the grey categorical strip, never a 0 mm obstacle. Band changes are real engine events, not threshold lines drawn as fake alerts. [3]", 529)
note(f"<b>{STATS['receiptIntervalMs']} ms was observed between the recorder receiving the first VALID sample and receiving the first FAR alert.</b> This is a local application receipt interval, including policy persistence and scheduling. It is not sensor-to-ear latency, an acoustic measurement, or a certified response time.", 595)
para("<b>Check the interpretation:</b> the walker stops near 0.45 m and NEAR continues. A stopped animation is not proof that an independent user avoided a collision; the trajectory was scripted. The saved trace preserves its tuning and source provenance.", M, 691, CW, size=8.8)

# 7 — The timing and derivative implications.
page(TITLES[6], "A faster approach reduces the time available even when the current distance is identical. The existing experimental rule estimates closing speed from VALID samples. [4]")
heading("Distance is a position; time-to-contact includes motion", M, 164)
para("With approximately constant positive closing speed v, travel distance satisfies d = v × t. Rearranging gives <b>TTC = d / v</b>. Metres divided by metres/second gives seconds; at zero closing speed there is no finite forward-contact estimate. The engine uses a least-squares slope over a 300 ms window, needs at least three VALID samples, and clears the estimate on a missing target.", M, 193, CW)
node(48, 305, 246, 105, "SAME 800 mm · FAST APPROACH", "v = 800 mm/s → TTC = 1.0 s.<br/>v ≥ 300 mm/s and TTC &lt; 1.5 s.<br/>Experimental rule raises MID → NEAR.", RED)
node(318, 305, 246, 105, "SAME 800 mm · SLOW APPROACH", "v = 200 mm/s → TTC = 4.0 s.<br/>Speed and TTC criteria are not met.<br/>No approach escalation; MID remains.", BLUE)
caption(7, "The examples change speed, not distance. They are calculations, not recorded test cases. Approach escalation can advance urgency after a return exists; it cannot see an unseen obstacle. At 1.0 m/s the 1.2 m white-target envelope offers at most 1.2 s before contact, before every delay below. [4]", 425)
heading("Where that geometric time is spent", M, 487)
# A timeline with unknown-length tails, deliberately not a fabricated total.
xs=[48,163,301,432]
ws=[106,129,122,132]
for xx,ww,tt,bb,col in zip(xs,ws,["SAMPLING","PERSISTENCE","TRANSPORT","AT THE EAR"],
    ["15 Hz target\n0–66.7 ms phase", "150 ms enter rule\n+ sample quantization", "Scheduling / UDP\nnot isolated here", "Bluetooth buffers\n+ audio output"], [TEAL,TEAL,BLUE,AMBER]):
    box(xx,523,ww,78,white,col)
    text(tt,xx+8,533,7.6,"Bold",col)
    para(bb.replace("\n","<br/>"),xx+8,553,ww-16,size=8,leading=11)
for j in range(3): arrow(xs[j]+ws[j],562,xs[j+1]-3,562,INK,.8)
caption(8, "A protocol ACK is not the final block. Bluetooth SIG literature gives typical A2DP delays around 100–200 ms, but that is not a measurement of this headset or of Ray-Ban Meta. A total needs a shared-clock event marker and a microphone/contact measurement at the actual output. [4, 7]", 618)
para("<b>Model implication:</b> 15 Hz and 150 ms do not imply exactly 216.7 ms to warning. Sample phase, entry evidence, onset gating, processing and output routing all matter; TTC also changes policy behaviour. Report distributions and failures, not one optimistic constant.", M, 686, CW, size=8.9)

# 8 — Contrasting failures and the availability rule.
page(TITLES[7], "“No warning” has several causes. The interface must keep them distinguishable because each implies a different next action. [1, 4]")
for xx,title,status,body,col in [
    (48,"OPEN PLAZA","NO_TARGET","No usable surface in range.\nDo not infer a clear path.",BLUE),
    (224,"GLASS FIXTURE","UNKNOWN","Model deliberately invalidates\nthe measurement. Not a glass detector.",AMBER),
    (400,"STAIRS DOWN","NO_TARGET","Drop-off is below the cone.\nThe cane still matters.",RED),
]:
    box(xx,174,164,173,white,BORDER)
    text(title,xx+10,186,9,"Bold",col)
    circle(xx+27,243,8,TEAL,TEAL)
    polygon([(xx+37,243),(xx+132,226),(xx+132,260)],HexColor("#E5EFEC"),TEAL)
    if title=="GLASS FIXTURE":
        line(xx+116,216,xx+116,279,BLUE,2,dashed=True)
    if title=="STAIRS DOWN":
        line(xx+12,273,xx+83,273,MUTED,2)
        line(xx+83,273,xx+83,290,MUTED,2)
        line(xx+83,290,xx+148,290,MUTED,2)
    text(status,xx+10,297,9,"Bold",col)
    para(body.replace("\n","<br/>"),xx+10,315,144,size=7.8,leading=10.3)
caption(9, "Identical quietness can mean a scene beyond range, a known sensing failure, or a hazard outside the zone. Only the explicit glass fixture supplies UNKNOWN here; physical glass can also return misleading ranges or no target. Schematics not to scale. [1, 2]", 364)
heading("Availability is stricter than the distance policy", M, 430)
node(48,468,156,72,"READY","Required liveness and usable range state hold.",TEAL)
node(228,468,156,72,"DEGRADED","ToF stale or UNKNOWN; no invented replacement range.",AMBER)
node(408,468,156,72,"UNAVAILABLE","Frame/heartbeat stale, ACK failure, route loss…",RED)
arrow(205,503,223,503,AMBER)
arrow(385,503,403,503,RED)
text("Illustrative failure progression; recovery is possible when guards hold.",M,554,8.1,color=MUTED)
para("In the current monitor, frame age, board heartbeat age and range age each have a 500 ms default limit. Range trouble degrades state; a stale camera frame makes the system UNAVAILABLE rather than silently entering a ToF-only mode. Camera content does not set the hazard band, but camera freshness still gates availability.", M, 584, CW, size=10)
note("<b>Failure proof shown in the local run:</b> unplugging the cue board produced range_stale, then cue_beat_missing; reconnecting recovered READY. A powered cue board's local watchdog and the phone's failure audio are independent paths. An unplugged board cannot provide its own alert.", 666, color=RED)

# 9 — Proposed wearable changes, drawn as distinct optical/audio paths.
page(TITLES[8], "The useful glasses proposal is a different camera source and possible output route—not “replace the entire headband”. This pathway is not integrated in the current code. [5, 6]")
# Generic front-view wearable construction. Deliberately not Ray-Ban industrial-design geometry.
box(158,193,292,16,fill=HexColor("#DDE6E4"),stroke=TEAL,radius=7)
box(292,187,24,27,fill=TEAL)
box(176,239,108,65,fill=HexColor("#EEF1F1"),stroke=INK,radius=19)
box(327,239,108,65,fill=HexColor("#EEF1F1"),stroke=INK,radius=19)
line(284,254,327,254,INK,4)
line(154,244,176,247,INK,5)
line(435,247,457,244,INK,5)
circle(166,243,5,BLUE,BLUE)
circle(447,257,4,AMBER,AMBER)
arrow(304,186,304,165,TEAL)
text("ToF headband stays",236,145,10,"Bold",TEAL)
arrow(166,244,101,231,BLUE)
para("<b>Camera</b><br/>DAT frames<br/>to phone",48,205,105,size=9.1)
arrow(447,257,500,291,AMBER)
para("<b>Speakers</b><br/>Open-ear;<br/>not bone conduction",439,306,125,size=9.1)
caption(10, "The near-field ranging module and haptic board remain separate from the glasses. Camera frames and media audio use different APIs/routes. Generic functional sketch, not Ray-Ban CAD or a claim about exact component positions. [5, 6]", 363)
node(48,426,237,75,"GLASSES → PHONE","DAT camera registration, permission,<br/>session lifecycle and timestamp handling.",BLUE,True)
node(326,426,238,75,"PHONE → GLASSES","Ordinary Bluetooth A2DP output.<br/>Verify the actual route and audible onset.",AMBER,True)
para("<b>Do not confuse the profiles.</b> A2DP is high-quality output. HFP enables microphone input but changes output to mono voice quality. DAT also documents in-stream microphone audio in development/beta channels; that is not production support for an unrestricted audio feature. [6]", M, 529, CW)
para("<b>Product implication.</b> Open-ear glasses speakers do not satisfy a bone-conduction requirement by equivalence. Keep the existing headset path unless the alternative route is explicitly accepted and evaluated for intelligibility, environmental audibility and failure announcements.", M, 614, CW)
para("<b>Geometry implication.</b> A different mount changes camera-to-ToF alignment. Never fuse a camera object and the one range reading by identity without a calibrated, separately validated association method.", M, 690, CW, size=9)

# 10 — Source crop mechanism and real integration gates.
page(TITLES[9], "The built Meta profile studies an image-interface shape. It never changes the live bridge, ToF, engine, camera pacing or audio route. [1]")
# 4:3 source and 9:16 crop retained at common source scale.
sx,sy,scale=58,185,.8
ww,hh=320*scale,240*scale
box(sx,sy,ww,hh,white,BORDER,0)
for xx,label in [(sx+20,"lost"),(sx+ww-46,"lost")]:
    text(label,xx,sy+94,9,color=MUTED)
cx=sx+STATS["cropX"]*scale
box(cx,sy,STATS["cropWidthPx"]*scale,hh,HexColor("#DCEDEA"),TEAL,0)
text("retained",cx+16,sy+93,9,"Bold",TEAL)
text("320 × 240 source",sx,sy+hh+12,9,"Mono")
arrow(332,280,408,280,BLUE)
text("resample",341,255,8.3,color=BLUE)
box(432,185,108,192,HexColor("#DCEDEA"),TEAL,0)
for j in range(1,9): line(432,185+j*24,540,185+j*24,HexColor("#A7C9C5"),.4)
for j in range(1,5): line(432+j*21.6,185,432+j*21.6,377,HexColor("#A7C9C5"),.4)
text("720 × 1280",426,390,9,"Mono")
caption(11, "The crop is 135 × 240 source pixels at x = 92.5, y = 0; resizing expands it by 5.33× on each axis. It retains 42.2% of the source width and adds no optical detail. Diagram shows image geometry, not a captured glasses view. [1]", 427)
para("The crop follows aspect ratio: width = 240 × 9/16 = 135 pixels. Centre offset = (320 − 135)/2 = 92.5 pixels. Selecting 720 × 1280 therefore proves an output dimension—not a field of view, effective resolution, calibrated mount or SDK connection.", M, 482, CW, size=9.8)
heading("What a real DAT path still needs", M, 545)
para("<b>Permissions and lifecycle:</b> app registration, Bluetooth/device access, camera permission, a started session, stream state handling and revoked-permission/disconnect behaviour. Mock Device Kit can test application lifecycle logic, not real RF reliability or ear latency. [5]", M, 574, CW, size=9.4)
para("<b>Power and heat:</b> sustained capture, encoding and radio traffic can trade battery life and phone temperature for image quality. Measure actual current and runtime; this simulation has no validated energy or thermal model.", M, 623, CW, size=8.8, leading=12)
para("<b>Bandwidth and freshness:</b> documented rates are 2, 7, 15, 24 or 30 fps; HIGH is 720 × 1280. DAT adapts compression/quality to Bluetooth bandwidth. Decoded RGB at 30 fps is about <b>82.9 MB/s</b> inside the app—not a Bluetooth bitrate. At 2 fps the nominal 500 ms inter-frame interval already consumes the current freshness limit; jitter makes it unsuitable without an explicitly reviewed liveness design. [4, 5]", M, 666, CW, size=9.1, leading=12.7)

# 11 — Cloud and privacy consequences, no synthetic evidence disguised as a deployment.
page(TITLES[10], "Describe is optional context, not a sensor or an authority that the path is safe. A slow or failed description must not become an invented warning or all-clear. [1, 9]")
node(48,171,156,83,"FRAME","Android face redaction<br/>before external upload.<br/>Synthetic scene here.",TEAL)
node(228,171,156,83,"DESCRIBE API","Auth + request limits.<br/>Configured backend only.<br/>Reject unsupported inputs.",BLUE)
node(408,171,156,83,"FILTERED TEXT","Safety filtering; caller<br/>adds “May be wrong.”<br/>Optional spoken context.",AMBER)
arrow(205,211,222,211,BLUE)
arrow(385,211,402,211,BLUE)
caption(12, "A cloud description crosses a privacy boundary before the model runs. A server-side blur pass would not erase a pre-upload exposure; phone-side redaction is a distinct control. No real cloud model was invoked in the documented local smoke. [9]", 276)
heading("Why the AWS/Annapurna connection is defensible", M, 339)
para("Warning deadlines constrain placement: a network round trip has no reliable bound tight enough to make it a dependency for urgent cues. Optional scene descriptions can wait and can therefore be evaluated on a real configured inference host. The simulator supplies controlled scenes and captures; the batch CLI compares explicit human-labelled objects with returned text, timings, failures and declared backend metadata.", M, 370, CW)
para("A Neuron tutorial genuinely documents Llama 3.2 Vision on <font name='Mono'>inf2.48xlarge</font> or <font name='Mono'>trn1.32xlarge</font>. That is not the project's small <font name='Mono'>inf2.xlarge</font> SmolVLM path. The tutorial is versioned 2.27.1; verify current SDK support, quota, model access and compile before promising a deployment. [10]", M, 470, CW, size=9.6)
heading("What a trustworthy receipt can—and cannot—say", M, 560)
para("Record observed JPEG bytes, redaction outcome and verified provider/model fields; label missing fields unknown. “The API contract stores no images” is a policy statement, not a measured zero-second retention guarantee across every host, log and provider. Synthetic faceless avatars also do not validate face detection on real bystanders.", M, 591, CW, size=9.6)
note("The implemented Describe evaluator is a limited English lexicon audit, not a semantic safety proof. Failures remain visible and zero denominators stay null. Cone-only truth cannot label all camera-visible objects; full-image labels require independent annotation.", 662, color=AMBER)

# 12 — Experiment chain and usable stage procedure.
page(TITLES[11], "A convincing demo makes an observable claim and also shows the experiment that could falsify it. Promotion to user-facing mobility claims requires evidence beyond this virtual world.")
# Ladder: each step adds a different validation domain.
steps=[
    ("1  SOFTWARE LOOP", "Repeat scene + policy + failure traces; export source clocks and tuning."),
    ("2  SENSOR BENCH", "White/grey/dark/glass targets; vary angle, distance and ambient light."),
    ("3  OUTPUT TIMING", "Measure acoustic headset onset and motor onset, not only ACK receipt."),
    ("4  EXTERNAL REPLAY", "Aria calibration + depth projection; separate ideal-depth from ToF assumptions."),
    ("5  SUPERVISED STUDY", "Orientation-and-mobility review, safeguards, and predeclared outcomes."),
]
for k,(title,body) in enumerate(steps):
    yy=167+k*63
    circle(59,yy+18,9,TEAL,TEAL)
    text(str(k+1),56,yy+12,8,"Bold",white)
    if k<4: line(59,yy+28,59,yy+72,TEAL,1)
    text(title,81,yy,9.3,"Bold",TEAL)
    para(body,81,yy+19,477,size=9.2,leading=12.5)
caption(13, "Each rung tests something the previous rung cannot: software logic, physical returns, physical actuation, external data generalization, then supervised human benefit. ADT sequence/derived public displays need licence review and permission; no Aria assets were included in this demo. [1, 3, 8]", 495)
heading("A 90-second stage sequence", M, 559)
para("<b>0–25 s:</b> show the sign, range, real engine and separate audio/vibration score. <b>25–45 s:</b> switch to glass or stairs and explain the blind spot. <b>45–60 s:</b> unplug/recover a board so failure is visibly announced. <b>60–90 s:</b> show an exported episode and the glasses crop sidecar; say which integration is still proposed.", M, 589, CW, size=9.7)
note("<b>Next experiment:</b> freeze a paired baseline-versus-approach protocol before running it. Hold paths, speeds and sensor streams constant; define washout periods, miss/false-alarm labels and promotion criteria. The camera-frame crop is not independent ground truth. Confirm demo and data rules with HackTX organizers before the submission.", 660)

# 13 — Citation home and reproduction.
page(TITLES[12], "Sources support specific claims, not blanket endorsements. Repository paths refer to this delivered workspace; upstream engine code is pinned to 65cfa0c.", section="REFERENCE / REPRODUCTION")
sources=[
    ("1", "Implemented world + profile boundaries", "world/README.md; world/README-profiles.md; docs/research/WORKLOG.md", "https://github.com/xeloproject/Determine"),
    ("2", "Sensor model and material assumptions", "world/src/sensor/tof.ts; world/src/scene/world.ts", "https://github.com/xeloproject/Determine"),
    ("3", "Recorded evidence included with this PDF source", f"assets/sign-trace.json; {TRACE['source']['rows']} original JSONL rows, {len(TRACE['sensors'])} sensor rows; screenshot assets/simulation.png", None),
    ("4", "Upstream policy, tuning and availability", "HazardPolicy.kt; Tuning.kt; SystemMonitor.kt (phone/core)", "https://github.com/xeloproject/Determine/tree/65cfa0c/phone/core/src/main/kotlin/org/determine/core"),
    ("5", "Meta DAT Android integration guide", "Registration, permissions, stream lifecycle, dimensions/rates and adaptive compression. Retrieved 24 Oct 2026.", "https://wearables.developer.meta.com/docs/build-integration-android/"),
    ("6", "Meta: microphones and speakers", "A2DP/HFP routing; beta in-stream capture; route verification. Retrieved 24 Oct 2026.", "https://wearables.developer.meta.com/docs/develop/dat/microphones-and-speakers/"),
    ("7", "Bluetooth SIG: Introducing Bluetooth LE Audio", "Nick Hunn (2022), pp. 128–129: typical A2DP delay; not a measurement of Determine hardware.", "https://www.bluetooth.com/wp-content/uploads/2022/01/Introducing-Bluetooth-LE-Audio-book.pdf"),
    ("8", "Sensor anchors and Aria use restrictions", "ST VL53L4CD datasheet DS13812; ADT sequence licence §1–3. L4CD → L4ED extrapolation is MODEL, not validation.", "https://www.projectaria.com/datasets/adt/license/"),
    ("9", "Describe contract and evaluation tool", "cloud/API.md; tools/sim/EVALUATION.md; docs/research/annapurna-labs.md", "https://github.com/xeloproject/Determine"),
    ("10", "AWS Neuron 2.27.1: Llama 3.2 multimodal", "Versioned tutorial; documented trn1.32xlarge / inf2.48xlarge setup.", "https://awsdocs-neuron.readthedocs-hosted.com/en/v2.27.1/libraries/nxd-inference/tutorials/llama3.2-multimodal-tutorial.html"),
]
y=160
for n,title,desc,url in sources:
    text(n,M,y,8.4,"Mono",TEAL)
    title_markup=f'<link href="{escape(url)}" color="#315EAD">{escape(title)}</link>' if url else escape(title)
    extra = ' <link href="https://www.st.com/resource/en/datasheet/vl53l4cd.pdf" color="#315EAD">ST datasheet</link>.' if n == "8" else ""
    y=para(f"<b>{title_markup}</b> — {escape(desc)}{extra}",M+24,y,CW-24,size=8.1,leading=11.1)+9
heading("Working vocabulary",M,y+5,size=11)
y=para("<b>ToF</b>: reflected-light ranging. <b>TTC</b>: time-to-contact under a closing-speed model. <b>DAT</b>: Meta Wearables Device Access Toolkit. <b>ACK</b>: protocol acknowledgment, not physical output. <b>VLM</b>: vision-language model. <b>SIL</b>: software-in-the-loop, not hardware validation.",M,y+28,CW,size=8.4,leading=11.6)+15
heading("Reproduce / edit",M,y,size=11)
y=para("Build the demo using <font name='Mono'>world/README.md</font>. Rebuild this PDF with <font name='Mono'>python3 docs/visual-guide/build.py</font> after installing its requirements. Source, snapshot data, screenshot and calculation metadata live beside this PDF. Body text and diagrams remain editable; PDF outlines and cover contents are clickable.",M,y+24,CW,size=8.4,leading=11.6)

C.save()
assert PAGE == 13
(HERE / "build-metadata.json").write_text(json.dumps({
    "pages":PAGE, "upstream":"65cfa0c", "wireVersion":2, "calculations":STATS,
    "traceSource":TRACE["source"], "pdfSha256":hashlib.sha256(OUT.read_bytes()).hexdigest(),
    "textRegionAudit":{"regions":len(AUDIT),"maximumBodyBottom":max(b for _,_,b,_ in AUDIT if b<752)},
}, indent=2)+"\n")
print(f"Built {OUT} ({PAGE} pages, {OUT.stat().st_size:,} bytes)")
print(json.dumps(STATS,indent=2))
