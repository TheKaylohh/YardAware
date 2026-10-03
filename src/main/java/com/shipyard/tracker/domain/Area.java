package com.shipyard.tracker.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A planning area of the ship (Aft Area, Double Bottom, Engine Room ...). Two-letter code, e.g. AA. */
@Entity
@Table(name = "areas")
public class Area {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 2)
    private String code;

    @Column(nullable = false)
    private String name;

    /** "Function / System" column of the workbook. */
    @Column(name = "function_desc", length = 300)
    private String function;

    @Column(length = 300)
    private String confidence;

    @Column(length = 600)
    private String note;

    /** Row order in the workbook, so areas list the way the planners wrote them. */
    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    protected Area() {
    }

    public Area(String code, String name, String function, String confidence, String note, int sortOrder) {
        this.code = code;
        this.name = name;
        this.function = function;
        this.confidence = confidence;
        this.note = note;
        this.sortOrder = sortOrder;
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getFunction() { return function; }
    public String getConfidence() { return confidence; }
    public String getNote() { return note; }
    public int getSortOrder() { return sortOrder; }
}
