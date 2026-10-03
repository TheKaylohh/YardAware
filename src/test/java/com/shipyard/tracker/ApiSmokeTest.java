package com.shipyard.tracker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;

/**
 * Goes through real HTTP so JSON shapes and error messages are checked exactly as the browser sees them.
 * Authentication is on (basic mode), so every call signs in as the demo editor from application-dev.properties.
 */
@ActiveProfiles("dev")
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
        String meta = editor().getForObject("/api/meta", String.class);
        assertTrue(meta.contains("Double Bottom"));
        assertTrue(meta.contains("Testing and sea trials"));
        assertTrue(editor().getForObject("/api/hulls", String.class).contains("S041"));
        String zones = editor().getForObject("/api/zones", String.class);
        assertTrue(zones.contains("Building Dock 1"));
        assertTrue(zones.contains("Building Dock 2"));
        assertFalse(zones.contains("Tool Crib"));
        assertTrue(zones.contains("A0 / B0 / C0"));
        assertTrue(editor().getForObject("/api/design-basis", String.class).contains("854 ft"));
        assertTrue(editor().getForObject("/api/phases", String.class).contains("Block outfitting"));
    }

    @Test
    void hierarchyEndpointCanBeFilteredByArea() {
        String ba = editor().getForObject("/api/hierarchy?area=ba", String.class);
        assertTrue(ba.contains("BA-U01-B01-S01"));
        assertTrue(ba.contains("\"levelLabel\":\"Unit (grand block)\""));
        assertFalse(ba.contains("AA-U01"));
        assertTrue(editor().getForObject("/api/hierarchy", String.class).contains("VSL-P10"));
    }

    @Test
    void itemsEndpointReturnsTheBusyYardAndFiltersByStatus() {
        String onYard = editor().getForObject("/api/items", String.class);
        assertNotNull(onYard);
        assertTrue(onYard.contains("S041-BA-U01"));
        assertTrue(onYard.contains("S042-BA-U01"));
        assertTrue(onYard.contains("\"levelLabel\":\"Block\""));
        assertTrue(onYard.contains("\"areaName\":\"Double Bottom\""));
        assertFalse(onYard.contains("S041-LA-U01"), "planned items are not on the map");

        assertTrue(editor().getForObject("/api/items?status=planned&hullId=1", String.class).contains("S041-LA-U01"));
        assertTrue(editor().getForObject("/api/items?status=consumed", String.class).contains("S041-BA-U01-B01-S01"));
        ResponseEntity<String> bad = editor().getForEntity("/api/items?status=nope", String.class);
        assertEquals(400, bad.getStatusCode().value());
        assertTrue(bad.getBody().contains("Status must be PLANNED, ACTIVE or CONSUMED."));
    }

    @Test
    void validationErrorsComeBackAsReadableMessages() {
        ResponseEntity<String> response = editor().postForEntity("/api/items/assemble",
                Map.of("childIds", List.of(1)), String.class);
        assertEquals(400, response.getStatusCode().value());
        assertTrue(response.getBody().contains("Select at least two items to assemble."));

        ResponseEntity<String> place = editor().postForEntity("/api/items/1/place", Map.of(), String.class);
        assertEquals(400, place.getStatusCode().value());
        assertTrue(place.getBody().contains("Choose a zone."));
    }

    @Test
    void unknownItemIs404() {
        ResponseEntity<String> response = editor().getForEntity("/api/items/999999", String.class);
        assertEquals(404, response.getStatusCode().value());
    }
}
