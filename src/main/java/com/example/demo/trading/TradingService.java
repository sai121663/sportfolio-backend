package com.example.demo.trading;

import com.example.demo.player.Player;
import com.example.demo.player.PlayerRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class TradingService {

    private final UserRepository userRepository;
    private final PlayerRepository playerRepository;
    private final HoldingRepository holdingRepository;
    private final TradeRepository tradeRepository;

    public TradingService(UserRepository userRepository, PlayerRepository playerRepository,
                           HoldingRepository holdingRepository, TradeRepository tradeRepository) {
        this.userRepository = userRepository;
        this.playerRepository = playerRepository;
        this.holdingRepository = holdingRepository;
        this.tradeRepository = tradeRepository;
    }

    public Trade buy(Long userId, Long playerId, int quantity) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        Player player = playerRepository.findById(playerId)
                .orElseThrow(() -> new IllegalArgumentException("Player not found: " + playerId));

        double cost = player.getPrice() * quantity;
        if (user.getCashBalance() < cost) {
            throw new IllegalStateException("Insufficient funds: need " + cost + ", have " + user.getCashBalance());
        }

        user.setCashBalance(user.getCashBalance() - cost);
        userRepository.save(user);

        Holding holding = holdingRepository.findByUserAndPlayer(user, player)
                .orElseGet(() -> {
                    Holding h = new Holding();
                    h.setUser(user);
                    h.setPlayer(player);
                    h.setQuantity(0);
                    h.setAvgCostBasis(0.0);
                    return h;
                });

        // Weighted-average cost basis: fold the new lot's price into the running average.
        int existingQty = holding.getQuantity();
        double existingCostBasis = existingQty > 0 && holding.getAvgCostBasis() != null
                ? holding.getAvgCostBasis()
                : 0.0;
        int newQty = existingQty + quantity;
        double newAvgCostBasis = ((existingCostBasis * existingQty) + (player.getPrice() * quantity)) / newQty;

        holding.setQuantity(newQty);
        holding.setAvgCostBasis(newAvgCostBasis);
        holdingRepository.save(holding);

        Trade trade = new Trade();
        trade.setUser(user);
        trade.setPlayer(player);
        trade.setType("BUY");
        trade.setQuantity(quantity);
        trade.setPricePerUnit(player.getPrice());
        trade.setTimestamp(Instant.now());
        return tradeRepository.save(trade);
    }

    public Trade sell(Long userId, Long playerId, int quantity) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        Player player = playerRepository.findById(playerId)
                .orElseThrow(() -> new IllegalArgumentException("Player not found: " + playerId));

        Holding holding = holdingRepository.findByUserAndPlayer(user, player)
                .orElseThrow(() -> new IllegalStateException("No holding found for this player"));

        if (holding.getQuantity() < quantity) {
            throw new IllegalStateException("Not enough shares to sell: have " + holding.getQuantity() + ", requested " + quantity);
        }

        double proceeds = player.getPrice() * quantity;
        user.setCashBalance(user.getCashBalance() + proceeds);
        userRepository.save(user);

        // Average cost basis per share is unchanged by a sell -- only the quantity shrinks.
        holding.setQuantity(holding.getQuantity() - quantity);
        if (holding.getQuantity() == 0) {
            holdingRepository.delete(holding);
        } else {
            holdingRepository.save(holding);
        }

        Trade trade = new Trade();
        trade.setUser(user);
        trade.setPlayer(player);
        trade.setType("SELL");
        trade.setQuantity(quantity);
        trade.setPricePerUnit(player.getPrice());
        trade.setTimestamp(Instant.now());
        return tradeRepository.save(trade);
    }
}
