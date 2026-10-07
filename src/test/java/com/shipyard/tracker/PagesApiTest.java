package com.shipyard.tracker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The Home, Data, Import and Admin pages talk to these endpoints. Goes through real HTTP with the demo users from
 * application-dev.properties (basic mode), so JSON shapes, permissions and messages are checked as the browser sees them.
 *
 * <p>The tests share one database and only ever place items that are still planned, so they do not depend on running in
 * any particular order.
 */
@ActiveProfiles("dev")
@AutoConfigureTestRestTemplate
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:h2:mem:pagestest;DB_CLOSE_DELAY=-1")
class PagesApiTest {

    @Autowired TestRestTemplate rest;
    @Autowired ObjectMapper mapper;

    private TestRestTemplate viewer() {
        return rest.withBasicAuth("viewer", "viewer123");
    }

    private TestRestTemplate editor() {
        return rest.withBasicAuth("editor", "editor123");
    }

    private TestRestTemplate admin() {
        return rest.withBasicAuth("admin", "admin123");
    }

    // ---------------------------------------------------------------- helpers

    private JsonNode get(TestRestTemplate as, String url) throws IOException {
        ResponseEntity<String> response = as.getForEntity(url, String.class);
        assertEquals(200, response.getStatusCode().value(), url + " -> " + response.getBody());
        return mapper.readTree(response.getBody());
    }

    private ResponseEntity<String> postJson(TestRestTemplate as, String url, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return as.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> putJson(TestRestTemplate as, String url, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return as.exchange(url, HttpMethod.PUT, new HttpEntity<>(body, headers), String.class);
    }

    private JsonNode json(ResponseEntity<String> response) throws IOException {
        return mapper.readTree(response.getBody());
    }

    /** Grid rows of items that are planned and not part of a bigger planned item, in grid order. */
    private List<JsonNode> plannedTopLevel() throws IOException {
        List<JsonNode> found = new ArrayList<>();
        for (JsonNode row : get(viewer(), "/api/grid/items")) {
            if ("PLANNED".equals(row.get("status").asString()) && row.get("parent").asString().isEmpty()) {
                found.add(row);
            }
        }
        assertTrue(found.size() >= 3, "the demo data has planned top-level items");
        return found;
    }

    private JsonNode gridRow(String name) throws IOException {
        for (JsonNode row : get(viewer(), "/api/grid/items")) {
            if (name.equals(row.get("name").asString())) {
                return row;
            }
        }
        throw new AssertionError("no grid row for " + name);
    }

    private String aZoneCode() throws IOException {
        return get(viewer(), "/api/zones").get(0).get("code").asString();
    }

    @SafeVarargs
    private static Map<String, Object> bulk(List<String> columns, boolean skipErrors, Map<String, Object>... rows) {
        return Map.of("columns", columns, "rows", List.of(rows), "skipErrors", skipErrors, "source", "Test edit");
    }

    private static Map<String, Object> zoneRow(int number, JsonNode item, String zone) {
        return Map.of("row", number, "name", item.get("name").asString(), "version", item.get("version").asString(), "zone", zone);
    }

    // ---------------------------------------------------------------- summary

    @Test
    void summaryHasTheNumbersTheHomePageShows() throws Exception {
        JsonNode summary = get(viewer(), "/api/summary");
        long total = summary.get("planned").asLong() + summary.get("active").asLong() + summary.get("consumed").asLong();
        assertEquals(summary.get("items").asLong(), total);
        assertTrue(summary.get("hulls").asLong() >= 2);
        assertTrue(summary.get("hullProgress").size() >= 2);
        assertEquals(11, summary.get("activeByPhase").size(), "one entry per production phase");
        assertTrue(summary.get("zoneLoad").size() >= 13);
        assertNotNull(summary.get("recent"));
        assertTrue(summary.get("recent").size() <= 12);
    }

    // ------------------------------------------------------------------- grid

    @Test
    void theGridListsEveryItemWithWhatTheEditorNeeds() throws Exception {
        JsonNode rows = get(viewer(), "/api/grid/items");
        JsonNode counted = get(viewer(), "/api/summary");
        assertEquals(counted.get("items").asInt(), rows.size());
        JsonNode first = rows.get(0);
        for (String field : new String[] {"id", "name", "hull", "level", "status", "zone", "phase", "phaseName", "route", "routePhases", "specs", "version"}) {
            assertTrue(first.has(field), "grid row has " + field);
        }
        assertTrue(first.get("routePhases").isArray());
    }

    @Test
    void viewersMayReadButNotCheckOrSave() throws Exception {
        JsonNode planned = plannedTopLevel().get(0);
        Map<String, Object> request = bulk(List.of("zone"), false, zoneRow(1, planned, aZoneCode()));
        assertEquals(403, postJson(viewer(), "/api/grid/items/check", request).getStatusCode().value());
        assertEquals(403, postJson(viewer(), "/api/grid/items/save", request).getStatusCode().value());
        assertEquals("PLANNED", gridRow(planned.get("name").asString()).get("status").asString());
    }

    @Test
    void checkChangesNothingAndSaveAppliesTheRowWithAHistoryEntry() throws Exception {
        JsonNode planned = plannedTopLevel().get(0);
        String name = planned.get("name").asString();
        String zone = aZoneCode();
        Map<String, Object> request = bulk(List.of("zone"), false, zoneRow(1, planned, zone));

        ResponseEntity<String> checked = postJson(editor(), "/api/grid/items/check", request);
        assertEquals(200, checked.getStatusCode().value(), checked.getBody());
        JsonNode report = json(checked);
        assertFalse(report.get("applied").asBoolean());
        assertEquals(1, report.get("changed").asInt());
        assertEquals("CHANGE", report.get("rows").get(0).get("outcome").asString());
        assertEquals("PLANNED", gridRow(name).get("status").asString(), "a check saves nothing");

        ResponseEntity<String> saved = postJson(editor(), "/api/grid/items/save", request);
        assertEquals(200, saved.getStatusCode().value(), saved.getBody());
        assertTrue(json(saved).get("applied").asBoolean());

        JsonNode after = gridRow(name);
        assertEquals("ACTIVE", after.get("status").asString());
        assertEquals(zone, after.get("zone").asString());
        assertEquals(planned.get("version").asLong() + 1, after.get("version").asLong(), "the item version moves on");

        String history = get(viewer(), "/api/items/" + after.get("id").asString() + "/activity").toString();
        assertTrue(history.contains("Test edit"), history);
        assertTrue(history.contains("Eddie Editor"), "the history names the signed-in person: " + history);
    }

    @Test
    void aStaleVersionIsRefusedInsteadOfOverwritingNewerWork() throws Exception {
        JsonNode planned = plannedTopLevel().get(0);
        Map<String, Object> stale = Map.of("row", 1, "name", planned.get("name").asString(),
                "version", String.valueOf(planned.get("version").asLong() + 7), "zone", aZoneCode());
        JsonNode report = json(postJson(editor(), "/api/grid/items/check", bulk(List.of("zone"), false, stale)));
        assertEquals(1, report.get("errors").asInt());
        assertTrue(report.get("rows").get(0).get("message").asString().contains("Someone changed"), report.toString());
    }

    @Test
    void oneBadRowStopsTheWholeSaveUnlessTheEditorChoosesToSkipIt() throws Exception {
        List<JsonNode> planned = plannedTopLevel();
        JsonNode good = planned.get(0);
        JsonNode other = planned.get(1);
        Map<String, Object> goodRow = zoneRow(1, good, aZoneCode());
        Map<String, Object> badRow = zoneRow(2, other, "NO-SUCH-ZONE");

        JsonNode refused = json(postJson(editor(), "/api/grid/items/save", bulk(List.of("zone"), false, goodRow, badRow)));
        assertFalse(refused.get("applied").asBoolean());
        assertEquals(1, refused.get("errors").asInt());
        assertEquals("PLANNED", gridRow(good.get("name").asString()).get("status").asString(), "all or nothing");

        JsonNode skipped = json(postJson(editor(), "/api/grid/items/save", bulk(List.of("zone"), true, goodRow, badRow)));
        assertTrue(skipped.get("applied").asBoolean());
        assertEquals(1, skipped.get("changed").asInt());
        assertEquals("ACTIVE", gridRow(good.get("name").asString()).get("status").asString());
        assertEquals("PLANNED", gridRow(other.get("name").asString()).get("status").asString(), "the bad row was left alone");
    }

    @Test
    void thePhaseMustBeOnTheItemsRouteAndSpecsMustBeAJsonObject() throws Exception {
        JsonNode planned = plannedTopLevel().get(0);
        String name = planned.get("name").asString();
        Map<String, Object> badPhase = Map.of("row", 1, "name", name, "phase", "99");
        JsonNode phaseReport = json(postJson(editor(), "/api/grid/items/check", bulk(List.of("phase"), false, badPhase)));
        assertEquals(1, phaseReport.get("errors").asInt(), phaseReport.toString());

        Map<String, Object> badSpecs = Map.of("row", 1, "name", name, "specs", "[1,2,3]");
        JsonNode specsReport = json(postJson(editor(), "/api/grid/items/check", bulk(List.of("specs"), false, badSpecs)));
        assertEquals(1, specsReport.get("errors").asInt(), specsReport.toString());

        Map<String, Object> goodSpecs = Map.of("row", 1, "name", name, "specs", "{\"Weight (t)\": 12.5}");
        JsonNode okReport = json(postJson(editor(), "/api/grid/items/check", bulk(List.of("specs"), false, goodSpecs)));
        assertEquals(0, okReport.get("errors").asInt(), okReport.toString());
        assertEquals(1, okReport.get("changed").asInt());
    }

    @Test
    void emptyAndUnknownRequestsAreExplained() {
        ResponseEntity<String> nothing = postJson(editor(), "/api/grid/items/check", Map.of("columns", List.of("zone"), "rows", List.of()));
        assertEquals(400, nothing.getStatusCode().value());
        assertTrue(nothing.getBody().contains("nothing to check"), nothing.getBody());

        ResponseEntity<String> column = postJson(editor(), "/api/grid/items/check",
                Map.of("columns", List.of("name"), "rows", List.of(Map.of("row", 1, "name", "X"))));
        assertEquals(400, column.getStatusCode().value());
        assertTrue(column.getBody().contains("Unknown column"), column.getBody());
    }

    // ------------------------------------------------------------ Excel files

    @Test
    void theExportIsAWorkbookAndImportsBackWithoutChangingAnything() throws Exception {
        ResponseEntity<byte[]> export = viewer().getForEntity("/api/export/items.xlsx", byte[].class);
        assertEquals(200, export.getStatusCode().value());
        byte[] bytes = export.getBody();
        assertNotNull(bytes);
        assertEquals('P', bytes[0]);
        assertEquals('K', bytes[1]);
        assertTrue(String.valueOf(export.getHeaders().getContentDisposition()).contains("yard-items-"));

        JsonNode report = upload(editor(), "items.xlsx", bytes, false, false);
        assertEquals(get(viewer(), "/api/summary").get("items").asInt(), report.get("total").asInt());
        assertEquals(0, report.get("errors").asInt(), "the export is a valid import: " + report);
        assertEquals(0, report.get("changed").asInt(), "nothing differs from the data it came from");
    }

    @Test
    void anEditedWorkbookIsCheckedFirstAndThenApplied() throws Exception {
        JsonNode planned = plannedTopLevel().get(0);
        String name = planned.get("name").asString();
        String zone = aZoneCode();
        byte[] file = workbook(new String[] {"Name", "Zone", "Version", "Notes"},
                List.<String[]>of(new String[] {name, zone, planned.get("version").asString(), "ignored column"}));

        JsonNode preview = upload(editor(), "moves.xlsx", file, false, false);
        assertFalse(preview.get("applied").asBoolean());
        assertEquals(1, preview.get("changed").asInt(), preview.toString());
        assertTrue(preview.get("notes").toString().contains("Notes"), "ignored columns are mentioned: " + preview);
        assertEquals("PLANNED", gridRow(name).get("status").asString(), "a preview saves nothing");

        JsonNode applied = upload(editor(), "moves.xlsx", file, true, false);
        assertTrue(applied.get("applied").asBoolean(), applied.toString());
        assertEquals("ACTIVE", gridRow(name).get("status").asString());
        String history = get(viewer(), "/api/items/" + gridRow(name).get("id").asString() + "/activity").toString();
        assertTrue(history.contains("Imported from moves.xlsx"), history);
    }

    @Test
    void importedProblemsAreReportedNotSilentlyDropped() throws Exception {
        JsonNode planned = plannedTopLevel().get(0);
        byte[] file = workbook(new String[] {"Name", "Zone"}, List.<String[]>of(
                new String[] {planned.get("name").asString(), aZoneCode()},
                new String[] {"NOT-AN-ITEM", aZoneCode()},
                new String[] {planned.get("name").asString(), aZoneCode()}));
        JsonNode report = upload(editor(), "problems.xlsx", file, false, false);
        assertEquals(3, report.get("total").asInt());
        assertEquals(2, report.get("errors").asInt(), "an unknown item and a duplicated item: " + report);

        JsonNode blocked = upload(editor(), "problems.xlsx", file, true, false);
        assertFalse(blocked.get("applied").asBoolean());
        assertEquals("PLANNED", gridRow(planned.get("name").asString()).get("status").asString());
    }

    @Test
    void onlyEditorsCanImportAndOnlyWorkbooksAreAccepted() throws Exception {
        byte[] file = workbook(new String[] {"Name", "Zone"}, List.<String[]>of(new String[] {"X", "Y"}));
        assertEquals(403, uploadRaw(viewer(), "a.xlsx", file, false, false).getStatusCode().value());

        ResponseEntity<String> csv = uploadRaw(editor(), "data.csv", "Name,Zone\nA,B\n".getBytes(), false, false);
        assertEquals(400, csv.getStatusCode().value());
        assertTrue(csv.getBody().contains(".xlsx"), csv.getBody());

        ResponseEntity<String> fake = uploadRaw(editor(), "fake.xlsx", "this is not a workbook".getBytes(), false, false);
        assertEquals(400, fake.getStatusCode().value());

        byte[] noHeader = workbook(new String[] {"Colour", "Size"}, List.<String[]>of(new String[] {"red", "L"}));
        ResponseEntity<String> noName = uploadRaw(editor(), "nohdr.xlsx", noHeader, false, false);
        assertEquals(400, noName.getStatusCode().value());
        assertTrue(noName.getBody().contains("Name"), noName.getBody());
    }

    // ------------------------------------------------------------------ admin

    @Test
    void theAdminApiIsForAdministratorsOnly() {
        for (String url : new String[] {"/api/admin/users", "/api/admin/events", "/api/admin/system"}) {
            assertEquals(403, viewer().getForEntity(url, String.class).getStatusCode().value(), url);
            assertEquals(403, editor().getForEntity(url, String.class).getStatusCode().value(), url);
            assertEquals(200, admin().getForEntity(url, String.class).getStatusCode().value(), url);
        }
        assertEquals(403, postJson(editor(), "/api/admin/users", Map.of("identifier", "x", "role", "VIEWER")).getStatusCode().value());
    }

    @Test
    void anAdministratorCanAddChangeAndRemovePeople() throws Exception {
        ResponseEntity<String> added = postJson(admin(), "/api/admin/users", Map.of("identifier", "New.Person@Example.test", "role", "EDITOR"));
        assertEquals(201, added.getStatusCode().value(), added.getBody());
        JsonNode person = json(added);
        assertEquals("new.person@example.test", person.get("email").asString(), "stored in lower case");
        assertEquals("EDITOR", person.get("role").asString());
        assertTrue(person.get("pending").asBoolean(), "has not signed in yet");
        long id = person.get("id").asLong();

        assertEquals(409, postJson(admin(), "/api/admin/users", Map.of("identifier", "new.person@example.test", "role", "VIEWER"))
                .getStatusCode().value(), "the same address twice");

        JsonNode viewerNow = json(putJson(admin(), "/api/admin/users/" + id, Map.of("role", "VIEWER")));
        assertEquals("VIEWER", viewerNow.get("role").asString());
        JsonNode noRole = json(putJson(admin(), "/api/admin/users/" + id, Map.of("role", "NONE")));
        assertTrue(noRole.get("role").isNull());
        JsonNode disabled = json(putJson(admin(), "/api/admin/users/" + id, Map.of("enabled", false)));
        assertFalse(disabled.get("enabled").asBoolean());
        assertEquals(400, putJson(admin(), "/api/admin/users/" + id, Map.of("role", "SUPERUSER")).getStatusCode().value());
        assertEquals(404, putJson(admin(), "/api/admin/users/999999", Map.of("role", "VIEWER")).getStatusCode().value());

        String log = get(admin(), "/api/admin/events").toString();
        assertTrue(log.contains("USER_ADDED") && log.contains("ROLE_CHANGED") && log.contains("USER_DISABLED"), log);
        assertTrue(log.contains("Ada Admin (admin)"), "the log names the administrator: " + log);

        ResponseEntity<Void> removed = admin().exchange("/api/admin/users/" + id, HttpMethod.DELETE, null, Void.class);
        assertEquals(204, removed.getStatusCode().value());
        for (JsonNode user : get(admin(), "/api/admin/users").get("users")) {
            assertTrue(user.get("id").asLong() != id, "the removed person is gone");
        }
    }

    @Test
    void anAdministratorCannotChangeTheirOwnAccess() throws Exception {
        JsonNode me = json(postJson(admin(), "/api/admin/users", Map.of("identifier", "admin", "role", "ADMIN")));
        ResponseEntity<String> change = putJson(admin(), "/api/admin/users/" + me.get("id").asLong(), Map.of("role", "VIEWER"));
        assertEquals(400, change.getStatusCode().value());
        assertTrue(change.getBody().contains("own access"), change.getBody());
        ResponseEntity<Void> remove = admin().exchange("/api/admin/users/" + me.get("id").asLong(), HttpMethod.DELETE, null, Void.class);
        assertEquals(400, remove.getStatusCode().value());
    }

    @Test
    void theUsersResponseSaysWhetherTheseRecordsApplyToHowPeopleSignIn() throws Exception {
        JsonNode users = get(admin(), "/api/admin/users");
        assertEquals("basic", users.get("authMode").asString());
        assertFalse(users.get("signInManaged").asBoolean(), "in basic mode the roles come from the configuration");
        assertEquals("basic", get(admin(), "/api/admin/system").get("authMode").asString());
    }

    @Test
    void onlyAdministratorsCanAddAShip() {
        Map<String, String> ship = Map.of("code", "S950", "name", "Page test ship", "color", "#445566");
        assertEquals(403, postJson(editor(), "/api/hulls", ship).getStatusCode().value());
        assertEquals(201, postJson(admin(), "/api/hulls", ship).getStatusCode().value());
    }

    // ------------------------------------------------------------ file helpers

    private JsonNode upload(TestRestTemplate as, String filename, byte[] content, boolean apply, boolean skipErrors) throws IOException {
        ResponseEntity<String> response = uploadRaw(as, filename, content, apply, skipErrors);
        assertEquals(200, response.getStatusCode().value(), filename + " -> " + response.getBody());
        return mapper.readTree(response.getBody());
    }

    private ResponseEntity<String> uploadRaw(TestRestTemplate as, String filename, byte[] content, boolean apply, boolean skipErrors) {
        ByteArrayResource file = new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", file);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return as.exchange("/api/import/items?apply=" + apply + "&skipErrors=" + skipErrors, HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
    }

    /** A one-sheet workbook ("Items") with a header row and text cells, the way someone would type it. */
    private static byte[] workbook(String[] header, List<String[]> rows) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Items");
            Row head = sheet.createRow(0);
            for (int c = 0; c < header.length; c++) {
                head.createCell(c).setCellValue(header[c]);
            }
            for (int r = 0; r < rows.size(); r++) {
                Row row = sheet.createRow(r + 1);
                for (int c = 0; c < header.length; c++) {
                    row.createCell(c).setCellValue(rows.get(r)[c]);
                }
            }
            workbook.write(out);
            return out.toByteArray();
        }
    }
}
