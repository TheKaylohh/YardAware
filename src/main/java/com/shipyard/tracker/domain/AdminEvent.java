package com.shipyard.tracker.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Immutable;

/** One thing an administrator did on the Admin page. Append-only (a database trigger rejects UPDATE and DELETE). */
@Entity
@Immutable
@Table(name = "admin_events")
public class AdminEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(nullable = false, updatable = false)
    private String actor;

    @Column(nullable = false, length = 40, updatable = false)
    private String action;

    @Column(updatable = false)
    private String target;

    @Column(length = 1000, updatable = false)
    private String detail;

    protected AdminEvent() {
    }

    public AdminEvent(Instant occurredAt, String actor, String action, String target, String detail) {
        this.occurredAt = occurredAt;
        this.actor = actor;
        this.action = action;
        this.target = target;
        this.detail = detail;
    }

    public Long getId() { return id; }
    public Instant getOccurredAt() { return occurredAt; }
    public String getActor() { return actor; }
    public String getAction() { return action; }
    public String getTarget() { return target; }
    public String getDetail() { return detail; }
}
