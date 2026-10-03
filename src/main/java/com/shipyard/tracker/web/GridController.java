package com.shipyard.tracker.web;

import com.shipyard.tracker.service.BulkDtos.BulkReport;
import com.shipyard.tracker.service.BulkDtos.BulkRequest;
import com.shipyard.tracker.service.BulkDtos.GridRow;
import com.shipyard.tracker.service.BulkItemService;
import com.shipyard.tracker.service.GridService;
import com.shipyard.tracker.service.ItemSheets;
import com.shipyard.tracker.service.ItemSheets.ParsedSheet;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * The data grid, the Excel export and the Excel import. Reading needs a signed-in viewer; every POST needs an editor
 * (see SecuritySupport).
 */
@RestController
@RequestMapping("/api")
public class GridController {

    private static final long MAX_UPLOAD_BYTES = 2L * 1024 * 1024;

    private final GridService grid;
    private final BulkItemService bulk;

    public GridController(GridService grid, BulkItemService bulk) {
        this.grid = grid;
        this.bulk = bulk;
    }

    /** Every item, whatever its status, in the shape the data grid shows. */
    @GetMapping("/grid/items")
    public List<GridRow> rows() {
        return grid.rows();
    }

    /** Dry run: what would these edits do? Saves nothing. */
    @PostMapping("/grid/items/check")
    public BulkReport check(@RequestBody BulkRequest request) {
        return bulk.check(withSource(request, "Bulk edit in the data grid"));
    }

    /** Saves the edits when every row is fine (or when skipErrors is set, the rows that are). */
    @PostMapping("/grid/items/save")
    public BulkReport save(@RequestBody BulkRequest request) {
        return bulk.save(withSource(request, "Bulk edit in the data grid"));
    }

    @GetMapping("/export/items.xlsx")
    public ResponseEntity<byte[]> export() throws IOException {
        byte[] data = grid.exportWorkbook();
        String filename = "yard-items-" + LocalDate.now() + ".xlsx";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(ItemSheets.XLSX))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
                .body(data);
    }

    /**
     * Reads an uploaded item sheet. Without {@code apply} it only reports what would change; with it, the changes are
     * saved (all or nothing, see BulkItemService). The browser sends the same file for both steps.
     */
    @PostMapping(value = "/import/items", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public BulkReport importItems(@RequestParam("file") MultipartFile file,
                                  @RequestParam(name = "apply", defaultValue = "false") boolean apply,
                                  @RequestParam(name = "skipErrors", defaultValue = "false") boolean skipErrors)
            throws IOException {
        if (file == null || file.isEmpty()) {
            throw bad("Choose an .xlsx file to upload.");
        }
        String original = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        if (!original.toLowerCase().endsWith(".xlsx")) {
            throw bad("Only .xlsx files can be imported. In Excel, use Save As and pick 'Excel Workbook (*.xlsx)'.");
        }
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            throw bad("That file is larger than 2 MB. Split it into smaller files.");
        }
        ParsedSheet sheet = ItemSheets.read(file.getBytes());
        String shortName = original.replaceAll("[\\\\/]", "_");
        BulkRequest request = new BulkRequest(sheet.columns(), sheet.rows(), skipErrors, "Imported from " + shortName);
        BulkReport report = apply ? bulk.save(request) : bulk.check(request);

        List<String> notes = new ArrayList<>(sheet.notes());
        notes.addAll(report.notes());
        return new BulkReport(report.applied(), report.total(), report.changed(), report.unchanged(), report.errors(),
                report.rows(), notes);
    }

    private static BulkRequest withSource(BulkRequest request, String fallback) {
        if (request == null) {
            throw bad("Send the rows to check.");
        }
        return new BulkRequest(request.columns(), request.rows(), request.skipErrors(),
                request.source() == null || request.source().isBlank() ? fallback : request.source());
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
