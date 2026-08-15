package com.example.demo.player;

import jakarta.persistence.*;

@Entity
public class Player {

    private String currentSeason; // e.g. "2023-24"

    public String getCurrentSeason() {
        return currentSeason;
    }

    public void setCurrentSeason(String currentSeason) {
        this.currentSeason = currentSeason;
    }

    private Integer gamesPlayed = 0;

    public Integer getGamesPlayed() {
        return gamesPlayed;
    }

    public void setGamesPlayed(Integer gamesPlayed) {
        this.gamesPlayed = gamesPlayed;
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private String team;
    private String sport;
    private String externalId; // Tank01's playerID
    private Double fantasyPoints;
    private Double price;
    private Double avgFantasyPoints;

    // Position from Tank01's box score (e.g. "P", "1B", "OF", "DH"). Used to decide
    // whether a player's card shows hitting stats (OPS/HR/RBI) or pitching stats
    // (ERA/Wins/Strikeouts).
    private String position;

    // MLB's own numeric team ID (e.g. 119 for the Dodgers), pulled from the same
    // season-stats response MlbSeasonStatsService already fetches. Used to build
    // team logo URLs: https://www.mlbstatic.com/team-logos/{teamId}.svg
    private Integer teamId;

    // Season totals, pulled from MLB's official Stats API (statsapi.mlb.com).
    private Integer homeRuns;
    private Integer rbi;
    private Double ops;
    private Double era;
    private Integer wins;
    private Integer losses;
    private Integer strikeouts;

    // Getters and setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getTeam() { return team; }
    public void setTeam(String team) { this.team = team; }

    public String getSport() { return sport; }
    public void setSport(String sport) { this.sport = sport; }

    public String getExternalId() { return externalId; }
    public void setExternalId(String externalId) { this.externalId = externalId; }

    public Double getFantasyPoints() { return fantasyPoints; }
    public void setFantasyPoints(Double fantasyPoints) { this.fantasyPoints = fantasyPoints; }

    public Double getPrice() { return price; }
    public void setPrice(Double price) { this.price = price; }

    public Double getAvgFantasyPoints() { return avgFantasyPoints; }
    public void setAvgFantasyPoints(Double avgFantasyPoints) { this.avgFantasyPoints = avgFantasyPoints; }

    public String getPosition() { return position; }
    public void setPosition(String position) { this.position = position; }

    public Integer getTeamId() { return teamId; }
    public void setTeamId(Integer teamId) { this.teamId = teamId; }

    public Integer getHomeRuns() { return homeRuns; }
    public void setHomeRuns(Integer homeRuns) { this.homeRuns = homeRuns; }

    public Integer getRbi() { return rbi; }
    public void setRbi(Integer rbi) { this.rbi = rbi; }

    public Double getOps() { return ops; }
    public void setOps(Double ops) { this.ops = ops; }

    public Double getEra() { return era; }
    public void setEra(Double era) { this.era = era; }

    public Integer getWins() { return wins; }
    public void setWins(Integer wins) { this.wins = wins; }

    public Integer getLosses() { return losses; }
    public void setLosses(Integer losses) { this.losses = losses; }

    public Integer getStrikeouts() { return strikeouts; }
    public void setStrikeouts(Integer strikeouts) { this.strikeouts = strikeouts; }

    public String getImageUrl() {
        if ("MLB".equals(sport) && externalId != null) {
            return "https://img.mlbstatic.com/mlb-photos/image/upload/w_213,q_60/v1/people/"
                    + externalId + "/headshot/67/current";
        }
        return null;
    }
}