package com.example.demo.tank01;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

// TEMPORARY -- looks up the raw projection + ADP data Tank01 returns for any
// player matching the given name, so you can see exactly what numbers feed
// into their starting price. Delete once you're done debugging.
@RestController
public class AdminPlayerDebugController {

    private final MlbClient mlbClient;

    public AdminPlayerDebugController(MlbClient mlbClient) {
        this.mlbClient = mlbClient;
    }

    @GetMapping("/admin/debug-player")
    public Map<String, Object> debugPlayer(@RequestParam String name) {
        Map<String, String> nameMap = mlbClient.getPlayerNameMap();
        Map<String, Tank01Dtos.PlayerProjection> projections = mlbClient.getProjections();
        Map<String, Double> adpMap = mlbClient.getAdpMap();

        Map<String, Object> results = new HashMap<>();

        for (Map.Entry<String, String> entry : nameMap.entrySet()) {
            if (entry.getValue().toLowerCase().contains(name.toLowerCase())) {
                String playerId = entry.getKey();
                Tank01Dtos.PlayerProjection projection = projections.get(playerId);
                Double adp = adpMap.get(playerId);

                Map<String, Object> playerData = new HashMap<>();
                playerData.put("playerId", playerId);
                playerData.put("projectedFantasyPoints", projection != null ? projection.fantasyPoints : null);
                playerData.put("adp", adp);

                results.put(entry.getValue(), playerData);
            }
        }

        return results;
    }
}