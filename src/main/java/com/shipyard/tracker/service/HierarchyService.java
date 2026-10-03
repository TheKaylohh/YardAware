package com.shipyard.tracker.service;

import com.shipyard.tracker.domain.Area;
import com.shipyard.tracker.domain.HierarchyNode;
import com.shipyard.tracker.domain.NodeLevel;
import com.shipyard.tracker.domain.Phase;
import com.shipyard.tracker.repo.AreaRepository;
import com.shipyard.tracker.repo.DesignBasisRepository;
import com.shipyard.tracker.repo.HierarchyNodeRepository;
import com.shipyard.tracker.repo.PhaseRepository;
import com.shipyard.tracker.service.Dtos.AreaDto;
import com.shipyard.tracker.service.Dtos.DesignBasisDto;
import com.shipyard.tracker.service.Dtos.LevelInfo;
import com.shipyard.tracker.service.Dtos.Meta;
import com.shipyard.tracker.service.Dtos.NodeDto;
import com.shipyard.tracker.service.Dtos.PhaseDto;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only access to the plan: phases, areas, the hierarchy itself and the design basis. */
@Service
@Transactional(readOnly = true)
public class HierarchyService {

    private final PhaseRepository phases;
    private final AreaRepository areas;
    private final HierarchyNodeRepository nodes;
    private final DesignBasisRepository designBasis;

    public HierarchyService(PhaseRepository phases, AreaRepository areas, HierarchyNodeRepository nodes,
                            DesignBasisRepository designBasis) {
        this.phases = phases;
        this.areas = areas;
        this.nodes = nodes;
        this.designBasis = designBasis;
    }

    public Meta meta() {
        List<LevelInfo> levels = Stream.of(NodeLevel.values())
                .map(l -> new LevelInfo(l.name(), l.getLabel(), l.isTracked()))
                .toList();
        return new Meta(levels, areas(), phases());
    }

    public List<PhaseDto> phases() {
        return phases.findAllByOrderByNumber().stream().map(HierarchyService::toDto).toList();
    }

    public List<AreaDto> areas() {
        return areas.findAllByOrderBySortOrder().stream().map(HierarchyService::toDto).toList();
    }

    public List<DesignBasisDto> designBasis() {
        return designBasis.findAllByOrderBySortOrder().stream()
                .map(d -> new DesignBasisDto(d.getItem(), d.getValue(), d.getStatus(), d.getSource()))
                .toList();
    }

    /** The plan rows, optionally limited to one area (two-letter code, case-insensitive). */
    public List<NodeDto> nodes(String areaCode) {
        List<HierarchyNode> list = areaCode == null || areaCode.isBlank()
                ? nodes.findAllByOrderBySortOrder()
                : nodes.findByAreaCodeOrderBySortOrder(areaCode.trim().toUpperCase());
        return list.stream().map(HierarchyService::toDto).toList();
    }

    static PhaseDto toDto(Phase p) {
        return new PhaseDto(p.getNumber(), p.getName(), p.getFacility(), p.getAppliesTo());
    }

    static AreaDto toDto(Area a) {
        return new AreaDto(a.getCode(), a.getName(), a.getFunction(), a.getConfidence(), a.getNote());
    }

    static NodeDto toDto(HierarchyNode n) {
        Area a = n.getArea();
        return new NodeDto(
                n.getId(),
                n.getCode(),
                n.getLevel(),
                n.getLevel().getLabel(),
                n.getParent() == null ? null : n.getParent().getCode(),
                a == null ? null : a.getCode(),
                a == null ? null : a.getName(),
                n.getBand(),
                n.getLatitude(),
                n.getSide(),
                n.getPrimaryPhase().getNumber(),
                n.getPrimaryPhase().getName(),
                n.getPhaseRoute(),
                n.getAssemblyFacility(),
                n.getErectionOrder(),
                n.getOutfitHeavy(),
                n.getFunction(),
                n.getConfidence(),
                n.getNote());
    }
}
