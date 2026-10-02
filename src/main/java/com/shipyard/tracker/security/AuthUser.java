package com.shipyard.tracker.security;

import java.util.List;
import java.util.Map;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;

/** The signed-in person, however they signed in (test user or OAuth2/OIDC). */
public record AuthUser(String username, String name, String email, List<String> roles) {

    public boolean canEdit() {
        return roles.contains(AppRoles.EDITOR) || roles.contains(AppRoles.ADMIN);
    }

    /** Returns null when nobody is signed in. */
    public static AuthUser from(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || authentication instanceof AnonymousAuthenticationToken) {
            return null;
        }
        Object principal = authentication.getPrincipal();
        String display = null;
        String email = null;
        if (principal instanceof BasicUser basic) {
            display = basic.getName();
            email = basic.getEmail();
        } else if (principal instanceof OidcUser oidc) {
            display = oidc.getFullName();
            email = oidc.getEmail();
        } else if (principal instanceof OAuth2User oauth) {
            Map<String, Object> attributes = oauth.getAttributes();
            display = text(attributes.get("name"));
            email = text(attributes.get("email"));
        }
        List<String> roles = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a != null && a.startsWith("ROLE_"))
                .map(a -> a.substring("ROLE_".length()))
                .sorted()
                .toList();
        String username = authentication.getName();
        return new AuthUser(username, display == null || display.isBlank() ? username : display, email, roles);
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }
}
