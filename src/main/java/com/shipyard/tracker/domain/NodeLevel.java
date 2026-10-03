package com.shipyard.tracker.domain;

/** The rows of the hierarchy sheet, besides the areas which have their own table. */
public enum NodeLevel {
    UNIT("Unit (grand block)", true),
    BLOCK("Block", true),
    SECTION("Section", true),
    /** Unit-level process milestone (phases 7-9). Plan data only; progress is tracked on the unit itself. */
    PROCESS("Unit milestone", false),
    /** Whole-vessel milestone (phases 10-11). */
    VESSEL("Vessel milestone", false);

    private final String label;
    private final boolean tracked;

    NodeLevel(String label, boolean tracked) {
        this.label = label;
        this.tracked = tracked;
    }

    public String getLabel() {
        return label;
    }

    /** True for levels that become physical, trackable items on the yard. */
    public boolean isTracked() {
        return tracked;
    }
}
