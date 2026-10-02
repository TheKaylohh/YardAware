package com.shipyard.tracker.domain;

public enum ItemStatus {
    /** Physically exists on the yard and is drawn on the map. */
    ACTIVE,
    /** Was assembled into a larger item. Kept for the parent's component tree and history, hidden from the map. */
    CONSUMED
}
