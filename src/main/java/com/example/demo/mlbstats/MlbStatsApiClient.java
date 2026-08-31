package com.example.demo.mlbstats;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

// Talks to MLB's own free, public Stats API (statsapi.mlb.com) -- no API key needed.
// Used for a player's current position and season-level hitting/pitching totals
// (OPS, HR, RBI, ERA, Wins, Strikeouts), independent of whether they played today.
@Component
public class MlbStatsApiClient {

    private static final String BASE_URL = "https://statsapi.mlb.com/api/v1";
    private final RestTemplate restTemplate = new RestTemplate();

    public String getPrimaryPosition(String personId) {
        String url = BASE_URL + "/people/" + personId;
        try {
            MlbStatsApiDtos.PeopleResponse response =
                    restTemplate.getForObject(url, MlbStatsApiDtos.PeopleResponse.class);

            if (response == null || response.people == null || response.people.isEmpty()) {
                return null;
            }
            MlbStatsApiDtos.Person person = response.people.get(0);
            return person.primaryPosition != null ? person.primaryPosition.abbreviation : null;
        } catch (Exception e) {
            return null;
        }
    }

    public MlbStatsApiDtos.StatBlock getHittingSeasonStats(String personId, int season) {
        return getSeasonStats(personId, "hitting", season);
    }

    public MlbStatsApiDtos.StatBlock getPitchingSeasonStats(String personId, int season) {
        return getSeasonStats(personId, "pitching", season);
    }

    private MlbStatsApiDtos.StatBlock getSeasonStats(String personId, String group, int season) {
        String url = UriComponentsBuilder.fromUriString(BASE_URL + "/people/" + personId + "/stats")
                .queryParam("stats", "season")
                .queryParam("group", group)
                .queryParam("season", season)
                .build()
                .toUriString();

        try {
            MlbStatsApiDtos.StatsResponse response =
                    restTemplate.getForObject(url, MlbStatsApiDtos.StatsResponse.class);

            if (response == null || response.stats == null || response.stats.isEmpty()) {
                return null;
            }
            MlbStatsApiDtos.StatGroup firstGroup = response.stats.get(0);
            if (firstGroup.splits == null || firstGroup.splits.isEmpty()) {
                return null;
            }

            MlbStatsApiDtos.Split split = firstGroup.splits.get(0);
            MlbStatsApiDtos.StatBlock stat = split.stat;

            // "team" lives alongside "stat" in the real response, not nested inside
            // it -- copy it over onto the StatBlock so callers can keep reading
            // stat.team like before, without needing to know about Split at all.
            //
            // For a player traded mid-season, MLB's API adds an extra split at
            // index 0: his combined season totals across every team he played
            // for, which is exactly the aggregate we want for pricing -- but
            // that entry has no team attached (he wasn't on just one), so
            // split.team is null here. The per-team stints follow after it, so
            // fall back to the LAST split's team (his most recent one) purely
            // for display purposes, without touching which stat totals get used.
            MlbStatsApiDtos.Team team = split.team;
            if (team == null && firstGroup.splits.size() > 1) {
                team = firstGroup.splits.get(firstGroup.splits.size() - 1).team;
            }
            if (stat != null) {
                stat.team = team;
            }

            return stat;
        } catch (Exception e) {
            // Player might not have played this season yet, or the ID doesn't
            // resolve -- just skip these stats for this player rather than failing
            // the whole refresh run.
            return null;
        }
    }
}