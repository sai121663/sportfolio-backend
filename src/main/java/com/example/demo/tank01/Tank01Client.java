package com.example.demo.tank01;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class Tank01Client {

    @Value("${rapidapi.key}")
    private String apiKey;

    private static final String HOST = "tank01-fantasy-stats.p.rapidapi.com";
    private final RestTemplate restTemplate = new RestTemplate();

    private HttpHeaders buildHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("x-rapidapi-key", apiKey);
        headers.set("x-rapidapi-host", HOST);
        return headers;
    }

    public List<String> getGameIdsForDate(String gameDate) {
        String url = "https://" + HOST + "/getNBAGamesForDate?gameDate=" + gameDate;
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders());
        Tank01Dtos.ScheduleResponse response = restTemplate.exchange(
                url, HttpMethod.GET, entity, Tank01Dtos.ScheduleResponse.class
        ).getBody();

        if (response == null || response.body == null) return List.of();
        return response.body.stream().map(g -> g.gameID).collect(Collectors.toList());
    }

    public Map<String, Tank01Dtos.PlayerStat> getBoxScore(String gameId) {
        String url = "https://" + HOST + "/getNBABoxScore?gameID=" + gameId + "&fantasyPoints=true";
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders());
        Tank01Dtos.BoxScoreResponse response = restTemplate.exchange(
                url, HttpMethod.GET, entity, Tank01Dtos.BoxScoreResponse.class
        ).getBody();

        if (response == null || response.body == null) return Map.of();
        return response.body.playerStats;
    }

    public Map<String, Tank01Dtos.PlayerProjection> getFantasyProjections() {
        String url = UriComponentsBuilder.fromUriString("https://" + HOST + "/getNBAProjections")
                .queryParam("numOfDays", 7)
                .queryParam("pts", 1)
                .queryParam("reb", 1.25)
                .queryParam("TOV", -1)
                .queryParam("stl", 3)
                .queryParam("blk", 3)
                .queryParam("ast", 1.5)
                .queryParam("mins", 0)
                .build()
                .encode()
                .toUriString();

        HttpHeaders headers = new HttpHeaders();
        headers.set("x-rapidapi-key", apiKey);
        headers.set("x-rapidapi-host", HOST);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        Tank01Dtos.ProjectionsResponse response = restTemplate.exchange(
                url, HttpMethod.GET, entity, Tank01Dtos.ProjectionsResponse.class
        ).getBody();

        if (response == null || response.body == null || response.body.playerProjections == null) {
            return Map.of();
        }
        return response.body.playerProjections;
    }
}
