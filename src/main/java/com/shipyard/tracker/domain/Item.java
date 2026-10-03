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
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import org.hibernate.annotations.ColumnDefault;

/**
 * A hull's copy of one tracked node of the hierarchy: "unit BA-U01 of hull S041".
 * The structure (what it is, which area, band, side, phase route, parent) comes from the {@link HierarchyNode};
 * the item only carries what changes: status, location, current phase and specs.
 */
@Entity
@Table(name = "items",
        uniqueConstraints = @UniqueConstraint(name = "uk_item_hull_node", columnNames = {"hull_id", "node_id"}),
        indexes = {@Index(name = "idx_item_status", columnList = "status"), @Index(name = "idx_item_parent", columnList = "parent_id")})
public class Item {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Hull code plus node code, e.g. S041-BA-U01-B02. Unique. */
    @Column(nullable = false, unique = true)
    private String name;

    @ManyToOne(optional = false)
    private Hull hull;

    @ManyToOne(optional = false)
    private HierarchyNode node;

    /** This hull's item for the node's parent (section -> block -> unit). Fixed by the plan. Null for units. */
    @ManyToOne
    private Item parent;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ItemStatus status = ItemStatus.PLANNED;

    /** Current location. Null while PLANNED. For consumed items this is where they were when joined. */
    @ManyToOne
    private Zone zone;

    /** Production phase the item is in now. Starts at the first phase of the node's route. */
    @ManyToOne(optional = false)
    @JoinColumn(name = "phase_id")
    private Phase phase;

    /** Flexible specs (weight, drawing number ...) stored as a JSON object. */
    @Column(length = 4000)
    private String specs;

    private Instant createdAt;
    private Instant updatedAt;

    /**
     * Optimistic lock. If two people change the same item at once (or assemble the same piece into two parents),
     * the second save fails with a conflict instead of silently overwriting the first.
     * The default lets ddl-auto=update add the column to an existing dev database.
     */
    @Version
    @ColumnDefault("0")
    @Column(nullable = false)
    private long version;

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Hull getHull() { return hull; }
    public void setHull(Hull hull) { this.hull = hull; }
    public HierarchyNode getNode() { return node; }
    public void setNode(HierarchyNode node) { this.node = node; }
    public Item getParent() { return parent; }
    public void setParent(Item parent) { this.parent = parent; }
    public ItemStatus getStatus() { return status; }
    public void setStatus(ItemStatus status) { this.status = status; }
    public Zone getZone() { return zone; }
    public void setZone(Zone zone) { this.zone = zone; }
    public Phase getPhase() { return phase; }
    public void setPhase(Phase phase) { this.phase = phase; }
    public String getSpecs() { return specs; }
    public void setSpecs(String specs) { this.specs = specs; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public long getVersion() { return version; }
}
