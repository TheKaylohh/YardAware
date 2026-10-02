package com.shipyard.tracker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;

/**
 * Goes through real HTTP so JSON shapes and error messages are checked exactly as the browser sees them.
 * Authentication is on (basic mode), so every call signs in as the demo editor from auth-defaults.properties.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:h2:mem:apitest;DB_CLOSE_DELAY=-1")
class ApiSmokeTest {

    @Autowired
    TestRestTemplate rest;

    private TestRestTemplate editor() {
        return rest.withBasicAuth("editor", "editor123");
    }

    @Test
    void referenceDataEndpoints() {
        assertTrue(editor().getForObject("/api/meta", String.class).contains("LNG storage"));
        assertTrue(editor().getForObject("/api/hulls", String.class).contains("S041"));
        assertTrue(editor().getForObject("/api/zones", String.class).contains("Building Dock"));
    }

    @Test
    void itemsEndpointReturnsSeededGrandBlock() {
        String json = editor().getForObject("/api/items", String.class);
        assertNotNull(json);
        assertTrue(json.contains("S041-EA500"));
        assertTrue(json.contains("Engine room, level 5"));
        assertTrue(json.contains("\"typeLabel\":\"Grand block\""));
    }

    @Test
    void validationErrorsComeBackAsReadableMessages() {
        ResponseEntity<String> response = editor().postForEntity("/api/items/assemble",
                Map.of("childIds", List.of(1), "type", "SECTION", "name", "X"), String.class);
        assertEquals(400, response.getStatusCode().value());
        assertTrue(response.getBody().contains("Select at least two items to assemble."));
    }

    @Test
    void unknownItemIs404() {
        ResponseEntity<String> response = editor().getForEntity("/api/items/999999", String.class);
        assertEquals(404, response.getStatusCode().value());
    }
}
