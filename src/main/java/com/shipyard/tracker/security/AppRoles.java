package com.shipyard.tracker.security;

/**
 * Role names (without the ROLE_ prefix). VIEWER may read; EDITOR and ADMIN may also change data.
 * With app.authz.require-viewer-role=true (the default) a signed-in user with none of these roles sees nothing.
 */
public final class AppRoles {
    public static final String VIEWER = "VIEWER";
    public static final String EDITOR = "EDITOR";
    public static final String ADMIN = "ADMIN";

    private AppRoles() {
    }
}
