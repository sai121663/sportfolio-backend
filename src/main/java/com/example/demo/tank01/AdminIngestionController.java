package com.example.demo.tank01;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

// TEMPORARY -- lets you manually trigger MLB ingestion for any date, e.g. to
// backfill yesterday and test that the new price formula moves prices across
// two game-days without waiting on the scheduled job. Delete this file once
// you're done testing; it has no auth and shouldn't ship anywhere real.
@RestController
public class AdminIngestionController {

    private static final DateTimeFormatter GAME_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final MlbIngestionService mlbIngestionService;

    public AdminIngestionController(MlbIngestionService mlbIngestionService) {
        this.mlbIngestionService = mlbIngestionService;
    }

    @GetMapping("/admin/ingest-mlb")
    public String ingest(@RequestParam String date) {
        int count = mlbIngestionService.ingestMlbFantasyData(date);
        return "Ingested " + count + " records for " + date;
    }

    // Backfills every day in a date range, one at a time, reusing the exact
    // same ingestion logic (including the existing skip-if-already-ingested
    // check). Meant for quickly harvesting a couple weeks of real games' worth
    // of raw stat data, instead of waiting for them to happen day by day.
    // Dates are inclusive, format yyyyMMdd. This can take a couple minutes to
    // run for a multi-week range -- that's expected, just let it finish.
    @GetMapping("/admin/ingest-range")
    public String ingestRange(@RequestParam String startDate, @RequestParam String endDate) {
        LocalDate start = LocalDate.parse(startDate, GAME_DATE_FORMAT);
        LocalDate end = LocalDate.parse(endDate, GAME_DATE_FORMAT);

        int totalRecords = 0;
        int daysProcessed = 0;

        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            String gameDate = date.format(GAME_DATE_FORMAT);
            totalRecords += mlbIngestionService.ingestMlbFantasyData(gameDate);
            daysProcessed++;
        }

        return "Backfilled " + daysProcessed + " days (" + startDate + " to " + endDate
                + "), " + totalRecords + " total records ingested.";
    }
}