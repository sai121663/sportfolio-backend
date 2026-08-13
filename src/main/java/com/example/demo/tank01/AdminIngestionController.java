package com.example.demo.tank01;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// TEMPORARY -- lets you manually trigger MLB ingestion for any date, e.g. to
// backfill yesterday and test that the new price formula moves prices across
// two game-days without waiting on the scheduled job. Delete this file once
// you're done testing; it has no auth and shouldn't ship anywhere real.
@RestController
public class AdminIngestionController {

    private final MlbIngestionService mlbIngestionService;

    public AdminIngestionController(MlbIngestionService mlbIngestionService) {
        this.mlbIngestionService = mlbIngestionService;
    }

    @GetMapping("/admin/ingest-mlb")
    public String ingest(@RequestParam String date) {
        int count = mlbIngestionService.ingestMlbFantasyData(date);
        return "Ingested " + count + " records for " + date;
    }
}
