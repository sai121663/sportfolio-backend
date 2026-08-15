package com.example.demo.tank01;

import com.example.demo.pricing.PriceHistory;
import com.example.demo.pricing.PriceHistoryRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

// TEMPORARY -- lets you manually trigger MLB ingestion for any date, e.g. to
// backfill yesterday and test that the new price formula moves prices across
// two game-days without waiting on the scheduled job. Delete this file once
// you're done testing; it has no auth and shouldn't ship anywhere real.
@RestController
public class AdminIngestionController {

    private static final DateTimeFormatter GAME_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final MlbIngestionService mlbIngestionService;
    private final PriceHistoryRepository priceHistoryRepository;

    public AdminIngestionController(MlbIngestionService mlbIngestionService, PriceHistoryRepository priceHistoryRepository) {
        this.mlbIngestionService = mlbIngestionService;
        this.priceHistoryRepository = priceHistoryRepository;
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

    // TEMPORARY -- dumps price_history rows that have a raw stat line attached,
    // one JSON object per line, PAGINATED so large exports can be pulled down
    // in controlled chunks instead of one giant response. Use ?offset=0&limit=300,
    // then ?offset=300&limit=300, etc. until a page comes back empty.
    @GetMapping("/admin/export-raw-stats")
    public String exportRawStats(
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "300") int limit
    ) {
        List<PriceHistory> all = priceHistoryRepository.findAll();

        StringBuilder json = new StringBuilder();
        int matched = 0;
        int emitted = 0;
        for (PriceHistory record : all) {
            String rawStats = record.getRawStatsJson();
            if (rawStats == null || rawStats.isEmpty() || rawStats.equals("{}")) continue;

            if (matched >= offset && emitted < limit) {
                String position = (record.getPlayer() != null && record.getPlayer().getPosition() != null)
                        ? record.getPlayer().getPosition() : "";

                json.append("{");
                json.append("\"position\":\"").append(position.replace("\"", "\\\"")).append("\",");
                json.append("\"fantasyPoints\":").append(record.getFantasyPoints() != null ? record.getFantasyPoints() : 0.0).append(",");
                json.append("\"gameDate\":\"").append(record.getGameDate()).append("\",");
                json.append("\"rawStats\":").append(rawStats);
                json.append("}\n");
                emitted++;
            }
            matched++;
        }
        return json.toString();
    }

    // TEMPORARY -- quick way to check how many total records are available to
    // export, so you know how many pages you'll need to pull.
    @GetMapping("/admin/export-raw-stats/count")
    public String countRawStats() {
        List<PriceHistory> all = priceHistoryRepository.findAll();
        long count = all.stream()
                .filter(r -> r.getRawStatsJson() != null && !r.getRawStatsJson().isEmpty() && !r.getRawStatsJson().equals("{}"))
                .count();
        return "Total records with raw stats: " + count;
    }
}