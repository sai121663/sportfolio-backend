package com.example.demo.trading;

import com.example.demo.player.Player;
import com.example.demo.player.PlayerRepository;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/watchlist")
public class WatchlistController {

    private final UserRepository userRepository;
    private final PlayerRepository playerRepository;
    private final WatchlistItemRepository watchlistItemRepository;

    public WatchlistController(
            UserRepository userRepository,
            PlayerRepository playerRepository,
            WatchlistItemRepository watchlistItemRepository
    ) {
        this.userRepository = userRepository;
        this.playerRepository = playerRepository;
        this.watchlistItemRepository = watchlistItemRepository;
    }

    @GetMapping("/{userId}")
    public List<Long> getWatchlist(@PathVariable Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        return watchlistItemRepository.findByUser(user).stream()
                .map(w -> w.getPlayer().getId())
                .collect(Collectors.toList());
    }

    @PostMapping("/add")
    public void add(@RequestParam Long userId, @RequestParam Long playerId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        Player player = playerRepository.findById(playerId)
                .orElseThrow(() -> new IllegalArgumentException("Player not found: " + playerId));

        if (watchlistItemRepository.findByUserAndPlayer(user, player).isEmpty()) {
            WatchlistItem item = new WatchlistItem();
            item.setUser(user);
            item.setPlayer(player);
            item.setAddedAt(Instant.now());
            watchlistItemRepository.save(item);
        }
    }

    @PostMapping("/remove")
    public void remove(@RequestParam Long userId, @RequestParam Long playerId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        Player player = playerRepository.findById(playerId)
                .orElseThrow(() -> new IllegalArgumentException("Player not found: " + playerId));

        watchlistItemRepository.deleteByUserAndPlayer(user, player);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public org.springframework.http.ResponseEntity<String> handleBadRequest(IllegalArgumentException e) {
        return org.springframework.http.ResponseEntity.badRequest().body(e.getMessage());
    }
}