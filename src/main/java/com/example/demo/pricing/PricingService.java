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

    // Roughly the MLB-wide average this season -- used as the "expected"
    // baseline a player's real stats get compared against when building their
    // starting price. Hitters above this OPS get a boost, below get a cut.
    private static final double LEAGUE_AVG_OPS = 0.715;

    // Same idea for pitchers, but inverted -- a LOWER era than this is good.
    private static final double LEAGUE_AVG_ERA = 4.00;

    // How far the real-season-performance adjustment is allowed to swing the
    // skill portion of the starting price, once fully trusted. 0.5 = as low as
    // half, 1.5 = as high as 150%.
    private static final double MIN_PERFORMANCE_MULTIPLIER = 0.5;
    private static final double MAX_PERFORMANCE_MULTIPLIER = 1.5;

    // How many real games played before the real-season-performance adjustment
    // is fully trusted. Below this, it's blended toward "neutral" (no
    // adjustment) so a hot or cold streak in a handful of games doesn't
    // overreact -- same philosophy as the daily swing ceiling, just for a
    // different part of the formula.
    private static final int GAMES_FOR_FULL_STAT_CONFIDENCE = 30;

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

    // Compares a player's real season stats (from MLB's own API, synced
    // separately by MlbSeasonStatsService) against a league-average baseline,
    // to decide whether their starting price should get a boost or a cut on
    // top of the preseason projection/ADP numbers. Returns 1.0 (no change) if
    // we don't have real stats yet, or if the player hasn't played enough real
    // games this season to trust the stat.
    private double calculatePerformanceMultiplier(Player player) {
        boolean isPitcher = "P".equals(player.getPosition());

        Double rawRatio = null;
        if (isPitcher && player.getEra() != null && player.getEra() > 0) {
            // Lower ERA is better, so this is inverted -- a below-average era
            // gives a ratio above 1.0.
            rawRatio = LEAGUE_AVG_ERA / player.getEra();
        } else if (!isPitcher && player.getOps() != null) {
            rawRatio = player.getOps() / LEAGUE_AVG_OPS;
        }

        if (rawRatio == null) {
            // No real season stats synced yet for this player -- don't adjust.
            return 1.0;
        }

        double clampedRatio = Math.max(MIN_PERFORMANCE_MULTIPLIER, Math.min(MAX_PERFORMANCE_MULTIPLIER, rawRatio));

        int gamesPlayed = player.getGamesPlayed() != null ? player.getGamesPlayed() : 0;
        double confidence = Math.min(1.0, gamesPlayed / (double) GAMES_FOR_FULL_STAT_CONFIDENCE);

        // Blend between "neutral" (1.0, no adjustment) and the full clamped
        // ratio, based on how many real games we're trusting this stat from.
        return 1.0 + confidence * (clampedRatio - 1.0);
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