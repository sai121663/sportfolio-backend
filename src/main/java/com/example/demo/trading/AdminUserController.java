package com.example.demo.trading;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// TEMPORARY -- lets you review and clean up stray/duplicate User rows (e.g.
// the leftover test accounts created through the old, now-removed
// POST /users endpoint, which had no dedup check at all). Delete this file
// once you're done cleaning up; it has no auth and shouldn't ship anywhere
// real. Real accounts only ever come through /auth/google now, which is
// correctly deduped by Google's stable "sub" ID.
@RestController
public class AdminUserController {

    private final UserRepository userRepository;
    private final AuthTokenRepository authTokenRepository;
    private final HoldingRepository holdingRepository;
    private final TradeRepository tradeRepository;
    private final WatchlistItemRepository watchlistItemRepository;

    public AdminUserController(
            UserRepository userRepository,
            AuthTokenRepository authTokenRepository,
            HoldingRepository holdingRepository,
            TradeRepository tradeRepository,
            WatchlistItemRepository watchlistItemRepository
    ) {
        this.userRepository = userRepository;
        this.authTokenRepository = authTokenRepository;
        this.holdingRepository = holdingRepository;
        this.tradeRepository = tradeRepository;
        this.watchlistItemRepository = watchlistItemRepository;
    }

    @GetMapping("/admin/users")
    public List<Map<String, Object>> listUsers() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (User u : userRepository.findAll()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", u.getId());
            row.put("username", u.getUsername());
            row.put("email", u.getEmail());
            row.put("googleId", u.getGoogleId());
            row.put("cashBalance", u.getCashBalance());
            row.put("holdingsCount", holdingRepository.findByUser(u).size());
            row.put("tradesCount", tradeRepository.findByUserOrderByTimestampDesc(u).size());
            result.add(row);
        }
        return result;
    }

    // Deletes a user and everything that references them (auth tokens,
    // holdings, trade history, watchlist items) -- those foreign keys would
    // otherwise block deleting the User row outright. Irreversible, so
    // double-check /admin/users first.
    @GetMapping("/admin/delete-user")
    public String deleteUser(@RequestParam Long userId) {
        User user = userRepository.findById(userId)
                .orElse(null);
        if (user == null) {
            return "No user found with id " + userId;
        }
        String username = user.getUsername();

        authTokenRepository.deleteByUser(user);
        watchlistItemRepository.deleteByUser(user);
        holdingRepository.deleteByUser(user);
        tradeRepository.deleteByUser(user);
        userRepository.delete(user);

        return "Deleted user " + userId + " (" + username + ") and all related rows.";
    }
}
