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
    }

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