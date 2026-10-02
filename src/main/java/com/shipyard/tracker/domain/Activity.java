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

/** One line in an item's history. Append-only: entries are never edited or removed. */
@Entity
@Table(name = "activities")
public class Activity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    private Item item;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ActivityType type;

    @ManyToOne
    private Zone fromZone;

    @ManyToOne
    private Zone toZone;

    private String actor;

    @Column(length = 1000)
    private String note;

    @Column(nullable = false)
    private Instant occurredAt;

    public Long getId() { return id; }
    public Item getItem() { return item; }
    public void setItem(Item item) { this.item = item; }
    public ActivityType getType() { return type; }
    public void setType(ActivityType type) { this.type = type; }
    public Zone getFromZone() { return fromZone; }
    public void setFromZone(Zone fromZone) { this.fromZone = fromZone; }
    public Zone getToZone() { return toZone; }
    public void setToZone(Zone toZone) { this.toZone = toZone; }
    public String getActor() { return actor; }
    public void setActor(String actor) { this.actor = actor; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public Instant getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Instant occurredAt) { this.occurredAt = occurredAt; }
}
