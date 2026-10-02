package com.shipyard.tracker.domain;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Two-letter ship area codes used in grand block tags.
 * A tag is the area code plus three digits, where the first digit is the deck level: EA500 = engine room, level 5.
 */
public enum AreaCode {
    AA("Aft area"),
    BA("Double hull"),
    DA("Deck area"),
    EA("Engine room"),
    FA("Forward area"),
    HA("Crew compartments"),
    LA("LNG storage"),
    PA("Passage");

    private static final Pattern TAG = Pattern.compile("^([A-Z]{2})(\\d{3})$");

    private final String label;

    AreaCode(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public static Optional<AreaCode> areaOf(String tag) {
        if (tag == null) {
            return Optional.empty();
        }
        Matcher m = TAG.matcher(tag);
        if (!m.matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(AreaCode.valueOf(m.group(1)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** Human-readable meaning of a tag, e.g. "Engine room, level 5". Empty if the tag is not valid. */
    public static Optional<String> describe(String tag) {
        return areaOf(tag).map(area -> area.getLabel() + ", level " + tag.charAt(2));
    }
}
