package com.shipyard.tracker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.shipyard.tracker.domain.HierarchyNode;
import com.shipyard.tracker.domain.NodeLevel;
import com.shipyard.tracker.repo.AreaRepository;
import com.shipyard.tracker.repo.DesignBasisRepository;
import com.shipyard.tracker.repo.HierarchyNodeRepository;
import com.shipyard.tracker.repo.PhaseRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The plan in H2 must match Aloha_Class_Production_Hierarchy_v2.xlsx (its Summary sheet:
 * 9 areas, 31 units, 82 blocks, 393 sections, 93 unit-level and 2 vessel-level process rows = 610 rows).
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:seedtest;DB_CLOSE_DELAY=-1")
class SeedDataTest {

    @Autowired
    PhaseRepository phases;
    @Autowired
    AreaRepository areas;
    @Autowired
    HierarchyNodeRepository nodes;
    @Autowired
    DesignBasisRepository designBasis;

    @Test
    void rowCountsMatchTheWorkbook() {
        assertEquals(11, phases.count());
        assertEquals(9, areas.count());
        assertEquals(11, designBasis.count());
        assertEquals(31, nodes.countByLevel(NodeLevel.UNIT));
        assertEquals(82, nodes.countByLevel(NodeLevel.BLOCK));
        assertEquals(393, nodes.countByLevel(NodeLevel.SECTION));
        assertEquals(93, nodes.countByLevel(NodeLevel.PROCESS));
        assertEquals(2, nodes.countByLevel(NodeLevel.VESSEL));
        assertEquals(610, areas.count() + nodes.count());
    }

    @Test
    void phasesAreTheElevenProductionPhases() {
        var all = phases.findAllByOrderByNumber();
        assertEquals("Prefabrication", all.get(0).getName());
        assertEquals("A0 / B0 / C0", all.get(0).getFacility());
        assertEquals("Testing and sea trials", all.get(10).getName());
        assertEquals("H0 / sea-trial berth", all.get(10).getFacility());
    }

    @Test
    void hierarchyLinksFollowTheSheet() {
        HierarchyNode unit = nodes.findByCode("BA-U01").orElseThrow();
        assertEquals(NodeLevel.UNIT, unit.getLevel());
        assertEquals("BA", unit.getArea().getCode());
        assertEquals("Double Bottom", unit.getArea().getName());
        assertEquals(1, unit.getErectionOrder()); // first dock-mounted grand block
        assertEquals("6 → 7 → 8 → 9", unit.getPhaseRoute());
        assertEquals("F01-F02", unit.getBand());
        assertNull(unit.getParent());

        HierarchyNode block = nodes.findByCode("BA-U01-B01").orElseThrow();
        assertEquals("BA-U01", block.getParent().getCode());
        assertEquals("4 → 5", block.getPhaseRoute());
        assertEquals(Boolean.TRUE, nodes.findByCode("BA-U01-B02").orElseThrow().getOutfitHeavy()); // B02 is outfit-heavy (Y) in the sheet

        HierarchyNode section = nodes.findByCode("BA-U01-B01-S01").orElseThrow();
        assertEquals("BA-U01-B01", section.getParent().getCode());
        assertEquals("LAT01", section.getLatitude());
        assertEquals("PORT", section.getSide());
        assertEquals(2, section.getPrimaryPhase().getNumber());

        HierarchyNode milestone = nodes.findByCode("BA-U01-P07").orElseThrow();
        assertEquals(NodeLevel.PROCESS, milestone.getLevel());
        assertEquals("BA-U01", milestone.getParent().getCode());
        assertEquals("Unit outfitting", milestone.getPrimaryPhase().getName());

        HierarchyNode vessel = nodes.findByCode("VSL-P10").orElseThrow();
        assertEquals(NodeLevel.VESSEL, vessel.getLevel());
        assertNull(vessel.getArea());
        assertNull(vessel.getParent());
    }

    @Test
    void everyNodeHasItsAreaAndPhase() {
        List<HierarchyNode> all = nodes.findAll();
        for (HierarchyNode n : all) {
            assertNotNull(n.getPrimaryPhase(), n.getCode());
            if (n.getLevel() != NodeLevel.VESSEL) {
                assertNotNull(n.getArea(), n.getCode());
            }
            if (n.getLevel() == NodeLevel.SECTION || n.getLevel() == NodeLevel.BLOCK || n.getLevel() == NodeLevel.PROCESS) {
                assertNotNull(n.getParent(), n.getCode());
            }
        }
        assertTrue(all.stream().map(HierarchyNode::getCode).distinct().count() == all.size());
    }
}
