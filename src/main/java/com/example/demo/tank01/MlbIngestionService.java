// MlbIngestionService.java
package com.example.demo.tank01;

import com.example.demo.pricing.PriceHistory;
import com.example.demo.pricing.PriceHistoryRepository;
import com.example.demo.pricing.PricingService;
import com.example.demo.player.Player;
import com.example.demo.player.PlayerRepository;
import jakarta.persistence.EntityManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Service
public class MlbIngestionService {

    // MLB game dates are always based on US time, regardless of what timezone
    // the server itself runs in (Railway defaults to UTC).
    private static final ZoneId MLB_ZONE = ZoneId.of("America/New_York");
    private static final DateTimeFormatter GAME_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    // How many players to process before flushing and clearing the Hibernate
    // session. Spring Boot keeps one database session open for the entire
    // length of a request by default (open-session-in-view), so a long
    // multi-day backfill would otherwise hold on to every single entity it
    // ever touched -- players, price history, raw stat archives -- for the
    // whole request, growing memory until the process gets OOM-killed. This
    // periodically hands that memory back without changing anything about
    // the actual pricing/ingestion logic.
    private static final int FLUSH_EVERY_N_RECORDS = 25;

    private final MlbClient mlbClient;
    private final PlayerRepository playerRepository;
    private final PricingService pricingService;
    private final PriceHistoryRepository priceHistoryRepository;
    private final RawGameStatRepository rawGameStatRepository;
    private final EntityManager entityManager;

    // Tracks the single background recompute job, if one is running. A plain
    // singleton field is fine here (not a database row or a queue) since
    // this is a solo-admin, single-server tool -- it doesn't need to survive
    // a restart or be visible across multiple instances.
    private final AtomicBoolean recomputeRunning = new AtomicBoolean(false);
    private volatile String recomputeStatusMessage = "No recompute has been run yet.";

    public MlbIngestionService(
            MlbClient mlbClient,
            PlayerRepository playerRepository,
            PricingService pricingService,
            PriceHistoryRepository priceHistoryRepository,
            RawGameStatRepository rawGameStatRepository,
            EntityManager entityManager
    ) {
        this.mlbClient = mlbClient;
        this.playerRepository = playerRepository;
        this.pricingService = pricingService;
        this.priceHistoryRepository = priceHistoryRepository;
        this.rawGameStatRepository = rawGameStatRepository;
        this.entityManager = entityManager;
    }

    // Hand-rolled, dependency-free JSON encoding for a simple flat map of
    // string keys to primitive/string values -- avoids needing Jackson's
    // ObjectMapper injected as a bean just for this one small use case.
    private String toJson(Map<String, Object> map) {
        if (map == null || map.isEmpty()) return "{}";

        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (!first) json.append(",");
            first = false;

            json.append("\"").append(escapeJson(entry.getKey())).append("\":");

            Object value = entry.getValue();
            if (value == null) {
                json.append("null");
            } else if (value instanceof Number || value instanceof Boolean) {
                json.append(value.toString());
            } else {
                json.append("\"").append(escapeJson(value.toString())).append("\"");
            }
        }
        json.append("}");
        return json.toString();
    }

    private String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // Hits the real Tank01 API. Fetches a day's games, box scores,
    // projections, and ADP, prices every player who appeared, AND archives
    // the raw stats to RawGameStat so this same day can be re-priced later
    // (via recomputePricesFromCache) without ever calling Tank01 again.
    public int ingestMlbFantasyData(String gameDate) {
        List<String> gameIds = mlbClient.getGameIdsForDate(gameDate);
        Map<String, String> nameMap = mlbClient.getPlayerNameMap();
        Map<String, Tank01Dtos.PlayerProjection> projections = mlbClient.getProjections();
        Map<String, Double> adpMap = mlbClient.getAdpMap();
        int updatedCount = 0;
        Set<String> processedExternalIds = new HashSet<>();

        for (String gameId : gameIds) {
            List<Tank01Dtos.MlbPlayerStat> stats = mlbClient.getBoxScore(gameId);

            for (Tank01Dtos.MlbPlayerStat stat : stats) {
                processedExternalIds.add(stat.playerID);

                Player player = playerRepository.findByExternalId(stat.playerID)
                        .orElseGet(Player::new);

                if (player.getId() != null && priceHistoryRepository.existsByPlayerAndGameDate(player, gameDate)) {
                    continue;
                }

                player.setExternalId(stat.playerID);
                player.setName(nameMap.getOrDefault(stat.playerID, "Unknown Player (" + stat.playerID + ")"));
                player.setTeam(stat.team);
                player.setSport("MLB");
                player.setFantasyPoints(
                        stat.fantasyPointsDefault != null ? Double.parseDouble(stat.fantasyPointsDefault) : 0.0
                );

                Tank01Dtos.PlayerProjection projection = projections.get(stat.playerID);
                Double weeklyProjection = (projection != null && projection.fantasyPoints != null)
                        ? Double.parseDouble(projection.fantasyPoints) : null;

                Double adpBonus = null;
                Double adp = adpMap.get(stat.playerID);
                if (adp != null) {
                    adpBonus = Math.max(0.0, 100.0 * (1 - adp / 300.0));
                }

                // Persist these onto the player itself (not just the in-memory
                // cache in MlbClient) so recomputePricesFromCache can reuse them
                // later without needing to call Tank01 again.
                player.setWeeklyProjection(weeklyProjection);
                player.setAdpBonus(adpBonus);

                String rawStatsJson = toJson(stat.getRawStats());

                playerRepository.save(player);

                archiveRawGameStat(player, gameDate, player.getFantasyPoints(), rawStatsJson);

                pricingService.updatePrice(player, gameDate, weeklyProjection, adpBonus, rawStatsJson);
                playerRepository.save(player);
                updatedCount++;

                if (updatedCount % FLUSH_EVERY_N_RECORDS == 0) {
                    // No entityManager.flush() here on purpose -- each save()
                    // call above already ran (and committed) its own
                    // transaction, so everything up to this point is already
                    // durably persisted. flush() specifically requires an
                    // active transaction bound to the current thread, which
                    // won't exist here since we're between transactions --
                    // calling it throws "No EntityManager with actual
                    // transaction available". clear() alone is what actually
                    // frees the memory (it detaches everything from the
                    // session), and it doesn't need a transaction to do that.
                    entityManager.clear();
                }
            }
        }

        // Single-day path: only ever needs the roster loaded once, and the
        // batched recent-games/already-priced lookups below cost just two
        // extra queries total instead of two queries PER inactive player.
        List<Player> allPlayers = playerRepository.findAll();
        repriceInactivePlayers(
                gameDate,
                processedExternalIds,
                allPlayers,
                fetchRecentGamesBatch(allPlayers, gameDate),
                fetchAlreadyPricedPlayerIds(allPlayers, gameDate)
        );

        return updatedCount;
    }

    // One query covering every player's last RECENT_WINDOW_DAYS of price
    // history for a given date, instead of one query per player. Grouped by
    // player ID so each player's slice can be looked up in memory afterward.
    private Map<Long, List<PriceHistory>> fetchRecentGamesBatch(List<Player> players, String gameDate) {
        if (players.isEmpty()) return Collections.emptyMap();
        LocalDate latestDate = LocalDate.parse(gameDate, GAME_DATE_FORMAT);
        String windowStart = latestDate.minusDays(PricingService.RECENT_WINDOW_DAYS).format(GAME_DATE_FORMAT);
        List<PriceHistory> windowHistory = priceHistoryRepository.findByPlayerInAndGameDateBetween(players, windowStart, gameDate);
        return windowHistory.stream()
                .collect(Collectors.groupingBy(ph -> ph.getPlayer().getId()));
    }

    // One query for which players already have a PriceHistory row for this
    // exact date, instead of an existsByPlayerAndGameDate query per player.
    // In the normal flow (reset-pricing before every recompute) this is
    // always empty -- it's a safety net against re-running without a reset
    // first, same as the original per-player check was.
    private Set<Long> fetchAlreadyPricedPlayerIds(List<Player> players, String gameDate) {
        if (players.isEmpty()) return Collections.emptySet();
        return priceHistoryRepository.findByPlayerInAndGameDate(players, gameDate).stream()
                .map(ph -> ph.getPlayer().getId())
                .collect(Collectors.toSet());
    }

    // For every already-known MLB player who did NOT appear in today's real
    // games (or cached raw stats, when called from recomputePricesFromCache),
    // still run them through pricing with no new stats to roll in. Nothing
    // about their season/projection/ADP changes just because they sat out,
    // but the recent-form window keeps sliding forward -- so a player who
    // stops playing (injury, benched, not called up yet) gradually reverts
    // toward their season baseline instead of staying frozen forever at
    // whatever price they had the day they stopped, and their card's "last
    // week" number starts moving again instead of being stuck at exactly
    // 0.00% indefinitely.
    //
    // allPlayers, recentGamesByPlayerId, and alreadyPricedPlayerIds are all
    // passed in rather than queried here so that multi-day callers
    // (recomputePricesFromCache) can compute them ONCE per day and reuse
    // them, instead of re-querying per player -- that per-player querying
    // was the main source of the memory/DB load that was crashing the
    // server on long ranges.
    private void repriceInactivePlayers(
            String gameDate,
            Set<String> processedExternalIds,
            List<Player> allPlayers,
            Map<Long, List<PriceHistory>> recentGamesByPlayerId,
            Set<Long> alreadyPricedPlayerIds
    ) {
        int count = 0;

        for (Player player : allPlayers) {
            if (player.getExternalId() == null || processedExternalIds.contains(player.getExternalId())) {
                continue;
            }
            if (!"MLB".equals(player.getSport()) || player.getPrice() == null) {
                continue;
            }
            if (alreadyPricedPlayerIds.contains(player.getId())) {
                continue;
            }

            // No game today -- explicitly null this out so
            // rollTodaysPerformanceIntoAverage skips it and today's
            // price_history row correctly records "no game", instead of
            // re-logging whatever points they scored in their last real game.
            player.setFantasyPoints(null);

            List<PriceHistory> recentGames = recentGamesByPlayerId.getOrDefault(player.getId(), Collections.emptyList());

            pricingService.updatePrice(player, gameDate, player.getWeeklyProjection(), player.getAdpBonus(), null, recentGames);
            playerRepository.save(player);
            count++;

            if (count % FLUSH_EVERY_N_RECORDS == 0) {
                entityManager.clear();
            }
        }
    }

    private void archiveRawGameStat(Player player, String gameDate, Double fantasyPoints, String rawStatsJson) {
        if (rawGameStatRepository.existsByPlayerAndGameDate(player, gameDate)) {
            return;
        }
        RawGameStat archive = new RawGameStat();
        archive.setPlayer(player);
        archive.setGameDate(gameDate);
        archive.setFantasyPoints(fantasyPoints);
        archive.setRawStatsJson(rawStatsJson);
        archive.setFetchedAt(Instant.now());
        rawGameStatRepository.save(archive);
    }

    // Fire-and-forget wrapper around recomputePricesFromCache. Returns false
    // (without starting anything) if a recompute is already running, so you
    // can't accidentally kick off two overlapping jobs. Otherwise starts the
    // real work on a daemon background thread and returns true immediately --
    // the caller (the controller) can respond to the HTTP request right away
    // regardless of how long the actual recompute takes.
    public boolean recomputePricesFromCacheAsync(String startDate, String endDate) {
        if (!recomputeRunning.compareAndSet(false, true)) {
            return false;
        }
        recomputeStatusMessage = "Running: recomputing " + startDate + " to " + endDate + "...";

        Thread worker = new Thread(() -> {
            try {
                int count = recomputePricesFromCache(startDate, endDate);
                recomputeStatusMessage = "Finished: recomputed " + count + " records ("
                        + startDate + " to " + endDate + ") at " + Instant.now();
                System.out.println(recomputeStatusMessage);
            } catch (Exception e) {
                recomputeStatusMessage = "FAILED recomputing " + startDate + " to " + endDate
                        + ": " + e;
                System.out.println(recomputeStatusMessage);
            } finally {
                recomputeRunning.set(false);
            }
        }, "recompute-range-worker");
        worker.setDaemon(true);
        worker.start();
        return true;
    }

    public String getRecomputeStatus() {
        return recomputeStatusMessage;
    }

    // Re-runs PricingService over already-archived RawGameStat rows for a date
    // range -- zero calls to Tank01, zero quota spent. Meant to be run right
    // after /admin/reset-pricing whenever you tweak the pricing formula and
    // want to see the result across real historical data again, without
    // re-fetching anything. Only works for dates that were already ingested
    // for real at least once via ingestMlbFantasyData/ingestRange.
    public int recomputePricesFromCache(String startDate, String endDate) {
        LocalDate start = LocalDate.parse(startDate, GAME_DATE_FORMAT);
        LocalDate end = LocalDate.parse(endDate, GAME_DATE_FORMAT);
        int updatedCount = 0;

        // Loaded ONCE for the whole range, not once per day -- see the
        // comment on repriceInactivePlayers for why this matters.
        List<Player> allPlayers = playerRepository.findAll();

        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            String gameDate = date.format(GAME_DATE_FORMAT);
            List<RawGameStat> cachedStats = rawGameStatRepository.findByGameDateOrderByPlayerAsc(gameDate);
            Set<String> processedExternalIds = new HashSet<>();

            // Batched ONCE per day, covering every player at once -- this is
            // the fix for the crash. It replaces what used to be roughly
            // 700 individual per-player queries a day (one recent-window
            // fetch + one exists-check per player) with just 2 queries total
            // for the whole day, active players included.
            Map<Long, List<PriceHistory>> recentGamesByPlayerId = fetchRecentGamesBatch(allPlayers, gameDate);
            Set<Long> alreadyPricedPlayerIds = fetchAlreadyPricedPlayerIds(allPlayers, gameDate);

            for (RawGameStat cached : cachedStats) {
                Player player = cached.getPlayer();
                if (player == null) continue;
                if (player.getExternalId() != null) {
                    processedExternalIds.add(player.getExternalId());
                }

                player.setFantasyPoints(cached.getFantasyPoints());

                List<PriceHistory> recentGames = recentGamesByPlayerId.getOrDefault(player.getId(), Collections.emptyList());

                pricingService.updatePrice(
                        player,
                        gameDate,
                        player.getWeeklyProjection(),
                        player.getAdpBonus(),
                        cached.getRawStatsJson(),
                        recentGames
                );

                playerRepository.save(player);
                updatedCount++;

                if (updatedCount % FLUSH_EVERY_N_RECORDS == 0) {
                    // No entityManager.flush() here on purpose -- each save()
                    // call above already ran (and committed) its own
                    // transaction, so everything up to this point is already
                    // durably persisted. flush() specifically requires an
                    // active transaction bound to the current thread, which
                    // won't exist here since we're between transactions --
                    // calling it throws "No EntityManager with actual
                    // transaction available". clear() alone is what actually
                    // frees the memory (it detaches everything from the
                    // session), and it doesn't need a transaction to do that.
                    entityManager.clear();
                }
            }

            repriceInactivePlayers(gameDate, processedExternalIds, allPlayers, recentGamesByPlayerId, alreadyPricedPlayerIds);

            // One line per day, not per record -- enough to confirm the
            // background job is actually making progress (and roughly how
            // fast) without flooding Railway's log rate limit the way
            // per-record SQL logging did earlier.
            System.out.println("Recompute progress: " + gameDate + " done, " + updatedCount + " total records so far");
        }

        return updatedCount;
    }

    // Fires at 6:05 a.m. US Eastern time every day -- well after any MLB game
    // could still be in progress, even for late West Coast games. Explicitly
    // pinned to America/New_York so this means the same real-world time
    // regardless of what timezone the server itself happens to run in.
    @Scheduled(cron = "0 5 6 * * *", zone = "America/New_York")
    public void scheduledMlbIngestion() {
        // By 6:05 a.m. ET, the calendar has already rolled over to a new day --
        // last night's games belong to the day that just ended, so we ingest
        // "yesterday" (in US time), not "today".
        String gameDate = LocalDate.now(MLB_ZONE).minusDays(1).format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        int count = ingestMlbFantasyData(gameDate);
        System.out.println("Scheduled MLB ingestion complete: " + count + " records updated for " + gameDate);
    }
}