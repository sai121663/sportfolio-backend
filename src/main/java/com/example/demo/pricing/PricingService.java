// PricingService.java
package com.example.demo.pricing;

import com.example.demo.player.Player;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Component
public class PricingService {

    private static final DateTimeFormatter GAME_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private static final double MIN_PRICE = 1.0;

    private static final int REFERENCE_SEASON_GAMES_HITTER = 162;
    private static final int REFERENCE_SEASON_GAMES_PITCHER = 32;
    private static final int REFERENCE_SEASON_GAMES_RELIEVER = 95;
    private static final double MAX_DAILY_MOVE_PERCENT = 0.30;
    private static final double MIN_DAILY_MOVE_PERCENT = 0.08;

    private static final int RECENT_WINDOW_DAYS = 15;

    private static final double LEAGUE_AVG_HITTER_FANTASY_POINTS = 1.78;
    private static final double LEAGUE_AVG_PITCHER_FANTASY_POINTS = 3.77;

    private static final double GAMES_PER_WEEK = 6.0;

    private static final double LEAGUE_AVG_ADP_BONUS = 50.0;

    private static final double LEAGUE_AVG_OPS = 0.715;
    private static final double LEAGUE_AVG_ERA = 4.00;

    // Caps how extreme the season-performance ratio is allowed to be, no
    // matter how good/bad the real OPS or ERA is. Without this, a pitcher
    // with a tiny innings sample (e.g. 10-15 IP) can post a fluky ERA like
    // 0.66 -- not real skill, just not enough innings yet for a bad outing to
    // even out -- and 4.00 / 0.66 = 6x average, an absurd multiplier for any
    // single factor to contribute. The confidence weighting below is a
    // separate lever: it controls how MUCH this ratio counts for a player
    // without an established track record. This clamp controls how extreme
    // the ratio itself can ever be, for anyone -- established veteran or not.
    private static final double MIN_SEASON_RATIO = 0.5;
    private static final double MAX_SEASON_RATIO = 2.0;

    private static final double BASE_RECENT_PERFORMANCE_WEIGHT = 0.15;
    private static final double BASE_SEASON_PERFORMANCE_WEIGHT = 0.50;
    private static final double BASE_PROJECTION_WEIGHT = 0.20;
    private static final double BASE_ADP_WEIGHT = 0.15;

    // 25% of each role's REFERENCE_SEASON_GAMES_* above, so "how big a sample
    // counts as trustworthy" scales with each role's real workload instead of
    // one hitter-scale number for everyone.
    private static final int GAMES_FOR_FULL_STAT_CONFIDENCE_HITTER = 40;
    private static final int GAMES_FOR_FULL_STAT_CONFIDENCE_STARTER = 8;
    private static final int GAMES_FOR_FULL_STAT_CONFIDENCE_RELIEVER = 24;

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
        }

        boolean isPitcher = "P".equals(player.getPosition());
        double leagueAvgPoints = isPitcher ? LEAGUE_AVG_PITCHER_FANTASY_POINTS : LEAGUE_AVG_HITTER_FANTASY_POINTS;

        double recentRatio = calculateRecentRatio(player, gameDate, leagueAvgPoints);
        double seasonRatio = calculateSeasonRatio(player, isPitcher);

        double projectionRatio = weeklyProjection != null
                ? (weeklyProjection / GAMES_PER_WEEK) / leagueAvgPoints
                : 1.0;

        double adpRatio = adpBonus != null ? adpBonus / LEAGUE_AVG_ADP_BONUS : 0.0;

        double[] weights = calculateEffectiveWeights(player, isPitcher);
        double compositeRatio =
                weights[0] * recentRatio +
                weights[1] * seasonRatio +
                weights[2] * projectionRatio +
                weights[3] * adpRatio;

        double targetPrice = Math.max(MIN_PRICE, 100.0 * compositeRatio);

        if (isNewSeason || player.getPrice() == null) {
            player.setPrice(targetPrice);
        } else {
            int gamesPlayed = player.getGamesPlayed() != null ? player.getGamesPlayed() : 0;
            double moveCeiling = swingCeiling(gamesPlayed + 1, determineReferenceGames(player, isPitcher));
            double gap = targetPrice - player.getPrice();
            double maxMove = player.getPrice() * moveCeiling;
            double actualMove = Math.max(-maxMove, Math.min(maxMove, gap));
            player.setPrice(Math.max(MIN_PRICE, player.getPrice() + actualMove));
        }

        rollTodaysPerformanceIntoAverage(player);
        savePriceHistory(player, gameDate, rawStatsJson);
    }

    private double[] calculateEffectiveWeights(Player player, boolean isPitcher) {
        int gamesPlayed = player.getGamesPlayed() != null ? player.getGamesPlayed() : 0;
        int gamesForFullConfidence = requiredGamesForConfidence(player, isPitcher);
        double confidence = Math.min(1.0, gamesPlayed / (double) gamesForFullConfidence);

        double recentWeight = BASE_RECENT_PERFORMANCE_WEIGHT * confidence;
        double seasonWeight = BASE_SEASON_PERFORMANCE_WEIGHT * confidence;

        double freedUpWeight = (BASE_RECENT_PERFORMANCE_WEIGHT + BASE_SEASON_PERFORMANCE_WEIGHT) * (1 - confidence);
        double projectionShare = BASE_PROJECTION_WEIGHT / (BASE_PROJECTION_WEIGHT + BASE_ADP_WEIGHT);
        double adpShare = BASE_ADP_WEIGHT / (BASE_PROJECTION_WEIGHT + BASE_ADP_WEIGHT);

        double projectionWeight = BASE_PROJECTION_WEIGHT + freedUpWeight * projectionShare;
        double adpWeight = BASE_ADP_WEIGHT + freedUpWeight * adpShare;

        return new double[]{recentWeight, seasonWeight, projectionWeight, adpWeight};
    }

    private int requiredGamesForConfidence(Player player, boolean isPitcher) {
        if (!isPitcher) {
            return GAMES_FOR_FULL_STAT_CONFIDENCE_HITTER;
        }
        boolean isReliever = (player.getSaves() != null && player.getSaves() > 0)
                || (player.getHolds() != null && player.getHolds() > 0);
        return isReliever ? GAMES_FOR_FULL_STAT_CONFIDENCE_RELIEVER : GAMES_FOR_FULL_STAT_CONFIDENCE_STARTER;
    }

    // Ratio of a player's real season-to-date OPS/ERA to the league average, as
    // synced by MlbSeasonStatsService straight from MLB's own stats API. Returns
    // a neutral 1.0 if we don't have real season stats for them yet.
    //
    // Pitchers use a LINEAR formula (2 - era/avgEra) rather than the more
    // obvious "avgEra / era" flip. That flip looks natural but is a real
    // statistical trap: averaging a bunch of "avg / actual" ratios across a
    // whole population does NOT come back out to 1.0 the way "actual / avg"
    // does for hitters -- dividing a fixed number by a spread of values
    // systematically skews the average upward (a classic bias from averaging
    // reciprocals). At 50% weight, that quiet skew was enough to make pitchers
    // price systematically higher than hitters overall, even though it didn't
    // change pitcher-vs-pitcher rankings among themselves. The linear form here
    // is symmetric with the hitter formula and doesn't have that bias: it
    // averages back to exactly 1.0 across a population whose mean ERA equals
    // LEAGUE_AVG_ERA, same as hitters' OPS ratio does.
    private double calculateSeasonRatio(Player player, boolean isPitcher) {
        double ratio = 1.0;
        if (isPitcher && player.getEra() != null && player.getEra() > 0) {
            ratio = 2.0 - (player.getEra() / LEAGUE_AVG_ERA);
        } else if (!isPitcher && player.getOps() != null) {
            ratio = player.getOps() / LEAGUE_AVG_OPS;
        }
        return Math.max(MIN_SEASON_RATIO, Math.min(MAX_SEASON_RATIO, ratio));
    }

    private double calculateRecentRatio(Player player, String gameDate, double leagueAvgPoints) {
        LocalDate latestDate = LocalDate.parse(gameDate, GAME_DATE_FORMAT);
        String windowStart = latestDate.minusDays(RECENT_WINDOW_DAYS).format(GAME_DATE_FORMAT);

        List<PriceHistory> recentGames = priceHistoryRepository
                .findByPlayerAndGameDateBetween(player, windowStart, gameDate);

        double avg = recentGames.stream()
                .filter(h -> h.getFantasyPoints() != null)
                .mapToDouble(PriceHistory::getFantasyPoints)
                .average()
                .orElse(leagueAvgPoints);

        return avg / leagueAvgPoints;
    }

    private int determineReferenceGames(Player player, boolean isPitcher) {
        if (!isPitcher) {
            return REFERENCE_SEASON_GAMES_HITTER;
        }
        boolean isReliever = (player.getSaves() != null && player.getSaves() > 0)
                || (player.getHolds() != null && player.getHolds() > 0);
        return isReliever ? REFERENCE_SEASON_GAMES_RELIEVER : REFERENCE_SEASON_GAMES_PITCHER;
    }

    private double swingCeiling(int gameNumber, int referenceGames) {
        double g = Math.max(1, gameNumber);
        double progress = (g - 1) / (double) (referenceGames - 1);
        return MAX_DAILY_MOVE_PERCENT * Math.pow(MIN_DAILY_MOVE_PERCENT / MAX_DAILY_MOVE_PERCENT, progress);
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