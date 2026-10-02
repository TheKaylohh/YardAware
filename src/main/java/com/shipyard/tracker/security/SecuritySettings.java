package com.shipyard.tracker.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Settings shared by the filter chains, read once from configuration. */
@Component
public class SecuritySettings {

    private final boolean requireViewerRole;
    private final boolean h2ConsoleEnabled;
    private final String contentSecurityPolicy;

    public SecuritySettings(@Value("${app.authz.require-viewer-role:true}") boolean requireViewerRole,
                            @Value("${spring.h2.console.enabled:false}") boolean h2ConsoleEnabled,
                            @Value("${app.security.content-security-policy:default-src 'self'; object-src 'none'; frame-ancestors 'none'}")
                            String contentSecurityPolicy) {
        this.requireViewerRole = requireViewerRole;
        this.h2ConsoleEnabled = h2ConsoleEnabled;
        this.contentSecurityPolicy = contentSecurityPolicy == null ? "" : contentSecurityPolicy.trim();
    }

    /** true: reading needs VIEWER, EDITOR or ADMIN. false: any signed-in user may read. */
    public boolean requireViewerRole() { return requireViewerRole; }
    public boolean h2ConsoleEnabled() { return h2ConsoleEnabled; }
    public String contentSecurityPolicy() { return contentSecurityPolicy; }
}
