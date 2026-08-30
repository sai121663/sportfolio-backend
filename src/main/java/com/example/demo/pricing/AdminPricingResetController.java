package com.example.demo.pricing;

import com.example.demo.player.Player;
import com.example.demo.player.PlayerRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// TEMPORARY -- wipes pricing data back to a clean slate so it can be
// recomputed correctly. Doesn't touch price directly (that would risk a null
// price crashing /players before the next ingestion runs) -- instead it just
// clears currentSeason, which makes PricingService treat every player's next
// game as the start of a new season, re-running its own existing reset logic
// (fresh starting price, gamesPlayed=0, avgFantasyPoints=null) safely.
// Delete this file once you're done using it; it has no auth and shouldn't
// ship anywhere real.
@RestController
public class AdminPricingResetController {

    private final PlayerRepository playerRepository;
    private final PriceHistoryRepository priceHistoryRepository;

    public AdminPricingResetController(PlayerRepository playerRepository, PriceHistoryRepository priceHistoryRepository) {
        this.playerRepository = playerRepository;
        this.priceHistoryRepository = priceHistoryRepository;
    }

    @GetMapping("/admin/reset-pricing")
    public String resetPricing() {
        List<Player> players = playerRepository.findAll();
        for (Player player : players) {
            player.setCurrentSeason(null);
        }
        playerRepository.saveAll(players);

        priceHistoryRepository.deleteAllInBulk();

        return "Cleared currentSeason for " + players.size() + " players and wiped price_history. "
                + "Next ingestion for each player will give them a fresh starting price.";
    }

    // One-time cleanup for duplicate price_history rows created before
    // savePriceHistory was fixed to reuse an existing row instead of always
    // inserting a new one. Safe to run any time -- it only removes true
    // duplicates (same player, same date), keeping the most recently
    // recorded row for each.
    @GetMapping("/admin/dedupe-price-history")
    public String dedupePriceHistory() {
        int removed = priceHistoryRepository.deduplicatePriceHistory();
        return "Removed " + removed + " duplicate price_history row(s).";
    }
}