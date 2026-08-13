package com.example.demo.tank01;

import com.example.demo.pricing.PriceHistoryRepository;
import com.example.demo.pricing.PricingService;
import com.example.demo.player.Player;
import com.example.demo.player.PlayerRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@EnableScheduling
@Service
public class PlayerIngestionService {

    private final Tank01Client tank01Client;
    private final PlayerRepository playerRepository;
    private final PricingService pricingService;
    private final PriceHistoryRepository priceHistoryRepository;

    public PlayerIngestionService(
            Tank01Client tank01Client,
            PlayerRepository playerRepository,
            PricingService pricingService,
            PriceHistoryRepository priceHistoryRepository
    ) {
        this.tank01Client = tank01Client;
        this.playerRepository = playerRepository;
        this.pricingService = pricingService;
        this.priceHistoryRepository = priceHistoryRepository;
    }

    public int ingestNbaFantasyData(String gameDate) {
        List<String> gameIds = tank01Client.getGameIdsForDate(gameDate);
        Map<String, Tank01Dtos.PlayerProjection> projections = tank01Client.getFantasyProjections();
        int updatedCount = 0;

        for (String gameId : gameIds) {
            Map<String, Tank01Dtos.PlayerStat> stats = tank01Client.getBoxScore(gameId);

            for (Tank01Dtos.PlayerStat stat : stats.values()) {
                Player player = playerRepository.findByExternalId(stat.playerID)
                        .orElseGet(Player::new);

                if (player.getId() != null && priceHistoryRepository.existsByPlayerAndGameDate(player, gameDate)) {
                    continue;
                }

                boolean isNewPlayer = player.getPrice() == null;

                player.setExternalId(stat.playerID);
                player.setName(stat.longName);
                player.setTeam(stat.team);
                player.setSport("NBA");
                player.setFantasyPoints(
                        stat.fantasyPoints != null ? Double.parseDouble(stat.fantasyPoints) : 0.0
                );

                Tank01Dtos.PlayerProjection projection = projections.get(stat.playerID);
                Double weeklyProjection = (projection != null && projection.fantasyPoints != null)
                        ? Double.parseDouble(projection.fantasyPoints) : null;

                playerRepository.save(player);
                pricingService.updatePrice(player, gameDate, weeklyProjection, null);
                playerRepository.save(player);

                updatedCount++;
            }
        }
        return updatedCount;
    }

    @Scheduled(cron = "0 0 6 * * *")
    public void scheduledNbaIngestion() {
        String gameDate = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        int count = ingestNbaFantasyData(gameDate);
        System.out.println("Scheduled NBA ingestion complete: " + count + " records updated for " + gameDate);
    }
}