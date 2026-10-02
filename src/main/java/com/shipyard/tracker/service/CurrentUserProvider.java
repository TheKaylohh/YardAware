package com.shipyard.tracker.service;

/**
 * Who is making the change. The POC uses a fixed stub; when SSO arrives, provide an implementation
 * that reads the authenticated principal (for example from the Spring Security context) instead.
 */
public interface CurrentUserProvider {
    String currentUser();
}
