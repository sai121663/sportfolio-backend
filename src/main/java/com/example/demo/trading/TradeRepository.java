package com.example.demo.trading;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.demo.player.Player;
import java.util.List;

public interface TradeRepository extends JpaRepository<Trade, Long> {
    List<Trade> findByUserOrderByTimestampDesc(User user);
    List<Trade> findByPlayer(Player player);     // in TradeRepository
    List<Trade> findByPlayerIn(List<Player> players);
}