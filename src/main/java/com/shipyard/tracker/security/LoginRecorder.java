package com.shipyard.tracker.security;

/** Told about every OpenID Connect sign-in. {@code verifiedEmail} is null unless the provider vouches for it. */
@FunctionalInterface
public interface LoginRecorder {
    void record(String username, String verifiedEmail, String displayName);
}
