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

    // Public so MlbIngestionService can batch-fetch this same window across
    // every player in one query instead of one query per player -- see
    // MlbIngestionService.fetchRecentGamesBatch. Keeping the number defined
    // here (not duplicated there) means the two can never drift out of sync.
    public static final int RECENT_WINDOW_DAYS = 15;

    private static final double LEAGUE_AVG_HITTER_FANTASY_POINTS = 1.78;
    private static final double LEAGUE_AVG_PITCHER_FANTASY_POINTS = 3.77;

    private static final double GAMES_PER_WEEK = 6.0;

    private static final double LEAGUE_AVG_ADP_BONUS = 50.0;

    private static final double LEAGUE_AVG_OPS = 0.715;
    private static final double LEAGUE_AVG_ERA = 4.00;

    // Caps how extreme the season-performance ratio is allowed to be, no
    // matter how good/bad the real OPS or ERA is.
    private static final double MIN_SEASON_RATIO = 0.5;
    private static final double MAX_SEASON_RATIO = 2.0;

    private static final double BASE_RECENT_PERFORMANCE_WEIGHT = 0.15;
    private static final double BASE_SEASON_PERFORMANCE_WEIGHT = 0.50;
    private static final double BASE_PROJECTION_WEIGHT = 0.20;
    private static final double BASE_ADP_WEIGHT = 0.15;

    // 25% of each role's REFERENCE_SEASON_GAMES_* above.
    private static final int GAMES_FOR_FULL_STAT_CONFIDENCE_HITTER = 40;
    private static final int GAMES_FOR_FULL_STAT_CONFIDENCE_STARTER = 8;
    private static final int GAMES_FOR_FULL_STAT_CONFIDENCE_RELIEVER = 24;

    private final PriceHistoryRepository priceHistoryRepository;

    public PricingService(PriceHistoryRepository priceHistoryRepository) {
        this.priceHistoryRepository = priceHistoryRepository;
    }

    // Original single-player entry point -- queries the DB itself for this
    // one player's recent window. Fine for low-volume callers (the real
    // day-of ingestion only deals with the handful of players who actually
    // played). Multi-day backfills should use the overload below instead.
    public void updatePrice(Player player, String gameDate, Double weeklyProjection, Double adpBonus, String rawStatsJson) {
        updatePrice(player, gameDate, weeklyProjection, adpBonus, rawStatsJson, fetchRecentGames(player, gameDate));
    }

    // Overload for callers that have already batch-fetched everyone's recent
    // price history in ONE query covering many players at once, instead of
    // querying separately per player -- see MlbIngestionService's
    // fetchRecentGamesBatch. Pass an empty list for a player with no games
    // in the window; that's exactly equivalent to what the single-player
    // query would have returned.
    public void updatePrice(Player player, String gameDate, Double weeklyProjection, Double adpBonus, String rawStatsJson, List<PriceHistory> recentGames) {
        String season = getSeasonForDate(gameDate, player.getSport());
        boolean isNewSeason = player.getCurrentSeason() == null
                || !player.getCurrentSeason().equals(season);

        if (isNewSeason) {
            player.setCurrentSeason(season);
            player.setAvgFantasyPoints(null);
        }

        // Position is "SP" or "RP" for a normal pitcher now, not the raw "P"
        // MLB's API returns -- see MlbSeasonStatsService for where that
        // split happens. Either one still counts as "a pitcher" here; which
        // specific role only matters for requiredGamesForConfidence/
        // determineReferenceGames below.
        boolean isPitcher = "SP".equals(player.getPosition()) || "RP".equals(player.getPosition());
        // MLB's Stats API reports a genuine two-way player (currently just
        // Shohei Ohtani) as "TWP" rather than "P". Rather than blending his
        // two ratios into one, he's priced as the SUM of what he'd be worth
        // as a standalone hitter plus what he'd be worth as a standalone
        // pitcher -- computeCompositeRatio below runs the exact same math
        // used for every other player, just twice, once per side. A strong
        // pitching performance genuinely stacks on top of his hitting price;
        // a below-average one drags it back down, since a sub-$100 "pitcher
        // price" contributes a negative amount to the sum.
        boolean isTwoWay = "TWP".equals(player.getPosition());

        double targetPrice;
        if (isTwoWay) {
            double hittingRatio = computeCompositeRatio(player, false, weeklyProjection, adpBonus, recentGames);
            double pitchingRatio = computeCompositeRatio(player, true, weeklyProjection, adpBonus, recentGames);
            targetPrice = Math.max(MIN_PRICE, 100.0 * hittingRatio + 100.0 * pitchingRatio);
        } else {
            double compositeRatio = computeCompositeRatio(player, isPitcher, weeklyProjection, adpBonus, recentGames);
            targetPrice = Math.max(MIN_PRICE, 100.0 * compositeRatio);
        }

        if (isNewSeason || player.getPrice() == null) {
            player.setPrice(targetPrice);
        } else {
            int gamesPlayed = player.getGamesPlayed() != null ? player.getGamesPlayed() : 0;
            // isPitcher (not isTwoWay) on purpose -- a two-way player's
            // gamesPlayed count tracks his (far more frequent) hitting
            // games, so the hitter reference table is the consistent one to
            // measure his day-to-day price-swing ceiling against, exactly
            // like a normal hitter would use.
            double moveCeiling = swingCeiling(gamesPlayed + 1, determineReferenceGames(player, isPitcher));
            double gap = targetPrice - player.getPrice();
            double maxMove = player.getPrice() * moveCeiling;
            double actualMove = Math.max(-maxMove, Math.min(maxMove, gap));
            player.setPrice(Math.max(MIN_PRICE, player.getPrice() + actualMove));
        }

        rollTodaysPerformanceIntoAverage(player);
        savePriceHistory(player, gameDate, rawStatsJson);
    }

    // The exact same composite-ratio math every player already goes
    // through, just pulled out into its own method so a two-way player can
    // run it twice (once as a hitter, once as a pitcher) and have the
    // results stacked in updatePrice above.
    private double computeCompositeRatio(Player player, boolean isPitcher, Double weeklyProjection, Double adpBonus, List<PriceHistory> recentGames) {
        double leagueAvgPoints = isPitcher ? LEAGUE_AVG_PITCHER_FANTASY_POINTS : LEAGUE_AVG_HITTER_FANTASY_POINTS;

        double recentRatio = calculateRecentRatio(recentGames, leagueAvgPoints);
        double seasonRatio = calculateSeasonRatio(player, isPitcher);

        double projectionRatio = weeklyProjection != null
                ? (weeklyProjection / GAMES_PER_WEEK) / leagueAvgPoints
                : 1.0;

        double adpRatio = adpBonus != null ? adpBonus / LEAGUE_AVG_ADP_BONUS : 0.0;

        double[] weights = calculateEffectiveWeights(player, isPitcher);
        return weights[0] * recentRatio +
                weights[1] * seasonRatio +
                weights[2] * projectionRatio +
                weights[3] * adpRatio;
    }

    private List<PriceHistory> fetchRecentGames(Player player, String gameDate) {
        LocalDate latestDate = LocalDate.parse(gameDate, GAME_DATE_FORMAT);
        String windowStart = latestDate.minusDays(RECENT_WINDOW_DAYS).format(GAME_DATE_FORMAT);
        return priceHistoryRepository.findByPlayerAndGameDateBetween(player, windowStart, gameDate);
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
        // "RP" is now an explicit, reliable label (see MlbSeasonStatsService)
        // instead of the old saves/holds guess, which misclassified a
        // starter who'd picked up an early hold, or a true reliever who
        // hadn't recorded a save/hold yet.
        boolean isReliever = "RP".equals(player.getPosition());
        return isReliever ? GAMES_FOR_FULL_STAT_CONFIDENCE_RELIEVER : GAMES_FOR_FULL_STAT_CONFIDENCE_STARTER;
    }

    // Pitchers use a LINEAR formula (2 - era/avgEra) rather than the more
    // obvious "avgEra / era" flip. That flip looks natural but is a real
    // statistical trap: averaging a bunch of "avg / actual" ratios across a
    // whole population does NOT come back out to 1.0 the way "actual / avg"
    // does for hitters -- dividing a fixed number by a spread of values
    // systematically skews the average upward (a classic bias from
    // averaging reciprocals). At 50% weight, that quiet skew was enough to
    // make pitchers price systematically higher than hitters overall, even
    // though it didn't change pitcher-vs-pitcher rankings among themselves.
    // The linear form here is symmetric with the hitter formula and doesn't
    // have that bias.
    private double calculateSeasonRatio(Player player, boolean isPitcher) {
        double ratio = 1.0;
        if (isPitcher && player.getEra() != null && player.getEra() > 0) {
            ratio = 2.0 - (player.getEra() / LEAGUE_AVG_ERA);
        } else if (!isPitcher && player.getOps() != null) {
            ratio = player.getOps() / LEAGUE_AVG_OPS;
        }
        return Math.max(MIN_SEASON_RATIO, Math.min(MAX_SEASON_RATIO, ratio));
    }

    private double calculateRecentRatio(List<PriceHistory> recentGames, double leagueAvgPoints) {
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
        boolean isReliever = "RP".equals(player.getPosition());
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

    // Reuses the existing row for this exact player+date if one already
    // exists, instead of always inserting a new one. Without this, pricing
    // the same date twice (e.g. a live ingestion run followed later by a
    // recompute-range over an overlapping range) creates duplicate
    // price_history rows for that day rather than updating the one that's
    // already there.
    private void savePriceHistory(Player player, String gameDate, String rawStatsJson) {
        List<PriceHistory> existing = priceHistoryRepository.findByPlayerAndGameDate(player, gameDate);
        PriceHistory record = existing.isEmpty() ? new PriceHistory() : existing.get(0);

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
