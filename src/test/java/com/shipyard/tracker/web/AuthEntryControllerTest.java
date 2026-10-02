package com.shipyard.tracker.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AuthEntryControllerTest {

    @Test
    void sameSitePathsAreAllowed() {
        assertEquals("/", AuthEntryController.safeTarget("/").toString());
        assertEquals("/assets/page?x=1", AuthEntryController.safeTarget("/assets/page?x=1").toString());
    }

    @Test
    void anythingElseFallsBackToHome() {
        assertEquals("/", AuthEntryController.safeTarget(null).toString());
        assertEquals("/", AuthEntryController.safeTarget("").toString());
        assertEquals("/", AuthEntryController.safeTarget("https://evil.example").toString());
        assertEquals("/", AuthEntryController.safeTarget("//evil.example").toString());
        assertEquals("/", AuthEntryController.safeTarget("/\\evil.example").toString());
        assertEquals("/", AuthEntryController.safeTarget("/bad path with spaces").toString());
    }
}
