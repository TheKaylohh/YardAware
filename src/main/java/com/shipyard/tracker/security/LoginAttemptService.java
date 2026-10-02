package com.shipyard.tracker.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Slows down password guessing for the local (basic) login.
 * After N failed attempts for a username, or M from one IP address, that key is locked for a while.
 * In memory, so it resets on restart and is per instance. That is enough for basic mode, which is
 * development-only; production signs in through the identity provider, which has its own protection.
 */
public class LoginAttemptService {

    private static final Logger audit = LoggerFactory.getLogger("SECURITY_AUDIT");
    private static final int MAX_TRACKED_KEYS = 10_000;

    private final int maxPerUser;
    private final int maxPerIp;
    private final Duration window;
    private final Clock clock;
    private final Map<String, Attempts> attempts = new ConcurrentHashMap<>();

    public LoginAttemptService(int maxPerUser, int maxPerIp, Duration window, Clock clock) {
        this.maxPerUser = maxPerUser;
        this.maxPerIp = maxPerIp;
        this.window = window;
        this.clock = clock;
    }

    public boolean isBlocked(String username, String ip) {
        return over(userKey(username), maxPerUser) || (ip != null && over(ipKey(ip), maxPerIp));
    }

    public void failed(String username, String ip) {
        Instant now = clock.instant();
        if (attempts.size() > MAX_TRACKED_KEYS) {
            attempts.values().removeIf(a -> a.expired(now, window));
        }
        record(userKey(username), now, maxPerUser, "user " + SecurityAuditListener.safe(username));
        if (ip != null) {
            record(ipKey(ip), now, maxPerIp, "address " + ip);
        }
    }

    public void succeeded(String username) {
        attempts.remove(userKey(username));
    }

    private void record(String key, Instant now, int limit, String who) {
        Attempts a = attempts.compute(key, (k, old) -> old == null || old.expired(now, window)
                ? new Attempts(1, now) : new Attempts(old.count() + 1, old.first()));
        if (a.count() == limit) {
            audit.warn("LOGIN_LOCKOUT {} locked for {} minutes after {} failed attempts", who, window.toMinutes(), limit);
        }
    }

    private boolean over(String key, int limit) {
        Attempts a = attempts.get(key);
        return a != null && !a.expired(clock.instant(), window) && a.count() >= limit;
    }

    private static String userKey(String username) {
        return "u:" + (username == null ? "" : username.trim().toLowerCase(Locale.ROOT));
    }

    private static String ipKey(String ip) {
        return "ip:" + ip;
    }

    private record Attempts(int count, Instant first) {
        boolean expired(Instant now, Duration window) {
            return first.plus(window).isBefore(now);
        }
    }
}
