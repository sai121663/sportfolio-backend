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

    // How many real games a full season roughly is -- used to calibrate how
    // fast the "move toward target" ceiling shrinks as a player racks up a
    // track record. Early in a player's season, a single update can move the
    // price a lot; by ~162 games in, moves are much smaller and steadier.
    private static final int REFERENCE_SEASON_GAMES = 162;
    private static final double MAX_DAILY_MOVE_PERCENT = 0.30;
    private static final double MIN_DAILY_MOVE_PERCENT = 0.08;

    // How many calendar days count as "recent" for the recent-form factor.
    private static final int RECENT_WINDOW_DAYS = 15;

    // Real per-game-appearance fantasy point averages, measured directly from
    // actual Tank01 box scores (hitters: ~104 real games, pitchers: ~47 real
    // games -- the same sample used to calibrate the old swing-ceiling
    // constants). This is the "average player" baseline every ratio below is
    // measured against, so an exactly-average player prices at $100.
    private static final double LEAGUE_AVG_HITTER_FANTASY_POINTS = 1.78;
    private static final double LEAGUE_AVG_PITCHER_FANTASY_POINTS = 3.77;

    // Rough MLB games-per-week, used to convert Tank01's weekly projection
    // number down to a per-game figure so it's on the same scale as the
    // fantasy-point averages above.
    private static final double GAMES_PER_WEEK = 6.0;

    // Roughly the midpoint of the ADP bonus's 0-100 range across a typical
    // ~300-player draft pool -- the "average" baseline for the market-value
    // ratio below.
    private static final double LEAGUE_AVG_ADP_BONUS = 50.0;

    // ---- The weighted pricing model ----
    // Each factor is expressed as a ratio to an "average player" baseline
    // (1.0 = exactly average). The four ratios are blended by weights into
    // one composite ratio, which maps straight to price: a player who's
    // exactly average on all four scores a 1.0 composite ratio -> $100.
    //
    // These are the FULL-CONFIDENCE weights -- what a player with an
    // established track record this season actually uses. Players with
    // fewer real games played this season use a dynamically-adjusted set
    // (see calculateEffectiveWeights) that leans more on projections/ADP
    // instead, since recent/season performance isn't a trustworthy signal
    // yet for someone who's barely played.
    private static final double BASE_RECENT_PERFORMANCE_WEIGHT = 0.40;
    private static final double BASE_SEASON_PERFORMANCE_WEIGHT = 0.30;
    private static final double BASE_PROJECTION_WEIGHT = 0.20;
    private static final double BASE_ADP_WEIGHT = 0.10;

    // How many real games played this season before the recent/season
    // performance weights are fully trusted. Below this, weight is shifted
    // from recent/season over to projections/ADP -- same "don't overreact to
    // a tiny sample" philosophy the old formula used for its performance
    // multiplier, just applied to weight distribution instead.
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
            // gamesPlayed is NOT reset here -- that field is owned entirely by
            // MlbSeasonStatsService, synced straight from MLB's own API. This
            // only resets the running fantasy-point average (which IS owned
            // here), so last season's number doesn't bleed into this season's
            // "season-long performance" factor.
            player.setAvgFantasyPoints(null);
        }

        boolean isPitcher = "P".equals(player.getPosition());
        double leagueAvgPoints = isPitcher ? LEAGUE_AVG_PITCHER_FANTASY_POINTS : LEAGUE_AVG_HITTER_FANTASY_POINTS;

        // 1. Recent fantasy performance (40%) -- average points over the last
        // RECENT_WINDOW_DAYS days, vs. the league-average player.
        double recentRatio = calculateRecentRatio(player, gameDate, leagueAvgPoints);

        // 2. Season-long performance (30%) -- the running season average, vs.
        // the league-average player. No games yet this season -> neutral (1.0).
        double seasonRatio = player.getAvgFantasyPoints() != null
                ? player.getAvgFantasyPoints() / leagueAvgPoints
                : 1.0;

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

        if (player.getPrice() == null) {
            // No price at all yet (brand new player) -- jump straight to the
            // target instead of "smoothing" from a price that doesn't exist.
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

    // Scales the recent/season weights down for players without an
    // established track record this season, and hands that freed-up weight
    // to projections/ADP instead -- split between the two proportionally to
    // their normal 2:1 ratio (projections matter roughly twice as much as
    // ADP once they're carrying extra weight, same as they do normally).
    //
    // A brand new player with 0 real games this season: recent/season
    // contribute nothing, weight goes ~67% projections / ~33% ADP. A player
    // with a full established season: weights are exactly the BASE_* values
    // (40/30/20/10). Everything in between is a smooth blend.
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

    // Average fantasy points over this player's games in the RECENT_WINDOW_DAYS
    // leading up to (and including) gameDate, as a ratio to the league-average
    // per-game figure for their position. Falls back to a neutral 1.0 ratio if
    // they have no games in that window yet.
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

    // The max % a single update is allowed to move the price toward its
    // target, as a function of which game number this is in the player's
    // season. Decays smoothly from MAX_DAILY_MOVE_PERCENT toward
    // MIN_DAILY_MOVE_PERCENT and keeps decaying past that -- no hard floor.
    private double swingCeiling(int gameNumber) {
        double g = Math.max(1, gameNumber);
        double progress = (g - 1) / (double) (REFERENCE_SEASON_GAMES - 1);
        return MAX_DAILY_MOVE_PERCENT * Math.pow(MIN_DAILY_MOVE_PERCENT / MAX_DAILY_MOVE_PERCENT, progress);
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