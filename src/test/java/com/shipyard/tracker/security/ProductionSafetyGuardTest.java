package com.shipyard.tracker.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

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
    void missingConfigurationIsRefused() {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> new ProductionSafetyGuard(new MockEnvironment()));
        assertTrue(ex.getMessage().contains("spring.datasource.url"));
    }

    @Test
    void testLoginsAndH2ConsoleAreRefusedOutsideDev() {
        IllegalStateException basic = assertThrows(IllegalStateException.class,
                () -> new ProductionSafetyGuard(safeProduction().withProperty("app.authn.type", "basic")));
        assertTrue(basic.getMessage().contains("app.authn.type=basic"));
        assertThrows(IllegalStateException.class,
                () -> new ProductionSafetyGuard(safeProduction().withProperty("app.authn.type", "none")));
        assertThrows(IllegalStateException.class,
                () -> new ProductionSafetyGuard(safeProduction().withProperty("spring.h2.console.enabled", "true")));
        assertThrows(IllegalStateException.class,
                () -> new ProductionSafetyGuard(safeProduction().withProperty("spring.datasource.url", "jdbc:h2:file:./data/yard")));
        assertThrows(IllegalStateException.class,
                () -> new ProductionSafetyGuard(safeProduction().withProperty("shipyard.seed-demo-data", "true")));
    }

    @Test
    void devModeOnlyWarns() {
        assertDoesNotThrow(() -> new ProductionSafetyGuard(new MockEnvironment()
                .withProperty("app.security.dev-mode", "true")
                .withProperty("app.authn.type", "basic")
                .withProperty("spring.h2.console.enabled", "true")));
    }
}
