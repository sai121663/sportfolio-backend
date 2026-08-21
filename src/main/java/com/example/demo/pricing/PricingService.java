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

    private static final int REFERENCE_SEASON_GAMES = 162;
    private static final double MAX_DAILY_MOVE_PERCENT = 0.30;
    private static final double MIN_DAILY_MOVE_PERCENT = 0.08;

    private static final int RECENT_WINDOW_DAYS = 15;

    private static final double LEAGUE_AVG_HITTER_FANTASY_POINTS = 1.78;
    private static final double LEAGUE_AVG_PITCHER_FANTASY_POINTS = 3.77;

    private static final double GAMES_PER_WEEK = 6.0;

    private static final double LEAGUE_AVG_ADP_BONUS = 50.0;

    // Roughly the MLB-wide average this season -- used as the baseline for
    // the season-long performance factor. Hitters above this OPS score above
    // 1.0, below score under 1.0. Same idea for pitchers, but inverted (a
    // LOWER era than this is good).
    private static final double LEAGUE_AVG_OPS = 0.715;
    private static final double LEAGUE_AVG_ERA = 4.00;

    private static final double BASE_RECENT_PERFORMANCE_WEIGHT = 0.40;
    private static final double BASE_SEASON_PERFORMANCE_WEIGHT = 0.30;
    private static final double BASE_PROJECTION_WEIGHT = 0.20;
    private static final double BASE_ADP_WEIGHT = 0.10;

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
        }

        boolean isPitcher = "P".equals(player.getPosition());
        double leagueAvgPoints = isPitcher ? LEAGUE_AVG_PITCHER_FANTASY_POINTS : LEAGUE_AVG_HITTER_FANTASY_POINTS;

        // 1. Recent fantasy performance (40%) -- average points over the last
        // RECENT_WINDOW_DAYS days, vs. the league-average player.
        double recentRatio = calculateRecentRatio(player, gameDate, leagueAvgPoints);

        // 2. Season-long performance (30%) -- the player's REAL season-to-date
        // OPS/ERA, synced daily straight from MLB's own stats API by
        // MlbSeasonStatsService. Deliberately NOT based on the internally
        // tracked avgFantasyPoints below, because that field gets reset to
        // null every time /admin/reset-pricing runs -- using it here would
        // mean "season-long performance" only ever reflected however many
        // days you'd backfilled since the last reset, instead of a player's
        // actual full season. OPS/ERA are untouched by that reset, so this
        // stays accurate no matter how the pricing data itself gets rebuilt.
        double seasonRatio = calculateSeasonRatio(player, isPitcher);

        // 3. Updated projections (20%) -- Tank01's rest-of-season projection,
        // converted from a weekly figure to a per-game one. No projection data
        // -> neutral (1.0) rather than punishing/rewarding an unprojected player.
        double projectionRatio = weeklyProjection != null
                ? (weeklyProjection / GAMES_PER_WEEK) / leagueAvgPoints
                : 1.0;

        // 4. Market/ADP value (10%) -- how early this player was drafted.
        // No ADP data at all (undrafted/deep bench) -> 0, i.e. well below average,
        // rather than a neutral 1.0 -- an unranked player shouldn't get credit
        // for market value it doesn't have.
        double adpRatio = adpBonus != null ? adpBonus / LEAGUE_AVG_ADP_BONUS : 0.0;

        double[] weights = calculateEffectiveWeights(player);
        double compositeRatio =
                weights[0] * recentRatio +
                weights[1] * seasonRatio +
                weights[2] * projectionRatio +
                weights[3] * adpRatio;

        double targetPrice = Math.max(MIN_PRICE, 100.0 * compositeRatio);

        if (isNewSeason || player.getPrice() == null) {
            // First update of a (re)started season, or no price at all yet --
            // jump straight to the target instead of smoothing from a stale
            // price. This matters most right after /admin/reset-pricing: an
            // established veteran with 100+ real games played would otherwise
            // get a tiny daily-move cap (see swingCeiling) and crawl toward
            // their correct price for days: not because the formula is wrong,
            // but purely because they'd been smoothing from an old, stale
            // number. Snapping once per season avoids that without ever
            // risking a wild jump mid-season on a single new game.
            player.setPrice(targetPrice);
        } else {
            // Smooth toward the target instead of snapping straight to it, so
            // one big game (or a stat correction) can't cause a wild one-day
            // jump. The cap shrinks the more games a player has played, same
            // philosophy as the old swing ceiling.
            int gamesPlayed = player.getGamesPlayed() != null ? player.getGamesPlayed() : 0;
            double moveCeiling = swingCeiling(gamesPlayed + 1);
            double gap = targetPrice - player.getPrice();
            double maxMove = player.getPrice() * moveCeiling;
            double actualMove = Math.max(-maxMove, Math.min(maxMove, gap));
            player.setPrice(Math.max(MIN_PRICE, player.getPrice() + actualMove));
        }

        rollTodaysPerformanceIntoAverage(player);
        savePriceHistory(player, gameDate, rawStatsJson);
    }

    private double[] calculateEffectiveWeights(Player player) {
        int gamesPlayed = player.getGamesPlayed() != null ? player.getGamesPlayed() : 0;
        double confidence = Math.min(1.0, gamesPlayed / (double) GAMES_FOR_FULL_STAT_CONFIDENCE);

        double recentWeight = BASE_RECENT_PERFORMANCE_WEIGHT * confidence;
        double seasonWeight = BASE_SEASON_PERFORMANCE_WEIGHT * confidence;

        double freedUpWeight = (BASE_RECENT_PERFORMANCE_WEIGHT + BASE_SEASON_PERFORMANCE_WEIGHT) * (1 - confidence);
        double projectionShare = BASE_PROJECTION_WEIGHT / (BASE_PROJECTION_WEIGHT + BASE_ADP_WEIGHT);
        double adpShare = BASE_ADP_WEIGHT / (BASE_PROJECTION_WEIGHT + BASE_ADP_WEIGHT);

        double projectionWeight = BASE_PROJECTION_WEIGHT + freedUpWeight * projectionShare;
        double adpWeight = BASE_ADP_WEIGHT + freedUpWeight * adpShare;

        return new double[]{recentWeight, seasonWeight, projectionWeight, adpWeight};
    }

    // Ratio of a player's real season-to-date OPS/ERA to the league average,
    // as synced by MlbSeasonStatsService straight from MLB's own stats API.
    // Returns a neutral 1.0 if we don't have real season stats for them yet
    // (e.g. a rookie who hasn't debuted).
    private double calculateSeasonRatio(Player player, boolean isPitcher) {
        if (isPitcher && player.getEra() != null && player.getEra() > 0) {
            return LEAGUE_AVG_ERA / player.getEra();
        }
        if (!isPitcher && player.getOps() != null) {
            return player.getOps() / LEAGUE_AVG_OPS;
        }
        return 1.0;
    }

    private double calculateRecentRatio(Player player, String gameDate, double leagueAvgPoints) {
        LocalDate latestDate = LocalDate.parse(gameDate, GAME_DATE_FORMAT);
        String windowStart = latestDate.minusDays(RECENT_WINDOW_DAYS).format(GAME_DATE_FORMAT);

        List<PriceHistory> recentGames = priceHistoryRepository
                .findByPlayerAndGameDateBetween(player, windowStart, gameDate);

        if (recentGames.isEmpty()) {
            return 1.0;
        }

        double avg = recentGames.stream()
                .mapToDouble(h -> h.getFantasyPoints() != null ? h.getFantasyPoints() : 0.0)
                .average()
                .orElse(leagueAvgPoints);

        return avg / leagueAvgPoints;
    }

    private double swingCeiling(int gameNumber) {
        double g = Math.max(1, gameNumber);
        double progress = (g - 1) / (double) (REFERENCE_SEASON_GAMES - 1);
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