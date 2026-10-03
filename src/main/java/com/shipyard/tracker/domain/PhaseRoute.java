package com.shipyard.tracker.domain;

import java.util.ArrayList;
import java.util.List;

/** Parses the workbook's phase route text, e.g. "1 → 2 → 3", into phase numbers. */
public final class PhaseRoute {

    private PhaseRoute() {
    }

    /** Empty for a null or blank route (areas and milestones have none). */
    public static List<Integer> parse(String route) {
        List<Integer> phases = new ArrayList<>();
        if (route == null || route.isBlank()) {
            return phases;
        }
        for (String part : route.split("→")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                phases.add(Integer.parseInt(trimmed));
            }
        }
        return phases;
    }
}
