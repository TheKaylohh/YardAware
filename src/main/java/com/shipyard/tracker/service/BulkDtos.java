package com.shipyard.tracker.service;

import java.time.Instant;
import java.util.List;

/** Request and response shapes for the data grid, the Excel export/import and the home page summary. */
public final class BulkDtos {

    private BulkDtos() {
    }

    /** One line of the data grid / the Excel sheet. Read-only columns first, then the three editable ones. */
    public record GridRow(
            Long id,
            String name,
            String hull,
            String level,
            String area,
            String parent,
            String status,
            String zone,
            Integer phase,
            String phaseName,
            String route,
            List<Integer> routePhases,
            String specs,
            long version) {
    }

    /**
     * One row to change. {@code zone} is a zone code or name, {@code phase} a phase number, {@code specs} a JSON object as
     * text, {@code version} the item version the sheet was made from (optional; guards against overwriting newer work).
     * Only the columns named in {@link BulkRequest#columns()} are looked at, and a blank value means "leave as it is".
     */
    public record BulkRow(Integer row, String name, String version, String zone, String phase, String specs) {
    }

    /** {@code columns} says which of zone / phase / specs this request is about. */
    public record BulkRequest(List<String> columns, List<BulkRow> rows, boolean skipErrors, String source) {
    }

    public record FieldChange(String field, String from, String to) {
    }

    /** outcome is CHANGE, UNCHANGED or ERROR. */
    public record RowResult(Integer row, String name, String outcome, String message, List<FieldChange> changes) {
    }

    /** {@code applied} is true only when changes were really saved. */
    public record BulkReport(
            boolean applied,
            int total,
            int changed,
            int unchanged,
            int errors,
            List<RowResult> rows,
            List<String> notes) {
    }

    // ---- home page ----

    public record HullProgress(Long id, String code, String name, String color, long planned, long active, long consumed) {
    }

    public record PhaseCount(int number, String name, long count) {
    }

    public record ZoneLoad(Long id, String code, String name, long count) {
    }

    public record RecentActivity(
            Long id,
            String type,
            String item,
            String hull,
            String fromZone,
            String toZone,
            String actor,
            String note,
            Instant timestamp) {
    }

    public record Summary(
            long hulls,
            long zones,
            long items,
            long planned,
            long active,
            long consumed,
            List<HullProgress> hullProgress,
            List<PhaseCount> activeByPhase,
            List<ZoneLoad> zoneLoad,
            List<RecentActivity> recent) {
    }
}
