package com.example.demo.tank01;

import com.example.demo.pricing.PriceHistoryRepository;
import com.example.demo.pricing.PricingService;
import com.example.demo.player.Player;
import com.example.demo.player.PlayerRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Service
public class MlbIngestionService {

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

                playerRepository.save(player);
                pricingService.updatePrice(player, gameDate, weeklyProjection, adpBonus);
                playerRepository.save(player);
                updatedCount++;
            }
        }
        return updatedCount;
    }

    @Scheduled(cron = "0 5 6  * * *") // resets price at 6:05 a.m. everyday for MLB
    public void scheduledMlbIngestion() {
        String gameDate = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        int count = ingestMlbFantasyData(gameDate);
        System.out.println("Scheduled MLB ingestion complete: " + count + " records updated for " + gameDate);
    }
}