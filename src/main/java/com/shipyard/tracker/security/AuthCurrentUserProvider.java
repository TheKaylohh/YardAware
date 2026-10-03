package com.shipyard.tracker.security;

import com.shipyard.tracker.service.CurrentUserProvider;
import com.shipyard.tracker.service.StubCurrentUserProvider;
import org.springframework.context.annotation.Primary;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Replaces the stub: the Activity log now records who is signed in.
 * With authentication off (or outside a request, such as the seeder and service tests) it falls back to the stub user.
 */
@Component
@Primary
public class AuthCurrentUserProvider implements CurrentUserProvider {

    private final StubCurrentUserProvider fallback;

    public AuthCurrentUserProvider(StubCurrentUserProvider fallback) {
        this.fallback = fallback;
    }

    @Override
    public String currentUser() {
        AuthUser user = AuthUser.from(SecurityContextHolder.getContext().getAuthentication());
        return user != null ? actorLabel(user) : fallback.currentUser();
    }

    /**
     * With OIDC the username is usually an opaque id (sub / oid), which is useless to someone reading the history.
     * Record "Jane Doe (3f2a...)" so the log is readable and still tied to the stable id. Column limit is 255.
     */
    public static String actorLabel(AuthUser user) {
        String id = user.username();
        String name = user.name();
        String label = name == null || name.isBlank() || name.equals(id) ? id : name + " (" + id + ")";
        return label.length() > 255 ? label.substring(0, 255) : label;
    }
}
