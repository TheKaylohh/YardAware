package com.shipyard.tracker.service;

import com.shipyard.tracker.service.BulkDtos.BulkRow;
import com.shipyard.tracker.service.BulkDtos.GridRow;
import com.shipyard.tracker.service.Dtos.PhaseDto;
import com.shipyard.tracker.service.Dtos.ZoneDto;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationConstraint;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * The Excel side of bulk editing: writes the item list as a workbook and reads one back. The workbook has an "Items"
 * sheet (grey columns are for reading, yellow headers are the ones that are imported: Zone, Phase, Specs), plus "Zones",
 * "Phases" and "Read me" sheets. Reading only looks at the headers it knows, so extra columns and reordered columns are fine.
 */
public final class ItemSheets {

    public static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    public static final int MAX_ROWS = 5000;

    private static final Logger log = LoggerFactory.getLogger(ItemSheets.class);

    private static final String[] HEADERS = {
        "Name", "Hull", "Level", "Area", "Parent", "Status", "Zone", "Phase", "Phase name", "Allowed phases", "Specs", "Version"};
    private static final int[] WIDTHS = {26, 8, 18, 7, 24, 11, 10, 7, 24, 16, 40, 8};
    private static final int ZONE_COL = 6;
    private static final int PHASE_COL = 7;
    private static final int SPECS_COL = 10;
    private static final Set<String> EDITABLE = Set.of("Zone", "Phase", "Specs");
    private static final Set<String> READ_ONLY_KEYS = Set.of("hull", "level", "area", "parent", "status", "phasename", "allowedphases");

    /** What was found in an uploaded sheet. {@code columns} lists which of zone / phase / specs it contains. */
    public record ParsedSheet(List<String> columns, List<BulkRow> rows, List<String> notes) {
    }

    private ItemSheets() {
    }

    // ------------------------------------------------------------------ write

    public static byte[] write(List<GridRow> rows, List<ZoneDto> zones, List<PhaseDto> phases) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSSFFont whiteBold = wb.createFont();
            whiteBold.setBold(true);
            whiteBold.setColor(IndexedColors.WHITE.getIndex());
            XSSFFont darkBold = wb.createFont();
            darkBold.setBold(true);

            CellStyle readOnlyHeader = wb.createCellStyle();
            readOnlyHeader.setFont(whiteBold);
            readOnlyHeader.setFillForegroundColor(IndexedColors.GREY_80_PERCENT.getIndex());
            readOnlyHeader.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            CellStyle editHeader = wb.createCellStyle();
            editHeader.setFont(darkBold);
            editHeader.setFillForegroundColor(IndexedColors.GOLD.getIndex());
            editHeader.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            CellStyle readOnly = wb.createCellStyle();
            readOnly.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            readOnly.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            Sheet items = wb.createSheet("Items");
            Row header = items.createRow(0);
            for (int c = 0; c < HEADERS.length; c++) {
                Cell cell = header.createCell(c);
                cell.setCellValue(HEADERS[c]);
                cell.setCellStyle(EDITABLE.contains(HEADERS[c]) ? editHeader : readOnlyHeader);
                items.setColumnWidth(c, WIDTHS[c] * 256);
            }
            int r = 1;
            for (GridRow row : rows) {
                Row line = items.createRow(r++);
                text(line, 0, row.name(), readOnly);
                text(line, 1, row.hull(), readOnly);
                text(line, 2, row.level(), readOnly);
                text(line, 3, row.area(), readOnly);
                text(line, 4, row.parent(), readOnly);
                text(line, 5, row.status(), readOnly);
                text(line, ZONE_COL, row.zone(), null);
                Cell phase = line.createCell(PHASE_COL);
                if (row.phase() != null) {
                    phase.setCellValue(row.phase().doubleValue());
                }
                text(line, 8, row.phaseName(), readOnly);
                text(line, 9, row.route(), readOnly);
                text(line, SPECS_COL, row.specs(), null);
                Cell version = line.createCell(11);
                version.setCellValue((double) row.version());
                version.setCellStyle(readOnly);
            }
            items.createFreezePane(1, 1);
            items.setAutoFilter(new CellRangeAddress(0, Math.max(1, rows.size()), 0, HEADERS.length - 1));

            Sheet zoneSheet = wb.createSheet("Zones");
            Row zoneHeader = zoneSheet.createRow(0);
            String[] zoneHeaders = {"Code", "Name", "Kind", "Facility"};
            for (int c = 0; c < zoneHeaders.length; c++) {
                Cell cell = zoneHeader.createCell(c);
                cell.setCellValue(zoneHeaders[c]);
                cell.setCellStyle(readOnlyHeader);
                zoneSheet.setColumnWidth(c, (c == 1 ? 28 : 14) * 256);
            }
            int z = 1;
            for (ZoneDto zone : zones) {
                Row line = zoneSheet.createRow(z++);
                text(line, 0, zone.code(), null);
                text(line, 1, zone.name(), null);
                text(line, 2, zone.kind(), null);
                text(line, 3, zone.facility(), null);
            }

            Sheet phaseSheet = wb.createSheet("Phases");
            Row phaseHeader = phaseSheet.createRow(0);
            String[] phaseHeaders = {"Number", "Name", "Facility"};
            for (int c = 0; c < phaseHeaders.length; c++) {
                Cell cell = phaseHeader.createCell(c);
                cell.setCellValue(phaseHeaders[c]);
                cell.setCellStyle(readOnlyHeader);
                phaseSheet.setColumnWidth(c, (c == 1 ? 30 : 14) * 256);
            }
            int p = 1;
            for (PhaseDto phase : phases) {
                Row line = phaseSheet.createRow(p++);
                line.createCell(0).setCellValue((double) phase.number());
                text(line, 1, phase.name(), null);
                text(line, 2, phase.facility(), null);
            }

            // A drop-down of zone codes on the Zone column (typing a code or a zone name also works on import).
            if (!zones.isEmpty() && !rows.isEmpty()) {
                DataValidationHelper helper = items.getDataValidationHelper();
                DataValidationConstraint list = helper.createFormulaListConstraint("Zones!$A$2:$A$" + (zones.size() + 1));
                CellRangeAddressList where = new CellRangeAddressList(1, rows.size(), ZONE_COL, ZONE_COL);
                DataValidation validation = helper.createValidation(list, where);
                validation.setShowErrorBox(true);
                validation.createErrorBox("Zone", "Choose a zone code from the Zones sheet.");
                items.addValidationData(validation);
            }

            Sheet help = wb.createSheet("Read me");
            help.setColumnWidth(0, 110 * 256);
            String[] lines = {
                "Yard Tracker: item sheet",
                "",
                "Change the yellow columns (Zone, Phase, Specs) and upload the file on the Import page. Grey columns are for reading.",
                "Zone: a zone code or name from the Zones sheet. A planned item that gets a zone is placed on the yard; an item on the yard is moved.",
                "Phase: a number from the item's 'Allowed phases'. Only items on the yard can change phase.",
                "Specs: a JSON object such as {\"Weight (t)\": 92.5, \"Drawing\": \"D-1204\"}. Write {} to clear them.",
                "A blank Zone, Phase or Specs cell means 'leave as it is'.",
                "Version: leave it alone. If someone else changed the item after this sheet was made, that row is refused instead of overwriting their work.",
                "Do not rename the Name column or the Items sheet. You may add your own columns; they are ignored.",
                "The Import page shows every change and every problem before anything is saved.",
            };
            for (int i = 0; i < lines.length; i++) {
                text(help.createRow(i), 0, lines[i], null);
            }

            wb.write(out);
            return out.toByteArray();
        }
    }

    private static void text(Row row, int column, String value, CellStyle style) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value == null ? "" : value);
        if (style != null) {
            cell.setCellStyle(style);
        }
    }

    // ------------------------------------------------------------------- read

    public static ParsedSheet read(byte[] data) {
        if (data == null || data.length < 4 || data[0] != 'P' || data[1] != 'K') {
            throw bad("That isn't an .xlsx workbook. In Excel, use Save As and pick 'Excel Workbook (*.xlsx)'.");
        }
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(data))) {
            if (wb.getNumberOfSheets() == 0) {
                throw bad("The workbook has no sheets.");
            }
            Sheet sheet = wb.getSheet("Items");
            if (sheet == null) {
                sheet = wb.getSheetAt(0);
            }
            return parse(sheet, wb.getCreationHelper().createFormulaEvaluator());
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Could not read an uploaded workbook: {}", e.toString());
            throw bad("That file couldn't be read as an .xlsx workbook. Export a fresh sheet from the Data page and try again.");
        }
    }

    private static ParsedSheet parse(Sheet sheet, FormulaEvaluator evaluator) {
        DataFormatter formatter = new DataFormatter(Locale.ROOT);
        int firstRow = sheet.getFirstRowNum();
        int lastRow = sheet.getLastRowNum();

        // Find the header row: the first of the top rows that has a Name column.
        Row header = null;
        Map<String, Integer> columns = new LinkedHashMap<>();
        for (int r = firstRow; r <= Math.min(lastRow, firstRow + 10) && header == null; r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            Map<String, Integer> found = new LinkedHashMap<>();
            for (int c = 0; c < Math.max(0, (int) row.getLastCellNum()); c++) {
                String key = key(cellText(row, c, formatter, evaluator));
                if (!key.isEmpty() && !found.containsKey(key)) {
                    found.put(key, c);
                }
            }
            if (found.containsKey("name") || found.containsKey("item")) {
                header = row;
                columns = found;
            }
        }
        if (header == null) {
            throw bad("Couldn't find a header row with a 'Name' column. Export a fresh sheet from the Data page and edit that.");
        }
        int nameCol = columns.containsKey("name") ? columns.get("name") : columns.get("item");
        Integer zoneCol = columns.get("zone");
        Integer phaseCol = columns.get("phase");
        Integer specsCol = columns.get("specs");
        Integer versionCol = columns.get("version");

        List<String> found = new ArrayList<>();
        if (zoneCol != null) {
            found.add("zone");
        }
        if (phaseCol != null) {
            found.add("phase");
        }
        if (specsCol != null) {
            found.add("specs");
        }
        if (found.isEmpty()) {
            throw bad("The sheet has no Zone, Phase or Specs column, so there is nothing to import.");
        }

        List<String> notes = new ArrayList<>();
        List<String> ignored = new ArrayList<>();
        for (Map.Entry<String, Integer> e : columns.entrySet()) {
            String k = e.getKey();
            boolean known = k.equals("name") || k.equals("item") || k.equals("zone") || k.equals("phase")
                    || k.equals("specs") || k.equals("version") || READ_ONLY_KEYS.contains(k);
            if (!known) {
                ignored.add(cellText(header, e.getValue(), formatter, evaluator));
            }
        }
        if (!ignored.isEmpty()) {
            notes.add("Ignored columns: " + String.join(", ", ignored) + ".");
        }
        if (versionCol == null) {
            notes.add("There is no Version column, so changes made by other people since the sheet was made can't be detected.");
        }

        List<BulkRow> rows = new ArrayList<>();
        int headerIndex = header.getRowNum();
        for (int r = headerIndex + 1; r <= lastRow; r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            String name = cellText(row, nameCol, formatter, evaluator);
            String zone = zoneCol == null ? "" : cellText(row, zoneCol, formatter, evaluator);
            String phase = phaseCol == null ? "" : cellText(row, phaseCol, formatter, evaluator);
            String specs = specsCol == null ? "" : cellText(row, specsCol, formatter, evaluator);
            String version = versionCol == null ? "" : cellText(row, versionCol, formatter, evaluator);
            if (name.isEmpty() && zone.isEmpty() && phase.isEmpty() && specs.isEmpty()) {
                continue;   // an empty line
            }
            if (rows.size() >= MAX_ROWS) {
                throw bad("The sheet has more than " + MAX_ROWS + " rows. Split it into smaller files.");
            }
            rows.add(new BulkRow(r + 1, name, version, zone, phase, specs));
        }
        if (rows.isEmpty()) {
            throw bad("The sheet has a header but no rows.");
        }
        return new ParsedSheet(found, rows, notes);
    }

    private static String cellText(Row row, int column, DataFormatter formatter, FormulaEvaluator evaluator) {
        Cell cell = row.getCell(column, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if (cell == null) {
            return "";
        }
        try {
            String text = formatter.formatCellValue(cell, evaluator);
            return text == null ? "" : text.trim();
        } catch (RuntimeException e) {
            return "";   // a formula that cannot be evaluated: treat as empty
        }
    }

    private static String key(String header) {
        return header.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
