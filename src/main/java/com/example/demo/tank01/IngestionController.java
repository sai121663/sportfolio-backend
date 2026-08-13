package com.example.demo.tank01;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/ingest")
public class IngestionController {

    private final PlayerIngestionService ingestionService;
    private final MlbIngestionService mlbIngestionService;

    public IngestionController(PlayerIngestionService ingestionService, MlbIngestionService mlbIngestionService) {
        this.ingestionService = ingestionService;
        this.mlbIngestionService = mlbIngestionService;
    }

    @PostMapping("/nba")
    public String ingestNba(@RequestParam String gameDate) {
        int count = ingestionService.ingestNbaFantasyData(gameDate);
        return "Ingested/updated " + count + " player records for " + gameDate;
    }

    @PostMapping("/mlb")
    public String ingestMlb(@RequestParam String gameDate) {
        int count = mlbIngestionService.ingestMlbFantasyData(gameDate);
        return "Ingested/updated " + count + " MLB player records for " + gameDate;
    }
}