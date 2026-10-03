package com.shipyard.tracker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;

/** Checks who may do what, against the demo users in application-dev.properties (basic mode). */
@ActiveProfiles("dev")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:h2:mem:sectest;DB_CLOSE_DELAY=-1")
class SecurityIntegrationTest {

    @Autowired
    TestRestTemplate rest;

    private TestRestTemplate as(String user, String password) {
        return rest.withBasicAuth(user, password);
    }

    private static final String PLACE_URL = "/api/items/1/place";

    /** Passes the role check but fails validation (no zone), so a 400 proves the request was allowed through. */
    private static Map<String, Object> incompletePlacement() {
        return Map.of("note", "SEC-TEST");
    }

    @Test
    void anonymousApiRequestsAreRejected() {
        assertEquals(401, rest.getForEntity("/api/items", String.class).getStatusCode().value());
        assertEquals(401, rest.getForEntity("/api/me", String.class).getStatusCode().value());
    }

    @Test
    void wrongPasswordIsRejected() {
        assertEquals(401, as("editor", "nope").getForEntity("/api/items", String.class).getStatusCode().value());
    }

    @Test
    void viewerCanReadButNotWrite() {
        assertEquals(200, as("viewer", "viewer123").getForEntity("/api/items", String.class).getStatusCode().value());
        ResponseEntity<String> write = as("viewer", "viewer123").postForEntity(PLACE_URL, incompletePlacement(), String.class);
        assertEquals(403, write.getStatusCode().value());
    }

    @Test
    void editorAndAdminCanWrite() {
        for (String[] login : new String[][] {{"editor", "editor123"}, {"admin", "admin123"}}) {
            ResponseEntity<String> write = as(login[0], login[1]).postForEntity(PLACE_URL, incompletePlacement(), String.class);
            assertEquals(400, write.getStatusCode().value(), login[0]);
            assertTrue(write.getBody().contains("Choose a zone."), login[0]);
        }
    }

    @Test
    void meEndpointReportsRoleAndEditRights() {
        String editor = as("editor", "editor123").getForObject("/api/me", String.class);
        assertTrue(editor.contains("\"username\":\"editor\""));
        assertTrue(editor.contains("\"name\":\"Eddie Editor\""));
        assertTrue(editor.contains("\"canEdit\":true"));

        String viewer = as("viewer", "viewer123").getForObject("/api/me", String.class);
        assertTrue(viewer.contains("\"canEdit\":false"));

        String admin = as("admin", "admin123").getForObject("/api/me", String.class);
        assertTrue(admin.contains("\"canEdit\":true"));
        assertTrue(admin.contains("ADMIN"));
    }
}
