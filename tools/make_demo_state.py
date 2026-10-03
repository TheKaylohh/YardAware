#!/usr/bin/env python3
"""Builds the demo yard state: two ships under construction, every zone busy.

Reads  src/main/resources/seed/aloha-hierarchy.json  (the plan) and
       src/main/resources/seed/yard-zones.json       (zone outlines, from make_map.py)
Writes src/main/resources/seed/demo-state.json       (applied by DemoDataSeeder)

S041 sits in Building Dock 1 and is the further-along ship, S042 sits in Building Dock 2 and started later. S043 is
left as plan only. The state follows the same rules ItemService enforces, so it could have been produced by clicking
through the app: a section is placed, moves through its phases, and all sections of a block are assembled into it;
all blocks of a unit are assembled into the unit; assembled pieces are CONSUMED. Every item gets a back-dated history
(from the plan being created to its current phase and zone) so the Activity tab is full too.

The output is deterministic (fixed seed). Run from the repo root:  python3 tools/make_demo_state.py
"""
import itertools
import json
import pathlib
import random

ROOT = pathlib.Path(__file__).resolve().parent.parent
SEED = ROOT / "src/main/resources/seed"

CREW = ["mhughes", "dkowalski", "tnguyen", "rpatel", "jbrennan", "lsantos", "kobrien", "awilliams", "cmoreau"]
MOVE_NOTES = [None, None, "Moved by transporter", "Gantry lift", "Cleared for next phase", "Staged for assembly",
              "Moved by SPMT", "Crane 2 lift"]

# --- how far each unit (in erection order) has got, per ship. The names are explained in plan_unit().
S041_STAGES = (["dock8"] * 3 + ["dock7"] * 3 + ["dock6"] * 2 + ["gby7"] * 2 + ["gby6"] * 1 + ["bp9"] * 4
               + ["pier8"] * 4 + ["blocks"] * 4 + ["mixed"] * 4 + ["secs"] * 4)
S042_STAGES = (["dock7"] * 2 + ["dock6"] * 2 + ["gby6"] * 1 + ["bp9"] * 3 + ["pier8"] * 3 + ["blocks"] * 7
               + ["mixed"] * 4 + ["secs"] * 5 + ["none"] * 4)
# Staging yards for finished sections fill up to this many tiles in total, so no zone overflows.
STAGING_CAP = {"LY1": 17, "POL": 16}
HULLS = [
    {"code": "S041", "name": "Hull S041", "color": "#2D6CDF", "dock": "BD1", "stages": S041_STAGES, "seed": 41},
    {"code": "S042", "name": "Hull S042", "color": "#F28C28", "dock": "BD2", "stages": S042_STAGES, "seed": 42},
    {"code": "S043", "name": "Hull S043", "color": "#B03AC2", "dock": None, "stages": [], "seed": 43},
]


def parse_route(text):
    return [int(p) for p in str(text).replace("→", " ").replace("->", " ").split() if p.isdigit()]


class Plan:
    def __init__(self):
        data = json.load(open(SEED / "aloha-hierarchy.json", encoding="utf-8"))
        self.phase_names = {p["number"]: p["name"] for p in data["phases"]}
        units, blocks = {}, {}
        for r in data["rows"]:
            lvl = r["level"]
            if lvl == "UNIT":
                units[r["unit"]] = {"code": r["unit"], "order": r["erectionOrder"] or 99, "blocks": []}
            elif lvl == "BLOCK":
                b = {"code": r["block"], "route": parse_route(r["phaseRoute"]), "sections": [],
                     "heavy": bool(r["outfitHeavy"])}
                blocks[r["block"]] = b
                units[r["unit"]]["blocks"].append(b)
            elif lvl == "SECTION":
                blocks[r["block"]]["sections"].append(
                    {"code": r["section"], "route": parse_route(r["phaseRoute"]), "heavy": bool(r["outfitHeavy"])})
        # erection order, ties broken by code so the ranking is stable
        self.units = sorted(units.values(), key=lambda u: (u["order"], u["code"]))


class Sim:
    def __init__(self, plan, hull, load):
        self.plan, self.hull = plan, hull
        self.load = load      # zone -> tiles placed so far, shared by both ships
        self.rng = random.Random(hull["seed"])
        self.states = {}      # item name -> dict(status, zone, phase, events)
        self.oldest = 0.0

    # ----------------------------------------------------------- helpers
    def r(self, a, b):
        return self.rng.uniform(a, b)

    def crew(self):
        return self.rng.choice(CREW)

    def name(self, code):
        return f"{self.hull['code']}-{code}"

    def entries(self, states, end, lo, hi, last=None):
        """Entry times (days ago) for each (zone, phase) state, built backwards from the time the item leaves.
        `last` pins when the newest state began; older states then follow, each earlier than the next."""
        t, out = end, []
        for zone, phase in reversed(states):
            t = (last if last is not None and not out else t + self.r(lo, hi))
            out.append((t, zone, phase))
        out.reverse()
        return out

    def record(self, code, status, entries, first, note_first, consumed_at=None, parent_name=None):
        """Turns a trajectory into ACTIVE/CONSUMED item state with its activity history."""
        events = []
        _, z0, p0 = entries[0]
        events.append([entries[0][0], first, None, z0, self.crew(), note_first])
        pn = self.plan.phase_names
        for (t1, z1, p1), (_, z0, p0) in zip(entries[1:], entries[:-1]):
            if z1 != z0:
                events.append([t1, "MOVED", z0, z1, self.crew(), self.rng.choice(MOVE_NOTES)])
            if p1 != p0:
                events.append([t1 - 0.03 if z1 != z0 else t1, "PHASE_CHANGED", None, None, self.crew(),
                               f"Phase {p0} {pn[p0]} to {p1} {pn[p1]}"])
        last_t, last_z, last_p = entries[-1]
        if consumed_at is not None:
            events.append([consumed_at, "CONSUMED", last_z, None, self.crew(), f"Assembled into {parent_name}"])
        self.oldest = max(self.oldest, entries[0][0])
        if status == "ACTIVE":
            self.load[last_z] = self.load.get(last_z, 0) + 1
        self.states[self.name(code)] = {
            "status": status, "zone": last_z, "phase": last_p, "events": events}
        return entries[0][0]

    # ------------------------------------------------------------ sections
    def section_states(self, sec, upto, zone_choice=None):
        """(zone, phase) path of a section through its route up to phase index `upto`."""
        path = []
        for phase in sec["route"][: upto + 1]:
            if phase == 1:
                zone = "SP" if self.rng.random() < 0.55 and self.load.get("SP", 0) < 16 else "PL"
            elif phase == 2:
                zone = "PL"
            else:
                zone = self.rng.choice(["PS", "OS"]) if sec["heavy"] else self.rng.choice(["OS", "PL", "OS"])
                if zone == "PS" and self.load.get("PS", 0) >= 12:
                    zone = "OS"
            path.append((zone, phase))
        return path

    def staged(self, sec, path):
        order = ["POL", "LY1"] if sec["heavy"] else ["LY1", "POL"]
        if self.rng.random() < 0.25:
            order.reverse()
        for zone in order:
            if self.load.get(zone, 0) < STAGING_CAP[zone]:
                return path + [(zone, path[-1][1])]
        return path           # both laydowns are full: the section stays where it was worked

    def active_section(self, sec, block_name):
        route = sec["route"]
        pick = self.rng.random()
        if sec["heavy"] and len(route) > 2 and pick < 0.55:
            idx = 2                      # outfit-heavy sections spend a long time in the pipe and outfitting shops
        elif pick < 0.36:
            idx = 0
        elif pick < 0.62:
            idx = min(1, len(route) - 1)
        elif pick < 0.74 and len(route) > 2:
            idx = 2
        else:
            idx = len(route) - 1
        path = self.section_states(sec, idx)
        if idx == len(route) - 1 and self.rng.random() < 0.62:   # finished: waits to be joined into its block
            path = self.staged(sec, path)
        ents = self.entries(path, 0, 0.2, 4.5, last=self.r(0.1, 3.0))
        self.record(sec["code"], "ACTIVE", ents, "PLACED", "Placed on the yard")

    def consumed_section(self, sec, block_name, end):
        path = self.section_states(sec, len(sec["route"]) - 1)
        if self.rng.random() < 0.7:
            path = self.staged(sec, path)
        ents = self.entries(path, end, 0.8, 3.5)
        self.record(sec["code"], "CONSUMED", ents, "PLACED", "Placed on the yard", consumed_at=end,
                    parent_name=block_name)

    # -------------------------------------------------------------- blocks
    def block_target(self, blk, dock):
        """Where a finished block is parked, as a trajectory starting in the Assembly Hall (phase 4)."""
        route = blk["route"]
        last = route[-1]
        roll = self.rng.random()
        path = [("AH", route[0])]
        if roll < 0.28 and self.load.get("AH", 0) >= 10:
            roll = 0.5            # the Assembly Hall is full, this one is already moved on
        if roll < 0.28:
            return path
        if last == 5 and roll < 0.50:
            return path + [("OS" if blk["heavy"] or self.rng.random() < 0.5 else "BP", 5)]
        if last == 5 and roll < 0.62:
            return path + [("BP", 5)]
        if roll < 0.74:
            return path + ([("BP", 5)] if last == 5 and self.rng.random() < 0.5 else []) + [("LY2", last)]
        if roll < 0.80:
            return path + [("POL", last)]
        if roll < 0.90:
            return path + [("GBY", last)]
        if roll < 0.95 and blk["heavy"]:
            return path + [("PIER", last)]
        return path + [(dock, last)]

    def build_block(self, blk, unit_code, mode, into=None, unit_end=None):
        """mode: 'done' (ACTIVE, all sections assembled), 'consumed' (assembled into its unit),
        'sections' (block still PLANNED, its sections are in work)."""
        bname = self.name(blk["code"])
        if mode == "sections":
            for sec in blk["sections"]:
                if self.rng.random() < 0.15:
                    continue       # not started yet, stays PLANNED
                self.active_section(sec, bname)
            return
        if mode == "done":
            path = self.block_target(blk, self.hull["dock"])
            ents = self.entries(path, 0, 0.6, 4.0, last=self.r(0.1, 2.5))
            status, consumed_at, parent = "ACTIVE", None, None
        else:
            route = blk["route"]
            path = [("AH", route[0])]
            if route[-1] == 5:
                path.append(("OS" if blk["heavy"] else self.rng.choice(["OS", "BP", "BP"]), 5))
            path.append(("GBY", route[-1]))
            ents = self.entries(path, unit_end, 0.8, 4.0)
            status, consumed_at, parent = "CONSUMED", unit_end, into
        names = ", ".join(self.name(s["code"]) for s in blk["sections"])
        built = self.record(blk["code"], status, ents, "ASSEMBLED", f"Assembled from {names}",
                            consumed_at=consumed_at, parent_name=parent)
        for sec in blk["sections"]:
            self.consumed_section(sec, bname, built)

    # --------------------------------------------------------------- units
    UNIT_PATHS = {
        # stage: states from unit assembly in the Grand Block Yard onwards
        "gby6": [("GBY", 6)],
        "gby7": [("GBY", 6), ("GBY", 7)],
        "dock6": [("GBY", 6), ("DOCK", 6)],
        "dock7": [("GBY", 6), ("GBY", 7), ("DOCK", 7)],
        "dock8": [("GBY", 6), ("GBY", 7), ("DOCK", 7), ("DOCK", 8)],
        "bp9": [("GBY", 6), ("GBY", 7), ("PIER", 8), ("BP", 9)],
        "pier8": [("GBY", 6), ("GBY", 7), ("PIER", 8)],
    }

    def plan_unit(self, unit, stage, bias=0.0):
        """`bias` pushes the whole story back in time: the first unit erected is the oldest."""
        uname = self.name(unit["code"])
        if stage in self.UNIT_PATHS:
            path = [(self.hull["dock"] if z == "DOCK" else z, p) for z, p in self.UNIT_PATHS[stage]]
            ents = self.entries(path, bias, 1.5, 7.0, last=bias + self.r(0.2, 5.0))
            names = ", ".join(self.name(b["code"]) for b in unit["blocks"])
            built = self.record(unit["code"], "ACTIVE", ents, "ASSEMBLED", f"Assembled from {names}")
            for blk in unit["blocks"]:
                self.build_block(blk, unit["code"], "consumed", into=uname, unit_end=built)
        elif stage == "blocks":
            for blk in unit["blocks"]:
                self.build_block(blk, unit["code"], "done")
        elif stage == "mixed":
            for i, blk in enumerate(unit["blocks"]):
                self.build_block(blk, unit["code"], "done" if i % 2 == 0 else "sections")
        elif stage == "secs":
            for blk in unit["blocks"]:
                self.build_block(blk, unit["code"], "sections")
        # "none": nothing started, every piece stays PLANNED

    def run(self):
        stages = self.hull["stages"]
        assembled = sum(1 for s in stages if s in self.UNIT_PATHS)
        # Units that are not assembled yet get their stages interleaved, so every kind of work is spread over the
        # whole ship instead of the last few small units carrying all of the sections.
        rest = [[s for s in stages[assembled:] if s == kind] for kind in ("blocks", "secs", "mixed", "none")]
        mixed_up = [s for group in itertools.zip_longest(*rest) for s in group if s]
        stages = stages[:assembled] + mixed_up
        for rank, (unit, stage) in enumerate(zip(self.plan.units, stages)):
            self.plan_unit(unit, stage, (assembled - rank) * 4.2 if rank < assembled else 0.0)
        return self.states


# --------------------------------------------------------------------------- output and checks
def fit(n, w, h):
    """Same arithmetic as fitTiles() in map.js: the largest tile scale at which n tiles fit the zone."""
    scale = 1.0
    while True:
        labelled = scale >= 0.8
        cw = 68 * scale if labelled else 48 * scale
        ch = 72 * scale if labelled else 48 * scale
        cols = max(1, int((w - 16) // cw))
        rows = max(1, int((h - 34 - 20) // ch))
        if cols * rows >= n or scale <= 0.551:
            return round(scale, 2), cols * rows
        scale = round(scale - 0.05, 2)


def main():
    plan = Plan()
    hulls_out, items_out = [], []
    load = {}
    for hull in HULLS:
        sim = Sim(plan, hull, load)
        states = sim.run()
        age = round(sim.oldest + 6.0 + sim.r(0, 3))
        hulls_out.append({"code": hull["code"], "name": hull["name"], "color": hull["color"], "ageDays": age})
        for name, st in states.items():
            events = sorted(st["events"], key=lambda e: -e[0])
            items_out.append({
                "name": name, "status": st["status"], "zone": st["zone"], "phase": st["phase"],
                "events": [[round(e[0] * 1440), e[1], e[2], e[3], e[4], e[5]] for e in events],
            })
    out = {"hulls": hulls_out, "items": items_out}
    (SEED / "demo-state.json").write_text(json.dumps(out, separators=(",", ":")) + "\n", encoding="utf-8")

    zones = {z["code"]: z for z in json.load(open(SEED / "yard-zones.json"))}
    counts = {}
    for it in items_out:
        if it["status"] == "ACTIVE":
            h = it["name"][:4]
            lvl = ("U" if it["name"].count("-") == 2 else "B" if it["name"].count("-") == 3 else "S")
            c = counts.setdefault(it["zone"], {})
            c[(h, lvl)] = c.get((h, lvl), 0) + 1
    print(f"{'zone':5} {'tiles':>5} {'scale':>5} {'slots':>5}   S041 U/B/S    S042 U/B/S")
    total = 0
    for code, z in zones.items():
        pts = [tuple(map(int, p.split(","))) for p in z["points"].split()]
        w, h = pts[1][0] - pts[0][0], pts[2][1] - pts[0][1]
        c = counts.get(code, {})
        n = sum(c.values())
        total += n
        scale, slots = fit(n, w, h)
        row = "   ".join("/".join(str(c.get((hc, l), 0)) for l in "UBS") for hc in ("S041", "S042"))
        print(f"{code:5} {n:5} {scale:5} {slots:5}   {row}")
    print("active tiles:", total, " items with state:", len(items_out),
          " hull ages (days):", [h["ageDays"] for h in hulls_out])


if __name__ == "__main__":
    main()
