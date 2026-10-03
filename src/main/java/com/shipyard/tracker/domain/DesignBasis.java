package com.shipyard.tracker.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A fact the hierarchy was built on (length, capacity, engine ...) with how well it is known and where it came from. */
@Entity
@Table(name = "design_basis")
public class DesignBasis {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String item;

    @Column(name = "item_value", nullable = false, length = 500)
    private String value;

    /** Public, Assumed or Unknown. */
    @Column(nullable = false)
    private String status;

    @Column(length = 300)
    private String source;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    protected DesignBasis() {
    }

    public DesignBasis(String item, String value, String status, String source, int sortOrder) {
        this.item = item;
        this.value = value;
        this.status = status;
        this.source = source;
        this.sortOrder = sortOrder;
    }

    public Long getId() { return id; }
    public String getItem() { return item; }
    public String getValue() { return value; }
    public String getStatus() { return status; }
    public String getSource() { return source; }
    public int getSortOrder() { return sortOrder; }
}
