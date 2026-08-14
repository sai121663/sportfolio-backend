package com.example.demo.mlbstats;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

// TEMPORARY -- lets you manually trigger the MLB season stats refresh (real
// games-played totals, position, season stats) instead of waiting on its
// schedule. Delete once you're done testing; it has no auth and shouldn't
// ship anywhere real.
@RestController
public class AdminSeasonStatsController {

    private final MlbSeasonStatsService mlbSeasonStatsService;

    public AdminSeasonStatsController(MlbSeasonStatsService mlbSeasonStatsService) {
        this.mlbSeasonStatsService = mlbSeasonStatsService;
    }

    @GetMapping("/admin/refresh-season-stats")
    public String refresh() {
        int count = mlbSeasonStatsService.refreshAllMlbSeasonStats();
        return "Refreshed season stats for " + count + " players";
    }
}