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



            if ("P".equals(position)) {
                MlbStatsApiDtos.StatBlock pitching =
                        mlbStatsApiClient.getPitchingSeasonStats(player.getExternalId(), season);
                if (pitching != null) {
                    player.setEra(pitching.era != null ? Double.parseDouble(pitching.era) : null);
                    player.setWins(pitching.wins);
                    player.setLosses(pitching.losses);
                    player.setStrikeouts(pitching.strikeOuts);
                    if (pitching.gamesPlayed != null) {
                        player.setGamesPlayed(pitching.gamesPlayed);
                    }
                }
            } else {
                MlbStatsApiDtos.StatBlock hitting =
                        mlbStatsApiClient.getHittingSeasonStats(player.getExternalId(), season);
                if (hitting != null) {
                    player.setHomeRuns(hitting.homeRuns);
                    player.setRbi(hitting.rbi);
                    player.setOps(hitting.ops != null ? Double.parseDouble(hitting.ops) : null);
                    if (hitting.gamesPlayed != null) {
                        player.setGamesPlayed(hitting.gamesPlayed);
                    }
                }
            }


                
            playerRepository.save(player);
            updatedCount++;
        }
        return updatedCount;
    }

    @Scheduled(cron = "0 0 0,1,10-23 * * *") // shortly after the Tank01 box-score ingestion
    
    public void scheduledMlbSeasonStatsRefresh() {
        int count = refreshAllMlbSeasonStats();
        System.out.println("MLB season stats refresh complete: " + count + " players updated");
    }
}
