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

    // How many real appearances a full season roughly is -- used to calibrate
    // how fast the "move toward target" ceiling shrinks as a player racks up
    // a track record. Early on, a single update can move the price a lot; by
    // the time they've hit this many appearances, moves are much smaller and
    // steadier. Split by position because a hitter's ~162 team games and a
    // starting pitcher's ~32 starts are completely different scales -- using
    // the hitter number for pitchers too was why pitchers were still getting
    // a ~25-30% daily move cap deep into the season (e.g. after only 20-25
    // starts, still under 15% of 162), instead of settling down like hitters
    // with equivalent playing time did.
    private static final int REFERENCE_SEASON_GAMES_HITTER = 162;
    private static final int REFERENCE_SEASON_GAMES_PITCHER = 32;

    // Full-time relievers rack up roughly double a starter's appearances in a
    // season (closers/setup men routinely hit 60-70+), so they get their own,
    // longer reference instead of settling down twice as fast as their real
    // workload would justify.
    private static final int REFERENCE_SEASON_GAMES_RELIEVER = 95;
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

    // Roughly the MLB-wide average this season -- used as the baseline for
    // the season-long performance factor. Hitters above this OPS score above
    // 1.0, below score under 1.0. Same idea for pitchers, but inverted (a
    // LOWER era than this is good).
    private static final double LEAGUE_AVG_OPS = 0.715;
    private static final double LEAGUE_AVG_ERA = 4.00;

    // ---- The weighted pricing model ----
    private static final double BASE_RECENT_PERFORMANCE_WEIGHT = 0.15;
    private static final double BASE_SEASON_PERFORMANCE_WEIGHT = 0.50;
    private static final double BASE_PROJECTION_WEIGHT = 0.20;
    private static final double BASE_ADP_WEIGHT = 0.15;

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

        double recentRatio = calculateRecentRatio(player, gameDate, leagueAvgPoints);
        double seasonRatio = calculateSeasonRatio(player, isPitcher);

        double projectionRatio = weeklyProjection != null
                ? (weeklyProjection / GAMES_PER_WEEK) / leagueAvgPoints
                : 1.0;

        double adpRatio = adpBonus != null ? adpBonus / LEAGUE_AVG_ADP_BONUS : 0.0;

        double[] weights = calculateEffectiveWeights(player);
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