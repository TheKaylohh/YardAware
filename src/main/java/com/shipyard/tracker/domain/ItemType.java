package com.shipyard.tracker.domain;

/** What kind of thing is being tracked. Batch types carry a quantity instead of being a single piece. */
public enum ItemType {
    SUB_ASSEMBLY("Sub-assembly", false),
    SECTION("Section", false),
    BLOCK("Block", false),
    GRAND_BLOCK("Grand block", false),
    UNIT("Unit", false),
    PIPE_OUTFITTING("Pipe / outfitting batch", true),
    TOOL("Tool", false);

    private final String label;
    private final boolean batch;

    ItemType(String label, boolean batch) {
        this.label = label;
        this.batch = batch;
    }

    public String getLabel() {
        return label;
    }

    public boolean isBatch() {
        return batch;
    }
}
