package com.shipyard.tracker.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Anything tracked on the yard: a piece of a ship, a batch of pipes, or a large tool.
 * When items are assembled into a bigger item, the pieces become CONSUMED and point at their parent.
 */
@Entity
@Table(name = "items")
public class Item {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Unique, human-meaningful name that doubles as the ID, e.g. S041-EA500. */
    @Column(nullable = false, unique = true)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ItemType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ItemStatus status = ItemStatus.ACTIVE;

    /** Ship this item belongs to. Null for tools and other unassigned items. */
    @ManyToOne
    private Hull hull;

    /** Current location. For consumed items this is where they were when they were assembled. */
    @ManyToOne
    private Zone zone;

    /** The item this one was assembled into. Null while the item is still standalone. */
    @ManyToOne
    private Item parent;

    /** Area tag for grand blocks, e.g. EA500. */
    private String tag;

    /** Only used by batch types. */
    private Integer quantity;
    private String unit;

    /** Flexible specs (weight, dimensions, drawing number...) stored as a JSON object. */
    @Column(length = 4000)
    private String specs;

    private Instant createdAt;
    private Instant updatedAt;

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public ItemType getType() { return type; }
    public void setType(ItemType type) { this.type = type; }
    public ItemStatus getStatus() { return status; }
    public void setStatus(ItemStatus status) { this.status = status; }
    public Hull getHull() { return hull; }
    public void setHull(Hull hull) { this.hull = hull; }
    public Zone getZone() { return zone; }
    public void setZone(Zone zone) { this.zone = zone; }
    public Item getParent() { return parent; }
    public void setParent(Item parent) { this.parent = parent; }
    public String getTag() { return tag; }
    public void setTag(String tag) { this.tag = tag; }
    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }
    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }
    public String getSpecs() { return specs; }
    public void setSpecs(String specs) { this.specs = specs; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
