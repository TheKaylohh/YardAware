package com.shipyard.tracker;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.shipyard.tracker.security.AuthCurrentUserProvider;
import com.shipyard.tracker.security.AuthUser;
import com.shipyard.tracker.security.ProductionSafetyGuard;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** Plain unit tests, no Spring context. */
class ProductionSafetyGuardTest {

    private static MockEnvironment safeProduction() {
        return new MockEnvironment()
                .withProperty("app.authn.type", "oauth2")
                .withProperty("spring.datasource.url", "jdbc:postgresql://db/yard")
                .withProperty("spring.jpa.hibernate.ddl-auto", "validate")
                .withProperty("server.servlet.session.cookie.secure", "true");
    }

    @Test
    void safeProductionSettingsStart() {
        assertDoesNotThrow(() -> new ProductionSafetyGuard(safeProduction()));
    }

    @Test
    void unsafeSettingsAreRefusedOutsideDevelopment() {
        MockEnvironment env = safeProduction().withProperty("spring.datasource.url", "jdbc:h2:file:./data/yard");
        assertThrows(IllegalStateException.class, () -> new ProductionSafetyGuard(env));
    }

    @Test
    void devModeCannotBeSwitchedOnTogetherWithTheProdProfile() {
        MockEnvironment env = safeProduction().withProperty("app.security.dev-mode", "true");
        env.setActiveProfiles("prod");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new ProductionSafetyGuard(env));
        assertTrue(e.getMessage().contains("prod profile"));
    }

    @Test
    void devModeStillWorksForLocalDevelopment() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("app.security.dev-mode", "true")
                .withProperty("app.authn.type", "basic");
        env.setActiveProfiles("dev");
        assertDoesNotThrow(() -> new ProductionSafetyGuard(env));
    }

    @Test
    void historyShowsANameNextToTheOpaqueId() {
        AuthUser oidc = new AuthUser("3f2a9c10-aaaa-bbbb-cccc-1234567890ab", "Jane Doe", "jane@corp.com", List.of("EDITOR"));
        assertTrue(AuthCurrentUserProvider.actorLabel(oidc).equals("Jane Doe (3f2a9c10-aaaa-bbbb-cccc-1234567890ab)"));
        AuthUser noName = new AuthUser("alice", "alice", null, List.of("EDITOR"));
        assertTrue(AuthCurrentUserProvider.actorLabel(noName).equals("alice"));
        AuthUser huge = new AuthUser("x".repeat(300), "Y", null, List.of());
        assertTrue(AuthCurrentUserProvider.actorLabel(huge).length() <= 255);
    }
}
