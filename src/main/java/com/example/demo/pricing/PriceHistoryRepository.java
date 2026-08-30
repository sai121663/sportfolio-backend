package com.example.demo.pricing;

import com.example.demo.player.Player;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PriceHistoryRepository extends JpaRepository<PriceHistory, Long> {
    List<PriceHistory> findByPlayerOrderByGameDateAsc(Player player);
    List<PriceHistory> findByPlayerIdOrderByRecordedAtAsc(Long playerId);
    List<PriceHistory> findByPlayerIn(List<Player> players);
    boolean existsByPlayerAndGameDate(Player player, String gameDate);
    List<PriceHistory> findByPlayerAndGameDateBetween(Player player, String startDate, String endDate);

    List<PriceHistory> findByPlayerInAndGameDateGreaterThanEqual(List<Player> players, String gameDate);

    // The batched replacements for the per-player queries above -- one query
    // covering every player in the list at once, instead of one query per
    // player. Used by MlbIngestionService during multi-day backfills, where
    // looping hundreds of players through a per-player query each day was
    // generating tens of thousands of round trips and crashing the server.
    List<PriceHistory> findByPlayerInAndGameDateBetween(List<Player> players, String startDate, String endDate);
    List<PriceHistory> findByPlayerInAndGameDate(List<Player> players, String gameDate);

    @Query("SELECT ph.player.id AS playerId, MAX(ph.price) AS maxPrice, MIN(ph.price) AS minPrice " +
           "FROM PriceHistory ph WHERE ph.player IN :players GROUP BY ph.player.id")
    List<PlayerPriceRange> findPriceRangeByPlayers(@Param("players") List<Player> players);

    interface PlayerPriceRange {
        Long getPlayerId();
        Double getMaxPrice();
        Double getMinPrice();
    }
}