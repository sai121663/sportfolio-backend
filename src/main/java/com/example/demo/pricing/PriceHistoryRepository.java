package com.example.demo.pricing;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.demo.player.Player;

import java.util.List;

public interface PriceHistoryRepository extends JpaRepository<PriceHistory, Long> {
    List<PriceHistory> findByPlayerOrderByGameDateAsc(Player player);
    List<PriceHistory> findByPlayerIdOrderByRecordedAtAsc(Long playerId);
    List<PriceHistory> findByPlayerIn(List<Player> players);
    boolean existsByPlayerAndGameDate(Player player, String gameDate);
    List<PriceHistory> findByPlayerAndGameDateBetween(Player player, String startDate, String endDate);
}