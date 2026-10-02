# Generates the placeholder yard schematic. Zone rectangles MUST match DemoDataSeeder.
W, H = 1600, 1000
land, road = "#E1E6DE", "#CBD2C9"
river, river_dk = "#B3CAD4", "#8FB0BE"
bldg, bldg_stroke = "#D5DBD5", "#6F8087"
yard_fill, grid = "#E6EAE2", "#C5CCC2"
ink = "#14232E"
o = []
a = o.append
a(f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H}" width="{W}" height="{H}" font-family="Bahnschrift, \'DIN Alternate\', \'Segoe UI\', Arial, sans-serif">')
a('<title>Placeholder schematic of the shipyard</title>')
a(f'<rect width="{W}" height="{H}" fill="{land}"/>')
# roads = the land itself; add a slightly darker base under the yard so gaps read as roads
a(f'<rect x="0" y="0" width="1262" height="{H}" fill="{road}"/>')
# river
a(f'<path d="M1262,0 C1278,150 1246,260 1262,400 C1278,540 1250,700 1266,820 C1276,900 1258,960 1262,{H} L{W},{H} L{W},0 Z" fill="{river}" stroke="{river_dk}" stroke-width="3"/>')
for y in range(80, 980, 70):
    a(f'<path d="M1330,{y} q30,-10 60,0 t60,0 t60,0" fill="none" stroke="{river_dk}" stroke-width="2" opacity=".55"/>')
a(f'<text transform="translate(1545,560) rotate(-90)" text-anchor="middle" font-size="30" font-style="italic" letter-spacing="5" fill="#5C8194">Delaware River</text>')

def yard(x1,y1,x2,y2):
    a(f'<rect x="{x1}" y="{y1}" width="{x2-x1}" height="{y2-y1}" fill="{yard_fill}" stroke="{bldg_stroke}" stroke-width="1.5"/>')
    x = x1+40
    while x < x2:
        a(f'<line x1="{x}" y1="{y1}" x2="{x}" y2="{y2}" stroke="{grid}" stroke-width="1" stroke-dasharray="6 8"/>')
        x += 40
    y = y1+40
    while y < y2:
        a(f'<line x1="{x1}" y1="{y}" x2="{x2}" y2="{y}" stroke="{grid}" stroke-width="1" stroke-dasharray="6 8"/>')
        y += 40

def building(x1,y1,x2,y2, lines=None):
    a(f'<rect x="{x1}" y="{y1}" width="{x2-x1}" height="{y2-y1}" fill="{bldg}" stroke="{bldg_stroke}" stroke-width="2.5"/>')
    a(f'<rect x="{x1+8}" y="{y1+8}" width="{x2-x1-16}" height="{y2-y1-16}" fill="none" stroke="{bldg_stroke}" stroke-width="1" opacity=".5"/>')
    if lines == 'h':
        y = y1+50
        while y < y2-10:
            a(f'<line x1="{x1+8}" y1="{y}" x2="{x2-8}" y2="{y}" stroke="{bldg_stroke}" stroke-width="1" opacity=".35"/>')
            y += 34
    if lines == 'v':
        x = x1+50
        while x < x2-10:
            a(f'<line x1="{x}" y1="{y1+8}" x2="{x}" y2="{y2-8}" stroke="{bldg_stroke}" stroke-width="1" opacity=".35"/>')
            x += 34

# Steel stockyard: open yard with plate stacks
yard(60,60,400,260)
for i,(sx,sy) in enumerate([(240,150),(300,150),(240,200),(300,200),(340,150),(340,200)]):
    a(f'<rect x="{sx}" y="{sy}" width="40" height="28" fill="#9AA8AD" stroke="{bldg_stroke}" stroke-width="1"/>')
# Panel line, pipe shop, outfitting shop, assembly hall, blast and paint
building(430,60,780,260,'h')
building(810,60,1010,260,'v')
building(1030,60,1230,260,'v')
building(60,300,520,520,'v')
building(550,300,780,520,'h')
# Laydown yards and grand block yard
yard(60,560,400,740)
yard(430,560,780,740)
yard(60,770,780,940)
yard(810,730,1060,940)
building(1080,730,1230,940,'h')

# Building dock (basin open to the river) and its gate
a(f'<rect x="810" y="300" width="452" height="260" fill="{river_dk}" stroke="{bldg_stroke}" stroke-width="3"/>')
a(f'<rect x="1232" y="300" width="36" height="260" fill="{river_dk}"/>')
a(f'<line x1="1234" y1="300" x2="1234" y2="560" stroke="{ink}" stroke-width="7"/>')
# dock wall rails and gantry crane
a(f'<line x1="810" y1="294" x2="1232" y2="294" stroke="{ink}" stroke-width="3"/>')
a(f'<line x1="810" y1="566" x2="1232" y2="566" stroke="{ink}" stroke-width="3"/>')
a(f'<rect x="1040" y="284" width="20" height="292" fill="#F5B800" stroke="{ink}" stroke-width="2.5"/>')
a(f'<rect x="1034" y="276" width="32" height="14" fill="#F5B800" stroke="{ink}" stroke-width="2"/>')
a(f'<rect x="1034" y="570" width="32" height="14" fill="#F5B800" stroke="{ink}" stroke-width="2"/>')
# keel blocks hint in the dock
for x in range(850, 1000, 30):
    a(f'<rect x="{x}" y="424" width="12" height="12" fill="{ink}" opacity=".25"/>')

# Outfitting pier
a(f'<rect x="810" y="600" width="640" height="90" fill="#C3CAC2" stroke="{bldg_stroke}" stroke-width="2.5"/>')
for x in range(1290, 1450, 26):
    a(f'<circle cx="{x}" cy="604" r="3.5" fill="{bldg_stroke}"/><circle cx="{x}" cy="686" r="3.5" fill="{bldg_stroke}"/>')

# main gate and compass
a(f'<rect x="0" y="528" width="10" height="24" fill="{ink}"/>')
a(f'<text x="18" y="545" font-size="15" fill="{ink}">Main gate</text>')
a(f'<g transform="translate(1510,110)"><path d="M0,-34 L10,8 L0,0 L-10,8 Z" fill="{ink}"/><text y="30" text-anchor="middle" font-size="16" fill="{ink}">N</text></g>')
a(f'<text x="60" y="978" font-size="14" font-style="italic" fill="#4F626B">Placeholder schematic, not to scale. Replace with a site plan or aerial image.</text>')
a('</svg>')
open('src/main/resources/static/img/yard.svg','w').write("\n".join(o))
