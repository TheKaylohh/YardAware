package com.shipyard.tracker.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shipyard.tracker.domain.Area;
import com.shipyard.tracker.domain.DesignBasis;
import com.shipyard.tracker.domain.HierarchyNode;
import com.shipyard.tracker.domain.NodeLevel;
import com.shipyard.tracker.domain.Phase;
import com.shipyard.tracker.repo.AreaRepository;
import com.shipyard.tracker.repo.DesignBasisRepository;
import com.shipyard.tracker.repo.HierarchyNodeRepository;
import com.shipyard.tracker.repo.PhaseRepository;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Loads the production plan into H2 on first start: the 11 phases, the areas, the whole hierarchy and the design basis.
 * The data comes from {@code seed/aloha-hierarchy.json}, which {@code tools/xlsx_to_seed.py} generates from the
 * Aloha class workbook. This is the structure of the ship, not demo data, so it loads even when
 * {@code shipyard.seed-demo-data=false}. It only runs when the phases table is empty.
 */
@Component
@Order(1)
public class HierarchyLoader implements ApplicationRunner {

    static final String RESOURCE = "seed/aloha-hierarchy.json";

    /** Shape of the JSON file. Field names match the keys written by tools/xlsx_to_seed.py. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record SeedFile(List<PhaseRow> phases, List<DesignBasisRow> designBasis, List<Row> rows) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PhaseRow(int number, String name, String facility, String level) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DesignBasisRow(String item, String value, String status, String source) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Row(String level, String areaCode, String area, String unit, String block, String section, String band,
               String latitude, String side, Integer phase, String phaseRoute, String facility,
               Integer erectionOrder, Boolean outfitHeavy, String function, String confidence, String note) {
    }

    private final PhaseRepository phases;
    private final AreaRepository areas;
    private final HierarchyNodeRepository nodes;
    private final DesignBasisRepository designBasis;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;

    public HierarchyLoader(PhaseRepository phases, AreaRepository areas, HierarchyNodeRepository nodes,
                           DesignBasisRepository designBasis, TransactionTemplate tx, ObjectMapper mapper) {
        this.phases = phases;
        this.areas = areas;
        this.nodes = nodes;
        this.designBasis = designBasis;
        this.tx = tx;
        this.mapper = mapper;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        if (phases.count() > 0) {
            return;
        }
        SeedFile seed;
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            seed = mapper.readValue(in, SeedFile.class);
        }
        tx.executeWithoutResult(status -> load(seed));
    }

    private void load(SeedFile seed) {
        Map<Integer, Phase> phaseByNumber = new HashMap<>();
        for (PhaseRow p : seed.phases()) {
            phaseByNumber.put(p.number(), phases.save(new Phase(p.number(), p.name(), p.facility(), p.level())));
        }

        int order = 0;
        for (DesignBasisRow d : seed.designBasis()) {
            designBasis.save(new DesignBasis(d.item(), d.value(), d.status(), d.source(), order++));
        }

        Map<String, Area> areaByCode = new HashMap<>();
        Map<String, HierarchyNode> nodeByCode = new HashMap<>();
        List<HierarchyNode> toSave = new ArrayList<>();
        order = 0;
        for (Row r : seed.rows()) {
            order++;
            if ("AREA".equals(r.level())) {
                areaByCode.put(r.areaCode(), areas.save(
                        new Area(r.areaCode(), r.area(), r.function(), r.confidence(), r.note(), order)));
                continue;
            }
            NodeLevel level = NodeLevel.valueOf(r.level());
            HierarchyNode node = new HierarchyNode();
            node.setLevel(level);
            node.setCode(codeOf(r, level));
            node.setParent(switch (level) {
                case BLOCK, PROCESS -> nodeByCode.get(r.unit());
                case SECTION -> nodeByCode.get(r.block());
                default -> null;
            });
            node.setArea(level == NodeLevel.VESSEL ? null : areaByCode.get(r.areaCode()));
            node.setBand(r.band());
            node.setLatitude(r.latitude());
            node.setSide(r.side());
            node.setPrimaryPhase(phaseByNumber.get(r.phase()));
            node.setPhaseRoute(r.phaseRoute());
            node.setAssemblyFacility(r.facility());
            node.setErectionOrder(r.erectionOrder());
            node.setOutfitHeavy(r.outfitHeavy());
            node.setFunction(r.function());
            node.setConfidence(r.confidence());
            node.setNote(r.note());
            node.setSortOrder(order);
            nodeByCode.put(node.getCode(), node);
            toSave.add(node);
        }
        // Parents come before children in the sheet, so saving in order keeps every foreign key valid.
        nodes.saveAll(toSave);
    }

    /** Units, blocks and sections carry their own ID in the sheet. Milestones get one built from unit and phase. */
    static String codeOf(Row r, NodeLevel level) {
        return switch (level) {
            case UNIT -> r.unit();
            case BLOCK -> r.block();
            case SECTION -> r.section();
            case PROCESS -> r.unit() + String.format("-P%02d", r.phase());
            case VESSEL -> String.format("VSL-P%02d", r.phase());
        };
    }
}
