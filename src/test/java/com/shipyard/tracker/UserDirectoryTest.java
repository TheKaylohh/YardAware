package com.shipyard.tracker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.shipyard.tracker.domain.AppUser;
import com.shipyard.tracker.security.EffectiveRoles;
import com.shipyard.tracker.security.RolesLookupService;
import com.shipyard.tracker.security.UserDirectory;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;

/**
 * The rules for who may do what: roles from the configuration plus roles from the Admin page, a disabled person gets
 * nothing, and an administrator named in the configuration can never be locked out.
 */
@ActiveProfiles("dev")
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:directorytest;DB_CLOSE_DELAY=-1")
class UserDirectoryTest {

    @Autowired UserDirectory directory;

    /** Stands in for the YARD_ADMINS / YARD_EDITORS / YARD_VIEWERS settings. */
    private static final RolesLookupService CONFIG = username -> switch (username.toLowerCase(Locale.ROOT)) {
        case "boss", "boss@example.test" -> Set.of("ADMIN");
        case "cfg-editor" -> Set.of("EDITOR");
        default -> Set.of();
    };

    private EffectiveRoles roles() {
        return new EffectiveRoles(CONFIG, directory);
    }

    @Test
    void aFirstSignInCreatesARecordWithNoRoleAndTheLastSignInMovesOn() {
        directory.recordLogin("first-timer", "first@example.test", "First Timer");
        AppUser user = directory.find("first-timer", null).orElseThrow();
        assertEquals(null, user.getRole());
        assertEquals("first@example.test", user.getEmail());
        assertEquals("First Timer", user.getDisplayName());
        assertTrue(roles().resolveFresh("first-timer", "first@example.test").isEmpty(), "no role, no access");

        directory.recordLogin("first-timer", "first@example.test", "First T. Timer");
        assertEquals("First T. Timer", directory.find("first-timer", null).orElseThrow().getDisplayName());
    }

    @Test
    void aPersonAddedByEmailIsLinkedAtTheirFirstSignInAndKeepsTheRole() {
        directory.add("Ready@Example.test", "EDITOR");
        assertEquals(Set.of(), roles().resolveFresh("ready-sub", null), "not linked yet, e-mail not verified");
        assertEquals(Set.of("EDITOR"), roles().resolveFresh("ready-sub", "ready@example.test"));

        directory.recordLogin("ready-sub", "ready@example.test", "Ready Person");
        AppUser linked = directory.find("ready-sub", null).orElseThrow();
        assertEquals("ready-sub", linked.getUsername());
        assertEquals("EDITOR", linked.getRole());
        assertEquals(Set.of("EDITOR"), roles().resolveFresh("ready-sub", null), "found by sign-in id from now on");
    }

    @Test
    void configurationRolesAndAdminPageRolesAreAddedTogether() {
        directory.add("cfg-editor", "VIEWER");
        assertEquals(Set.of("EDITOR", "VIEWER"), roles().resolveFresh("cfg-editor", null));
    }

    @Test
    void aDisabledPersonGetsNothingButAConfiguredAdministratorIsNeverLockedOut() {
        Long editorId = directory.add("disabled-editor", "EDITOR").getId();
        directory.update(editorId, false, null, false);
        assertTrue(roles().resolveFresh("disabled-editor", null).isEmpty());
        directory.update(editorId, false, null, true);
        assertEquals(Set.of("EDITOR"), roles().resolveFresh("disabled-editor", null));

        Long bossId = directory.add("boss", "VIEWER").getId();
        directory.update(bossId, false, null, false);
        assertTrue(roles().resolveFresh("boss", null).contains("ADMIN"), "named in the configuration, so still an administrator");
    }

    @Test
    void cachedRolesNoticeChangesFromTheAdminPageAtOnce() {
        EffectiveRoles roles = roles();
        Long id = directory.add("cache-check", "VIEWER").getId();
        assertEquals(Set.of("VIEWER"), roles.resolve("cache-check", null));
        directory.update(id, true, "EDITOR", null);
        assertEquals(Set.of("EDITOR"), roles.resolve("cache-check", null), "no waiting for the cache to expire");
        directory.update(id, true, "NONE", null);
        assertTrue(roles.resolve("cache-check", null).isEmpty());
    }

    @Test
    void identifiersAndRolesAreValidated() {
        assertThrows(ResponseStatusException.class, () -> directory.add("   ", "VIEWER"));
        assertThrows(ResponseStatusException.class, () -> directory.add("not-an@", "VIEWER"));
        assertThrows(ResponseStatusException.class, () -> directory.add("somebody", "GOD"));
        assertThrows(ResponseStatusException.class, () -> directory.add("x".repeat(300), "VIEWER"));
        directory.add("dupe-check", "VIEWER");
        assertThrows(ResponseStatusException.class, () -> directory.add("DUPE-CHECK", "VIEWER"));
    }

    @Test
    void everyAdminChangeIsWrittenToTheLogWithTheActor() {
        Long id = directory.add("logged-person", "VIEWER").getId();
        directory.update(id, true, "EDITOR", null);
        directory.remove(id);
        var actions = directory.recentEvents().stream().filter(e -> "logged-person".equals(e.getTarget())).map(e -> e.getAction()).toList();
        assertTrue(actions.containsAll(Set.of("USER_ADDED", "ROLE_CHANGED", "USER_REMOVED")), actions.toString());
        assertFalse(directory.recentEvents().stream().anyMatch(e -> e.getActor() == null || e.getActor().isBlank()));
    }
}
