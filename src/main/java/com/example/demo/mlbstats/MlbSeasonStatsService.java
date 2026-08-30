package com.example.demo.mlbstats;

import com.example.demo.player.Player;
import com.example.demo.player.PlayerRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Year;
import java.util.List;

// Refreshes each MLB player's position and season hitting/pitching totals from
// MLB's own Stats API. Runs independently of the Tank01 box-score ingestion --
// it covers every player in the database regardless of whether they played today,
// since these are season-to-date totals, not per-game numbers.
@Service
public class MlbSeasonStatsService {

    private final MlbStatsApiClient mlbStatsApiClient;
    private final PlayerRepository playerRepository;

    public MlbSeasonStatsService(MlbStatsApiClient mlbStatsApiClient, PlayerRepository playerRepository) {
        this.mlbStatsApiClient = mlbStatsApiClient;
        this.playerRepository = playerRepository;
    }

    public int refreshAllMlbSeasonStats() {
        int season = Year.now().getValue();
        List<Player> mlbPlayers = playerRepository.findBySport("MLB");
        int updatedCount = 0;

        for (Player player : mlbPlayers) {
            if (player.getExternalId() == null) continue;

            String position = mlbStatsApiClient.getPrimaryPosition(player.getExternalId());
            if (position == null) continue; // couldn't resolve this player right now, skip and retry next run

            player.setPosition(position);

            // MLB's own Stats API reports a genuine two-way player (currently
            // just Shohei Ohtani) with the official position code "TWP"
            // rather than "P" -- so without this check he'd fall into the
            // hitting-only branch below and his pitching stats (and
            // therefore his pitching performance) would never factor into
            // his price at all. For a TWP, fetch BOTH stat groups instead of
            // picking one.
            boolean isTwoWay = "TWP".equals(position);

            if ("P".equals(position) || isTwoWay) {
                MlbStatsApiDtos.StatBlock pitching =
                        mlbStatsApiClient.getPitchingSeasonStats(player.getExternalId(), season);
                if (pitching != null) {
                    player.setEra(pitching.era != null ? Double.parseDouble(pitching.era) : null);
                    player.setWins(pitching.wins);
                    player.setLosses(pitching.losses);
                    player.setEarnedRuns(pitching.earnedRuns);
                    player.setSaves(pitching.saves);
                    player.setHolds(pitching.holds);
                    player.setOuts(pitching.outs);
                    player.setGamesStarted(pitching.gamesStarted);

                    // MLB's Stats API only ever hands back the generic "P"
                    // for a pitcher -- it doesn't distinguish a starter from
                    // a reliever the way it distinguishes, say, "1B" from
                    // "OF". Any real start this season is a reliable signal
                    // he's used as a starter; a two-way player keeps "TWP"
                    // as-is rather than being folded into either bucket,
                    // since his card already has its own hitting/pitching
                    // toggle instead of a single role label.
                    if (!isTwoWay) {
                        boolean hasStarted = pitching.gamesStarted != null && pitching.gamesStarted > 0;
                        player.setPosition(hasStarted ? "SP" : "RP");

                        // These fields are shared with the hitting branch below
                        // (they mean something different for each side) -- for
                        // a normal pitcher there's no hitting branch to
                        // conflict with, but for a two-way player we let the
                        // hitting numbers win below, since that's the far more
                        // frequent, representative side of his workload.
                        player.setStrikeouts(pitching.strikeOuts);
                        player.setHits(pitching.hits);
                        player.setWalks(pitching.baseOnBalls);
                        if (pitching.gamesPlayed != null) {
                            player.setGamesPlayed(pitching.gamesPlayed);
                        }
                    }
                    if (pitching.team != null) {
                        player.setTeamId(pitching.team.id);
                    }
                }
            }
            if (!"P".equals(position) || isTwoWay) {
                MlbStatsApiDtos.StatBlock hitting =
                        mlbStatsApiClient.getHittingSeasonStats(player.getExternalId(), season);
                if (hitting != null) {
                    player.setHomeRuns(hitting.homeRuns);
                    player.setRbi(hitting.rbi);
                    player.setOps(hitting.ops != null ? Double.parseDouble(hitting.ops) : null);
                    player.setHits(hitting.hits);
                    player.setDoubles(hitting.doubles);
                    player.setTriples(hitting.triples);
                    player.setWalks(hitting.baseOnBalls);
                    player.setHitByPitch(hitting.hitByPitch);
                    player.setStolenBases(hitting.stolenBases);
                    player.setRuns(hitting.runs);
                    player.setStrikeouts(hitting.strikeOuts);
                    if (hitting.gamesPlayed != null) {
                        player.setGamesPlayed(hitting.gamesPlayed);
                    }
                    if (hitting.team != null) {
                        player.setTeamId(hitting.team.id);
                    }
                }
            }

            Double trueAvg = calculateTrueSeasonAvgFantasyPoints(player);
            if (trueAvg != null) {
                player.setAvgFantasyPoints(trueAvg);
            }

            playerRepository.save(player);
            updatedCount++;
        }
        return updatedCount;
    }

    // Computes a real, per-game fantasy-points average straight from this player's
    // full-season MLB stat totals, using the exact scoring formula reverse-
    // engineered from real Tank01 box scores (solved via regression against real
    // paired raw-stats/fantasy-points data):
    //
    //   Hitters: 1B=1, 2B=2, 3B=3, HR=4, plus +1 each for R/RBI/BB/HBP/SB, -1 per K
    //   Pitchers: +1 per out, -1 per hit allowed, -2 per earned run, -1 per walk,
    //             +1 per strikeout, +/-2 for win/loss/save/hold
    //
    // This replaces the old approach of building the average incrementally, one
    // ingested game at a time -- which left it "thin" (based on only however many
    // games we happened to have ingested since the app existed) even for players
    // who had already played 100+ real games this season. Recalculating this
    // fresh from real season totals every night means the baseline is always
    // accurate, regardless of how much or little day-to-day ingestion history we
    // happen to have for a given player.
    private Double calculateTrueSeasonAvgFantasyPoints(Player player) {
        Integer gamesPlayed = player.getGamesPlayed();
        if (gamesPlayed == null || gamesPlayed <= 0) return null;

        // Position is now "SP" or "RP" (not the raw "P" MLB's API returns) --
        // see the reclassification above.
        if ("SP".equals(player.getPosition()) || "RP".equals(player.getPosition())) {
            if (player.getOuts() == null || player.getHits() == null || player.getEarnedRuns() == null
                    || player.getWalks() == null || player.getStrikeouts() == null
                    || player.getWins() == null || player.getLosses() == null
                    || player.getSaves() == null || player.getHolds() == null) {
                return null;
            }
            double total = player.getOuts()
                    - player.getHits()
                    - (2.0 * player.getEarnedRuns())
                    - player.getWalks()
                    + player.getStrikeouts()
                    + (2.0 * player.getWins())
                    - (2.0 * player.getLosses())
                    + (2.0 * player.getSaves())
                    + (2.0 * player.getHolds());
            return total / gamesPlayed;
        } else {
            if (player.getHits() == null || player.getDoubles() == null || player.getTriples() == null
                    || player.getHomeRuns() == null || player.getRuns() == null || player.getRbi() == null
                    || player.getWalks() == null || player.getHitByPitch() == null
                    || player.getStolenBases() == null || player.getStrikeouts() == null) {
                return null;
            }
            int singles = player.getHits() - player.getDoubles() - player.getTriples() - player.getHomeRuns();
            double total = singles
                    + (2.0 * player.getDoubles())
                    + (3.0 * player.getTriples())
                    + (4.0 * player.getHomeRuns())
                    + player.getRuns()
                    + player.getRbi()
                    + player.getWalks()
                    + player.getHitByPitch()
                    + player.getStolenBases()
                    - player.getStrikeouts();
            return total / gamesPlayed;
        }
    }

    // Fires at 6:15 a.m. US Eastern time -- ten minutes after MlbIngestionService's
    // 6:05 a.m. run, so gamesPlayed/position/season stats are fresh before that
    // day's price ingestion needs them. Pinned to America/New_York so this means
    // the same real-world time regardless of what timezone the server runs in.
    @Scheduled(cron = "0 15 6 * * *", zone = "America/New_York")
    public void scheduledMlbSeasonStatsRefresh() {
        int count = refreshAllMlbSeasonStats();
        System.out.println("MLB season stats refresh complete: " + count + " players updated");
    }
}