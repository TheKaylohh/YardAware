package com.shipyard.tracker.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * One row of the production hierarchy: the plan, shared by every hull of the class.
 * Area -> Unit (grand block) -> Block -> Section, plus unit-level (phases 7-9) and vessel-level (phases 10-11) milestones.
 * Per-hull progress lives in {@link Item}, which points at one of these.
 */
@Entity
@Table(name = "hierarchy_nodes", indexes = @Index(name = "idx_node_parent", columnList = "parent_id"))
public class HierarchyNode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "node_level", nullable = false)
    private NodeLevel level;

    /** Planning ID, e.g. AA-U01, AA-U01-B02, AA-U01-B02-S03. Milestones: AA-U01-P07 and VSL-P10. */
    @Column(nullable = false, unique = true)
    private String code;

    /** Block -> its unit, section -> its block, unit milestone -> its unit. Null for units and vessel milestones. */
    @ManyToOne
    private HierarchyNode parent;

    /** Null for the whole-vessel milestones. */
    @ManyToOne
    private Area area;

    /** Longitudinal band, F00 (aft) to F09 (forward). */
    @Column(name = "long_band")
    private String band;

    /** LAT01-LAT07, sections only. */
    private String latitude;

    /** PORT, CENTER, STBD, or a combination such as PORT/CTR/STBD for blocks. */
    private String side;

    @ManyToOne(optional = false)
    @JoinColumn(name = "primary_phase_id")
    private Phase primaryPhase;

    /** Phases the node passes through in order, as written in the workbook: "1 → 2 → 3". */
    private String phaseRoute;

    private String assemblyFacility;

    /** Dock erection order, units only. */
    private Integer erectionOrder;

    private Boolean outfitHeavy;

    @Column(name = "function_desc", length = 300)
    private String function;

    @Column(length = 300)
    private String confidence;

    @Column(length = 600)
    private String note;

    /** Row order in the workbook. */
    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    public Long getId() { return id; }
    public NodeLevel getLevel() { return level; }
    public void setLevel(NodeLevel level) { this.level = level; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public HierarchyNode getParent() { return parent; }
    public void setParent(HierarchyNode parent) { this.parent = parent; }
    public Area getArea() { return area; }
    public void setArea(Area area) { this.area = area; }
    public String getBand() { return band; }
    public void setBand(String band) { this.band = band; }
    public String getLatitude() { return latitude; }
    public void setLatitude(String latitude) { this.latitude = latitude; }
    public String getSide() { return side; }
    public void setSide(String side) { this.side = side; }
    public Phase getPrimaryPhase() { return primaryPhase; }
    public void setPrimaryPhase(Phase primaryPhase) { this.primaryPhase = primaryPhase; }
    public String getPhaseRoute() { return phaseRoute; }
    public void setPhaseRoute(String phaseRoute) { this.phaseRoute = phaseRoute; }
    public String getAssemblyFacility() { return assemblyFacility; }
    public void setAssemblyFacility(String assemblyFacility) { this.assemblyFacility = assemblyFacility; }
    public Integer getErectionOrder() { return erectionOrder; }
    public void setErectionOrder(Integer erectionOrder) { this.erectionOrder = erectionOrder; }
    public Boolean getOutfitHeavy() { return outfitHeavy; }
    public void setOutfitHeavy(Boolean outfitHeavy) { this.outfitHeavy = outfitHeavy; }
    public String getFunction() { return function; }
    public void setFunction(String function) { this.function = function; }
    public String getConfidence() { return confidence; }
    public void setConfidence(String confidence) { this.confidence = confidence; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
}
