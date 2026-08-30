package com.example.demo.mlbstats;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

public class MlbStatsApiDtos {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class StatsResponse {
        public List<StatGroup> stats;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class StatGroup {
        public List<Split> splits;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Split {
        public StatBlock stat;
        public Team team; // sits alongside "stat" in the real response, not inside it
    }

    // Shared by both the "hitting" and "pitching" group responses -- unused fields
    // for whichever group you didn't ask for are just left null. Field meaning
    // depends on which group was requested (e.g. "hits" means hits recorded for a
    // hitting call, hits allowed for a pitching call) -- same pattern already used
    // by the pre-existing fields here (ops/era, wins/losses, etc).
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class StatBlock {
        public Integer gamesPlayed;
        public Integer homeRuns;
        public Integer rbi;
        public String ops;          // e.g. ".940"
        public String era;          // e.g. "4.51"
        public Integer wins;
        public Integer losses;
        public Integer strikeOuts;
        public Team team;           // which team they played for during this stat period

        // Added to compute a real, formula-based season average fantasy-points-per-
        // game, using the exact scoring weights reverse-engineered from Tank01's
        // real box scores.
        public Integer hits;           // hits recorded (hitting) / hits allowed (pitching)
        public Integer doubles;
        public Integer triples;
        public Integer baseOnBalls;    // walks drawn (hitting) / walks allowed (pitching)
        public Integer hitByPitch;
        public Integer stolenBases;
        public Integer runs;           // runs scored (hitting only)
        public Integer earnedRuns;     // pitching only
        public Integer saves;          // pitching only
        public Integer holds;          // pitching only
        public Integer outs;           // pitching only -- total outs recorded, cleaner
                                        // than parsing the "11.2"-style innings-pitched string
        public Integer gamesStarted;   // pitching only -- used to tell a starter from a reliever
    }

    // MLB's numeric team ID (e.g. 119 for the Dodgers) -- used to build team logo
    // URLs: https://www.mlbstatic.com/team-logos/{teamId}.svg
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Team {
        public Integer id;
    }

    // For GET /people/{id} -- used to get a player's primary position
    // (e.g. "P", "1B", "OF", "TWP") regardless of what they played today.
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PeopleResponse {
        public List<Person> people;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Person {
        public PrimaryPosition primaryPosition;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PrimaryPosition {
        public String abbreviation;
    }
}