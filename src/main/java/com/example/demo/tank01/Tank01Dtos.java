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
}