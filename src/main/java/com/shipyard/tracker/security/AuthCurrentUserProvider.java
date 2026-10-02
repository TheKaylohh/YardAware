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
        return user != null ? user.username() : fallback.currentUser();
    }
}
