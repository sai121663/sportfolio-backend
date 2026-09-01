package com.example.demo.tank01;

import com.example.demo.player.Player;
import com.example.demo.player.PlayerRepository;
import com.example.demo.pricing.NflPricingService;
import com.example.demo.pricing.PriceHistory;
import com.example.demo.pricing.PriceHistoryRepository;
import jakarta.persistence.EntityManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Mirrors MlbIngestionService's shape, but simpler -- no two-way-player or
// starter/reliever branching, just skill positions priced the same way.
@Service
public class NflIngestionService {

    private static final ZoneId ET_ZONE = ZoneId.of("America/New_York");
    private static final DateTimeFormatter GAME_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    // Only these positions get priced -- see the "skill positions only"
    // scoping decision. K/DEF/OL/DL/etc. are skipped entirely, including
    // being excluded from the roster (no Player row created for them),
    // which keeps the whole ingestion + pricing surface to a few hundred
    // players instead of the full ~1,700-player league.
    private static final Set<String> SKILL_POSITIONS = Set.of("QB", "RB", "WR", "TE");

    private static final int FLUSH_EVERY_N_RECORDS = 25;

    // NFL's Week 1 kickoff is different every year (first Thursday after
    // Labor Day) -- this needs updating each season. Only used by the
    // scheduled job to guess the current week; the manual /admin/ingest-nfl
    // endpoint takes week/season explicitly and doesn't depend on this.
    private static final LocalDate SEASON_1_START = LocalDate.of(2026, 9, 10);

    private final NflClient nflClient;
    private final PlayerRepository playerRepository;
    private final NflPricingService nflPricingService;
    private final PriceHistoryRepository priceHistoryRepository;
    private final RawGameStatRepository rawGameStatRepository;
    private final EntityManager entityManager;

    public NflIngestionService(
            NflClient nflClient,
            PlayerRepository playerRepository,
            NflPricingService nflPricingService,
            PriceHistoryRepository priceHistoryRepository,
            RawGameStatRepository rawGameStatRepository,
            EntityManager entityManager
    ) {
        this.nflClient = nflClient;
        this.playerRepository = playerRepository;
        this.nflPricingService = nflPricingService;
        this.priceHistoryRepository = priceHistoryRepository;
        this.rawGameStatRepository = rawGameStatRepository;
        this.entityManager = entityManager;
    }

    private String toJson(Map<String, Object> map) {
        if (map == null || map.isEmpty()) return "{}";
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (!first) json.append(",");
            first = false;
            json.append("\"").append(escapeJson(entry.getKey())).append("\":");
            Object value = entry.getValue();
            if (value == null) {
                json.append("null");
            } else if (value instanceof Number || value instanceof Boolean) {
                json.append(value.toString());
            } else {
                json.append("\"").append(escapeJson(value.toString())).append("\"");
            }
        }
        json.append("}");
        return json.toString();
    }

    private String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // gameDate here is the calendar date results are being recorded under
    // (same convention as MLB's daily job -- "the day we found out about
    // this week's results"), NOT the game week itself. Keeps price_history/
    // RawGameStat schema-compatible with the MLB side without any changes.
    public int ingestNflFantasyData(int week, int season, String seasonType, String gameDate) {
        List<String> gameIds = nflClient.getGameIdsForWeek(week, season, seasonType);
        Map<String, Tank01Dtos.NflPlayerInfo> playerInfoMap = nflClient.getPlayerInfoMap();
        Map<String, Tank01Dtos.PlayerProjection> projections = nflClient.getProjections(week, season);
        Map<String, Double> adpMap = nflClient.getAdpMap();

        int updatedCount = 0;
        Set<String> processedExternalIds = new HashSet<>();

        for (String gameId : gameIds) {
            List<Tank01Dtos.NflPlayerStat> stats = nflClient.getBoxScore(gameId);

            for (Tank01Dtos.NflPlayerStat stat : stats) {
                if (stat.playerID == null) continue;

                Tank01Dtos.NflPlayerInfo info = playerInfoMap.get(stat.playerID);
                String position = info != null ? info.pos : stat.pos;
                if (!SKILL_POSITIONS.contains(position)) {
                    continue; // K/DEF/OL/etc. -- not priced, no Player row created
                }

                processedExternalIds.add(stat.playerID);

                Player player = playerRepository.findByExternalId(stat.playerID)
                        .orElseGet(Player::new);

                if (player.getId() != null && priceHistoryRepository.existsByPlayerAndGameDate(player, gameDate)) {
                    continue;
                }

                player.setExternalId(stat.playerID);
                player.setName(info != null && info.longName != null ? info.longName : stat.longName);
                player.setTeam(info != null && info.team != null ? info.team : stat.team);
                player.setSport("NFL");
                player.setPosition(position);
                if (info != null && info.espnID != null) {
                    player.setEspnId(info.espnID);
                }
                player.setFantasyPoints(
                        stat.fantasyPoints != null ? Double.parseDouble(stat.fantasyPoints) : 0.0
                );

                Tank01Dtos.PlayerProjection projection = projections.get(stat.playerID);
                Double weeklyProjection = (projection != null && projection.fantasyPoints != null)
                        ? Double.parseDouble(projection.fantasyPoints) : null;

                Double adpBonus = null;
                Double adp = adpMap.get(stat.playerID);
                if (adp != null) {
                    adpBonus = Math.max(0.0, 100.0 * (1 - adp / 300.0));
                }

                player.setWeeklyProjection(weeklyProjection);
                player.setAdpBonus(adpBonus);
                // NFL's own gamesPlayed isn't tracked by a separate season-stats
                // refresh yet (no MlbSeasonStatsService equivalent) -- increment
                // it directly here, once per real game ingested for this player.
                int gamesPlayed = player.getGamesPlayed() != null ? player.getGamesPlayed() : 0;
                player.setGamesPlayed(gamesPlayed + 1);

                String rawStatsJson = toJson(stat.getRawStats());

                playerRepository.save(player);
                archiveRawGameStat(player, gameDate, player.getFantasyPoints(), rawStatsJson);

                nflPricingService.updatePrice(player, gameDate, weeklyProjection, adpBonus, rawStatsJson);
                playerRepository.save(player);
                updatedCount++;

                if (updatedCount % FLUSH_EVERY_N_RECORDS == 0) {
                    entityManager.clear();
                }
            }
        }

        return updatedCount;
    }

    // Gives every skill-position player a real starting price BEFORE any
    // games have been played, using just their roster info + preseason
    // projection + ADP -- no box scores needed. This works because
    // NflPricingService already leans entirely on projection/ADP when
    // gamesPlayed is 0 (recent/season weight naturally redistributes to
    // them, same confidence mechanism used once real games start rolling
    // in) -- this method just needs to call it once per player. Safe to
    // re-run any time before kickoff (e.g. after Tank01 updates its
    // projections) -- gamesPlayed stays untouched, so it never fakes real
    // game history, and an already-seeded player just gets a gentle nudge
    // toward the refreshed target instead of a jump.
    public int seedNflPricesFromProjections(int season) {
        Map<String, Tank01Dtos.NflPlayerInfo> playerInfoMap = nflClient.getPlayerInfoMap();
        // Week 1 -- there's no real game yet to project "recent"/"season"
        // form from, so the opening week's projection is the closest thing
        // to "expected production entering the season" that exists.
        Map<String, Tank01Dtos.PlayerProjection> projections = nflClient.getProjections(1, season);
        Map<String, Double> adpMap = nflClient.getAdpMap();

        String seedDate = LocalDate.now(ET_ZONE).format(GAME_DATE_FORMAT);
        int seededCount = 0;

        for (Tank01Dtos.NflPlayerInfo info : playerInfoMap.values()) {
            if (info.playerID == null || !SKILL_POSITIONS.contains(info.pos)) continue;

            Player player = playerRepository.findByExternalId(info.playerID)
                    .orElseGet(Player::new);

            player.setExternalId(info.playerID);
            player.setName(info.longName);
            player.setTeam(info.team);
            player.setSport("NFL");
            player.setPosition(info.pos);
            if (info.espnID != null) {
                player.setEspnId(info.espnID);
            }

            Tank01Dtos.PlayerProjection projection = projections.get(info.playerID);
            Double weeklyProjection = (projection != null && projection.fantasyPoints != null)
                    ? Double.parseDouble(projection.fantasyPoints) : null;

            Double adpBonus = null;
            Double adp = adpMap.get(info.playerID);
            if (adp != null) {
                adpBonus = Math.max(0.0, 100.0 * (1 - adp / 300.0));
            }

            player.setWeeklyProjection(weeklyProjection);
            player.setAdpBonus(adpBonus);

            playerRepository.save(player);
            // No fantasyPoints, no RawGameStat archive, gamesPlayed untouched
            // -- this is explicitly NOT a real game, just an opening price.
            nflPricingService.updatePrice(player, seedDate, weeklyProjection, adpBonus, null);
            playerRepository.save(player);
            seededCount++;

            if (seededCount % FLUSH_EVERY_N_RECORDS == 0) {
                entityManager.clear();
            }
        }

        return seededCount;
    }

    private void archiveRawGameStat(Player player, String gameDate, Double fantasyPoints, String rawStatsJson) {
        if (rawGameStatRepository.existsByPlayerAndGameDate(player, gameDate)) {
            return;
        }
        RawGameStat archive = new RawGameStat();
        archive.setPlayer(player);
        archive.setGameDate(gameDate);
        archive.setFantasyPoints(fantasyPoints);
        archive.setRawStatsJson(rawStatsJson);
        archive.setFetchedAt(java.time.Instant.now());
        rawGameStatRepository.save(archive);
    }

    // Best-effort current-week guess for the scheduled job -- see
    // SEASON_1_START's comment. Clamped to the 1-18 regular-season range;
    // returns -1 outside that window (offseason), which the scheduled job
    // treats as "nothing to do today" rather than guessing week 1 or 18.
    private int guessCurrentWeek() {
        LocalDate today = LocalDate.now(ET_ZONE);
        long daysSinceStart = java.time.temporal.ChronoUnit.DAYS.between(SEASON_1_START, today);
        if (daysSinceStart < 0) return -1;
        int week = (int) (daysSinceStart / 7) + 1;
        return (week >= 1 && week <= 18) ? week : -1;
    }

    // Runs daily, same cadence as MLB -- most days this finds nothing new
    // (NFL games cluster on Sunday, with a few Thursday/Monday games), but
    // checking daily means a Thu/Mon game's results show up the next
    // morning instead of waiting for a fixed weekly slot.
    @Scheduled(cron = "0 20 6 * * *", zone = "America/New_York")
    public void scheduledNflIngestion() {
        int week = guessCurrentWeek();
        if (week == -1) {
            System.out.println("Scheduled NFL ingestion skipped: outside the regular season window.");
            return;
        }
        String gameDate = LocalDate.now(ET_ZONE).format(GAME_DATE_FORMAT);
        int season = LocalDate.now(ET_ZONE).getYear();
        int count = ingestNflFantasyData(week, season, "reg", gameDate);
        System.out.println("Scheduled NFL ingestion complete: " + count + " records updated for week " + week);
    }
}
