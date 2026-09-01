// AdminIngestionController.java
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
    private final NflIngestionService nflIngestionService;

    public AdminIngestionController(MlbIngestionService mlbIngestionService, NflIngestionService nflIngestionService) {
        this.mlbIngestionService = mlbIngestionService;
        this.nflIngestionService = nflIngestionService;
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
        int daysFailed = 0;
        StringBuilder failures = new StringBuilder();

        // Per-day try/catch so a single transient failure (a RapidAPI gateway
        // hiccup reaching Tank01, a rate limit, etc.) doesn't abort the whole
        // range -- it just gets reported and the rest of the days still run.
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            String gameDate = date.format(GAME_DATE_FORMAT);
            try {
                totalRecords += mlbIngestionService.ingestMlbFantasyData(gameDate);
                daysProcessed++;
            } catch (Exception e) {
                daysFailed++;
                failures.append(gameDate).append(": ").append(e.getMessage()).append("; ");
            }
        }

        String result = "Backfilled " + daysProcessed + " days (" + startDate + " to " + endDate
                + "), " + totalRecords + " total records ingested.";
        if (daysFailed > 0) {
            result += " " + daysFailed + " day(s) FAILED: " + failures;
        }
        return result;
    }

    // Re-prices a date range from already-archived RawGameStat data instead
    // of calling Tank01 -- zero API quota spent. Use this after tweaking
    // PricingService: run /admin/reset-pricing first, then hit this with the
    // same range you already backfilled for real at least once.
    //
    // Runs on a background thread and returns immediately -- a full-season
    // range can take several minutes of real work (tens of thousands of DB
    // round trips), and Railway's own proxy will kill the HTTP connection
    // with a 502 long before that finishes if we make the client wait on it
    // synchronously. The actual recompute keeps running server-side
    // regardless of the response; poll /admin/recompute-status to see how
    // it's going, or just watch the Railway deploy logs (one line gets
    // printed per day processed).
    @GetMapping("/admin/recompute-range")
    public String recomputeRange(@RequestParam String startDate, @RequestParam String endDate) {
        boolean started = mlbIngestionService.recomputePricesFromCacheAsync(startDate, endDate);
        if (!started) {
            return "A recompute is already running: " + mlbIngestionService.getRecomputeStatus()
                    + " Wait for it to finish (check /admin/recompute-status) before starting another.";
        }
        return "Started recomputing " + startDate + " to " + endDate + " in the background. "
                + "Check /admin/recompute-status for progress, or watch the Railway logs.";
    }

    @GetMapping("/admin/recompute-status")
    public String recomputeStatus() {
        return mlbIngestionService.getRecomputeStatus();
    }

    // Same as recompute-range but scoped to one player by name -- zero
    // Tank01 quota spent (same as recompute-range), and doesn't touch
    // anyone else's price_history, so there's no risk of the memory/DB load
    // that caused the 502s during the last full-range recompute. Runs
    // synchronously since one player's history is small; the response IS
    // the result, no need to poll recompute-status.
    @GetMapping("/admin/recompute-player")
    public String recomputePlayer(@RequestParam String name) {
        return mlbIngestionService.recomputePricesForPlayer(name);
    }

    // Manually trigger NFL ingestion for a specific week -- e.g. to test the
    // new NFL pricing without waiting for the scheduled job or an actual
    // game day. gameDate tags the resulting price_history/RawGameStat rows
    // (defaults to today if omitted). seasonType defaults to "reg".
    @GetMapping("/admin/ingest-nfl")
    public String ingestNfl(
            @RequestParam int week,
            @RequestParam int season,
            @RequestParam(defaultValue = "reg") String seasonType,
            @RequestParam(required = false) String gameDate
    ) {
        String date = gameDate != null ? gameDate
                : LocalDate.now().format(GAME_DATE_FORMAT);
        int count = nflIngestionService.ingestNflFantasyData(week, season, seasonType, date);
        return "Ingested " + count + " NFL records for week " + week + ", " + season + " (" + seasonType + ")";
    }
}