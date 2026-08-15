package com.example.demo.tank01;

import com.example.demo.pricing.PriceHistoryRepository;
import com.example.demo.pricing.PricingService;
import com.example.demo.player.Player;
import com.example.demo.player.PlayerRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

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

    private final MlbClient mlbClient;
    private final PlayerRepository playerRepository;
    private final PricingService pricingService;
    private final PriceHistoryRepository priceHistoryRepository;

    public MlbIngestionService(
            MlbClient mlbClient,
            PlayerRepository playerRepository,
            PricingService pricingService,
            PriceHistoryRepository priceHistoryRepository
    ) {
        this.mlbClient = mlbClient;
        this.playerRepository = playerRepository;
        this.pricingService = pricingService;
        this.priceHistoryRepository = priceHistoryRepository;
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

                String rawStatsJson = toJson(stat.getRawStats());

                playerRepository.save(player);
                pricingService.updatePrice(player, gameDate, weeklyProjection, adpBonus, rawStatsJson);
                playerRepository.save(player);
                updatedCount++;
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