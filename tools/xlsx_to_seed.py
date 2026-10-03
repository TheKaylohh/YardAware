#!/usr/bin/env python3
"""Converts the Aloha class production hierarchy workbook into src/main/resources/seed/aloha-hierarchy.json.

The application seeds H2 from that JSON on first start, so the workbook stays the source of truth:
edit the workbook, re-run this script, delete ./data, restart.
The "Summary" sheet is formulas and "Audit of Original" is commentary on the previous workbook, so neither is imported.

    python3 tools/xlsx_to_seed.py [workbook.xlsx] [output.json]
"""
import json
import sys
from pathlib import Path

import openpyxl

ROOT = Path(__file__).resolve().parent.parent
src = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "tools" / "Aloha_Class_Production_Hierarchy_v2.xlsx"
dst = Path(sys.argv[2]) if len(sys.argv) > 2 else ROOT / "src/main/resources/seed/aloha-hierarchy.json"


def clean(value):
    if value is None:
        return None
    if isinstance(value, str):
        value = value.strip()
        return value or None
    return value


def sheet_rows(ws):
    rows = list(ws.iter_rows(values_only=True))
    header = [clean(h) for h in rows[0]]
    for row in rows[1:]:
        if all(clean(c) is None for c in row):
            continue
        yield dict(zip(header, [clean(c) for c in row]))


wb = openpyxl.load_workbook(src, data_only=False)

phases = [
    {"number": int(r["Phase"]), "name": r["Name"], "facility": r["Facility"], "level": r["Level"]}
    for r in sheet_rows(wb["Phases"])
]

design_basis = [
    {"item": r["Item"], "value": r["Value"], "status": r["Status"], "source": r["Source"]}
    for r in sheet_rows(wb["Design Basis"])
]

rows = []
for r in sheet_rows(wb["Aloha Hierarchy"]):
    rows.append({
        "level": r["Level"],
        "areaCode": r["Area Code"],
        "area": r["Area"],
        "unit": r["Unit (Grand Block)"],
        "block": r["Block"],
        "section": r["Section"],
        "band": r["Longitudinal Band (aft→fwd)"],
        "latitude": r["Transverse Latitude"],
        "side": r["Side"],
        "phase": r["Primary Phase"],
        "phaseName": r["Phase Name"],
        "phaseRoute": r["Phase Route"],
        "facility": r["Assembly Facility"],
        "erectionOrder": r["Dock Erection Order"],
        "outfitHeavy": None if r["Outfit-Heavy"] is None else r["Outfit-Heavy"] == "Y",
        "function": r["Function / System"],
        "confidence": r["Confidence"],
        "note": r["Planning Basis / Note"],
    })

dst.parent.mkdir(parents=True, exist_ok=True)
dst.write_text(json.dumps(
    {"source": src.name, "phases": phases, "designBasis": design_basis, "rows": rows},
    ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
print(f"wrote {dst}: {len(phases)} phases, {len(design_basis)} design-basis rows, {len(rows)} hierarchy rows")
