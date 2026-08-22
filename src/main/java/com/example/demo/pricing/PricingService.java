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
    private static final double BASE_RECENT_PERFORMANCE_WEIGHT = 0.15;
    private static final double BASE_SEASON_PERFORMANCE_WEIGHT = 0.50;
    private static final double BASE_PROJECTION_WEIGHT = 0.20;
    private static final double BASE_ADP_WEIGHT = 0.15;

    // How many real games played this season before the recent/season
    // performance weights are fully trusted. Below this, weight is shifted
    // from recent/season over to projections/ADP -- same "don't overreact to
    // a tiny sample" philosophy the old formula used for its performance
    // multiplier, just applied to weight distribution instead.
    //
    // Each of these is 25% of that role's REFERENCE_SEASON_GAMES_* above, so
    // "how big a sample counts as trustworthy" scales consistently with each
    // role's actual real-world workload, instead of using one hitter-scale
    // number for everyone. That old bug (a single threshold of 30 for every
    // position) meant a starter with 18 starts -- more than half a season,
    // a perfectly trustworthy sample of real, if mediocre, performance -- was
    // still only at 60% confidence, keeping 40% of their recent+season weight
    // diverted to projections/ADP. That's exactly why a starter with a bad
    // ERA (e.g. 4.73) could still price near the top: their real results were
    // only getting 60% of their normal say.
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
            // gamesPlayed is NOT reset here -- that field is owned entirely by
            // MlbSeasonStatsService, synced straight from MLB's own API. This
            // only resets the running fantasy-point average (which IS owned
            // here), so last season's number doesn't bleed into this season's
            // "season-long performance" factor.
            player.setAvgFantasyPoints(null);
        }

        boolean isPitcher = "P".equals(player.getPosition());
        double leagueAvgPoints = isPitcher ? LEAGUE_AVG_PITCHER_FANTASY_POINTS : LEAGUE_AVG_HITTER_FANTASY_POINTS;

        // 1. Recent fantasy performance (15%) -- average points over the last
        // RECENT_WINDOW_DAYS days, vs. the league-average player.
        double recentRatio = calculateRecentRatio(player, gameDate, leagueAvgPoints);

        // 2. Season-long performance (50%) -- the player's REAL season-to-date
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

        // 4. Market/ADP value (15%) -- how early this player was drafted.
        // No ADP data at all (undrafted/deep bench) -> 0, i.e. well below average,
        // rather than a neutral 1.0 -- an unranked player shouldn't get credit
        // for market value it doesn't have.
        double adpRatio = adpBonus != null ? adpBonus / LEAGUE_AVG_ADP_BONUS : 0.0;

        double[] weights = calculateEffectiveWeights(player, isPitcher);
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
            double moveCeiling = swingCeiling(gamesPlayed + 1, determineReferenceGames(player, isPitcher));
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
    // their normal ratio (projections carry a bit more than ADP once they're
    // carrying extra weight, same relative split as they do normally).
    //
    // A brand new player with 0 real games this season: recent/season
    // contribute nothing, weight goes ~57% projections / ~43% ADP. A player
    // with a full established season (relative to their role's threshold):
    // weights are exactly the BASE_* values (15/50/20/15). Everything in
    // between is a smooth blend.
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

    // Same role split as determineReferenceGames, but tuned to "how many
    // games is a trustworthy sample" rather than "how many games is a full
    // season for swing-ceiling purposes" -- a starter's ERA is a meaningful
    // signal well before they've made 32 starts.
    private int requiredGamesForConfidence(Player player, boolean isPitcher) {
        if (!isPitcher) {
            return GAMES_FOR_FULL_STAT_CONFIDENCE_HITTER;
        }
        boolean isReliever = (player.getSaves() != null && player.getSaves() > 0)
                || (player.getHolds() != null && player.getHolds() > 0);
        return isReliever ? GAMES_FOR_FULL_STAT_CONFIDENCE_RELIEVER : GAMES_FOR_FULL_STAT_CONFIDENCE_STARTER;
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

    // Average fantasy points over this player's ACTUAL GAMES in the
    // RECENT_WINDOW_DAYS leading up to (and including) gameDate, as a ratio
    // to the league-average per-game figure for their position. Falls back to
    // a neutral 1.0 ratio if they have no real games in that window.
    //
    // Explicitly filters out rows with a null fantasyPoints -- those are
    // "off-day" price_history entries (see repriceInactivePlayers over in
    // MlbIngestionService), written on days a player didn't actually play so
    // their card doesn't look permanently frozen. Counting those as 0 would
    // badly drag down the average for anyone who's played rarely in the
    // window instead of just excluding the off-days entirely.
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

    // Relievers are told apart from starters with a simple heuristic: any
    // pitcher with a meaningful saves or holds total is a reliever. Not
    // perfect (a swingman with zero of either would get treated as a
    // starter), but a solid approximation with the data actually available.
    private int determineReferenceGames(Player player, boolean isPitcher) {
        if (!isPitcher) {
            return REFERENCE_SEASON_GAMES_HITTER;
        }
        boolean isReliever = (player.getSaves() != null && player.getSaves() > 0)
                || (player.getHolds() != null && player.getHolds() > 0);
        return isReliever ? REFERENCE_SEASON_GAMES_RELIEVER : REFERENCE_SEASON_GAMES_PITCHER;
    }

    // The max % a single update is allowed to move the price toward its
    // target, as a function of which appearance number this is in the
    // player's season. Decays smoothly from MAX_DAILY_MOVE_PERCENT toward
    // MIN_DAILY_MOVE_PERCENT and keeps decaying past that -- no hard floor.
    private double swingCeiling(int gameNumber, int referenceGames) {
        double g = Math.max(1, gameNumber);
        double progress = (g - 1) / (double) (referenceGames - 1);
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