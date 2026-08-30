package com.example.demo.trading;

import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class LeaderboardService {

    private final UserRepository userRepository;
    private final HoldingRepository holdingRepository;

    public LeaderboardService(UserRepository userRepository, HoldingRepository holdingRepository) {
        this.userRepository = userRepository;
        this.holdingRepository = holdingRepository;
    }

    // Ranks every user by total account value (cash + current holdings
    // value) -- the same number each user sees on their own Portfolio tab.
    // Deliberately avoids querying holdings per-user (which would be one
    // query per user, the same N+1 pattern that caused real problems
    // elsewhere in this app): holdings and their player's current price come
    // back in a single findAll() call (Hibernate joins the eager @ManyToOne
    // user/player associations into that one query), then everything is
    // grouped and summed in memory.
    public List<LeaderboardEntry> getLeaderboard() {
        List<User> users = userRepository.findAll();
        List<Holding> allHoldings = holdingRepository.findAll();

        Map<Long, Double> holdingsValueByUserId = allHoldings.stream()
                .filter(h -> h.getUser() != null && h.getPlayer() != null
                        && h.getQuantity() != null && h.getPlayer().getPrice() != null)
                .collect(Collectors.groupingBy(
                        h -> h.getUser().getId(),
                        Collectors.summingDouble(h -> h.getQuantity() * h.getPlayer().getPrice())
                ));

        List<LeaderboardEntry> entries = users.stream()
                .map(u -> {
                    double holdingsValue = holdingsValueByUserId.getOrDefault(u.getId(), 0.0);
                    double cashBalance = u.getCashBalance() != null ? u.getCashBalance() : 0.0;

                    LeaderboardEntry entry = new LeaderboardEntry();
                    entry.setUserId(u.getId());
                    entry.setUsername(u.getUsername());
                    entry.setCashBalance(cashBalance);
                    entry.setTotalPortfolioValue(holdingsValue);
                    entry.setTotalAccountValue(cashBalance + holdingsValue);
                    return entry;
                })
                .sorted(Comparator.comparingDouble(LeaderboardEntry::getTotalAccountValue).reversed())
                .collect(Collectors.toList());

        for (int i = 0; i < entries.size(); i++) {
            entries.get(i).setRank(i + 1);
        }

        return entries;
    }
}
