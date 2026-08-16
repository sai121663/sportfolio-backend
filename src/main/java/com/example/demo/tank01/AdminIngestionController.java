package com.example.demo.tank01;

import com.example.demo.pricing.PriceHistory;
import com.example.demo.pricing.PriceHistoryRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

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

    @GetMapping("/admin/ingest-range")
    public String ingestRange(@RequestParam String startDate, @RequestParam String endDate) {
        LocalDate start = LocalDate.parse(startDate, GAME_DATE_FORMAT);
        LocalDate end = LocalDate.parse(endDate, GAME_DATE_FORMAT);

        int totalRecords = 0;
        int daysProcessed = 0;
        int daysFailed = 0;
        StringBuilder failures = new StringBuilder();

        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            String gameDate = date.format(GAME_DATE_FORMAT);
            try {
                totalRecords += mlbIngestionService.ingestMlbFantasyData(gameDate);
                daysProcessed++;
            } catch (Exception e) {
                daysFailed++;
                failures.append(gameDate).append(": ").append(e.getMessage()).append("; ");
                // Keep going -- one bad day (e.g. a quota/rate-limit error from Tank01)
                // shouldn't take down the rest of the backfill.
            }
        }

        String result = "Backfilled " + daysProcessed + " days (" + startDate + " to " + endDate
                + "), " + totalRecords + " total records ingested.";
        if (daysFailed > 0) {
            result += " " + daysFailed + " day(s) FAILED: " + failures;
        }
        return result;
    }

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

    @GetMapping("/admin/export-raw-stats/count")
    public String countRawStats() {
        List<PriceHistory> all = priceHistoryRepository.findAll();
        long count = all.stream()
                .filter(r -> r.getRawStatsJson() != null && !r.getRawStatsJson().isEmpty() && !r.getRawStatsJson().equals("{}"))
                .count();
        return "Total records with raw stats: " + count;
    }
}