package com.example.demo.pricing;

import com.example.demo.player.Player;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class PricingService {

    private static final double MAX_DAILY_CHANGE_PERCENT = 0.30;
    private static final double MIN_DAILY_CHANGE_PERCENT = 0.08;
    private static final double MIN_PRICE = 1.0;

    // Roughly how many games a full season is, used to calibrate how fast the
    // daily swing ceiling shrinks. A player's 2nd game moves the price close to
    // MAX_DAILY_CHANGE_PERCENT; by the time they've played this many games the
    // ceiling has shrunk to MIN_DAILY_CHANGE_PERCENT -- and it keeps shrinking
    // past that if they somehow play more (there's no hard floor).
    private static final int REFERENCE_SEASON_GAMES = 162;

    private final PriceHistoryRepository priceHistoryRepository;

    public PricingService(PriceHistoryRepository priceHistoryRepository) {
        this.priceHistoryRepository = priceHistoryRepository;
    }

    public void updatePrice(Player player, String gameDate, Double weeklyProjection, Double adpBonus) {
        String season = getSeasonForDate(gameDate, player.getSport());
        boolean isNewSeason = player.getCurrentSeason() == null
                || !player.getCurrentSeason().equals(season);

        if (isNewSeason) {
            player.setCurrentSeason(season);
            // NOTE: gamesPlayed is NOT reset here on purpose. That field is owned
            // entirely by MlbSeasonStatsService, which copies the real season
            // total straight from MLB. This code only resets things it actually
            // owns: the running fantasy-point average and the price.
            player.setAvgFantasyPoints(null);

            double startingPrice = 100.0;
            if (weeklyProjection != null) {
                startingPrice += weeklyProjection;
            }
            if (adpBonus != null) {
                startingPrice += adpBonus;
            }
            player.setPrice(startingPrice);
        }

        if (player.getFantasyPoints() != null) {
            if (player.getAvgFantasyPoints() != null) {
                // Normal case: compare today's performance to their actual
                // running average from real games played.
                adjustPriceForTodaysPerformance(player, player.getAvgFantasyPoints());
            } else if (weeklyProjection != null) {
                // No real average yet -- this is their first tracked game. Instead
                // of leaving the price untouched, compare today against the same
                // preseason projection that was used to set the starting price.
                adjustPriceForTodaysPerformance(player, weeklyProjection);
            }
        }

        rollTodaysPerformanceIntoAverage(player);
        savePriceHistory(player, gameDate);
    }

    private void adjustPriceForTodaysPerformance(Player player, double comparisonBaseline) {
        double todayPoints = player.getFantasyPoints();
        int gamesPlayedBeforeToday = player.getGamesPlayed() != null ? player.getGamesPlayed() : 0;

        // How far off was today from what we'd expect, as a fraction of the
        // baseline (their real average, or the preseason projection if this is
        // their first tracked game)? Floor the denominator so low scorers don't
        // cause wild divide-by-near-zero swings.
        double baseline = Math.max(Math.abs(comparisonBaseline), 1.0);
        double performanceDelta = (todayPoints - comparisonBaseline) / baseline;

        double ceiling = swingCeiling(gamesPlayedBeforeToday + 1);
        double changePercent = Math.max(-ceiling, Math.min(ceiling, performanceDelta));

        double newPrice = player.getPrice() * (1 + changePercent);
        player.setPrice(Math.max(MIN_PRICE, newPrice));
    }

    // The percentage a single game is allowed to move the price, as a function
    // of which game number this is in the player's season. Decays smoothly from
    // MAX_DAILY_CHANGE_PERCENT toward MIN_DAILY_CHANGE_PERCENT and keeps
    // decaying past that -- no hard floor.
    private double swingCeiling(int gameNumber) {
        double g = Math.max(1, gameNumber);
        double progress = (g - 1) / (double) (REFERENCE_SEASON_GAMES - 1);
        return MAX_DAILY_CHANGE_PERCENT * Math.pow(MIN_DAILY_CHANGE_PERCENT / MAX_DAILY_CHANGE_PERCENT, progress);
    }

    private void rollTodaysPerformanceIntoAverage(Player player) {
        if (player.getFantasyPoints() == null) return;

        double todayPoints = player.getFantasyPoints();
        // gamesPlayed here is the REAL season total, as last synced by
        // MlbSeasonStatsService -- this method only reads it, never writes it.
        int gamesPlayed = player.getGamesPlayed() != null ? player.getGamesPlayed() : 0;
        Double avgSoFar = player.getAvgFantasyPoints();

        double newAvg = (avgSoFar != null)
                ? ((avgSoFar * gamesPlayed) + todayPoints) / (gamesPlayed + 1)
                : todayPoints;

        player.setAvgFantasyPoints(newAvg);
        // No setGamesPlayed call -- this field belongs to MlbSeasonStatsService.
    }

    private void savePriceHistory(Player player, String gameDate) {
        PriceHistory record = new PriceHistory();
        record.setPlayer(player);
        record.setGameDate(gameDate);
        record.setPrice(player.getPrice());
        record.setFantasyPoints(player.getFantasyPoints());
        record.setRecordedAt(Instant.now());
        priceHistoryRepository.save(record);
    }

    private String getSeasonForDate(String gameDate, String sport) {
        int year = Integer.parseInt(gameDate.substring(0, 4));

        if ("MLB".equals(sport)) {
            // MLB season runs within a single calendar year (spring training through World Series)
            return String.valueOf(year);
        }

        // NBA/NHL-style leagues cross the calendar year boundary
        int month = Integer.parseInt(gameDate.substring(4, 6));
        int startYear = (month >= 8) ? year : year - 1;
        int endYearShort = (startYear + 1) % 100;
        return String.format("%d-%02d", startYear, endYearShort);
    }
}