package com.shipyard.tracker.security;

import java.util.Set;

/**
 * Looks up the application roles (EDITOR, ADMIN) for a signed-in OAuth2 user.
 * Same contract as the template's RolesLookupService, so the eLDAP implementation can be dropped in:
 * change its import to this package and declare it as a bean with app.authz.oauth2.type=eldap.
 */
public interface RolesLookupService {
    Set<String> getRolesForUser(String username);
}
