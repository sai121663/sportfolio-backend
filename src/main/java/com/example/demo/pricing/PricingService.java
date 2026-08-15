package com.example.demo.pricing;

import com.example.demo.player.Player;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class PricingService {

    private static final double MAX_DAILY_CHANGE_PERCENT = 0.30;
    private static final double MIN_DAILY_CHANGE_PERCENT = 0.08;
    private static final double MIN_PRICE = 1.0;

    private static final int REFERENCE_SEASON_GAMES = 162;

    private static final double LEAGUE_AVG_OPS = 0.715;
    private static final double LEAGUE_AVG_ERA = 4.00;

    private static final double MIN_PERFORMANCE_MULTIPLIER = 0.5;
    private static final double MAX_PERFORMANCE_MULTIPLIER = 1.5;

    private static final int GAMES_FOR_FULL_STAT_CONFIDENCE = 30;

    private final PriceHistoryRepository priceHistoryRepository;

    public PricingService(PriceHistoryRepository priceHistoryRepository) {
        this.priceHistoryRepository = priceHistoryRepository;
    }

    public void updatePrice(Player player, String gameDate, Double weeklyProjection, Double adpBonus, String rawStatsJson) {
        String season = getSeasonForDate(gameDate, player.getSport());
        boolean isNewSeason = player.getCurrentSeason() == null
                || !player.getCurrentSeason().equals(season);

        if (isNewSeason) {
            player.setCurrentSeason(season);
            player.setAvgFantasyPoints(null);

            double skillPortion = 0.0;
            if (weeklyProjection != null) {
                skillPortion += weeklyProjection;
            }
            if (adpBonus != null) {
                skillPortion += adpBonus;
            }

            double performanceMultiplier = calculatePerformanceMultiplier(player);
            double startingPrice = 100.0 + (skillPortion * performanceMultiplier);
            player.setPrice(startingPrice);
        }

        if (player.getFantasyPoints() != null) {
            if (player.getAvgFantasyPoints() != null) {
                adjustPriceForTodaysPerformance(player, player.getAvgFantasyPoints());
            } else if (weeklyProjection != null) {
                adjustPriceForTodaysPerformance(player, weeklyProjection);
            }
        }

        rollTodaysPerformanceIntoAverage(player);
        savePriceHistory(player, gameDate, rawStatsJson);
    }

    private double calculatePerformanceMultiplier(Player player) {
        boolean isPitcher = "P".equals(player.getPosition());

        Double rawRatio = null;
        if (isPitcher && player.getEra() != null && player.getEra() > 0) {
            rawRatio = LEAGUE_AVG_ERA / player.getEra();
        } else if (!isPitcher && player.getOps() != null) {
            rawRatio = player.getOps() / LEAGUE_AVG_OPS;
        }

        if (rawRatio == null) {
            return 1.0;
        }

        double clampedRatio = Math.max(MIN_PERFORMANCE_MULTIPLIER, Math.min(MAX_PERFORMANCE_MULTIPLIER, rawRatio));

        int gamesPlayed = player.getGamesPlayed() != null ? player.getGamesPlayed() : 0;
        double confidence = Math.min(1.0, gamesPlayed / (double) GAMES_FOR_FULL_STAT_CONFIDENCE);

        return 1.0 + confidence * (clampedRatio - 1.0);
    }

    private void adjustPriceForTodaysPerformance(Player player, double comparisonBaseline) {
        double todayPoints = player.getFantasyPoints();
        int gamesPlayedBeforeToday = player.getGamesPlayed() != null ? player.getGamesPlayed() : 0;

        double baseline = Math.max(Math.abs(comparisonBaseline), 1.0);
        double performanceDelta = (todayPoints - comparisonBaseline) / baseline;

        double ceiling = swingCeiling(gamesPlayedBeforeToday + 1);
        double changePercent = Math.max(-ceiling, Math.min(ceiling, performanceDelta));

        double newPrice = player.getPrice() * (1 + changePercent);
        player.setPrice(Math.max(MIN_PRICE, newPrice));
    }

    private double swingCeiling(int gameNumber) {
        double g = Math.max(1, gameNumber);
        double progress = (g - 1) / (double) (REFERENCE_SEASON_GAMES - 1);
        return MAX_DAILY_CHANGE_PERCENT * Math.pow(MIN_DAILY_CHANGE_PERCENT / MAX_DAILY_CHANGE_PERCENT, progress);
    }

    private void rollTodaysPerformanceIntoAverage(Player player) {
        if (player.getFantasyPoints() == null) return;

        double todayPoints = player.getFantasyPoints();
        int gamesPlayed = player.getGamesPlayed() != null ? player.getGamesPlayed() : 0;
        Double avgSoFar = player.getAvgFantasyPoints();

        double newAvg = (avgSoFar != null)
                ? ((avgSoFar * gamesPlayed) + todayPoints) / (gamesPlayed + 1)
                : todayPoints;

        player.setAvgFantasyPoints(newAvg);
    }

    private void savePriceHistory(Player player, String gameDate, String rawStatsJson) {
        PriceHistory record = new PriceHistory();
        record.setPlayer(player);
        record.setGameDate(gameDate);
        record.setPrice(player.getPrice());
        record.setFantasyPoints(player.getFantasyPoints());
        record.setRecordedAt(Instant.now());
        record.setRawStatsJson(rawStatsJson);
        priceHistoryRepository.save(record);
    }

    private String getSeasonForDate(String gameDate, String sport) {
        int year = Integer.parseInt(gameDate.substring(0, 4));

        if ("MLB".equals(sport)) {
            return String.valueOf(year);
        }

        int month = Integer.parseInt(gameDate.substring(4, 6));
        int startYear = (month >= 8) ? year : year - 1;
        int endYearShort = (startYear + 1) % 100;
        return String.format("%d-%02d", startYear, endYearShort);
    }
}