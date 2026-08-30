package com.example.demo.tank01;

import com.example.demo.player.Player;
import com.example.demo.player.PlayerRepository;
import com.example.demo.pricing.PriceHistory;
import com.example.demo.pricing.PriceHistoryRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

// TEMPORARY -- debug endpoints for inspecting a player's raw data without
// needing direct database access. Delete once you're done debugging; these
// have no auth and shouldn't ship anywhere real.
@RestController
public class AdminPlayerDebugController {

    private static final DateTimeFormatter GAME_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final ZoneId MLB_ZONE = ZoneId.of("America/New_York");

    private final MlbClient mlbClient;
    private final PlayerRepository playerRepository;
    private final PriceHistoryRepository priceHistoryRepository;

    public AdminPlayerDebugController(
            MlbClient mlbClient,
            PlayerRepository playerRepository,
            PriceHistoryRepository priceHistoryRepository
    ) {
        this.mlbClient = mlbClient;
        this.playerRepository = playerRepository;
        this.priceHistoryRepository = priceHistoryRepository;
    }

    // Looks up the raw projection + ADP data Tank01 returns for any player
    // matching the given name, so you can see exactly what numbers feed into
    // their starting price.
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

    // Shows a player's actual price_history rows (day-by-day price and
    // fantasy points) for the last N days -- pass ?name=<any substring of
    // their name>&days=<optional, default 7>. Matches by partial,
    // case-insensitive name, same as the Market page's search bar.
    @GetMapping("/admin/price-history")
    public Object priceHistory(@RequestParam String name, @RequestParam(defaultValue = "7") int days) {
        List<Player> matches = playerRepository.findByNameContainingIgnoreCase(name);
        if (matches.isEmpty()) {
            return Map.of("error", "No player found matching \"" + name + "\"");
        }
        if (matches.size() > 1) {
            List<String> names = matches.stream().map(Player::getName).collect(Collectors.toList());
            return Map.of(
                    "error", "Multiple players matched \"" + name + "\" -- be more specific",
                    "matches", names
            );
        }

        Player player = matches.get(0);
        String cutoff = LocalDate.now(MLB_ZONE).minusDays(days).format(GAME_DATE_FORMAT);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (PriceHistory h : priceHistoryRepository.findByPlayerOrderByGameDateAsc(player)) {
            if (h.getGameDate() == null || h.getGameDate().compareTo(cutoff) < 0) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("gameDate", h.getGameDate());
            row.put("price", h.getPrice());
            row.put("fantasyPoints", h.getFantasyPoints());
            rows.add(row);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("player", player.getName());
        result.put("currentPrice", player.getPrice());
        result.put("history", rows);
        return result;
    }
}
