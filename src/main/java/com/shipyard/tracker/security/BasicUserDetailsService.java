package com.shipyard.tracker.security;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

/**
 * Serves the configured test users. A fresh BasicUser is built on every lookup, because Spring Security
 * erases the password on the object it authenticates and that must never touch the stored definition.
 * Users (or client addresses) with too many recent failures come back locked.
 */
public class BasicUserDetailsService implements UserDetailsService {

    private final Map<String, BasicAuthProps.UserEntry> users = new LinkedHashMap<>();
    private final LoginAttemptService attempts;

    public BasicUserDetailsService(List<BasicAuthProps.UserEntry> entries, LoginAttemptService attempts) {
        this.attempts = attempts;
        for (BasicAuthProps.UserEntry entry : entries) {
            if (entry.getUsername() == null || entry.getUsername().isBlank()
                    || entry.getPassword() == null || entry.getPassword().isBlank()) {
                throw new IllegalStateException("Every app.authn.basic.users entry needs a username and a password.");
            }
            users.put(entry.getUsername().trim().toLowerCase(Locale.ROOT), entry);
        }
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        BasicAuthProps.UserEntry entry = users.get(username == null ? "" : username.trim().toLowerCase(Locale.ROOT));
        if (entry == null) {
            throw new UsernameNotFoundException("Unknown user");
        }
        boolean locked = attempts != null
                && attempts.isBlocked(entry.getUsername(), SecurityAuditListener.ip(null));
        return new BasicUser(entry.getUsername(), entry.getPassword(), entry.getRoles(), entry.getName(),
                entry.getEmail(), !locked);
    }
}
