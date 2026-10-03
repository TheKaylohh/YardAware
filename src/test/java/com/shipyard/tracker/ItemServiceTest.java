package com.shipyard.tracker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.shipyard.tracker.domain.ActivityType;
import com.shipyard.tracker.domain.ItemStatus;
import com.shipyard.tracker.domain.NodeLevel;
import com.shipyard.tracker.repo.ItemRepository;
import com.shipyard.tracker.repo.ZoneRepository;
import com.shipyard.tracker.service.Dtos.ActivityDto;
import com.shipyard.tracker.service.Dtos.AssembleRequest;
import com.shipyard.tracker.service.Dtos.ItemDto;
import com.shipyard.tracker.service.Dtos.MoveRequest;
import com.shipyard.tracker.service.Dtos.PhaseRequest;
import com.shipyard.tracker.service.Dtos.PlaceRequest;
import com.shipyard.tracker.service.Dtos.TreeNode;
import com.shipyard.tracker.service.Dtos.UpdateItemRequest;
import com.shipyard.tracker.service.ItemService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.server.ResponseStatusException;

/** Runs against an in-memory H2 database loaded by HierarchyLoader and DemoDataSeeder. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:servicetest;DB_CLOSE_DELAY=-1")
class ItemServiceTest {

    @Autowired
    ItemService service;
    @Autowired
    ItemRepository items;
    @Autowired
    ZoneRepository zones;

    private Long zoneId(String code) {
        return zones.findByCode(code).orElseThrow().getId();
    }

    private ItemDto item(String name) {
        return service.get(items.findByNameIgnoreCase(name).orElseThrow().getId());
    }

    private ItemDto place(String name, String zoneCode) {
        return service.place(item(name).id(), new PlaceRequest(zoneId(zoneCode), null));
    }

    private static ResponseStatusException rejected(Runnable action) {
        return assertThrows(ResponseStatusException.class, action::run);
    }

    @Test
    void demoYardHasTwoShipsAndEveryZoneIsBusy() {
        List<ItemDto> onMap = service.listOnMap().stream().filter(i -> !i.hullCode().equals("S043")).toList();
        assertTrue(onMap.size() >= 150, "tiles on the map: " + onMap.size());
        for (var zone : zones.findAll()) {
            long inZone = onMap.stream().filter(i -> zone.getId().equals(i.zoneId())).count();
            assertTrue(inZone >= 5, zone.getName() + " has only " + inZone + " items");
        }
        // One ship in each dry dock; S043 is still on paper.
        assertTrue(onMap.stream().anyMatch(i -> i.hullCode().equals("S041") && i.level() == NodeLevel.UNIT
                && i.zoneName().equals("Building Dock 1")));
        assertTrue(onMap.stream().anyMatch(i -> i.hullCode().equals("S042") && i.level() == NodeLevel.UNIT
                && i.zoneName().equals("Building Dock 2")));
        assertTrue(onMap.stream().noneMatch(i -> i.hullCode().equals("S041") && i.zoneName().equals("Building Dock 2")));
        // Work of every kind is going on: sections, blocks and units.
        for (NodeLevel level : List.of(NodeLevel.SECTION, NodeLevel.BLOCK, NodeLevel.UNIT)) {
            assertTrue(onMap.stream().anyMatch(i -> i.level() == level), level.toString());
        }

        ItemDto unit = onMap.stream().filter(i -> i.name().equals("S041-BA-U01")).findFirst().orElseThrow();
        assertEquals(NodeLevel.UNIT, unit.level());
        assertEquals("Building Dock 1", unit.zoneName());
        assertEquals("BA", unit.areaCode());
        assertEquals("Double Bottom", unit.areaName());
        assertEquals(8, unit.phaseNumber());
        assertEquals(4, unit.childCount());
    }

    @Test
    void everyHullGetsOneItemPerTrackedNode() {
        for (String hull : List.of("S041", "S042", "S043")) {
            assertEquals(31, count(hull, NodeLevel.UNIT), hull);
            assertEquals(82, count(hull, NodeLevel.BLOCK), hull);
            assertEquals(393, count(hull, NodeLevel.SECTION), hull);
        }
    }

    private long count(String hullCode, NodeLevel level) {
        return items.findAll().stream()
                .filter(i -> i.getHull().getCode().equals(hullCode) && i.getNode().getLevel() == level)
                .count();
    }

    @Test
    void consumedPiecesAreHiddenFromMapButKeptInTree() {
        assertTrue(service.listOnMap().stream().noneMatch(i -> i.name().equals("S041-BA-U01-B01-S01")));
        ItemDto consumed = item("S041-BA-U01-B01-S01");
        assertEquals(ItemStatus.CONSUMED, consumed.status());
        assertEquals("S041-BA-U01-B01", consumed.parentName());

        // BA-U01 is in the dock, so everything below it was assembled up the tree.
        TreeNode unit = service.tree(item("S041-BA-U01").id());
        assertEquals(ItemStatus.ACTIVE, unit.status());
        assertEquals(4, unit.children().size());
        assertTrue(unit.children().stream().allMatch(c -> c.status() == ItemStatus.CONSUMED));
        TreeNode b01 = unit.children().stream().filter(c -> c.name().equals("S041-BA-U01-B01")).findFirst().orElseThrow();
        assertEquals(7, b01.children().size());
        assertTrue(b01.children().stream().allMatch(c -> c.status() == ItemStatus.CONSUMED));
    }

    @Test
    void seededHistoryIsBackDatedAndTellsTheStory() {
        List<ActivityDto> history = service.activity(item("S041-BA-U01-B01-S01").id());
        assertEquals(ActivityType.CONSUMED, history.get(0).type());
        assertEquals(ActivityType.CREATED, history.get(history.size() - 1).type());
        assertTrue(history.stream().anyMatch(a -> a.type() == ActivityType.PLACED));
        assertTrue(history.stream().map(ActivityDto::actor).distinct().count() > 1, "more than one person worked on it");
        assertTrue(history.get(history.size() - 1).timestamp().isBefore(history.get(0).timestamp()));
        assertTrue(history.get(0).timestamp().isBefore(java.time.Instant.now().minusSeconds(3600)));
    }

    @Test
    void placingMovesAPlannedItemOntoTheMapAndLogsIt() {
        ItemDto before = item("S043-AA-U01-B01-S01");
        assertEquals(ItemStatus.PLANNED, before.status());
        assertEquals(null, before.zoneId());
        assertEquals(1, before.phaseNumber());

        ItemDto placed = place("S043-AA-U01-B01-S01", "PL");
        assertEquals(ItemStatus.ACTIVE, placed.status());
        assertEquals("Panel Line", placed.zoneName());
        assertTrue(service.listOnMap().stream().anyMatch(i -> i.name().equals("S043-AA-U01-B01-S01")));

        List<ActivityDto> history = service.activity(placed.id());
        assertEquals(ActivityType.PLACED, history.get(0).type());
        assertEquals(ActivityType.CREATED, history.get(history.size() - 1).type());
    }

    @Test
    void placingNeedsAZoneAndAPlannedItem() {
        ItemDto planned = item("S043-AA-U02-B01-S01");
        assertEquals("Choose a zone.", rejected(() -> service.place(planned.id(), new PlaceRequest(null, null))).getReason());
        assertTrue(rejected(() -> service.place(planned.id(), new PlaceRequest(999999L, null))).getReason().contains("doesn't exist"));

        place("S043-AA-U02-B01-S01", "PL");
        assertTrue(rejected(() -> place("S043-AA-U02-B01-S01", "AH")).getReason().contains("already on the yard"));
    }

    @Test
    void movingNeedsAnActiveItemInADifferentZone() {
        ItemDto planned = item("S043-AA-U03-B01-S01");
        assertTrue(rejected(() -> service.move(planned.id(), new MoveRequest(zoneId("AH"), null))).getReason().contains("Place it first"));

        ItemDto placed = place("S043-AA-U03-B01-S01", "PL");
        assertTrue(rejected(() -> service.move(placed.id(), new MoveRequest(zoneId("PL"), null))).getReason().contains("already in"));
        ItemDto moved = service.move(placed.id(), new MoveRequest(zoneId("AH"), "to the hall"));
        assertEquals("Assembly Hall", moved.zoneName());
        ActivityDto last = service.activity(placed.id()).get(0);
        assertEquals(ActivityType.MOVED, last.type());
        assertEquals("Panel Line", last.fromZone());
        assertEquals("Assembly Hall", last.toZone());

        ItemDto consumed = item("S041-BA-U01-B01-S01");
        assertTrue(rejected(() -> service.move(consumed.id(), new MoveRequest(zoneId("AH"), null))).getReason().contains("assembled into"));
    }

    @Test
    void phaseMustBeOnTheItemsOwnRoute() {
        ItemDto section = place("S043-BA-U01-B04-S01", "PL"); // route 1 -> 2 -> 3, starts in phase 1
        assertEquals(1, section.phaseNumber());

        assertTrue(rejected(() -> service.changePhase(section.id(), new PhaseRequest(7, null))).getReason().contains("isn't on the route"));
        assertTrue(rejected(() -> service.changePhase(section.id(), new PhaseRequest(1, null))).getReason().contains("already in phase 1"));
        assertEquals("Choose a phase.", rejected(() -> service.changePhase(section.id(), new PhaseRequest(null, null))).getReason());

        ItemDto advanced = service.changePhase(section.id(), new PhaseRequest(2, "welded"));
        assertEquals(2, advanced.phaseNumber());
        assertEquals("Section assembly", advanced.phaseName());
        ActivityDto last = service.activity(section.id()).get(0);
        assertEquals(ActivityType.PHASE_CHANGED, last.type());
        assertTrue(last.note().contains("welded"));
    }

    @Test
    void assemblingJoinsAllPiecesIntoThePlannedParent() {
        // BA-U01-B02 is made of three sections (the seeder only places S041's and S042's).
        ItemDto s1 = place("S043-BA-U01-B02-S01", "PL");
        ItemDto s2 = place("S043-BA-U01-B02-S02", "PL");
        ItemDto s3 = place("S043-BA-U01-B02-S03", "PL");

        ItemDto block = service.assemble(new AssembleRequest(List.of(s1.id(), s2.id(), s3.id()), zoneId("AH"), "closed up"));

        assertEquals("S043-BA-U01-B02", block.name());
        assertEquals(ItemStatus.ACTIVE, block.status());
        assertEquals("Assembly Hall", block.zoneName());
        assertEquals(4, block.phaseNumber());
        assertEquals(3, block.childCount());
        assertTrue(service.listOnMap().stream().anyMatch(i -> i.name().equals("S043-BA-U01-B02")));
        assertTrue(service.listOnMap().stream().noneMatch(i -> i.name().equals("S043-BA-U01-B02-S01")));

        ItemDto consumed = service.get(s1.id());
        assertEquals(ItemStatus.CONSUMED, consumed.status());
        assertEquals(block.id(), consumed.parentId());
        assertEquals(ActivityType.CONSUMED, service.activity(s1.id()).get(0).type());
        assertEquals(ActivityType.ASSEMBLED, service.activity(block.id()).get(0).type());
    }

    @Test
    void assemblyNeedsEveryPieceOfOneParentOnTheYard() {
        ItemDto s1 = place("S043-CA-U01-B01-S01", "PL");
        ItemDto s2 = place("S043-CA-U01-B01-S02", "PL");
        ItemDto other = place("S043-CA-U01-B02-S01", "PL");

        assertEquals("Select at least two items to assemble.",
                rejected(() -> service.assemble(new AssembleRequest(List.of(s1.id()), null, null))).getReason());

        String incomplete = rejected(() -> service.assemble(new AssembleRequest(List.of(s1.id(), s2.id()), null, null))).getReason();
        assertTrue(incomplete.contains("S043-CA-U01-B01 is made of"), incomplete);
        assertTrue(incomplete.contains("Missing: S043-CA-U01-B01-S03"), incomplete);

        assertTrue(rejected(() -> service.assemble(new AssembleRequest(List.of(s1.id(), other.id()), null, null)))
                .getReason().contains("different parents"));

        ItemDto notPlaced = item("S043-CA-U01-B01-S03");
        assertTrue(rejected(() -> service.assemble(new AssembleRequest(List.of(s1.id(), notPlaced.id()), null, null)))
                .getReason().contains("isn't on the yard yet"));

        ItemDto consumed = item("S041-BA-U01-B01-S01");
        assertTrue(rejected(() -> service.assemble(new AssembleRequest(List.of(s1.id(), consumed.id()), null, null)))
                .getReason().contains("already been assembled"));
    }

    @Test
    void unitsAreTheTopOfTheHierarchy() {
        ItemDto unit = place("S043-FA-U01", "GBY");
        ItemDto unit2 = place("S043-FA-U02", "GBY");
        assertTrue(rejected(() -> service.assemble(new AssembleRequest(List.of(unit.id(), unit2.id()), null, null)))
                .getReason().contains("top of the hierarchy"));
    }

    @Test
    void piecesCannotBePlacedOnceTheirParentIsBuilt() {
        // S041-BA-U01-B01 was assembled by the seeder, so S042's copy is unaffected but S041's sections are consumed.
        ItemDto consumed = item("S041-BA-U01-B01-S01");
        assertTrue(rejected(() -> service.place(consumed.id(), new PlaceRequest(zoneId("PL"), null))).getReason().contains("already on the yard"));
    }

    @Test
    void onlySpecsAreEditableAndChangesAreLogged() {
        ItemDto placed = place("S043-HA-U01-B01-S01", "PL");
        ItemDto edited = service.update(placed.id(), new UpdateItemRequest(Map.of("Weight (t)", 18.4), "weighed"));
        assertEquals(18.4, ((Number) edited.specs().get("Weight (t)")).doubleValue());
        assertEquals(ActivityType.EDITED, service.activity(placed.id()).get(0).type());

        int events = service.activity(placed.id()).size();
        service.update(placed.id(), new UpdateItemRequest(Map.of("Weight (t)", 18.4), null)); // unchanged: no new event
        assertEquals(events, service.activity(placed.id()).size());
    }

    @Test
    void plannedItemsCanBeListedPerHull() {
        Long hullId = item("S043-AA-U01").hullId();
        List<ItemDto> planned = service.list(ItemStatus.PLANNED, hullId);
        assertTrue(planned.size() > 400);
        assertTrue(planned.stream().allMatch(i -> i.status() == ItemStatus.PLANNED && i.hullId().equals(hullId)));
        assertFalse(planned.isEmpty());
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> service.get(999999L)).getStatusCode().value());
    }
}
