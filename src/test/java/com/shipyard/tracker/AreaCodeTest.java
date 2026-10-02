package com.shipyard.tracker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.shipyard.tracker.domain.AreaCode;
import org.junit.jupiter.api.Test;

class AreaCodeTest {

    @Test
    void engineRoomLevelFive() {
        assertEquals("Engine room, level 5", AreaCode.describe("EA500").orElseThrow());
    }

    @Test
    void everyAreaCodeIsDecoded() {
        assertEquals("Aft area, level 1", AreaCode.describe("AA100").orElseThrow());
        assertEquals("Double hull, level 2", AreaCode.describe("BA200").orElseThrow());
        assertEquals("Deck area, level 7", AreaCode.describe("DA700").orElseThrow());
        assertEquals("Forward area, level 4", AreaCode.describe("FA400").orElseThrow());
        assertEquals("Crew compartments, level 6", AreaCode.describe("HA610").orElseThrow());
        assertEquals("LNG storage, level 3", AreaCode.describe("LA300").orElseThrow());
        assertEquals("Passage, level 3", AreaCode.describe("PA350").orElseThrow());
    }

    @Test
    void invalidTagsAreRejected() {
        assertTrue(AreaCode.describe(null).isEmpty());
        assertTrue(AreaCode.describe("").isEmpty());
        assertTrue(AreaCode.describe("XX500").isEmpty());   // unknown area
        assertTrue(AreaCode.describe("EA50").isEmpty());    // too short
        assertTrue(AreaCode.describe("EA5000").isEmpty());  // too long
        assertTrue(AreaCode.describe("ea500").isEmpty());   // callers normalise to upper case first
    }
}
