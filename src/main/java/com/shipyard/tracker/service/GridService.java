package com.shipyard.tracker.service;

import com.shipyard.tracker.domain.HierarchyNode;
import com.shipyard.tracker.domain.Item;
import com.shipyard.tracker.domain.Phase;
import com.shipyard.tracker.domain.PhaseRoute;
import com.shipyard.tracker.domain.Zone;
import com.shipyard.tracker.repo.ItemRepository;
import com.shipyard.tracker.repo.PhaseRepository;
import com.shipyard.tracker.repo.ZoneRepository;
import com.shipyard.tracker.service.BulkDtos.GridRow;
import com.shipyard.tracker.service.Dtos.PhaseDto;
import com.shipyard.tracker.service.Dtos.ZoneDto;
import java.io.IOException;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads every item for the data grid and the Excel export. Changes go through {@link BulkItemService}. */
@Service
public class GridService {

    private final ItemRepository items;
    private final ZoneRepository zones;
    private final PhaseRepository phases;

    public GridService(ItemRepository items, ZoneRepository zones, PhaseRepository phases) {
        this.items = items;
        this.zones = zones;
        this.phases = phases;
    }

    @Transactional(readOnly = true)
    public List<GridRow> rows() {
        return items.findAllForGrid().stream().map(GridService::toRow).toList();
    }

    /** The whole grid as an .xlsx workbook, with the zone and phase lists the importer understands. */
    @Transactional(readOnly = true)
    public byte[] exportWorkbook() throws IOException {
        List<ZoneDto> zoneList = zones.findAllByOrderByName().stream()
                .map(z -> new ZoneDto(z.getId(), z.getCode(), z.getName(), z.getKind(), z.getFacility(), z.getPoints()))
                .toList();
        List<PhaseDto> phaseList = phases.findAllByOrderByNumber().stream()
                .map(p -> new PhaseDto(p.getNumber(), p.getName(), p.getFacility(), p.getAppliesTo()))
                .toList();
        return ItemSheets.write(rows(), zoneList, phaseList);
    }

    private static GridRow toRow(Item i) {
        HierarchyNode n = i.getNode();
        Zone z = i.getZone();
        Phase ph = i.getPhase();
        Item p = i.getParent();
        return new GridRow(
                i.getId(),
                i.getName(),
                i.getHull().getCode(),
                n.getLevel().getLabel(),
                n.getArea() == null ? "" : n.getArea().getCode(),
                p == null ? "" : p.getName(),
                i.getStatus().name(),
                z == null ? "" : z.getCode(),
                ph.getNumber(),
                ph.getName(),
                n.getPhaseRoute() == null ? "" : n.getPhaseRoute(),
                PhaseRoute.parse(n.getPhaseRoute()),
                i.getSpecs() == null ? "" : i.getSpecs(),
                i.getVersion());
    }
}
