package com.shipyard.tracker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shipyard.tracker.repo.HullRepository;
import com.shipyard.tracker.repo.ItemRepository;
import com.shipyard.tracker.repo.PhaseRepository;
import com.shipyard.tracker.repo.ZoneRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The production path, which no H2 test can cover: a real PostgreSQL, Flyway building the schema from V1..V3,
 * Hibernate in {@code validate} mode, the ProductionSafetyGuard with dev-mode off, and OAuth2 login.
 * No identity provider is needed: the provider endpoints are fixed URLs that are never called, and signed-in users are
 * simulated with Spring Security's test support.
 *
 * <p>Needs Docker; skipped automatically where Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        // Production-like. Set explicitly so a leftover dev profile cannot change what is being tested.
        "app.security.dev-mode=false",
        "app.authn.type=oauth2",
        "spring.h2.console.enabled=false",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "server.servlet.session.cookie.secure=true",
        "shipyard.seed-demo-data=false",
        "app.authz.oauth2.memory.roles.admin=root",
        // An identity provider that is never contacted.
        "spring.security.oauth2.client.registration.idp.client-id=test-client",
        "spring.security.oauth2.client.registration.idp.client-secret=test-secret",
        "spring.security.oauth2.client.registration.idp.authorization-grant-type=authorization_code",
        "spring.security.oauth2.client.registration.idp.redirect-uri={baseUrl}/login/oauth2/code/{registrationId}",
        "spring.security.oauth2.client.registration.idp.scope=openid,profile,email",
        "spring.security.oauth2.client.provider.idp.authorization-uri=https://idp.invalid/authorize",
        "spring.security.oauth2.client.provider.idp.token-uri=https://idp.invalid/token",
        "spring.security.oauth2.client.provider.idp.jwk-set-uri=https://idp.invalid/jwks",
        "spring.security.oauth2.client.provider.idp.user-info-uri=https://idp.invalid/userinfo",
        "spring.security.oauth2.client.provider.idp.user-name-attribute=sub"
})
@AutoConfigureMockMvc
class ProductionPathIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ZoneRepository zones;
    @Autowired PhaseRepository phases;
    @Autowired HullRepository hulls;
    @Autowired ItemRepository items;

    private static final String NEW_HULL = "{\"code\":\"%s\",\"name\":\"Test ship\",\"color\":\"#112233\"}";

    @Test
    void emptyProductionDatabaseStillGetsTheYardLayoutAndThePlan() {
        assertTrue(zones.count() >= 13, "the zones load without demo data");
        assertEquals(11, phases.count());
        assertEquals(0, hulls.count() - hullsCreatedByThisClass(), "no demo ships");
    }

    @Test
    void anonymousUsersAreSentToSignInAndTheApiAnswers401() throws Exception {
        mvc.perform(get("/api/items")).andExpect(status().isUnauthorized());
        var response = mvc.perform(get("/")).andExpect(status().is3xxRedirection()).andReturn().getResponse();
        assertTrue(response.getRedirectedUrl().contains("/oauth2/authorization/idp"), response.getRedirectedUrl());
    }

    @Test
    void healthProbeIsPublicButOtherActuatorEndpointsAreNot() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
        int env = mvc.perform(get("/actuator/env")).andReturn().getResponse().getStatus();
        assertTrue(env != 200, "env must not be exposed, got " + env);
    }

    @Test
    void usersWithoutARoleAreRefused() throws Exception {
        mvc.perform(get("/api/items").with(oidcLogin())).andExpect(status().isForbidden());
    }

    @Test
    void onlyAnAdminCanCreateAShipAndItComesWithItsWholePlan() throws Exception {
        mvc.perform(post("/api/hulls").with(oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_EDITOR")))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(NEW_HULL.formatted("S901")))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/hulls").with(oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(NEW_HULL.formatted("S902")))
                .andExpect(status().isCreated());

        var hull = hulls.findByCode("S902").orElseThrow();
        assertTrue(items.countByHull(hull) > 0, "the new ship has its planned items");

        mvc.perform(post("/api/hulls").with(oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(NEW_HULL.formatted("S902")))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/hulls").with(oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(NEW_HULL.formatted("bad code!")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void theAuditTrailCannotBeChangedEvenWithFullDatabaseAccess() {
        assertThrows(DataAccessException.class, () -> jdbc.update("update activities set note = 'x'"));
        assertThrows(DataAccessException.class, () -> jdbc.update("delete from activities"));
        assertThrows(DataAccessException.class, () -> jdbc.execute("truncate table activities cascade"));
    }

    /** Hulls created by the other tests in this class (the context and database are shared). */
    private long hullsCreatedByThisClass() {
        return hulls.findAll().stream().filter(h -> h.getCode().startsWith("S90")).count();
    }
}
