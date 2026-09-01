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
// All four endpoint names/params below are now confirmed against real
// working curl examples (which is also what caught the host name needing a
// trailing "-nfl" the naming-convention guess had missed). What's still
// unconfirmed is the exact response SHAPE for getGameIdsForWeek/getBoxScore/
// getPlayerInfoMap -- the DTOs they parse into (ScheduleResponse,
// NflBoxScoreResponse, NflPlayerListResponse) are still guesses. Every call
// here fails soft (empty list/map, not an exception), so a shape mismatch
// just means "found nothing," not a crash -- worth checking a real response
// against these DTOs once there's live data to test with.
@Component
public class NflClient {

    @Value("${rapidapi.key}")
    private String apiKey;

    private static final String HOST = "tank01-nfl-live-in-game-real-time-statistics-nfl.p.rapidapi.com";
    private final RestTemplate restTemplate = new RestTemplate();

    // Same reasoning as MlbClient's cache -- the full player list and
    // season projections barely change day to day, so there's no reason to
    // re-fetch them on every call within the same ingestion run.
    private static final long CACHE_TTL_HOURS = 24;

    private Map<String, Tank01Dtos.NflPlayerInfo> cachedPlayerInfo;
    private Instant playerInfoCachedAt;

    private Map<String, Tank01Dtos.PlayerProjection> cachedProjections;
    private String cachedProjectionsKey;
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

    // Shared by getProjections and getBoxScore -- both need the SAME
    // scoring ruleset spelled out as query params, since Tank01 has no
    // single "fantasyPoints=true" shortcut for NFL the way MLB/NBA do.
    // Standardized to half-PPR to match getAdpMap's adpType=halfPPR --
    // the confirmed curl examples for projections and box score actually
    // used two different, inconsistent rulesets (full-PPR vs half-PPR,
    // among other small differences), which would have made projected and
    // actual fantasy points not comparable to each other. If you want a
    // different scoring system, change it here once and every caller stays
    // in sync automatically.
    private UriComponentsBuilder addScoringWeights(UriComponentsBuilder builder) {
        return builder
                .queryParam("twoPointConversions", "2")
                .queryParam("passYards", ".04")
                .queryParam("passAttempts", "0")
                .queryParam("passTD", "4")
                .queryParam("passCompletions", "0")
                .queryParam("passInterceptions", "-2")
                .queryParam("pointsPerReception", ".5")
                .queryParam("carries", ".2")
                .queryParam("rushYards", ".1")
                .queryParam("rushTD", "6")
                .queryParam("fumbles", "-2")
                .queryParam("receivingYards", ".1")
                .queryParam("receivingTD", "6")
                .queryParam("targets", "0")
                .queryParam("defTD", "0")
                .queryParam("fgMade", "3")
                .queryParam("fgMissed", "-1")
                .queryParam("xpMade", "1")
                .queryParam("xpMissed", "-1")
                .queryParam("idpTotalTackles", "0")
                .queryParam("idpSoloTackles", "0")
                .queryParam("idpTFL", "0")
                .queryParam("idpQbHits", "0")
                .queryParam("idpInt", "0")
                .queryParam("idpSacks", "0")
                .queryParam("idpPassDeflections", "0")
                .queryParam("idpFumblesRecovered", "0");
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
            System.out.println("NflClient.getGameIdsForWeek failed: " + e);
            return List.of();
        }
    }

    public List<Tank01Dtos.NflPlayerStat> getBoxScore(String gameId) {
        String url = addScoringWeights(
                UriComponentsBuilder.fromUriString("https://" + HOST + "/getNFLBoxScore")
                        .queryParam("gameID", gameId)
                        .queryParam("fantasyPoints", "true")
                        // Play-by-play detail isn't used for anything -- leave it
                        // off to keep the response smaller/faster.
                        .queryParam("playByPlay", "false")
        )
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
            System.out.println("NflClient.getBoxScore failed: " + e);
            return List.of();
        }
    }

    // TEMPORARY debug helper -- returns the raw, unparsed JSON string so a
    // real response can be inspected directly instead of guessing why the
    // typed DTO parse came back empty. Remove once getPlayerInfoMap's
    // parsing is confirmed working against a real response.
    public String getRawPlayerListJson() {
        String url = UriComponentsBuilder.fromUriString("https://" + HOST + "/getNFLPlayerList")
                .queryParam("all", "true")
                .build()
                .encode()
                .toUriString();
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders());
        try {
            return restTemplate.exchange(url, HttpMethod.GET, entity, String.class).getBody();
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            // RestTemplate throws (instead of just returning the body) on any
            // non-2xx response -- that's almost certainly why the earlier
            // "0 players seeded" happened. Surfacing the real status code +
            // Tank01's own error body here instead of letting it bubble up
            // as a generic 500, so the actual cause (bad key, not
            // subscribed to this product, rate limited, etc.) is visible.
            return "HTTP " + e.getStatusCode() + " from Tank01: " + e.getResponseBodyAsString();
        } catch (Exception e) {
            return "Request failed: " + e;
        }
    }

    // Keyed by Tank01's playerID -- includes name/team/position/espnID so
    // NflIngestionService doesn't need a second lookup just to filter to
    // skill positions or build a headshot URL.
    public Map<String, Tank01Dtos.NflPlayerInfo> getPlayerInfoMap() {
        if (!isExpired(playerInfoCachedAt)) {
            return cachedPlayerInfo;
        }

        // all=true -- without it, this only returns a partial/notable-players
        // subset instead of the full league roster.
        String url = UriComponentsBuilder.fromUriString("https://" + HOST + "/getNFLPlayerList")
                .queryParam("all", "true")
                .build()
                .encode()
                .toUriString();
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
            System.out.println("NflClient.getPlayerInfoMap failed: " + e);
        }

        cachedPlayerInfo = playerInfo;
        playerInfoCachedAt = Instant.now();
        return playerInfo;
    }

    // Tank01's NFL projections are WEEKLY (unlike MLB's 7-day-ahead
    // projection), and -- unlike MLB/NBA -- require every scoring weight
    // spelled out explicitly as query params rather than a single
    // "fantasyPoints=true" flag (see addScoringWeights). Cached by
    // week+season so a multi-week backfill doesn't re-fetch the same
    // week's projections over and over, same reasoning as the 24h TTL on
    // the other cached lookups.
    public Map<String, Tank01Dtos.PlayerProjection> getProjections(int week, int season) {
        String cacheKey = week + "-" + season;
        if (!isExpired(projectionsCachedAt) && cacheKey.equals(cachedProjectionsKey)) {
            return cachedProjections;
        }

        String url = addScoringWeights(
                UriComponentsBuilder.fromUriString("https://" + HOST + "/getNFLProjections")
                        .queryParam("week", week)
                        .queryParam("archiveSeason", season)
                        // No itemFormat param -- omitting it gets the default map-
                        // keyed-by-playerID shape, matching ProjectionsResponse's
                        // DTO (same as MLB/NBA's projections endpoints, which use
                        // this same shape without needing itemFormat at all). The
                        // confirmed curl example passed itemFormat=list, but
                        // that's an alternate shape this DTO doesn't parse --
                        // worth switching to if the map format doesn't pan out.
        )
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
            System.out.println("NflClient.getProjections failed: " + e);
        }

        cachedProjections = projections;
        cachedProjectionsKey = cacheKey;
        projectionsCachedAt = Instant.now();
        return projections;
    }

    // Half-PPR ADP, confirmed against the real endpoint.
    public Map<String, Double> getAdpMap() {
        if (!isExpired(adpMapCachedAt)) {
            return cachedAdpMap;
        }

        String url = UriComponentsBuilder.fromUriString("https://" + HOST + "/getNFLADP")
                .queryParam("adpType", "halfPPR")
                .build()
                .encode()
                .toUriString();
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
            System.out.println("NflClient.getAdpMap failed: " + e);
        }

        cachedAdpMap = adpMap;
        adpMapCachedAt = Instant.now();
        return adpMap;
    }
}
