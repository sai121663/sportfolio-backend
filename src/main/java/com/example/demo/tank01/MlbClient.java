package com.example.demo.tank01;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class MlbClient {

    @Value("${rapidapi.key}")
    private String apiKey;

    private static final String HOST = "tank01-mlb-live-in-game-real-time-statistics.p.rapidapi.com";
    private final RestTemplate restTemplate = new RestTemplate();

    private HttpHeaders buildHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("x-rapidapi-key", apiKey);
        headers.set("x-rapidapi-host", HOST);
        return headers;
    }

    public List<String> getGameIdsForDate(String gameDate) {
        String url = "https://" + HOST + "/getMLBGamesForDate?gameDate=" + gameDate;
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders());
        Tank01Dtos.ScheduleResponse response = restTemplate.exchange(
                url, HttpMethod.GET, entity, Tank01Dtos.ScheduleResponse.class
        ).getBody();

        if (response == null || response.body == null) return List.of();
        return response.body.stream().map(g -> g.gameID).collect(Collectors.toList());
    }

    public List<Tank01Dtos.MlbPlayerStat> getBoxScore(String gameId) {
        String url = UriComponentsBuilder.fromUriString("https://" + HOST + "/getMLBBoxScore")
                .queryParam("gameID", gameId)
                .queryParam("playerStatsFormat", "list")
                .queryParam("fantasyPoints", "true")
                .build()
                .encode()
                .toUriString();

        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders());
        Tank01Dtos.MlbBoxScoreResponse response = restTemplate.exchange(
                url, HttpMethod.GET, entity, Tank01Dtos.MlbBoxScoreResponse.class
        ).getBody();

        if (response == null || response.body == null || response.body.playerStats == null) {
            return List.of();
        }
        return response.body.playerStats;
    }

    public Map<String, String> getPlayerNameMap() {
        String url = "https://" + HOST + "/getMLBPlayerList";
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders());
        Tank01Dtos.MlbPlayerListResponse response = restTemplate.exchange(
                url, HttpMethod.GET, entity, Tank01Dtos.MlbPlayerListResponse.class
        ).getBody();

        Map<String, String> nameMap = new HashMap<>();
        if (response != null && response.body != null) {
            for (Tank01Dtos.MlbPlayerInfo p : response.body) {
                nameMap.put(p.playerID, p.longName);
            }
        }
        return nameMap;
    }

    public Map<String, Tank01Dtos.PlayerProjection> getProjections() {
        String url = UriComponentsBuilder.fromUriString("https://" + HOST + "/getMLBProjections")
                .queryParam("projectionType", "7")
                .queryParam("fantasyPoints", "true")
                .build()
                .encode()
                .toUriString();

        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders());
        Tank01Dtos.ProjectionsResponse response = restTemplate.exchange(
                url, HttpMethod.GET, entity, Tank01Dtos.ProjectionsResponse.class
        ).getBody();

        if (response == null || response.body == null || response.body.playerProjections == null) {
            return Map.of();
        }
        return response.body.playerProjections;
    }

    public Map<String, Double> getAdpMap() {
        String url = "https://" + HOST + "/getMLBADP";
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders());
        Tank01Dtos.AdpResponse response = restTemplate.exchange(
                url, HttpMethod.GET, entity, Tank01Dtos.AdpResponse.class
        ).getBody();

        Map<String, Double> adpMap = new HashMap<>();
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
        return adpMap;
    }
}