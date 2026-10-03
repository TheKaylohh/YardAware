package com.shipyard.tracker.domain;

public enum ActivityType {
    /** Entered the hull's plan. */
    CREATED,
    /** First placed on the yard. */
    PLACED,
    MOVED,
    /** Moved to another production phase. */
    PHASE_CHANGED,
    /** Parent item: the pieces below it were joined into it. */
    ASSEMBLED,
    /** Child item: joined into its parent. */
    CONSUMED,
    EDITED
}
