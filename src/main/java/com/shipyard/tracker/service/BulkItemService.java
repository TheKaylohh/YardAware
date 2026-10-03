package com.shipyard.tracker.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shipyard.tracker.domain.Item;
import com.shipyard.tracker.domain.ItemStatus;
import com.shipyard.tracker.domain.Zone;
import com.shipyard.tracker.repo.ItemRepository;
import com.shipyard.tracker.repo.ZoneRepository;
import com.shipyard.tracker.service.BulkDtos.BulkReport;
import com.shipyard.tracker.service.BulkDtos.BulkRequest;
import com.shipyard.tracker.service.BulkDtos.BulkRow;
import com.shipyard.tracker.service.BulkDtos.FieldChange;
import com.shipyard.tracker.service.BulkDtos.RowResult;
import com.shipyard.tracker.service.Dtos.MoveRequest;
import com.shipyard.tracker.service.Dtos.PhaseRequest;
import com.shipyard.tracker.service.Dtos.PlaceRequest;
import com.shipyard.tracker.service.Dtos.UpdateItemRequest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Changes many items at once, for the data grid and the Excel import. A row says where an item should be (zone), which
 * phase it is in and what its specs are. Every change goes through {@link ItemService}, so the same rules apply as in the
 * map screen (a planned item is placed, an item on the yard is moved, a phase must be on the item's route) and every
 * change gets its history entry under the signed-in person's name.
 *
 * <p>Two steps. <b>check</b> tries every row in a transaction that is always rolled back and reports what would happen.
 * <b>save</b> does the same check, and when nothing is wrong (or the caller chose to skip bad rows) applies all good rows in
 * one transaction: either they all land or none does.
 */
@Service
public class BulkItemService {

    private static final Logger log = LoggerFactory.getLogger(BulkItemService.class);

    private static final Set<String> COLUMNS = Set.of("zone", "phase", "specs");
    private static final Pattern PHASE = Pattern.compile("(\\d{1,3})(\\.0+)?(\\s.*)?");
    private static final Pattern VERSION = Pattern.compile("(\\d{1,18})(\\.0+)?");
    private static final Pattern UNSAFE_TEXT = Pattern.compile("[\\p{Cc}\\u202A-\\u202E\\u2066-\\u2069]");
    private static final int SHOWN_SPECS = 80;

    private final ItemRepository items;
    private final ZoneRepository zones;
    private final ItemService itemService;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;

    public BulkItemService(ItemRepository items, ZoneRepository zones, ItemService itemService, TransactionTemplate tx,
                           ObjectMapper mapper) {
        this.items = items;
        this.zones = zones;
        this.itemService = itemService;
        this.tx = tx;
        this.mapper = mapper;
    }

    /** What would happen. Saves nothing. */
    public BulkReport check(BulkRequest request) {
        return run(request, false);
    }

    /** Applies the good rows if there are no bad ones (or {@code skipErrors} is set). */
    public BulkReport save(BulkRequest request) {
        return run(request, true);
    }

    // ------------------------------------------------------------------ flow

    private BulkReport run(BulkRequest request, boolean apply) {
        if (request == null || request.rows() == null || request.rows().isEmpty()) {
            throw bad("There is nothing to " + (apply ? "save" : "check") + ".");
        }
        if (request.rows().size() > ItemSheets.MAX_ROWS) {
            throw bad("At most " + ItemSheets.MAX_ROWS + " rows at a time.");
        }
        Set<String> columns = new HashSet<>();
        for (String column : request.columns() == null ? List.<String>of() : request.columns()) {
            String c = column == null ? "" : column.trim().toLowerCase(Locale.ROOT);
            if (!COLUMNS.contains(c)) {
                throw bad("Unknown column '" + column + "'. Columns can be zone, phase or specs.");
            }
            columns.add(c);
        }
        if (columns.isEmpty()) {
            throw bad("Say which columns to look at: zone, phase or specs.");
        }

        Context ctx = context(columns, request.source());
        List<RowResult> results = new ArrayList<>(request.rows().size());
        Set<String> seen = new HashSet<>();
        for (BulkRow row : request.rows()) {
            String key = row.name() == null ? "" : row.name().trim().toLowerCase(Locale.ROOT);
            if (!key.isEmpty() && !seen.add(key)) {
                results.add(error(row, row.name().trim(), "This item appears more than once in the file. Keep one row per item."));
                continue;
            }
            results.add(dryRun(row, ctx));
        }

        int changed = count(results, "CHANGE");
        int unchanged = count(results, "UNCHANGED");
        int errors = count(results, "ERROR");
        List<String> notes = new ArrayList<>();

        if (!apply) {
            return new BulkReport(false, results.size(), changed, unchanged, errors, results, notes);
        }
        if (errors > 0 && !request.skipErrors()) {
            notes.add("Nothing was saved because " + errors + (errors == 1 ? " row has" : " rows have")
                    + " problems. Fix them, or choose to skip the rows with problems.");
            return new BulkReport(false, results.size(), changed, unchanged, errors, results, notes);
        }
        if (changed == 0) {
            notes.add("There was nothing to change.");
            return new BulkReport(false, results.size(), changed, unchanged, errors, results, notes);
        }

        List<BulkRow> good = new ArrayList<>();
        for (int i = 0; i < results.size(); i++) {
            if ("CHANGE".equals(results.get(i).outcome())) {
                good.add(request.rows().get(i));
            }
        }
        applyAll(good, ctx);
        if (errors > 0) {
            notes.add(errors + (errors == 1 ? " row was" : " rows were") + " skipped because of problems.");
        }
        return new BulkReport(true, results.size(), changed, unchanged, errors, results, notes);
    }

    /** One row, in a transaction that is always rolled back. */
    private RowResult dryRun(BulkRow row, Context ctx) {
        return tx.execute(status -> {
            try {
                return perform(row, ctx);
            } catch (RuntimeException e) {
                log.warn("Bulk check failed for row {}: {}", row.row(), e.toString());
                return error(row, clean(row.name()) == null ? "" : clean(row.name()),
                        "This row couldn't be checked (" + e.getClass().getSimpleName() + "). See the server log.");
            } finally {
                status.setRollbackOnly();
            }
        });
    }

    /** All good rows in one transaction. If something changed since the check, nothing is saved. */
    private void applyAll(List<BulkRow> good, Context ctx) {
        tx.executeWithoutResult(status -> {
            for (BulkRow row : good) {
                RowResult result = perform(row, ctx);
                if ("ERROR".equals(result.outcome())) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Row " + describe(row) + " can no longer be saved ("
                            + result.message() + "). Someone may have changed the data. Nothing was saved; check again.");
                }
            }
        });
    }

    // ------------------------------------------------------------------- row

    /** Works out and performs the changes for one row. Never throws for a bad row: that is an ERROR result. */
    private RowResult perform(BulkRow row, Context ctx) {
        String name = clean(row.name());
        if (name == null) {
            return error(row, "", "The row has no item name.");
        }
        Item item = items.findByNameIgnoreCase(name).orElse(null);
        if (item == null) {
            return error(row, name, "There is no item called " + name + ".");
        }
        String actual = item.getName();

        Long expectedVersion;
        Long wantZoneId = null;
        Integer wantPhase = null;
        Map<String, Object> wantSpecs = null;
        Map<String, Object> currentSpecs = null;
        boolean specsChange = false;
        try {
            expectedVersion = parseVersion(row.version());
            if (ctx.columns.contains("zone")) {
                String z = clean(row.zone());
                if (z != null) {
                    Long id = ctx.zoneIds.get(z.toLowerCase(Locale.ROOT));
                    if (id == null) {
                        throw new IllegalArgumentException("Unknown zone '" + shorten(z, 40)
                                + "'. Use a zone code or name from the Zones list.");
                    }
                    Zone current = item.getZone();
                    if (current == null || !current.getId().equals(id)) {
                        wantZoneId = id;
                    }
                }
            }
            if (ctx.columns.contains("phase")) {
                wantPhase = parsePhase(row.phase());
            }
            if (ctx.columns.contains("specs")) {
                String s = clean(row.specs());
                if (s != null) {
                    wantSpecs = parseSpecs(s);
                    currentSpecs = readSpecs(item.getSpecs());
                    specsChange = !wantSpecs.equals(currentSpecs);
                }
            }
        } catch (IllegalArgumentException e) {
            return error(row, actual, e.getMessage());
        }

        if (expectedVersion != null && expectedVersion.longValue() != item.getVersion()) {
            return error(row, actual, "Someone changed this item after your sheet was made. Reload the data and redo this row.");
        }

        List<FieldChange> changes = new ArrayList<>();
        int phaseBefore = item.getPhase().getNumber();
        String zoneBefore = item.getZone() == null ? "" : item.getZone().getCode();
        ItemStatus statusBefore = item.getStatus();
        try {
            if (wantZoneId != null) {
                if (statusBefore == ItemStatus.PLANNED) {
                    itemService.place(item.getId(), new PlaceRequest(wantZoneId, ctx.note));
                    changes.add(new FieldChange("status", "PLANNED", "ACTIVE"));
                } else {
                    itemService.move(item.getId(), new MoveRequest(wantZoneId, ctx.note));
                }
                changes.add(new FieldChange("zone", zoneBefore, ctx.zoneCodes.get(wantZoneId)));
            }
            if (wantPhase != null && wantPhase.intValue() != item.getPhase().getNumber()) {
                itemService.changePhase(item.getId(), new PhaseRequest(wantPhase, ctx.note));
                changes.add(new FieldChange("phase", String.valueOf(phaseBefore), String.valueOf(wantPhase)));
            }
            if (specsChange) {
                itemService.update(item.getId(), new UpdateItemRequest(wantSpecs, ctx.note));
                changes.add(new FieldChange("specs", shorten(json(currentSpecs), SHOWN_SPECS),
                        shorten(json(wantSpecs), SHOWN_SPECS)));
            }
        } catch (ResponseStatusException e) {
            return error(row, actual, e.getReason() == null ? "Not allowed." : e.getReason());
        }
        return new RowResult(row.row(), actual, changes.isEmpty() ? "UNCHANGED" : "CHANGE", null, changes);
    }

    // --------------------------------------------------------------- parsing

    private static Integer parsePhase(String raw) {
        String text = clean(raw);
        if (text == null) {
            return null;
        }
        Matcher m = PHASE.matcher(text);
        if (!m.matches()) {
            throw new IllegalArgumentException("The phase '" + shorten(text, 30) + "' isn't a phase number.");
        }
        return Integer.valueOf(m.group(1));
    }

    private static Long parseVersion(String raw) {
        String text = clean(raw);
        if (text == null) {
            return null;
        }
        Matcher m = VERSION.matcher(text);
        if (!m.matches()) {
            throw new IllegalArgumentException("The version '" + shorten(text, 30) + "' isn't a number. Leave the Version column as exported.");
        }
        return Long.valueOf(m.group(1));
    }

    private Map<String, Object> parseSpecs(String text) {
        try {
            Map<String, Object> parsed = mapper.readValue(text, new TypeReference<LinkedHashMap<String, Object>>() { });
            if (parsed == null) {
                throw new IllegalArgumentException("Specs must be a JSON object like {\"Weight (t)\": 92.5}. Write {} to clear them.");
            }
            return parsed;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Specs must be a JSON object like {\"Weight (t)\": 92.5}. Write {} to clear them.");
        }
    }

    private Map<String, Object> readSpecs(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return mapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (JsonProcessingException e) {
            return new LinkedHashMap<>();
        }
    }

    private String json(Map<String, Object> specs) {
        try {
            return mapper.writeValueAsString(specs);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    // --------------------------------------------------------------- helpers

    /** Everything one request shares. */
    private static final class Context {
        final Set<String> columns;
        final Map<String, Long> zoneIds = new HashMap<>();
        final Map<Long, String> zoneCodes = new HashMap<>();
        final String note;

        Context(Set<String> columns, String note) {
            this.columns = columns;
            this.note = note;
        }
    }

    private Context context(Set<String> columns, String source) {
        String label = source == null ? "" : UNSAFE_TEXT.matcher(source).replaceAll("").trim();
        if (label.length() > 120) {
            label = label.substring(0, 120);
        }
        Context ctx = new Context(columns, label.isEmpty() ? "Bulk edit" : label);
        for (Zone zone : zones.findAll()) {
            ctx.zoneIds.put(zone.getCode().toLowerCase(Locale.ROOT), zone.getId());
            ctx.zoneIds.putIfAbsent(zone.getName().toLowerCase(Locale.ROOT), zone.getId());
            ctx.zoneCodes.put(zone.getId(), zone.getCode());
        }
        return ctx;
    }

    private static int count(List<RowResult> results, String outcome) {
        int n = 0;
        for (RowResult r : results) {
            if (outcome.equals(r.outcome())) {
                n++;
            }
        }
        return n;
    }

    private static RowResult error(BulkRow row, String name, String message) {
        return new RowResult(row.row(), name, "ERROR", message, List.of());
    }

    private static String describe(BulkRow row) {
        String name = row.name() == null ? "" : row.name().trim();
        return row.row() == null ? name : row.row() + " (" + name + ")";
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String shorten(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 3) + "...";
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
