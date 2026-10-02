package com.shipyard.tracker.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shipyard.tracker.domain.Activity;
import com.shipyard.tracker.domain.ActivityType;
import com.shipyard.tracker.domain.Hull;
import com.shipyard.tracker.domain.Item;
import com.shipyard.tracker.domain.ItemStatus;
import com.shipyard.tracker.domain.ItemType;
import com.shipyard.tracker.domain.Zone;
import com.shipyard.tracker.repo.ActivityRepository;
import com.shipyard.tracker.repo.HullRepository;
import com.shipyard.tracker.repo.ItemRepository;
import com.shipyard.tracker.repo.ZoneRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Fills an empty database with a placeholder yard: 3 hulls, 13 zones and ~24 items with backdated history.
 * The zone outlines match the schematic in static/img/yard.svg. Delete the ./data folder to re-seed.
 */
@Component
public class DemoDataSeeder implements ApplicationRunner {

    private static final String[] ACTORS = {"j.ortiz", "m.kowalski", "t.nguyen", "d.williams"};

    private static final Map<String, String> MOVE_NOTES = Map.of(
            "BP", "Sent to blast and paint",
            "BD", "Lifted into the building dock",
            "GBY", "Staged in the grand block yard",
            "PIER", "Moved to the outfitting pier",
            "AH", "Moved to the assembly hall",
            "LY1", "Staged in laydown yard 1",
            "LY2", "Staged in laydown yard 2",
            "POL", "Staged for outfitting");

    private final HullRepository hulls;
    private final ZoneRepository zones;
    private final ItemRepository items;
    private final ActivityRepository activities;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final boolean enabled;

    private final Map<String, Zone> zoneByCode = new HashMap<>();
    private Instant now;
    private int actorCounter = 0;

    public DemoDataSeeder(HullRepository hulls,
                          ZoneRepository zones,
                          ItemRepository items,
                          ActivityRepository activities,
                          TransactionTemplate tx,
                          ObjectMapper mapper,
                          @Value("${shipyard.seed-demo-data:true}") boolean enabled) {
        this.hulls = hulls;
        this.zones = zones;
        this.items = items;
        this.activities = activities;
        this.tx = tx;
        this.mapper = mapper;
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled || hulls.count() > 0) {
            return;
        }
        tx.executeWithoutResult(status -> seed());
    }

    private void seed() {
        now = Instant.now();

        Hull s041 = hulls.save(new Hull("S041", "Hull S041", "#2D6CDF"));
        Hull s042 = hulls.save(new Hull("S042", "Hull S042", "#F28C28"));
        Hull s043 = hulls.save(new Hull("S043", "Hull S043", "#B03AC2"));

        // Coordinates are in the 1600 x 1000 space of static/img/yard.svg
        zone("SP", "Steel Stockyard", "yard", rect(60, 60, 400, 260));
        zone("PL", "Panel Line", "shop", rect(430, 60, 780, 260));
        zone("PS", "Pipe Shop", "shop", rect(810, 60, 1010, 260));
        zone("OS", "Outfitting Shop", "shop", rect(1030, 60, 1230, 260));
        zone("AH", "Assembly Hall", "shop", rect(60, 300, 520, 520));
        zone("BP", "Blast & Paint", "shop", rect(550, 300, 780, 520));
        zone("BD", "Building Dock", "dock", rect(810, 300, 1230, 560));
        zone("LY1", "Laydown Yard 1", "yard", rect(60, 560, 400, 740));
        zone("LY2", "Laydown Yard 2", "yard", rect(430, 560, 780, 740));
        zone("GBY", "Grand Block Yard", "yard", rect(60, 770, 780, 940));
        zone("PIER", "Outfitting Pier", "pier", rect(810, 600, 1450, 690));
        zone("POL", "Pipe & Outfit Laydown", "yard", rect(810, 730, 1060, 940));
        zone("TC", "Tool Crib", "store", rect(1080, 730, 1230, 940));

        // ---- Hull S041: the grand block in the dock is assembled from a full component tree
        Item ea511a = born("S041-EA511-A", ItemType.SUB_ASSEMBLY, s041, null, null, null,
                specs("Weight (t)", 6.2, "Material", "AH36", "Drawing", "EA511-SA-01"), 52, 40, "PL", "AH");
        Item ea511b = born("S041-EA511-B", ItemType.SUB_ASSEMBLY, s041, null, null, null,
                specs("Weight (t)", 5.8, "Material", "AH36", "Drawing", "EA511-SA-02"), 52, 40, "PL", "AH");
        Item ea511 = assemble("S041-EA511", ItemType.SECTION, s041, null,
                specs("Weight (t)", 18.4, "Length (m)", 9.6, "Drawing", "EA511-SEC"), "AH", 40, ea511a, ea511b);
        Item ea512 = born("S041-EA512", ItemType.SECTION, s041, null, null, null,
                specs("Weight (t)", 21.0, "Length (m)", 10.2, "Drawing", "EA512-SEC"), 50, 34, "PL", "AH");
        Item ea510 = assemble("S041-EA510", ItemType.BLOCK, s041, null,
                specs("Weight (t)", 92.5, "Length (m)", 14.0, "Width (m)", 12.0, "Drawing", "EA510-BLK"), "AH", 34, ea511, ea512);
        walk(ea510, 34, 20, "AH", "BP", "LY2");
        Item ea520 = born("S041-EA520", ItemType.BLOCK, s041, null, null, null,
                specs("Weight (t)", 88.0, "Length (m)", 13.5, "Drawing", "EA520-BLK"), 45, 20, "AH", "BP", "LY1");
        Item ea530 = born("S041-EA530", ItemType.BLOCK, s041, null, null, null,
                specs("Weight (t)", 95.3, "Length (m)", 14.5, "Drawing", "EA530-BLK"), 44, 20, "AH", "BP", "LY1");
        Item ea500 = assemble("S041-EA500", ItemType.GRAND_BLOCK, s041, "EA500",
                specs("Weight (t)", 410.0, "Length (m)", 28.0, "Width (m)", 24.0, "Height (m)", 12.5, "Drawing", "EA500-GB"),
                "GBY", 20, ea510, ea520, ea530);
        walk(ea500, 20, 0, "GBY", "BD");

        Item fa410 = born("S041-FA410", ItemType.BLOCK, s041, null, null, null,
                specs("Weight (t)", 76.0, "Drawing", "FA410-BLK"), 38, 15, "AH", "BP", "LY2");
        Item fa420 = born("S041-FA420", ItemType.BLOCK, s041, null, null, null,
                specs("Weight (t)", 81.4, "Drawing", "FA420-BLK"), 37, 15, "AH", "BP", "LY2");
        assemble("S041-FA400", ItemType.GRAND_BLOCK, s041, "FA400",
                specs("Weight (t)", 340.0, "Length (m)", 26.0, "Drawing", "FA400-GB"), "GBY", 15, fa410, fa420);

        born("S041-AA100", ItemType.GRAND_BLOCK, s041, "AA100", null, null,
                specs("Weight (t)", 520.0, "Length (m)", 30.0, "Height (m)", 14.0, "Drawing", "AA100-GB"), 30, 0, "GBY", "BD");
        born("S041-LA300", ItemType.GRAND_BLOCK, s041, "LA300", null, null,
                specs("Weight (t)", 610.0, "Tank type", "Membrane cargo hold", "Drawing", "LA300-GB"), 25, 0, "AH", "BP");
        born("S041-BA200", ItemType.GRAND_BLOCK, s041, "BA200", null, null,
                specs("Weight (t)", 450.0, "Drawing", "BA200-GB"), 28, 0, "GBY");
        born("S041-DA700", ItemType.GRAND_BLOCK, s041, "DA700", null, null,
                specs("Weight (t)", 380.0, "Drawing", "DA700-GB"), 10, 0, "AH", "BP", "GBY");
        born("S041-HA610", ItemType.BLOCK, s041, null, null, null,
                specs("Weight (t)", 64.0, "Drawing", "HA610-BLK"), 12, 0, "PL", "AH");
        born("S041-HA611", ItemType.SECTION, s041, null, null, null,
                specs("Weight (t)", 17.5, "Drawing", "HA611-SEC"), 14, 0, "PL", "AH");
        born("S041-UNIT-ER01", ItemType.UNIT, s041, null, null, null,
                specs("Description", "Engine room pump unit", "Weight (t)", 14.2), 18, 0, "OS", "PIER");
        born("S041-PIPE-0412", ItemType.PIPE_OUTFITTING, s041, null, 48, "pcs",
                specs("Description", "Fuel oil pipe spools", "Material", "Carbon steel", "Diameter (mm)", 150), 20, 0, "PS", "POL");
        born("S041-OUT-0219", ItemType.PIPE_OUTFITTING, s041, null, 300, "m",
                specs("Description", "Cable tray, straight runs", "Material", "Galvanised steel"), 16, 0, "OS", "PIER");

        // ---- Hull S042
        born("S042-BA200", ItemType.GRAND_BLOCK, s042, "BA200", null, null,
                specs("Weight (t)", 445.0, "Drawing", "BA200-GB"), 9, 0, "AH", "LY1");
        born("S042-EA510", ItemType.BLOCK, s042, null, null, null,
                specs("Weight (t)", 91.0, "Drawing", "EA510-BLK"), 11, 0, "PL", "AH");
        born("S042-EA511", ItemType.SECTION, s042, null, null, null,
                specs("Weight (t)", 18.0, "Drawing", "EA511-SEC"), 13, 0, "PL", "AH");
        born("S042-SA-101", ItemType.SUB_ASSEMBLY, s042, null, null, null,
                specs("Weight (t)", 5.1, "Material", "AH36"), 8, 0, "SP", "PL");
        born("S042-SA-102", ItemType.SUB_ASSEMBLY, s042, null, null, null,
                specs("Weight (t)", 4.9, "Material", "AH36"), 8, 0, "SP", "PL");
        born("S042-PIPE-0107", ItemType.PIPE_OUTFITTING, s042, null, 120, "pcs",
                specs("Description", "Ballast pipe spools", "Material", "Cu-Ni 90/10", "Diameter (mm)", 200), 12, 0, "PS", "POL");

        // ---- Hull S043: just starting
        born("S043-SA-001", ItemType.SUB_ASSEMBLY, s043, null, null, null,
                specs("Weight (t)", 5.4, "Material", "AH36"), 4, 0, "SP", "PL");
        born("S043-SA-002", ItemType.SUB_ASSEMBLY, s043, null, null, null,
                specs("Weight (t)", 5.6, "Material", "AH36"), 4, 0, "SP", "PL");
        born("S043-SEC-001", ItemType.SECTION, s043, null, null, null,
                specs("Weight (t)", 16.9, "Drawing", "SEC-001"), 5, 0, "PL", "LY1");

        // ---- Tools (no hull, neutral outline)
        born("Crawler Crane CC-1", ItemType.TOOL, null, null, null, null,
                specs("Lift capacity (t)", 250, "Owner", "Yard equipment"), 200, 0, "LY2");
        born("SPMT Transporter T-07", ItemType.TOOL, null, null, null, null,
                specs("Payload (t)", 600, "Axle lines", 12), 150, 0, "LY2", "GBY");
        born("Welding Generator WG-12", ItemType.TOOL, null, null, null, null,
                specs("Output (kVA)", 60), 90, 0, "TC");
        born("Hydraulic Jack Set HJ-4", ItemType.TOOL, null, null, null, null,
                specs("Capacity each (t)", 100, "Pieces in set", 4), 70, 0, "TC", "AH");
    }

    // ------------------------------------------------------------ helpers

    private void zone(String code, String name, String kind, String points) {
        zoneByCode.put(code, zones.save(new Zone(code, name, kind, points)));
    }

    private static String rect(int x1, int y1, int x2, int y2) {
        return x1 + "," + y1 + " " + x2 + "," + y1 + " " + x2 + "," + y2 + " " + x1 + "," + y2;
    }

    private static Map<String, Object> specs(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }

    private String json(Map<String, Object> specs) {
        try {
            return mapper.writeValueAsString(specs);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not write seed specs", e);
        }
    }

    private Instant daysAgo(int days) {
        return now.minus(days * 24L, ChronoUnit.HOURS);
    }

    /**
     * Creates an item in path[0] {@code fromDays} days ago, then walks it through the remaining zones,
     * spreading the moves evenly until {@code untilDays} days ago (0 = now).
     */
    private Item born(String name, ItemType type, Hull hull, String tag, Integer quantity, String unit,
                      Map<String, Object> specs, int fromDays, int untilDays, String... path) {
        Zone start = zoneByCode.get(path[0]);
        Instant created = daysAgo(fromDays);
        Item item = newItem(name, type, hull, tag, quantity, unit, specs, start, created);
        log(item, ActivityType.CREATED, null, start, "Added to the yard log", created);
        walk(item, fromDays, untilDays, path);
        return item;
    }

    /** Moves an existing item through path[1..], starting from path[0] (where it already is). */
    private void walk(Item item, int fromDays, int untilDays, String... path) {
        int n = path.length;
        long spanHours = (fromDays - untilDays) * 24L;
        long startHours = fromDays * 24L;
        for (int i = 1; i < n; i++) {
            Zone from = zoneByCode.get(path[i - 1]);
            Zone to = zoneByCode.get(path[i]);
            Instant at = now.minus(startHours - spanHours * i / n, ChronoUnit.HOURS);
            log(item, ActivityType.MOVED, from, to, MOVE_NOTES.getOrDefault(path[i], "Moved"), at);
            item.setZone(to);
            item.setUpdatedAt(at);
        }
        items.save(item);
    }

    /** Creates the parent in the given zone and marks the children as consumed by it. */
    private Item assemble(String name, ItemType type, Hull hull, String tag, Map<String, Object> specs,
                          String zoneCode, int daysAgo, Item... children) {
        Zone zone = zoneByCode.get(zoneCode);
        Instant at = daysAgo(daysAgo);
        Item parent = newItem(name, type, hull, tag, null, null, specs, zone, at);
        String names = Arrays.stream(children).map(Item::getName).collect(Collectors.joining(", "));
        log(parent, ActivityType.ASSEMBLED, null, zone, "Assembled from " + names, at);
        for (Item child : children) {
            Zone last = child.getZone();
            child.setStatus(ItemStatus.CONSUMED);
            child.setParent(parent);
            child.setUpdatedAt(at);
            items.save(child);
            log(child, ActivityType.CONSUMED, last, null, "Assembled into " + name, at);
        }
        return parent;
    }

    private Item newItem(String name, ItemType type, Hull hull, String tag, Integer quantity, String unit,
                         Map<String, Object> specs, Zone zone, Instant created) {
        Item item = new Item();
        item.setName(name);
        item.setType(type);
        item.setHull(hull);
        item.setZone(zone);
        item.setTag(tag);
        item.setQuantity(quantity);
        item.setUnit(unit);
        item.setSpecs(json(specs));
        item.setCreatedAt(created);
        item.setUpdatedAt(created);
        return items.save(item);
    }

    private void log(Item item, ActivityType type, Zone from, Zone to, String note, Instant at) {
        Activity a = new Activity();
        a.setItem(item);
        a.setType(type);
        a.setFromZone(from);
        a.setToZone(to);
        a.setActor(ACTORS[actorCounter++ % ACTORS.length]);
        a.setNote(note);
        a.setOccurredAt(at);
        activities.save(a);
    }
}
