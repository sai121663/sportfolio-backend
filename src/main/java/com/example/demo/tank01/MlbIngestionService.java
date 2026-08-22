// MlbIngestionService.java
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class MlbIngestionService {

    private static final ZoneId MLB_ZONE = ZoneId.of("America/New_York");
    private static final DateTimeFormatter GAME_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

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

                player.setWeeklyProjection(weeklyProjection);
                player.setAdpBonus(adpBonus);

                String rawStatsJson = toJson(stat.getRawStats());

                playerRepository.save(player);

                archiveRawGameStat(player, gameDate, player.getFantasyPoints(), rawStatsJson);

                pricingService.updatePrice(player, gameDate, weeklyProjection, adpBonus, rawStatsJson);
                playerRepository.save(player);
                updatedCount++;

                if (updatedCount % FLUSH_EVERY_N_RECORDS == 0) {
                    entityManager.clear();
                }
            }
        }

        repriceInactivePlayers(gameDate, processedExternalIds);

        return updatedCount;
    }

    private void repriceInactivePlayers(String gameDate, Set<String> processedExternalIds) {
        List<Player> allPlayers = playerRepository.findAll();
        int count = 0;

        for (Player player : allPlayers) {
            if (player.getExternalId() == null || processedExternalIds.contains(player.getExternalId())) {
                continue;
            }
            if (!"MLB".equals(player.getSport()) || player.getPrice() == null) {
                continue;
            }
            if (priceHistoryRepository.existsByPlayerAndGameDate(player, gameDate)) {
                continue;
            }

            player.setFantasyPoints(null);

            pricingService.updatePrice(player, gameDate, player.getWeeklyProjection(), player.getAdpBonus(), null);
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

    public int recomputePricesFromCache(String startDate, String endDate) {
        LocalDate start = LocalDate.parse(startDate, GAME_DATE_FORMAT);
        LocalDate end = LocalDate.parse(endDate, GAME_DATE_FORMAT);
        int updatedCount = 0;

        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            String gameDate = date.format(GAME_DATE_FORMAT);
            List<RawGameStat> cachedStats = rawGameStatRepository.findByGameDateOrderByPlayerAsc(gameDate);
            Set<String> processedExternalIds = new HashSet<>();

            for (RawGameStat cached : cachedStats) {
                Player player = cached.getPlayer();
                if (player == null) continue;
                if (player.getExternalId() != null) {
                    processedExternalIds.add(player.getExternalId());
                }

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
                    entityManager.clear();
                }
            }

            repriceInactivePlayers(gameDate, processedExternalIds);
        }

        return updatedCount;
    }

    @Scheduled(cron = "0 5 6 * * *", zone = "America/New_York")
    public void scheduledMlbIngestion() {
        String gameDate = LocalDate.now(MLB_ZONE).minusDays(1).format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        int count = ingestMlbFantasyData(gameDate);
        System.out.println("Scheduled MLB ingestion complete: " + count + " records updated for " + gameDate);
    }
}