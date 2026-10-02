package com.shipyard.tracker.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class LoginAttemptServiceTest {

    /** A clock the test can move forward. */
    static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test
    void locksAfterTooManyFailuresAndUnlocksLater() {
        TestClock clock = new TestClock();
        LoginAttemptService service = new LoginAttemptService(3, 100, Duration.ofMinutes(15), clock);
        for (int i = 0; i < 2; i++) {
            service.failed("Editor", "10.0.0.1");
        }
        assertFalse(service.isBlocked("editor", "10.0.0.1"));
        service.failed("editor", "10.0.0.1");
        assertTrue(service.isBlocked("EDITOR", "10.0.0.2"), "username matching ignores case and address");

        clock.now = clock.now.plus(Duration.ofMinutes(16));
        assertFalse(service.isBlocked("editor", "10.0.0.1"));
    }

    @Test
    void locksAnAddressThatTriesManyUsernames() {
        LoginAttemptService service = new LoginAttemptService(100, 3, Duration.ofMinutes(15), new TestClock());
        service.failed("a", "10.0.0.9");
        service.failed("b", "10.0.0.9");
        service.failed("c", "10.0.0.9");
        assertTrue(service.isBlocked("anyone", "10.0.0.9"));
        assertFalse(service.isBlocked("anyone", "10.0.0.10"));
    }

    @Test
    void successClearsTheUserCounter() {
        LoginAttemptService service = new LoginAttemptService(2, 100, Duration.ofMinutes(15), new TestClock());
        service.failed("viewer", null);
        service.succeeded("viewer");
        service.failed("viewer", null);
        assertFalse(service.isBlocked("viewer", null));
    }
}
