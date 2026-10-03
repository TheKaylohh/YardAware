package com.shipyard.tracker.web;

import com.shipyard.tracker.service.BulkDtos.Summary;
import com.shipyard.tracker.service.SummaryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The numbers on the home page. */
@RestController
@RequestMapping("/api")
public class SummaryController {

    private final SummaryService summary;

    public SummaryController(SummaryService summary) {
        this.summary = summary;
    }

    @GetMapping("/summary")
    public Summary summary() {
        return summary.summary();
    }
}
