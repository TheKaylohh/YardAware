package com.shipyard.tracker.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One of the 11 production phases (prefabrication ... testing and sea trials). The phase number is the key. */
@Entity
@Table(name = "phases")
public class Phase {

    @Id
    @Column(name = "phase_number")
    private Integer number;

    @Column(nullable = false)
    private String name;

    /** Assembly facility code(s) the phase runs in, e.g. "A0 / B0 / C0". */
    @Column(nullable = false)
    private String facility;

    /** Which level of the hierarchy the phase applies to: Sections, Blocks, Units or Vessel. */
    @Column(name = "applies_to", nullable = false)
    private String appliesTo;

    protected Phase() {
    }

    public Phase(Integer number, String name, String facility, String appliesTo) {
        this.number = number;
        this.name = name;
        this.facility = facility;
        this.appliesTo = appliesTo;
    }

    public Integer getNumber() { return number; }
    public String getName() { return name; }
    public String getFacility() { return facility; }
    public String getAppliesTo() { return appliesTo; }
}
