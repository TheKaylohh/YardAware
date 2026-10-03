#!/usr/bin/env python3
"""Draws the clip-art map of the Philly shipyard and writes the zone outlines.

Outputs (run from the repo root):
  src/main/resources/static/img/yard.svg     background drawn behind the zones and tiles
  src/main/resources/seed/yard-zones.json    zone outlines, loaded by DemoDataSeeder

Everything is drawn in the pixel space of the aerial screenshot the layout was traced from, then one transform
puts it into the 1800 x 1500 map space the app uses:  X = x * K + 200,  Y = y * K.
The zones are rectangles in that same aerial space, so the zones and the picture can never drift apart.
"""
import json
import pathlib

W, H = 1800, 1500
K, OFF_X = 1.0818, 200.0   # aerial pixels -> map units: X = x * K + OFF_X, Y = y * K

ROOT = pathlib.Path(__file__).resolve().parent.parent
SVG_OUT = ROOT / "src/main/resources/static/img/yard.svg"
ZONES_OUT = ROOT / "src/main/resources/seed/yard-zones.json"

# code, name, kind, facility (workbook code, ties the zone to the phases that run there), aerial x1,y1,x2,y2
ZONES = [
    ("SP",   "Steel Stockyard",   "yard", None,                 100, 95, 245, 285),
    ("PL",   "Panel Line",        "shop", "A0 / B0 / C0",       245, 40, 975, 375),
    ("PS",   "Pipe Shop",         "shop", None,                 1040, 495, 1225, 700),
    ("OS",   "Outfitting Shop",   "shop", None,                 1215, 395, 1470, 815),
    ("AH",   "Assembly Hall",     "shop", "D0 / E0 / F0",       800, 440, 945, 680),
    ("BP",   "Blast & Paint",     "shop", "Paint shop / dock",  820, 690, 1010, 945),
    ("BD1",  "Building Dock 1",   "dock", "L0 / M0 + dockside", 238, 578, 378, 1328),
    ("BD2",  "Building Dock 2",   "dock", "L0 / M0 + dockside", 392, 578, 532, 1328),
    ("LY1",  "Laydown 1",    "yard", None,                 150, 575, 236, 1130),
    ("GBY",  "Grand Block Yard",  "yard", "L0 / M0",            545, 720, 770, 1150),
    ("LY2",  "Laydown 2",    "yard", None,                 345, 440, 710, 570),
    ("POL",  "Outfit Laydown",    "yard", None,                 790, 960, 1050, 1295),
    ("PIER", "Pier",   "pier", "H0",                 1385, 1050, 1470, 1375),
]


def to_map(x, y):
    return round(x * K + OFF_X), round(y * K)


def zone_rows():
    rows = []
    for code, name, kind, facility, x1, y1, x2, y2 in ZONES:
        (a, b), (c, d) = to_map(x1, y1), to_map(x2, y2)
        rows.append({
            "code": code, "name": name, "kind": kind, "facility": facility,
            "points": f"{a},{b} {c},{b} {c},{d} {a},{d}",
        })
    return rows


# ------------------------------------------------------------------ drawing
land, road, road_edge = "#E1E6DE", "#CBD2C9", "#B7BFB4"
water, water_dk = "#B3CAD4", "#8FB0BE"
green, green_dk = "#C6D9B4", "#A9C493"
bldg, bldg_stroke = "#D5DBD5", "#6F8087"
roof, roof_dk = "#C3CED8", "#9FB0BE"
yard_fill, grid = "#E6EAE2", "#C5CCC2"
concrete = "#D9D6CB"
ink = "#14232E"
signal = "#F5B800"

o = []
a = o.append


def rect(x1, y1, x2, y2, fill, stroke=None, sw=1.2, extra=""):
    st = f' stroke="{stroke}" stroke-width="{sw}"' if stroke else ""
    a(f'<rect x="{x1}" y="{y1}" width="{x2 - x1}" height="{y2 - y1}" fill="{fill}"{st} {extra}/>')


def poly(points, fill, stroke=None, sw=1.2, extra=""):
    pts = " ".join(f"{x},{y}" for x, y in points)
    st = f' stroke="{stroke}" stroke-width="{sw}" stroke-linejoin="round"' if stroke else ""
    a(f'<polygon points="{pts}" fill="{fill}"{st} {extra}/>')


def path(d, stroke, sw, fill="none", extra=""):
    a(f'<path d="{d}" fill="{fill}" stroke="{stroke}" stroke-width="{sw}" stroke-linecap="round" '
      f'stroke-linejoin="round" {extra}/>')


def road_line(d, w=9):
    path(d, road_edge, w + 2)
    path(d, road, w)


def yard_area(x1, y1, x2, y2, step=18):
    rect(x1, y1, x2, y2, yard_fill, bldg_stroke, 1)
    x = x1 + step
    while x < x2:
        a(f'<line x1="{x}" y1="{y1}" x2="{x}" y2="{y2}" stroke="{grid}" stroke-width=".7" stroke-dasharray="4 5"/>')
        x += step
    y = y1 + step
    while y < y2:
        a(f'<line x1="{x1}" y1="{y}" x2="{x2}" y2="{y}" stroke="{grid}" stroke-width=".7" stroke-dasharray="4 5"/>')
        y += step


def hall(points_or_box, ridges=None, vertical=True):
    if len(points_or_box) == 4 and not isinstance(points_or_box[0], tuple):
        x1, y1, x2, y2 = points_or_box
        poly([(x1, y1), (x2, y1), (x2, y2), (x1, y2)], roof, bldg_stroke, 1.6)
        box = (x1, y1, x2, y2)
    else:
        poly(points_or_box, roof, bldg_stroke, 1.6)
        xs = [p[0] for p in points_or_box]
        ys = [p[1] for p in points_or_box]
        box = (min(xs), min(ys), max(xs), max(ys))
    if ridges:
        x1, y1, x2, y2 = box
        if vertical:
            x = x1 + ridges
            while x < x2:
                a(f'<line x1="{x}" y1="{y1 + 3}" x2="{x}" y2="{y2 - 3}" stroke="{roof_dk}" stroke-width="1.4"/>')
                x += ridges
        else:
            y = y1 + ridges
            while y < y2:
                a(f'<line x1="{x1 + 3}" y1="{y}" x2="{x2 - 3}" y2="{y}" stroke="{roof_dk}" stroke-width="1.4"/>')
                y += ridges


def ship(cx, top, bottom, half, hull="#8E9AA0"):
    """Top view of a hull in a dock: pointed bow at the top, flat stern at the bottom."""
    l, r = cx - half, cx + half
    bow = top + half * 2.6
    a(f'<path d="M{cx},{top} C{r - 2},{top + half * 0.9} {r},{bow - 6} {r},{bow} L{r},{bottom} L{l},{bottom} '
      f'L{l},{bow} C{l},{bow - 6} {l + 2},{top + half * 0.9} {cx},{top} Z" fill="{hull}" stroke="{ink}" '
      f'stroke-width="1.2" stroke-linejoin="round"/>')
    a(f'<path d="M{cx},{top + 8} L{cx},{bottom - 4}" stroke="#C9D1D4" stroke-width="1.2" stroke-dasharray="5 4"/>')
    n = int((bottom - bow) // 26)
    for i in range(n):
        y = bow + 8 + i * 26
        rect(cx - half + 4, y, cx + half - 4, y + 11, "#AEB8BC", "#6F8087", .8)


def build():
    a(f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H}" width="{W}" height="{H}" '
      f'font-family="Bahnschrift, \'DIN Alternate\', \'Segoe UI\', Arial, sans-serif">')
    a('<title>Schematic of the Philly shipyard</title>')
    rect(0, 0, W, H, water)
    a(f'<g transform="translate({OFF_X},0) scale({K})">')

    # --- water texture
    for yy in range(60, 1380, 70):
        for xx in range(-150, 1500, 170):
            off = (yy // 70 % 2) * 70
            path(f"M{xx + off},{yy} q12,-6 24,0 t24,0 t24,0", water_dk, 1.3, extra='opacity=".4"')

    # --- the land
    land_pts = [(110, -5), (1500, -5), (1500, 1062), (1420, 1062), (1260, 1030), (1080, 1060), (1078, 1300),
                (660, 1326), (654, 1215), (565, 1212), (560, 1342), (408, 1346), (216, 1372), (190, 1250),
                (130, 1100), (80, 830), (55, 560), (60, 300), (100, 130)]
    poly(land_pts, land, bldg_stroke, 3)
    # green tip at the south-west corner
    a(f'<ellipse cx="215" cy="1215" rx="52" ry="85" fill="{green}" stroke="{green_dk}" stroke-width="2"/>')
    for tx, ty in [(190, 1160), (225, 1190), (200, 1230), (235, 1260), (185, 1200), (215, 1140), (240, 1225)]:
        a(f'<circle cx="{tx}" cy="{ty}" r="13" fill="{green_dk}"/>')
    # lawn strip next to S 21st St
    a(f'<path d="M1040,0 L1075,0 L1090,330 L1060,330 Z" fill="{green}" stroke="{green_dk}" stroke-width="1.5"/>')

    # --- roads
    road_line("M128,500 C190,486 290,452 440,432 L700,408 L1000,402 L1100,396 L1500,378", 22)   # Kitty Hawk Ave
    road_line("M130,510 C100,400 130,250 235,170 C270,125 340,112 520,104", 14)                  # Basin Bridge Rd
    road_line("M1008,0 C1030,200 1022,300 1002,402 L1016,700 L1034,1030", 18)                   # S 21st St
    road_line("M1086,0 L1092,400 L1140,600 L1150,1040", 16)
    road_line("M1060,45 L1500,38", 14)                                                           # Constitution Ave
    road_line("M330,440 L338,578", 12)
    road_line("M772,440 L776,960", 12)

    # --- yards and open storage (behind the zone tiles)
    yard_area(100, 95, 245, 285, 26)                                  # steel stockyard (plate stacks)
    for sx, sy in [(110, 105), (160, 105), (110, 160), (160, 160), (110, 215), (160, 215), (200, 130), (200, 190)]:
        rect(sx, sy, sx + 38, sy + 38, "#9AA8AD", bldg_stroke, 1)
    yard_area(150, 575, 236, 1130, 28)                                # laydown 1
    yard_area(345, 440, 710, 570, 28)                                 # laydown 2
    yard_area(545, 720, 770, 1150, 30)                                # grand block yard
    for bx, by, bw, bh, col in [(560, 760, 60, 40, "#D9B09A"), (640, 760, 60, 40, "#D9B09A"),
                                (560, 830, 60, 40, "#C8D1D6"), (640, 830, 60, 40, "#D9B09A"),
                                (570, 920, 70, 46, "#D9B09A"), (665, 925, 70, 46, "#C8D1D6")]:
        rect(bx, by, bx + bw, by + bh, col, bldg_stroke, 1)
    yard_area(790, 960, 1050, 1295, 30)                               # outfit laydown
    for by1, by2 in [(1035, 1082), (1088, 1136), (1142, 1190)]:       # white warehouses
        rect(812, by1, 995, by2, "#F1F3EF", bldg_stroke, 1.4)
    for cx, cy in [(830, 1230), (900, 1235), (975, 1240), (870, 1268)]:
        rect(cx, cy, cx + 45, cy + 22, "#8EA6C4", bldg_stroke, 1)

    # --- shops
    hall([(575, 40), (975, 40), (975, 320), (670, 320), (670, 375), (290, 375), (290, 230), (575, 230)], ridges=48)
    hall((800, 440, 945, 680), 38)                                                      # assembly hall
    for ry1, ry2 in [(688, 740), (745, 800)]:                                           # blast and paint
        rect(820, ry1, 1010, ry2, "#F1F3EF", bldg_stroke, 1.6)
    hall((840, 805, 940, 945), 30, False)
    hall((1215, 395, 1470, 815), 36)                                                    # outfitting shop
    rect(1040, 495, 1130, 620, "#C9C2B6", bldg_stroke, 1.4)                             # pipe shop
    rect(1135, 495, 1225, 620, "#D5D9D3", bldg_stroke, 1.4)
    rect(1050, 628, 1140, 800, "#BFC7CC", bldg_stroke, 1.2)
    rect(1160, 628, 1225, 810, "#BFC7CC", bldg_stroke, 1.2)
    # neighbours that are not zones
    hall((1105, 100, 1260, 340), 28, False)
    for bx1, by1, bx2, by2 in [(1290, 120, 1470, 200), (1290, 215, 1400, 290), (1410, 215, 1470, 330),
                               (1300, 300, 1400, 360)]:
        rect(bx1, by1, bx2, by2, "#E4E8E2", bldg_stroke, 1.2)
    rect(1075, 840, 1230, 1010, "#D5D9D3", bldg_stroke, 1)                              # car park
    for cy in range(850, 1000, 20):
        a(f'<line x1="1082" y1="{cy}" x2="1224" y2="{cy}" stroke="#B8BFB6" stroke-width="1" stroke-dasharray="4 6"/>')
    rect(100, 590, 160, 740, "#F1F3EF", bldg_stroke, 1.4)                               # long white shed on the west quay
    for bx1, by1, bx2, by2 in [(305, 880, 340, 930), (330, 560, 380, 640)]:
        pass

    # --- two equal dry docks, side by side
    for x1 in (240, 395):
        rect(x1 - 6, 572, x1 + 142, 1332, concrete, bldg_stroke, 2)
    rect(240, 578, 376, 1326, water_dk, ink, 2.4)            # dock 1: flooded
    rect(246, 584, 370, 1320, water, None)
    rect(395, 578, 531, 1326, "#C9B896", ink, 2.4)           # dock 2: dry, hull being built
    rect(401, 584, 525, 1320, "#D8C9A8", None)
    ship(308, 650, 985, 36)                                  # finished hull afloat in dock 1
    for sy in (1030, 1130, 1230):                            # keel blocks on the empty stretch of dock 1
        rect(285, sy, 331, sy + 50, "#B7C3C8", bldg_stroke, 1)
    # dock 2: hull under construction (flat, blocky) with erected blocks and a pile of blocks below
    rect(404, 735, 514, 965, "#9AA6AB", ink, 1.6)
    rect(414, 745, 504, 955, "#B4BEC2", bldg_stroke, 1)
    for by in range(760, 950, 38):
        a(f'<line x1="414" y1="{by}" x2="504" y2="{by}" stroke="{bldg_stroke}" stroke-width="1.2"/>')
    for bx, by in [(415, 1010), (470, 1010), (415, 1090), (470, 1090), (440, 1170), (415, 1240), (470, 1240)]:
        rect(bx, by, bx + 46, by + 62, "#C79B86", bldg_stroke, 1.2)
    # cranes: dock 2 has the top gantry and the side cranes, dock 1 one medium crane on its side
    rect(380, 684, 724, 698, signal, ink, 1.4)                                          # top gantry beam
    for gx in (384, 536, 716):
        rect(gx - 7, 676, gx + 7, 706, signal, ink, 1.4)
    a(f'<line x1="540" y1="706" x2="540" y2="1180" stroke="#8C8F3C" stroke-width="3" stroke-dasharray="9 5"/>')
    for cy in (780, 960, 1120):                                                         # side cranes on dock 2's rail
        rect(528, cy, 556, cy + 34, signal, ink, 1.4)
        rect(542, cy + 12, 600, cy + 20, signal, ink, 1)
    a(f'<line x1="236" y1="760" x2="236" y2="940" stroke="#8C8F3C" stroke-width="3" stroke-dasharray="9 5"/>')
    rect(224, 820, 252, 856, signal, ink, 1.4)                                          # medium crane, dock 1
    rect(244, 834, 330, 842, signal, ink, 1)

    # --- pier
    rect(1396, 1062, 1458, 1380, concrete, bldg_stroke, 2)
    for by in range(1080, 1370, 36):
        a(f'<circle cx="1403" cy="{by}" r="3.4" fill="{ink}"/><circle cx="1451" cy="{by}" r="3.4" fill="{ink}"/>')
    a(f'<line x1="1427" y1="1068" x2="1427" y2="1376" stroke="#B3AEA0" stroke-width="1.4" stroke-dasharray="10 7"/>')

    # --- road names
    a(f'<text transform="translate(560,426) rotate(-4)" font-size="22" font-weight="700" fill="#5E6A62" '
      f'text-anchor="middle" letter-spacing="1">Kitty Hawk Ave</text>')
    a(f'<text transform="translate(1004,190) rotate(90)" font-size="19" font-weight="700" fill="#5E6A62" '
      f'text-anchor="middle">S 21st St</text>')
    a(f'<text transform="translate(1136,720) rotate(90)" font-size="19" font-weight="700" fill="#5E6A62" '
      f'text-anchor="middle">S 21st St</text>')
    a(f'<text transform="translate(305,125) rotate(-24)" font-size="19" font-weight="700" fill="#5E6A62" '
      f'text-anchor="middle">Basin Bridge Rd</text>')
    a('</g>')

    # --- map furniture, in map space
    a(f'<text transform="translate(110,780) rotate(-90)" text-anchor="middle" font-size="30" font-style="italic" '
      f'letter-spacing="7" fill="#5C8194">Schuylkill River</text>')
    a(f'<text x="1530" y="1290" text-anchor="middle" font-size="34" font-style="italic" letter-spacing="8" '
      f'fill="#5C8194">Delaware River</text>')
    a(f'<g transform="translate(18,24)"><rect width="270" height="84" rx="6" fill="#F8F9F6" stroke="{ink}" '
      f'stroke-width="2"/><rect width="10" height="84" rx="3" fill="{signal}"/>'
      f'<text x="24" y="38" font-size="25" font-weight="700" fill="{ink}">Philly Shipyard</text>'
      f'<text x="24" y="64" font-size="14" fill="#4A5B66">Yard schematic, not to scale</text></g>')
    a(f'<g transform="translate(110,1400)"><circle r="42" fill="#F8F9F6" stroke="{ink}" stroke-width="2"/>'
      f'<path d="M0,-34 L10,6 L0,0 L-10,6 Z" fill="{ink}"/><text y="-48" text-anchor="middle" font-size="19" '
      f'font-weight="700" fill="{ink}">N</text></g>')
    a(f'<text x="30" y="1486" text-anchor="start" font-size="14" fill="#5C8194">Layout traced from an aerial view; '
      f'zones are approximate</text>')
    a('</svg>')


def main():
    build()
    SVG_OUT.parent.mkdir(parents=True, exist_ok=True)
    SVG_OUT.write_text("\n".join(o) + "\n", encoding="utf-8")
    ZONES_OUT.parent.mkdir(parents=True, exist_ok=True)
    ZONES_OUT.write_text(json.dumps(zone_rows(), indent=1) + "\n", encoding="utf-8")
    print(f"wrote {SVG_OUT.relative_to(ROOT)} and {ZONES_OUT.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
