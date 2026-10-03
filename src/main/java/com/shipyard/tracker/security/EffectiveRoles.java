package com.shipyard.tracker.security;

import com.shipyard.tracker.domain.AppUser;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a person may do right now: the roles from the configuration (environment variables or your own
 * {@link RolesLookupService}) plus the role an administrator gave them on the Admin page.
 * <ul>
 *   <li>Configuration roles are always granted. An administrator listed in the configuration can never be locked out
 *       from the Admin page (break-glass access).</li>
 *   <li>Anyone else marked "disabled" on the Admin page gets no role at all.</li>
 * </ul>
 * Lookups are cached for a few seconds and the cache is dropped whenever the Admin page changes anything, so a change is
 * seen on the person's next request without a restart.
 */
public class EffectiveRoles {

    private static final long TTL_NANOS = 3_000_000_000L;
    private static final int MAX_CACHED = 2000;

    private record Cached(Set<String> roles, long directoryVersion, long expiresAt) {
    }

    private final RolesLookupService config;
    private final UserDirectory directory;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public EffectiveRoles(RolesLookupService config, UserDirectory directory) {
        this.config = config;
        this.directory = directory;
    }

    /** Roles for a person, possibly from a few-seconds-old cache. */
    public Set<String> resolve(String username, String email) {
        String key = (username == null ? "" : username.toLowerCase(Locale.ROOT)) + "|"
                + (email == null ? "" : email.toLowerCase(Locale.ROOT));
        long version = directory.changeCount();
        long now = System.nanoTime();
        Cached hit = cache.get(key);
        if (hit != null && hit.directoryVersion() == version && now < hit.expiresAt()) {
            return hit.roles();
        }
        Set<String> roles = resolveFresh(username, email);
        if (cache.size() > MAX_CACHED) {
            cache.clear();
        }
        cache.put(key, new Cached(roles, version, now + TTL_NANOS));
        return roles;
    }

    /** Roles for a person, straight from the sources. Used at sign-in. */
    public Set<String> resolveFresh(String username, String email) {
        Set<String> fromConfig = configRoles(username, email);
        AppUser stored = directory.find(username, email).orElse(null);
        boolean breakGlass = fromConfig.contains(AppRoles.ADMIN);
        if (stored != null && !stored.isEnabled() && !breakGlass) {
            return Set.of();
        }
        Set<String> roles = new HashSet<>(fromConfig);
        if (stored != null && stored.isEnabled() && stored.getRole() != null) {
            roles.add(stored.getRole());
        }
        return Set.copyOf(roles);
    }

    /** Only the roles the configuration gives this person. */
    public Set<String> configRoles(String username, String email) {
        Set<String> roles = new HashSet<>();
        if (username != null && !username.isBlank()) {
            roles.addAll(config.getRolesForUser(username));
        }
        if (email != null && !email.isBlank()) {
            roles.addAll(config.getRolesForUser(email));
        }
        return roles;
    }
}
