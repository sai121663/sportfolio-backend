package com.example.demo.tank01;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

// Talks to Tank01's dedicated NFL product on RapidAPI -- same company/API
// family as MlbClient, just a separate host and "NFL"-prefixed endpoints,
// same as how Tank01 splits MLB and NBA into their own hosts too.
//
// NOTE: unlike MlbClient/Tank01Client (which were built and tested against
// real responses), these exact endpoint paths/params are inferred from
// Tank01's consistent naming convention across their MLB/NBA/NFL products
// (getMLBGamesForDate -> getNFLGamesForWeek, getMLBBoxScore ->
// getNFLBoxScore, etc.) rather than confirmed against live NFL docs. Every
// call here already fails soft (empty list/map, not an exception) via the
// same try/catch pattern used elsewhere, so a wrong endpoint name just means
// "found nothing" the first time this runs -- worth a quick real test call
// before trusting it for anything.
@Component
public class NflClient {

    @Value("${rapidapi.key}")
    private String apiKey;

    private static final String HOST = "tank01-nfl-live-in-game-real-time-statistics.p.rapidapi.com";
    private final RestTemplate restTemplate = new RestTemplate();

    // Same reasoning as MlbClient's cache -- the full player list and
    // season projections barely change day to day, so there's no reason to
    // re-fetch them on every call within the same ingestion run.
    private static final long CACHE_TTL_HOURS = 24;

    private Map<String, Tank01Dtos.NflPlayerInfo> cachedPlayerInfo;
    private Instant playerInfoCachedAt;

    private Map<String, Tank01Dtos.PlayerProjection> cachedProjections;
    private Instant projectionsCachedAt;

    private Map<String, Double> cachedAdpMap;
    private Instant adpMapCachedAt;

    private boolean isExpired(Instant cachedAt) {
        return cachedAt == null || ChronoUnit.HOURS.between(cachedAt, Instant.now()) >= CACHE_TTL_HOURS;
    }

    private HttpHeaders buildHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("x-rapidapi-key", apiKey);
        headers.set("x-rapidapi-host", HOST);
        return headers;
    }

    // NFL games are weekly, not daily -- week/season/seasonType together
    // identify a slate of games the same way a single gameDate does for MLB.
    public List<String> getGameIdsForWeek(int week, int season, String seasonType) {
        String url = UriComponentsBuilder.fromUriString("https://" + HOST + "/getNFLGamesForWeek")
                .queryParam("week", week)
                .queryParam("season", season)
                .queryParam("seasonType", seasonType)
                .build()
                .encode()
                .toUriString();

        try {
            HttpEntity<Void> entity = new HttpEntity<>(buildHeaders());
            Tank01Dtos.ScheduleResponse response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, Tank01Dtos.ScheduleResponse.class
            ).getBody();

            if (response == null || response.body == null) return List.of();
            return response.body.stream().map(g -> g.gameID).collect(Collectors.toList());
        } catch (Exception e) {
            return List.of();
        }
    }

    public List<Tank01Dtos.NflPlayerStat> getBoxScore(String gameId) {
        String url = UriComponentsBuilder.fromUriString("https://" + HOST + "/getNFLBoxScore")
                .queryParam("gameID", gameId)
                .queryParam("fantasyPoints", "true")
                .build()
                .encode()
                .toUriString();

        try {
            HttpEntity<Void> entity = new HttpEntity<>(buildHeaders());
            Tank01Dtos.NflBoxScoreResponse response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, Tank01Dtos.NflBoxScoreResponse.class
            ).getBody();

            if (response == null || response.body == null || response.body.playerStats == null) {
                return List.of();
            }
            return response.body.playerStats;
        } catch (Exception e) {
            return List.of();
        }
    }

    // Keyed by Tank01's playerID -- includes name/team/position/espnID so
    // NflIngestionService doesn't need a second lookup just to filter to
    // skill positions or build a headshot URL.
    public Map<String, Tank01Dtos.NflPlayerInfo> getPlayerInfoMap() {
        if (!isExpired(playerInfoCachedAt)) {
            return cachedPlayerInfo;
        }

        String url = "https://" + HOST + "/getNFLPlayerList";
        Map<String, Tank01Dtos.NflPlayerInfo> playerInfo = new HashMap<>();
        try {
            HttpEntity<Void> entity = new HttpEntity<>(buildHeaders());
            Tank01Dtos.NflPlayerListResponse response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, Tank01Dtos.NflPlayerListResponse.class
            ).getBody();

            if (response != null && response.body != null) {
                for (Tank01Dtos.NflPlayerInfo p : response.body) {
                    if (p.playerID != null) {
                        playerInfo.put(p.playerID, p);
                    }
                }
            }
        } catch (Exception e) {
            // Fall through with whatever we managed to collect (likely empty).
        }

        cachedPlayerInfo = playerInfo;
        playerInfoCachedAt = Instant.now();
        return playerInfo;
    }

    public Map<String, Tank01Dtos.PlayerProjection> getProjections() {
        if (!isExpired(projectionsCachedAt)) {
            return cachedProjections;
        }

        String url = UriComponentsBuilder.fromUriString("https://" + HOST + "/getNFLProjections")
                .queryParam("fantasyPoints", "true")
                .build()
                .encode()
                .toUriString();

        Map<String, Tank01Dtos.PlayerProjection> projections = Map.of();
        try {
            HttpEntity<Void> entity = new HttpEntity<>(buildHeaders());
            Tank01Dtos.ProjectionsResponse response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, Tank01Dtos.ProjectionsResponse.class
            ).getBody();

            if (response != null && response.body != null && response.body.playerProjections != null) {
                projections = response.body.playerProjections;
            }
        } catch (Exception e) {
            // Keep projections empty -- computeCompositeRatio treats a
            // missing projection as neutral (ratio of 1.0), not a crash.
        }

        cachedProjections = projections;
        projectionsCachedAt = Instant.now();
        return projections;
    }

    public Map<String, Double> getAdpMap() {
        if (!isExpired(adpMapCachedAt)) {
            return cachedAdpMap;
        }

        String url = "https://" + HOST + "/getNFLADP";
        Map<String, Double> adpMap = new HashMap<>();
        try {
            HttpEntity<Void> entity = new HttpEntity<>(buildHeaders());
            Tank01Dtos.AdpResponse response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, Tank01Dtos.AdpResponse.class
            ).getBody();

            if (response != null && response.body != null && response.body.adpList != null) {
                for (Tank01Dtos.AdpEntry entry : response.body.adpList) {
                    if (entry.playerID != null && !entry.playerID.isEmpty() && entry.overallADP != null) {
                        try {
                            adpMap.put(entry.playerID, Double.parseDouble(entry.overallADP));
                        } catch (NumberFormatException ignored) {
                            // skip malformed entries
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Keep adpMap empty -- treated as neutral (ratio of 0.0), same
            // as any player Tank01 just doesn't have ADP data for.
        }

        cachedAdpMap = adpMap;
        adpMapCachedAt = Instant.now();
        return adpMap;
    }
}
