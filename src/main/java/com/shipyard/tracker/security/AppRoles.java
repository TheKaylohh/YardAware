package com.shipyard.tracker.security;

/** Role names (without the ROLE_ prefix). Everyone who is signed in can view; only these roles can change data. */
public final class AppRoles {
    public static final String EDITOR = "EDITOR";
    public static final String ADMIN = "ADMIN";

    private AppRoles() {
    }
}
