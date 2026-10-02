package com.shipyard.tracker.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class OAuth2RoleMappingTest {

    @Test
    void onlyVerifiedEmailCanGrantRoles() {
        assertEquals("carol@corp.com", OAuth2RoleMapping.trusted("carol@corp.com", true, false));
        assertEquals("carol@corp.com", OAuth2RoleMapping.trusted("carol@corp.com", "true", false));
        assertNull(OAuth2RoleMapping.trusted("carol@corp.com", false, false));
        assertNull(OAuth2RoleMapping.trusted("carol@corp.com", null, false));
    }

    @Test
    void unverifiedEmailOnlyWhenExplicitlyTrusted() {
        assertEquals("carol@corp.com", OAuth2RoleMapping.trusted("carol@corp.com", null, true));
        assertNull(OAuth2RoleMapping.trusted(null, true, true));
    }
}
