package com.example.demo.tank01;

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
import java.util.List;
import java.util.Map;

@Service
public class MlbIngestionService {

    // MLB game dates are always based on US time, regardless of what timezone
    // the server itself runs in (Railway defaults to UTC).
    private static final ZoneId MLB_ZONE = ZoneId.of("America/New_York");
    private static final DateTimeFormatter GAME_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    // How many players to process before clearing the Hibernate session.
    // Spring Boot keeps one database session open for the entire length of a
    // request by default (open-session-in-view), so a long multi-day backfill
    // would otherwise hold on to every single entity it ever touched --
    // players, price history, raw stat archives -- for the whole request,
    // growing memory until the process gets OOM-killed. This periodically
    // hands that memory back without changing anything about the actual
    // pricing/ingestion logic.
    private static final int FLUSH_EVERY_N_RECORDS = 25;

    private final MlbClient mlbClient;
    private final PlayerRepository playerRepository;
    private final PricingService pricingService;
    private final PriceHistoryRepository priceHistoryRepository;
    private final RawGameStatRepository rawGameStatRepository;
    private final EntityManager entityManager;

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

        for (String gameId : gameIds) {
            List<Tank01Dtos.MlbPlayerStat> stats = mlbClient.getBoxScore(gameId);

            for (Tank01Dtos.MlbPlayerStat stat : stats) {
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
        return updatedCount;
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

        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            String gameDate = date.format(GAME_DATE_FORMAT);
            List<RawGameStat> cachedStats = rawGameStatRepository.findByGameDateOrderByPlayerAsc(gameDate);

            for (RawGameStat cached : cachedStats) {
                Player player = cached.getPlayer();
                if (player == null) continue;

                player.setFantasyPoints(cached.getFantasyPoints());

                pricingService.updatePrice(
                        player,
                        gameDate,
                        player.getWeeklyProjection(),
                        player.getAdpBonus(),
                        cached.getRawStatsJson()
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