package com.example.demo.pricing;

import com.example.demo.player.Player;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

// NFL's own pricing engine -- deliberately separate from PricingService
// rather than bolted onto it. MLB's formula leans on stats that don't have
// an NFL equivalent (OPS, ERA), and MLB's constants were carefully
// calibrated against real archived data over this whole project -- mixing
// NFL branches into that same class would risk destabilizing a formula
// that's already tuned, for no real benefit. The underlying IDEA is the
// same one PricingService uses (recent form + season performance +
// preseason projection + ADP, weighted by confidence, moves capped day to
// day), just applied with NFL-appropriate numbers.
@Component
public class NflPricingService {

    private static final DateTimeFormatter GAME_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private static final double MIN_PRICE = 1.0;

    // NFL has one game a week, 17 in a regular season -- everything below is
    // scaled to that, not MLB's ~162-game rhythm.
    private static final int REFERENCE_SEASON_GAMES = 17;
    private static final int GAMES_FOR_FULL_STAT_CONFIDENCE = 6; // roughly a third of a season

    // "Recent form" for NFL means the last couple of games, not the last
    // couple of weeks -- there's only one game a week, so a 15-day window
    // (MLB's number) would barely ever span more than 2 games anyway. 21
    // days comfortably covers the last 2-3 games even around a bye week.
    public static final int RECENT_WINDOW_DAYS = 21;

    private static final double MAX_DAILY_MOVE_PERCENT = 0.30;
    private static final double MIN_DAILY_MOVE_PERCENT = 0.08;

    // Starting estimates based on standard PPR fantasy scoring norms across
    // roughly all starting-caliber, fantasy-relevant players at each
    // position -- NOT calibrated from real archived data yet, since there
    // isn't any until the first games get ingested. Same situation MLB's
    // pitcher baseline was in before it got fixed with real numbers (see
    // PricingService) -- worth recalibrating the same way (a
    // /admin/fantasy-points-breakdown-style endpoint, grouped by position)
    // once a real sample of NFL games has been archived.
    private static final Map<String, Double> LEAGUE_AVG_FANTASY_POINTS_BY_POSITION = Map.of(
            "QB", 17.0,
            "RB", 11.0,
            "WR", 10.0,
            "TE", 7.0
    );
    private static final double DEFAULT_LEAGUE_AVG_FANTASY_POINTS = 10.0;

    private static final double LEAGUE_AVG_ADP_BONUS = 50.0;

    // NFL's own weights -- deliberately different from MLB's (15/50/20/15).
    // Season performance carries a bit less weight here (45% vs MLB's 50%)
    // and recent form a lot more (25% vs 15%), since "recent" for NFL means
    // the last 2-3 games out of a 17-game season -- a much bigger, more
    // meaningful slice of the season than MLB's last couple weeks out of 162.
    private static final double BASE_RECENT_PERFORMANCE_WEIGHT = 0.25;
    private static final double BASE_SEASON_PERFORMANCE_WEIGHT = 0.45;
    private static final double BASE_PROJECTION_WEIGHT = 0.20;
    private static final double BASE_ADP_WEIGHT = 0.10;

    private final PriceHistoryRepository priceHistoryRepository;

    public NflPricingService(PriceHistoryRepository priceHistoryRepository) {
        this.priceHistoryRepository = priceHistoryRepository;
    }

    public void updatePrice(Player player, String gameDate, Double weeklyProjection, Double adpBonus, String rawStatsJson) {
        updatePrice(player, gameDate, weeklyProjection, adpBonus, rawStatsJson, fetchRecentGames(player, gameDate));
    }

    // Same batched-recent-games overload pattern as PricingService, for
    // callers that already fetched everyone's recent history in one query.
    public void updatePrice(Player player, String gameDate, Double weeklyProjection, Double adpBonus, String rawStatsJson, List<PriceHistory> recentGames) {
        String season = getSeasonForDate(gameDate);
        boolean isNewSeason = player.getCurrentSeason() == null || !player.getCurrentSeason().equals(season);

        if (isNewSeason) {
            player.setCurrentSeason(season);
            player.setAvgFantasyPoints(null);
        }

        double leagueAvgPoints = LEAGUE_AVG_FANTASY_POINTS_BY_POSITION.getOrDefault(
                player.getPosition(), DEFAULT_LEAGUE_AVG_FANTASY_POINTS);

        double recentRatio = calculateRecentRatio(recentGames, leagueAvgPoints);
        double seasonRatio = calculateSeasonRatio(player, leagueAvgPoints);
        double projectionRatio = weeklyProjection != null ? weeklyProjection / leagueAvgPoints : 1.0;
        double adpRatio = adpBonus != null ? adpBonus / LEAGUE_AVG_ADP_BONUS : 0.0;

        double[] weights = calculateEffectiveWeights(player);
        double compositeRatio = weights[0] * recentRatio +
                weights[1] * seasonRatio +
                weights[2] * projectionRatio +
                weights[3] * adpRatio;

        double targetPrice = Math.max(MIN_PRICE, 100.0 * compositeRatio);

        if (isNewSeason || player.getPrice() == null) {
            player.setPrice(targetPrice);
        } else {
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

    private List<PriceHistory> fetchRecentGames(Player player, String gameDate) {
        LocalDate latestDate = LocalDate.parse(gameDate, GAME_DATE_FORMAT);
        String windowStart = latestDate.minusDays(RECENT_WINDOW_DAYS).format(GAME_DATE_FORMAT);
        return priceHistoryRepository.findByPlayerAndGameDateBetween(player, windowStart, gameDate);
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

    // No OPS/ERA equivalent tracked for NFL yet, so season performance is
    // just the player's own season-long fantasy-point average against their
    // position's baseline -- same shape as recentRatio, just over the whole
    // season instead of the last few games.
    private double calculateSeasonRatio(Player player, double leagueAvgPoints) {
        Double seasonAvg = player.getAvgFantasyPoints();
        if (seasonAvg == null) return 1.0;
        return seasonAvg / leagueAvgPoints;
    }

    private double calculateRecentRatio(List<PriceHistory> recentGames, double leagueAvgPoints) {
        double avg = recentGames.stream()
                .filter(h -> h.getFantasyPoints() != null)
                .mapToDouble(PriceHistory::getFantasyPoints)
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

    // NFL seasons span two calendar years (Sept-Feb) -- same "does the month
    // roll into next year's label" logic PricingService already uses for
    // non-MLB sports, kept here rather than shared since it's one line.
    private String getSeasonForDate(String gameDate) {
        int year = Integer.parseInt(gameDate.substring(0, 4));
        int month = Integer.parseInt(gameDate.substring(4, 6));
        int startYear = (month >= 8) ? year : year - 1;
        int endYearShort = (startYear + 1) % 100;
        return String.format("%d-%02d", startYear, endYearShort);
    }
}
