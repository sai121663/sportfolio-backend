package com.example.demo.tank01;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Tank01Dtos {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ScheduleResponse {
        public List<ScheduleGame> body;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ScheduleGame {
        public String gameID;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BoxScoreResponse {
        public BoxScoreBody body;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BoxScoreBody {
        public Map<String, PlayerStat> playerStats;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PlayerStat {
        public String playerID;
        public String longName;
        public String team;
        public String fantasyPoints;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MlbBoxScoreResponse {
        public MlbBoxScoreBody body;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MlbBoxScoreBody {
        public List<MlbPlayerStat> playerStats;
    }

    // NOTE: no @JsonIgnoreProperties(ignoreUnknown = true) here anymore -- we
    // WANT to catch every unknown field (hits, walks, home runs, innings
    // pitched, etc.) instead of silently dropping them, so we can later figure
    // out Tank01's exact fantasy scoring formula from real examples.
    public static class MlbPlayerStat {
        public String playerID;
        public String team;
        public String fantasyPointsDefault;
        // e.g. "P", "1B", "C", "OF", "DH" -- used to tell pitchers from hitters.
        public String startingPosition;

        // Catches every other field Tank01 sends for this player's box score
        // line (hits, homeRuns, walks, strikeouts, inningsPitched, earnedRuns,
        // etc.) that we haven't explicitly named above -- whatever Tank01 calls
        // them, they land in here automatically.
        private final Map<String, Object> rawStats = new HashMap<>();

        @JsonAnySetter
        public void captureUnknownField(String key, Object value) {
            rawStats.put(key, value);
        }

        public Map<String, Object> getRawStats() {
            return rawStats;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MlbPlayerListResponse {
        public List<MlbPlayerInfo> body;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MlbPlayerInfo {
        public String playerID;
        public String longName;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ProjectionsResponse {
        public ProjectionsBody body;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ProjectionsBody {
        public Map<String, PlayerProjection> playerProjections;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PlayerProjection {
        public String playerID;
        public String longName;
        public String fantasyPoints;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AdpResponse {
        public AdpBody body;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AdpBody {
        public String adpDate;
        public List<AdpEntry> adpList;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AdpEntry {
        public String overallADP;
        public String playerID;
        public String longName;
        public String posADP;
    }

    // --- NFL ---

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class NflBoxScoreResponse {
        public NflBoxScoreBody body;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class NflBoxScoreBody {
        public List<NflPlayerStat> playerStats;
    }

    // Same "capture everything unnamed" approach as MlbPlayerStat -- passing
    // yards/TDs/INTs, rushing yards/TDs, receptions/receiving yards/TDs all
    // land in rawStats automatically, whatever Tank01 calls each one, so we
    // don't have to guess every field name up front. fantasyPoints itself
    // IS named explicitly since that's the one field pricing actually reads.
    public static class NflPlayerStat {
        public String playerID;
        public String longName;
        public String team;
        // e.g. "QB", "RB", "WR", "TE", "K" -- used to filter to skill
        // positions and to pick the right pricing baseline.
        public String pos;
        public String fantasyPoints;

        private final Map<String, Object> rawStats = new HashMap<>();

        @JsonAnySetter
        public void captureUnknownField(String key, Object value) {
            rawStats.put(key, value);
        }

        public Map<String, Object> getRawStats() {
            return rawStats;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class NflPlayerListResponse {
        public NflPlayerListBody body;
    }

    // Confirmed against a real response -- "body" is an object wrapping the
    // list under "players", not the list itself (unlike most other Tank01
    // responses in this file, where "body" IS the array/map directly).
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class NflPlayerListBody {
        public List<NflPlayerInfo> players;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class NflPlayerInfo {
        public String playerID;
        public String longName;
        public String team;
        public String pos;
        // Tank01's own ID isn't the same as ESPN's -- this is what lets us
        // build a real headshot URL (espncdn.com/i/headshots/nfl/players/full/{espnID}.png),
        // same pattern already used for the placeholder NFL roster.
        public String espnID;
    }

    // Confirmed against a real getNFLTeamRoster response -- same wrapping
    // shape as the global player list (body is an object, not the array
    // itself), but here the array lives under "roster" instead of
    // "players". Each roster entry has every field NflPlayerInfo needs
    // (playerID, longName, team, pos, espnID) plus dozens more we don't
    // use (injury, draftInfo, stats, etc.) -- ignoreUnknown drops those
    // automatically.
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class NflTeamRosterResponse {
        public NflTeamRosterBody body;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class NflTeamRosterBody {
        public String team;
        public List<NflPlayerInfo> roster;
    }
}