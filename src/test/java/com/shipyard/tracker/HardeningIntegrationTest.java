package com.shipyard.tracker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/** The production-hardening behaviour: input limits, safe error bodies, response headers and login lockout. */
@ActiveProfiles("dev")
@AutoConfigureTestRestTemplate
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:h2:mem:hardeningtest;DB_CLOSE_DELAY=-1")
class HardeningIntegrationTest {

    @Autowired
    TestRestTemplate rest;

    private TestRestTemplate editor() {
        return rest.withBasicAuth("editor", "editor123");
    }

    @Test
    void overlongNoteIsRejectedWithAReadableMessageNotADatabaseError() {
        Map<String, Object> request = Map.of("zoneId", 1, "note", "x".repeat(2000));
        ResponseEntity<String> response = editor().postForEntity("/api/items/1/place", request, String.class);
        assertEquals(400, response.getStatusCode().value());
        assertTrue(response.getBody().contains("Notes can be at most"));
        assertFalse(response.getBody().toLowerCase().contains("sql"));
    }

    @Test
    void controlCharactersInNotesAreRejected() {
        Map<String, Object> request = Map.of("zoneId", 1, "note", "H-\u202Eevil");
        ResponseEntity<String> response = editor().postForEntity("/api/items/1/place", request, String.class);
        assertEquals(400, response.getStatusCode().value());
        assertTrue(response.getBody().contains("control or text-direction"));
    }

    @Test
    void nestedSpecsAreRejected() {
        Map<String, Object> request = Map.of("specs", Map.of("Weight", Map.of("deep", 1)));
        ResponseEntity<String> response = editor().exchange("/api/items/1", HttpMethod.PUT,
                new HttpEntity<>(request), String.class);
        assertEquals(400, response.getStatusCode().value());
    }

    @Test
    void malformedJsonGetsAGenericMessage() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = editor().exchange("/api/items/1/place", HttpMethod.POST,
                new HttpEntity<>("{\"zoneId\": ", headers), String.class);
        assertEquals(400, response.getStatusCode().value());
        assertTrue(response.getBody().contains("malformed"));
        assertFalse(response.getBody().contains("Exception"));
    }

    @Test
    void securityHeadersAreSent() {
        ResponseEntity<String> page = rest.getForEntity("/login", String.class);
        HttpHeaders headers = page.getHeaders();
        assertNotNull(headers.getFirst("Content-Security-Policy"));
        assertTrue(headers.getFirst("Content-Security-Policy").contains("frame-ancestors 'none'"));
        assertEquals("DENY", headers.getFirst("X-Frame-Options"));
        assertEquals("nosniff", headers.getFirst("X-Content-Type-Options"));
        assertNotNull(headers.getFirst("Referrer-Policy"));
        assertNotNull(headers.getFirst("Permissions-Policy"));
    }

    @Test
    void repeatedWrongPasswordsLockTheAccount() {
        for (int i = 0; i < 5; i++) {
            rest.withBasicAuth("admin", "wrong-" + i).getForEntity("/api/items", String.class);
        }
        // Even the right password is refused while the lock lasts.
        assertEquals(401, rest.withBasicAuth("admin", "admin123").getForEntity("/api/items", String.class).getStatusCode().value());
        // Other accounts are unaffected.
        assertEquals(200, editor().getForEntity("/api/items", String.class).getStatusCode().value());
    }
}
