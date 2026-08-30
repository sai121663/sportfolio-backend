package com.example.demo.player;

import com.example.demo.pricing.PriceHistory;
import com.example.demo.pricing.PriceHistoryRepository;
import com.example.demo.pricing.PriceHistoryRepository.PlayerPriceRange;
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
    private static final int HISTORY_WINDOW_DAYS = 30;

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
        String cutoff = LocalDate.now(MLB_ZONE).minusDays(HISTORY_WINDOW_DAYS).format(GAME_DATE_FORMAT);

        Map<Long, List<PriceHistory>> historyByPlayer = priceHistoryRepository
                .findByPlayerInAndGameDateGreaterThanEqual(players, cutoff).stream()
                .collect(Collectors.groupingBy(h -> h.getPlayer().getId()));

        Map<Long, PlayerPriceRange> rangeByPlayer = priceHistoryRepository
                .findPriceRangeByPlayers(players).stream()
                .collect(Collectors.toMap(PlayerPriceRange::getPlayerId, r -> r));

        Map<Long, List<Holding>> holdingsByPlayer = holdingRepository.findByPlayerIn(players).stream()
                .collect(Collectors.groupingBy(h -> h.getPlayer().getId()));

        Map<Long, List<Trade>> tradesByPlayer = tradeRepository.findByPlayerIn(players).stream()
                .collect(Collectors.groupingBy(t -> t.getPlayer().getId()));

        List<PlayerCardDto> result = new ArrayList<>();
        for (Player player : players) {
            result.add(buildCard(
                    player,
                    historyByPlayer.getOrDefault(player.getId(), List.of()),
                    rangeByPlayer.get(player.getId()),
                    holdingsByPlayer.getOrDefault(player.getId(), List.of()),
                    tradesByPlayer.getOrDefault(player.getId(), List.of())
            ));
        }
        return result;
    }

    private PlayerCardDto buildCard(
            Player player,
            List<PriceHistory> history,
            PlayerPriceRange priceRange,
            List<Holding> holdings,
            List<Trade> trades
    ) {
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

        dto.seasonHigh = priceRange != null && priceRange.getMaxPrice() != null ? priceRange.getMaxPrice() : player.getPrice();
        dto.seasonLow = priceRange != null && priceRange.getMinPrice() != null ? priceRange.getMinPrice() : player.getPrice();

        List<PriceHistory> sortedHistory = history.stream()
                .sorted(Comparator.comparing(PriceHistory::getGameDate))
                .collect(Collectors.toList());

        LocalDate activeCutoff = LocalDate.now(MLB_ZONE).minusDays(RECENTLY_ACTIVE_WINDOW_DAYS);
        dto.recentlyActive = sortedHistory.stream()
                .filter(h -> h.getFantasyPoints() != null)
                .anyMatch(h -> !LocalDate.parse(h.getGameDate(), GAME_DATE_FORMAT).isBefore(activeCutoff));

        if (!sortedHistory.isEmpty()) {
            // Only the latest price and the ~week-ago price are actually
            // needed below -- the frontend never renders a sparkline or any
            // other use of the full price trail, so building and shipping a
            // priceHistory array here was pure wasted work and payload size
            // repeated across all 700+ players on every Market load.
            List<Double> recentPrices = sortedHistory.stream().map(PriceHistory::getPrice).collect(Collectors.toList());

            double latest = recentPrices.get(recentPrices.size() - 1);

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
                weekAgoPrice = recentPrices.get(0);
            }

            dto.priceChange = latest - weekAgoPrice;
            dto.priceChangePercent = weekAgoPrice != 0 ? (dto.priceChange / weekAgoPrice) * 100.0 : 0.0;
        } else {
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