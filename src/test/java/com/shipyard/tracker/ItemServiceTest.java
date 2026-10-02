package com.shipyard.tracker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.shipyard.tracker.domain.ActivityType;
import com.shipyard.tracker.domain.ItemStatus;
import com.shipyard.tracker.domain.ItemType;
import com.shipyard.tracker.repo.HullRepository;
import com.shipyard.tracker.repo.ZoneRepository;
import com.shipyard.tracker.service.Dtos.ActivityDto;
import com.shipyard.tracker.service.Dtos.AssembleRequest;
import com.shipyard.tracker.service.Dtos.CreateItemRequest;
import com.shipyard.tracker.service.Dtos.ItemDto;
import com.shipyard.tracker.service.Dtos.MoveRequest;
import com.shipyard.tracker.service.Dtos.TreeNode;
import com.shipyard.tracker.service.ItemService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.server.ResponseStatusException;

/** Runs against an in-memory H2 database seeded by DemoDataSeeder. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:servicetest;DB_CLOSE_DELAY=-1")
class ItemServiceTest {

    @Autowired
    ItemService service;
    @Autowired
    HullRepository hulls;
    @Autowired
    ZoneRepository zones;

    private Long hullId(String code) {
        return hulls.findByCode(code).orElseThrow().getId();
    }

    private Long zoneId(String code) {
        return zones.findByCode(code).orElseThrow().getId();
    }

    private ItemDto create(String name, ItemType type, String hullCode, String zoneCode) {
        return service.create(new CreateItemRequest(name, type, hullCode == null ? null : hullId(hullCode),
                zoneId(zoneCode), null, null, null, null, null));
    }

    private ItemDto onMap(String name) {
        return service.listOnMap().stream().filter(i -> i.name().equals(name)).findFirst().orElseThrow();
    }

    private static ResponseStatusException rejected(Runnable action) {
        return assertThrows(ResponseStatusException.class, action::run);
    }

    @Test
    void seedDataIsLoaded() {
        assertTrue(service.listOnMap().size() >= 24);
        ItemDto grandBlock = onMap("S041-EA500");
        assertEquals("EA500", grandBlock.tag());
        assertEquals("Engine room, level 5", grandBlock.tagDescription());
        assertEquals("Building Dock", grandBlock.zoneName());
        assertEquals("S041", grandBlock.hullCode());
        assertEquals(3, grandBlock.childCount());
    }

    @Test
    void consumedComponentsAreHiddenFromMapButKeptInTree() {
        assertTrue(service.listOnMap().stream().noneMatch(i -> i.name().equals("S041-EA510")));
        TreeNode root = service.tree(onMap("S041-EA500").id());
        assertEquals(3, root.children().size());
        TreeNode block = root.children().stream().filter(c -> c.name().equals("S041-EA510")).findFirst().orElseThrow();
        assertEquals(ItemStatus.CONSUMED, block.status());
        assertEquals(2, block.children().size());
        TreeNode section = block.children().stream().filter(c -> c.name().equals("S041-EA511")).findFirst().orElseThrow();
        assertEquals(2, section.children().size());
    }

    @Test
    void assemblingConsumesChildrenAndCreatesParent() {
        ItemDto a = create("T-ASM-A", ItemType.SUB_ASSEMBLY, "S042", "PL");
        ItemDto b = create("T-ASM-B", ItemType.SUB_ASSEMBLY, "S042", "PL");

        ItemDto parent = service.assemble(new AssembleRequest(
                List.of(a.id(), b.id()), "T-ASM-SECTION", ItemType.SECTION, null, zoneId("AH"), null, "test"));

        assertEquals("S042", parent.hullCode());
        assertEquals("Assembly Hall", parent.zoneName());
        assertEquals(2, parent.childCount());
        assertTrue(service.listOnMap().stream().anyMatch(i -> i.name().equals("T-ASM-SECTION")));
        assertTrue(service.listOnMap().stream().noneMatch(i -> i.name().equals("T-ASM-A") || i.name().equals("T-ASM-B")));

        ItemDto consumed = service.get(a.id());
        assertEquals(ItemStatus.CONSUMED, consumed.status());
        assertEquals(parent.id(), consumed.parentId());

        List<ActivityDto> childHistory = service.activity(a.id());
        assertEquals(ActivityType.CONSUMED, childHistory.get(0).type());
        assertEquals(ActivityType.CREATED, childHistory.get(childHistory.size() - 1).type());
        assertEquals(ActivityType.ASSEMBLED, service.activity(parent.id()).get(0).type());
    }

    @Test
    void consumedItemsCannotBeMovedOrReassembled() {
        ItemDto a = create("T-CON-A", ItemType.SUB_ASSEMBLY, "S043", "PL");
        ItemDto b = create("T-CON-B", ItemType.SUB_ASSEMBLY, "S043", "PL");
        ItemDto c = create("T-CON-C", ItemType.SUB_ASSEMBLY, "S043", "PL");
        service.assemble(new AssembleRequest(List.of(a.id(), b.id()), "T-CON-SEC", ItemType.SECTION, null, null, null, null));

        ResponseStatusException move = rejected(() -> service.move(a.id(), new MoveRequest(zoneId("LY1"), null)));
        assertEquals(400, move.getStatusCode().value());
        ResponseStatusException again = rejected(() -> service.assemble(new AssembleRequest(
                List.of(a.id(), c.id()), "T-CON-SEC-2", ItemType.SECTION, null, null, null, null)));
        assertTrue(again.getReason().contains("already been assembled"));
    }

    @Test
    void assemblyRejectsMixedHullsToolsAndSingleItems() {
        ItemDto s041 = onMap("S041-HA610");
        ItemDto s043 = onMap("S043-SA-001");
        ItemDto tool = onMap("Crawler Crane CC-1");

        ResponseStatusException mixed = rejected(() -> service.assemble(new AssembleRequest(
                List.of(s041.id(), s043.id()), "T-MIXED", ItemType.BLOCK, null, null, null, null)));
        assertTrue(mixed.getReason().contains("different hulls"));

        ResponseStatusException withTool = rejected(() -> service.assemble(new AssembleRequest(
                List.of(s041.id(), tool.id()), "T-TOOL", ItemType.BLOCK, null, null, null, null)));
        assertTrue(withTool.getReason().contains("tool"));

        ResponseStatusException single = rejected(() -> service.assemble(new AssembleRequest(
                List.of(s041.id()), "T-ONE", ItemType.BLOCK, null, null, null, null)));
        assertTrue(single.getReason().contains("at least two"));

        assertFalse(service.listOnMap().stream().anyMatch(i -> i.name().startsWith("T-MIXED")));
    }

    @Test
    void grandBlocksNeedAValidTagAndGetAGeneratedName() {
        ResponseStatusException noTag = rejected(() -> service.create(new CreateItemRequest(
                "T-GB-NOTAG", ItemType.GRAND_BLOCK, hullId("S042"), zoneId("GBY"), "", null, null, null, null)));
        assertTrue(noTag.getReason().contains("area tag"));

        ResponseStatusException badTag = rejected(() -> service.create(new CreateItemRequest(
                "T-GB-BAD", ItemType.GRAND_BLOCK, hullId("S042"), zoneId("GBY"), "ZZ999", null, null, null, null)));
        assertTrue(badTag.getReason().contains("area code"));

        ItemDto made = service.create(new CreateItemRequest(
                "", ItemType.GRAND_BLOCK, hullId("S042"), zoneId("GBY"), " da700 ", null, null, null, null));
        assertEquals("S042-DA700", made.name());
        assertEquals("DA700", made.tag());
        assertEquals("Deck area, level 7", made.tagDescription());
    }

    @Test
    void namesMustBeUnique() {
        create("T-UNIQUE", ItemType.UNIT, "S041", "OS");
        ResponseStatusException dup = rejected(() -> create("t-unique", ItemType.UNIT, "S041", "OS"));
        assertTrue(dup.getReason().contains("already exists"));
    }

    @Test
    void batchesNeedAQuantity() {
        ResponseStatusException noQty = rejected(() -> service.create(new CreateItemRequest(
                "T-BATCH-BAD", ItemType.PIPE_OUTFITTING, hullId("S041"), zoneId("PS"), null, null, null, null, null)));
        assertTrue(noQty.getReason().contains("quantity"));

        ItemDto ok = service.create(new CreateItemRequest(
                "T-BATCH", ItemType.PIPE_OUTFITTING, hullId("S041"), zoneId("PS"), null, 25, null, null, null));
        assertEquals(25, ok.quantity());
        assertEquals("pcs", ok.unit());
    }

    @Test
    void movingLogsHistoryAndRejectsSameZone() {
        ItemDto item = create("T-MOVE", ItemType.SECTION, "S041", "PL");
        ItemDto moved = service.move(item.id(), new MoveRequest(zoneId("LY1"), "staging"));
        assertEquals("Laydown Yard 1", moved.zoneName());

        ActivityDto latest = service.activity(item.id()).get(0);
        assertEquals(ActivityType.MOVED, latest.type());
        assertEquals("Panel Line", latest.fromZone());
        assertEquals("Laydown Yard 1", latest.toZone());
        assertEquals("staging", latest.note());

        ResponseStatusException same = rejected(() -> service.move(item.id(), new MoveRequest(zoneId("LY1"), null)));
        assertTrue(same.getReason().contains("already in"));
    }
}
