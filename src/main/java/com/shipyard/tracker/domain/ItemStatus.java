package com.shipyard.tracker.domain;

public enum ItemStatus {
    /** In the plan for this hull but not yet physically on the yard. Not drawn on the map. */
    PLANNED,
    /** Physically exists on the yard and is drawn on the map. */
    ACTIVE,
    /** Was joined into its parent. Kept for the parent's component tree and history, hidden from the map. */
    CONSUMED
}
