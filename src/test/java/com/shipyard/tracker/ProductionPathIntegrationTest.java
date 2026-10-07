package com.shipyard.tracker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shipyard.tracker.repo.HullRepository;
import com.shipyard.tracker.repo.ItemRepository;
import com.shipyard.tracker.repo.PhaseRepository;
import com.shipyard.tracker.repo.ZoneRepository;
import com.shipyard.tracker.security.UserDirectory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The production path, which no H2 test can cover: a real PostgreSQL, Flyway building the schema from V1..V4,
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
        // People with roles from configuration: the break-glass administrators, an editor and a viewer.
        "app.authz.oauth2.memory.roles.admin=root,root2",
        "app.authz.oauth2.memory.roles.editor=ed",
        "app.authz.oauth2.memory.roles.viewer=vw",
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
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

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
    @Autowired UserDirectory directory;

    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    /** Editor-only, and refused with 400 ("nothing to check") once the role check has passed. */
    private static final String EMPTY_CHECK = "{\"columns\":[\"zone\"],\"rows\":[]}";
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
        mvc.perform(post("/api/hulls").with(as("ed"))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(NEW_HULL.formatted("S901")))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/hulls").with(as("root"))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(NEW_HULL.formatted("S902")))
                .andExpect(status().isCreated());

        var hull = hulls.findByCode("S902").orElseThrow();
        assertTrue(items.countByHull(hull) > 0, "the new ship has its planned items");

        mvc.perform(post("/api/hulls").with(as("root"))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(NEW_HULL.formatted("S902")))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/hulls").with(as("root"))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(NEW_HULL.formatted("bad code!")))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ roles and the Admin page

    @Test
    void rolesFromConfigurationNeedNoDatabaseRow() throws Exception {
        mvc.perform(get("/api/items").with(as("vw"))).andExpect(status().isOk());
        mvc.perform(get("/api/items").with(as("ed"))).andExpect(status().isOk());
        mvc.perform(get("/api/admin/users").with(as("vw"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/users").with(as("ed"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/users").with(as("root"))).andExpect(status().isOk());
        mvc.perform(post("/api/grid/items/check").with(as("vw")).with(csrf()).contentType(JSON).content(EMPTY_CHECK))
                .andExpect(status().isForbidden());
    }

    @Test
    void anAdministratorCanGiveChangeAndWithdrawAccessAndItWorksOnTheNextRequest() throws Exception {
        mvc.perform(get("/api/items").with(as("pat"))).andExpect(status().isForbidden());

        mvc.perform(post("/api/admin/users").with(as("root")).with(csrf()).contentType(JSON)
                        .content("{\"identifier\":\"pat\",\"role\":\"EDITOR\"}"))
                .andExpect(status().isCreated());
        long id = jdbc.queryForObject("select id from app_users where username = 'pat'", Long.class);

        // Editor: reads, and gets past the editor-only role check (then 400 because the request is empty).
        mvc.perform(get("/api/items").with(as("pat"))).andExpect(status().isOk());
        mvc.perform(post("/api/grid/items/check").with(as("pat")).with(csrf()).contentType(JSON).content(EMPTY_CHECK))
                .andExpect(status().isBadRequest());

        // Viewer: reads, cannot change.
        mvc.perform(put("/api/admin/users/" + id).with(as("root")).with(csrf()).contentType(JSON).content("{\"role\":\"VIEWER\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/items").with(as("pat"))).andExpect(status().isOk());
        mvc.perform(post("/api/grid/items/check").with(as("pat")).with(csrf()).contentType(JSON).content(EMPTY_CHECK))
                .andExpect(status().isForbidden());

        // Disabled: nothing at all. Enabled again: back to viewer.
        mvc.perform(put("/api/admin/users/" + id).with(as("root")).with(csrf()).contentType(JSON).content("{\"enabled\":false}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/items").with(as("pat"))).andExpect(status().isForbidden());
        mvc.perform(put("/api/admin/users/" + id).with(as("root")).with(csrf()).contentType(JSON).content("{\"enabled\":true}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/items").with(as("pat"))).andExpect(status().isOk());

        // No role: nothing.
        mvc.perform(put("/api/admin/users/" + id).with(as("root")).with(csrf()).contentType(JSON).content("{\"role\":\"NONE\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/items").with(as("pat"))).andExpect(status().isForbidden());

        Integer logged = jdbc.queryForObject(
                "select count(*) from admin_events where target = 'pat'", Integer.class);
        assertTrue(logged != null && logged >= 5, "every change is in the admin log, got " + logged);
    }

    @Test
    void aSignedInPersonIsRecordedSoTheyShowUpOnTheAdminPage() throws Exception {
        directory.recordLogin("newcomer", "newcomer@example.test", "New Comer");
        mvc.perform(get("/api/admin/users").with(as("root")))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(
                        org.hamcrest.Matchers.containsString("newcomer@example.test")));
        // Added in advance by e-mail address, then the first sign-in links the record and keeps the role.
        mvc.perform(post("/api/admin/users").with(as("root")).with(csrf()).contentType(JSON)
                        .content("{\"identifier\":\"Prepared@Example.test\",\"role\":\"VIEWER\"}"))
                .andExpect(status().isCreated());
        directory.recordLogin("prepared-sub", "prepared@example.test", "Prepared Person");
        mvc.perform(get("/api/items").with(as("prepared-sub"))).andExpect(status().isOk());
        assertEquals(1, jdbc.queryForObject("select count(*) from app_users where email = 'prepared@example.test'", Integer.class));
    }

    @Test
    void aConfiguredAdministratorCannotBeLockedOutAndNobodyCanChangeTheirOwnAccess() throws Exception {
        mvc.perform(post("/api/admin/users").with(as("root2")).with(csrf()).contentType(JSON)
                        .content("{\"identifier\":\"root\",\"role\":\"VIEWER\"}"))
                .andExpect(status().isCreated());
        long id = jdbc.queryForObject("select id from app_users where username = 'root'", Long.class);
        mvc.perform(put("/api/admin/users/" + id).with(as("root2")).with(csrf()).contentType(JSON).content("{\"enabled\":false}"))
                .andExpect(status().isOk());
        // Still an administrator, because the configuration says so.
        mvc.perform(get("/api/admin/users").with(as("root"))).andExpect(status().isOk());

        // Own record: refused.
        mvc.perform(put("/api/admin/users/" + id).with(as("root")).with(csrf()).contentType(JSON).content("{\"enabled\":true}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void badInputToTheAdminApiIsRefusedWithAMessage() throws Exception {
        mvc.perform(post("/api/admin/users").with(as("root")).with(csrf()).contentType(JSON)
                        .content("{\"identifier\":\"x@\",\"role\":\"EDITOR\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/users").with(as("root")).with(csrf()).contentType(JSON)
                        .content("{\"identifier\":\"someone\",\"role\":\"SUPERUSER\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/admin/users/999999").with(as("root")).with(csrf()).contentType(JSON).content("{\"role\":\"VIEWER\"}"))
                .andExpect(status().isNotFound());
        // Writes need the CSRF token.
        mvc.perform(post("/api/admin/users").with(as("root")).contentType(JSON)
                        .content("{\"identifier\":\"nocsrf\",\"role\":\"VIEWER\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void theAdminLogCannotBeChangedEvenWithFullDatabaseAccess() {
        directory.recordLogin("auditor", null, null);
        directory.add("audited-person", "VIEWER");
        assertThrows(DataAccessException.class, () -> jdbc.update("update admin_events set detail = 'x'"));
        assertThrows(DataAccessException.class, () -> jdbc.update("delete from admin_events"));
    }

    @Test
    void pagesAreServedToThePeopleWhoMayUseThem() throws Exception {
        for (String page : new String[] {"/", "/map.html", "/data.html"}) {
            mvc.perform(get(page).with(as("vw"))).andExpect(status().isOk());
        }
        mvc.perform(get("/import.html").with(as("vw"))).andExpect(status().isForbidden());
        mvc.perform(get("/import.html").with(as("ed"))).andExpect(status().isOk());
        mvc.perform(get("/admin.html").with(as("ed"))).andExpect(status().isForbidden());
        mvc.perform(get("/admin.html").with(as("root"))).andExpect(status().isOk());
    }

    @Test
    void theGridAndTheSummaryWorkAgainstPostgres() throws Exception {
        mvc.perform(post("/api/hulls").with(as("root")).with(csrf()).contentType(JSON).content(NEW_HULL.formatted("S903")))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/grid/items").with(as("vw"))).andExpect(status().isOk());
        mvc.perform(get("/api/summary").with(as("vw"))).andExpect(status().isOk());
        mvc.perform(get("/api/export/items.xlsx").with(as("vw"))).andExpect(status().isOk());
    }

    /** Signs in as the person whose OpenID subject is {@code subject}; what they may do comes from the application. */
    private static RequestPostProcessor as(String subject) {
        return oidcLogin().idToken(token -> token.subject(subject));
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
