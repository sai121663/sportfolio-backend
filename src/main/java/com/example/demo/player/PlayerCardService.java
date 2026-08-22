// PlayerCardService.java
package com.example.demo.player;

import com.example.demo.pricing.PriceHistory;
import com.example.demo.pricing.PriceHistoryRepository;
import com.example.demo.trading.Holding;
import com.example.demo.trading.HoldingRepository;
import com.example.demo.trading.Trade;
import com.example.demo.trading.TradeRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class PlayerCardService {

    private static final DateTimeFormatter GAME_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private static final int RECENTLY_ACTIVE_WINDOW_DAYS = 7;
    private static final ZoneId MLB_ZONE = ZoneId.of("America/New_York");

    private final PriceHistoryRepository priceHistoryRepository;
    private final HoldingRepository holdingRepository;
    private final TradeRepository tradeRepository;

    public PlayerCardService(
            PriceHistoryRepository priceHistoryRepository,
            HoldingRepository holdingRepository,
            TradeRepository tradeRepository
    ) {
        this.priceHistoryRepository = priceHistoryRepository;
        this.holdingRepository = holdingRepository;
        this.tradeRepository = tradeRepository;
    }

    public List<PlayerCardDto> buildCards(List<Player> players) {
        Map<Long, List<PriceHistory>> historyByPlayer = priceHistoryRepository.findByPlayerIn(players).stream()
                .collect(Collectors.groupingBy(h -> h.getPlayer().getId()));

        Map<Long, List<Holding>> holdingsByPlayer = holdingRepository.findByPlayerIn(players).stream()
                .collect(Collectors.groupingBy(h -> h.getPlayer().getId()));

        Map<Long, List<Trade>> tradesByPlayer = tradeRepository.findByPlayerIn(players).stream()
                .collect(Collectors.groupingBy(t -> t.getPlayer().getId()));

        List<PlayerCardDto> result = new ArrayList<>();
        for (Player player : players) {
            result.add(buildCard(
                    player,
                    historyByPlayer.getOrDefault(player.getId(), List.of()),
                    holdingsByPlayer.getOrDefault(player.getId(), List.of()),
                    tradesByPlayer.getOrDefault(player.getId(), List.of())
            ));
        }
        return result;
    }

    private PlayerCardDto buildCard(Player player, List<PriceHistory> history, List<Holding> holdings, List<Trade> trades) {
        PlayerCardDto dto = new PlayerCardDto();
        dto.id = player.getId();
        dto.name = player.getName();
        dto.team = player.getTeam();
        dto.teamId = player.getTeamId();
        dto.sport = player.getSport();
        dto.price = player.getPrice();
        dto.imageUrl = player.getImageUrl();
        dto.avgFantasyPoints = player.getAvgFantasyPoints();
        dto.gamesPlayed = player.getGamesPlayed();

        dto.position = player.getPosition();
        dto.homeRuns = player.getHomeRuns();
        dto.rbi = player.getRbi();
        dto.ops = player.getOps();
        dto.era = player.getEra();
        dto.wins = player.getWins();
        dto.losses = player.getLosses();
        dto.strikeouts = player.getStrikeouts();

        List<PriceHistory> sortedHistory = history.stream()
                .sorted(Comparator.comparing(PriceHistory::getGameDate))
                .collect(Collectors.toList());

        LocalDate activeCutoff = LocalDate.now(MLB_ZONE).minusDays(RECENTLY_ACTIVE_WINDOW_DAYS);
        dto.recentlyActive = sortedHistory.stream()
                .filter(h -> h.getFantasyPoints() != null)
                .anyMatch(h -> !LocalDate.parse(h.getGameDate(), GAME_DATE_FORMAT).isBefore(activeCutoff));

        if (!sortedHistory.isEmpty()) {
            List<Double> allPrices = sortedHistory.stream().map(PriceHistory::getPrice).collect(Collectors.toList());

            int start = Math.max(0, allPrices.size() - 20);
            dto.priceHistory = allPrices.subList(start, allPrices.size());

            dto.seasonHigh = allPrices.stream().max(Double::compareTo).orElse(player.getPrice());
            dto.seasonLow = allPrices.stream().min(Double::compareTo).orElse(player.getPrice());

            double latest = allPrices.get(allPrices.size() - 1);

            PriceHistory latestRecord = sortedHistory.get(sortedHistory.size() - 1);
            LocalDate latestDate = LocalDate.parse(latestRecord.getGameDate(), GAME_DATE_FORMAT);
            LocalDate weekAgoTarget = latestDate.minusDays(7);

            Double weekAgoPrice = null;
            for (PriceHistory record : sortedHistory) {
                LocalDate recordDate = LocalDate.parse(record.getGameDate(), GAME_DATE_FORMAT);
                if (!recordDate.isAfter(weekAgoTarget)) {
                    weekAgoPrice = record.getPrice();
                } else {
                    break;
                }
            }
            if (weekAgoPrice == null) {
                weekAgoPrice = allPrices.get(0);
            }

            dto.priceChange = latest - weekAgoPrice;
            dto.priceChangePercent = weekAgoPrice != 0 ? (dto.priceChange / weekAgoPrice) * 100.0 : 0.0;
        } else {
            dto.priceHistory = List.of();
            dto.seasonHigh = player.getPrice();
            dto.seasonLow = player.getPrice();
            dto.priceChange = 0.0;
            dto.priceChangePercent = 0.0;
        }

        double totalShares = holdings.stream()
                .mapToDouble(h -> h.getQuantity() != null ? h.getQuantity() : 0.0)
                .sum();
        dto.marketCap = totalShares * player.getPrice();

        dto.volume = trades.stream()
                .mapToDouble(t -> t.getQuantity() != null ? t.getQuantity() : 0.0)
                .sum();

        return dto;
    }
}