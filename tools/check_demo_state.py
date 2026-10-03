#!/usr/bin/env python3
"""Checks seed/demo-state.json against the plan: the same rules ItemService enforces, plus sane histories."""
import json
import pathlib
import sys

SEED = pathlib.Path(__file__).resolve().parent.parent / "src/main/resources/seed"
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from make_demo_state import parse_route  # noqa: E402

plan = json.load(open(SEED / "aloha-hierarchy.json", encoding="utf-8"))
parent, route, kids = {}, {}, {}
for r in plan["rows"]:
    key = {"UNIT": "unit", "BLOCK": "block", "SECTION": "section"}.get(r["level"])
    if key:
        parent[r[key]] = {"UNIT": None, "BLOCK": r.get("unit"), "SECTION": r.get("block")}[r["level"]]
        route[r[key]] = parse_route(r["phaseRoute"])
for code, p in parent.items():
    if p:
        kids.setdefault(p, []).append(code)

state = json.load(open(SEED / "demo-state.json", encoding="utf-8"))
zones = {z["code"] for z in json.load(open(SEED / "yard-zones.json", encoding="utf-8"))}
S = {tuple(i["name"].split("-", 1)): i for i in state["items"]}
errors = []
for (hull, code), it in S.items():
    tag = f"{hull}-{code}"
    p = parent[code]
    if it["zone"] not in zones:
        errors.append(f"{tag}: unknown zone {it['zone']}")
    if it["phase"] not in route[code]:
        errors.append(f"{tag}: phase {it['phase']} is not on its route {route[code]}")
    if it["status"] == "ACTIVE" and p and (hull, p) in S:
        errors.append(f"{tag}: ACTIVE although its parent {p} is already built")
    if it["status"] == "CONSUMED" and (not p or (hull, p) not in S):
        errors.append(f"{tag}: CONSUMED but its parent is not built")
    if it["status"] in ("ACTIVE", "CONSUMED") and any(
            S.get((hull, k), {}).get("status") != "CONSUMED" for k in kids.get(code, [])) and code in kids:
        errors.append(f"{tag}: built, but not every piece below it was assembled into it")
    ev = it["events"]
    mins = [e[0] for e in ev]
    if mins != sorted(mins, reverse=True) or mins[-1] < 0:
        errors.append(f"{tag}: events out of order")
    if ev[0][1] not in ("PLACED", "ASSEMBLED"):
        errors.append(f"{tag}: first event is {ev[0][1]}")
    if (it["status"] == "CONSUMED") != (ev[-1][1] == "CONSUMED"):
        errors.append(f"{tag}: CONSUMED event does not match status")
    zone = None
    for e in ev:
        if e[1] in ("PLACED", "ASSEMBLED", "MOVED"):
            zone = e[3]
    if zone != it["zone"]:
        errors.append(f"{tag}: history ends in {zone}, state says {it['zone']}")
print("items:", len(S), "errors:", len(errors))
for e in errors[:30]:
    print(" ", e)
sys.exit(1 if errors else 0)
