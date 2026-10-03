package com.shipyard.tracker.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A named area of the yard. The outline is stored as SVG polygon points in map coordinates. */
@Entity
@Table(name = "zones")
public class Zone {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(nullable = false)
    private String name;

    /** Free-form category such as shop, yard, dock or pier. */
    private String kind;

    /**
     * Workbook facility code(s) that run in this zone, e.g. "A0 / B0 / C0". Matches {@link Phase#getFacility()}, so a
     * newly placed item can default to the zone where its current phase happens. Null when no phase maps here.
     */
    private String facility;

    /** Space-separated "x,y" pairs, e.g. "60,60 400,60 400,260 60,260". */
    @Column(nullable = false, length = 2000)
    private String points;

    protected Zone() {
    }

    public Zone(String code, String name, String kind, String points) {
        this(code, name, kind, points, null);
    }

    public Zone(String code, String name, String kind, String points, String facility) {
        this.code = code;
        this.name = name;
        this.kind = kind;
        this.points = points;
        this.facility = facility;
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getFacility() { return facility; }
    public void setFacility(String facility) { this.facility = facility; }
    public String getPoints() { return points; }
    public void setPoints(String points) { this.points = points; }
}
