package com.example.demo.pricing;

import com.example.demo.player.Player;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface PriceHistoryRepository extends JpaRepository<PriceHistory, Long> {
    List<PriceHistory> findByPlayerOrderByGameDateAsc(Player player);
    List<PriceHistory> findByPlayerIdOrderByRecordedAtAsc(Long playerId);
    List<PriceHistory> findByPlayerIn(List<Player> players);
    boolean existsByPlayerAndGameDate(Player player, String gameDate);
    // Returns a List rather than an Optional/single result because rows from
    // before the dedupe fix below may still have duplicates for a given
    // player+date -- a single-result query would throw on those until
    // deduplicatePriceHistory() has been run to clean them up.
    List<PriceHistory> findByPlayerAndGameDate(Player player, String gameDate);
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

    // Replaces the default deleteAll(), which loads every row into memory as
    // a managed entity before deleting them one at a time -- fine for a
    // small table, but a real crash risk once price_history has built up
    // tens of thousands of rows (which is exactly when you actually need
    // reset-pricing to work, e.g. right after a big backfill). This runs a
    // single SQL DELETE statement instead, with no entities loaded at all.
    @Modifying
    @Transactional
    @Query("DELETE FROM PriceHistory")
    void deleteAllInBulk();

    // One-time cleanup for duplicate rows created before savePriceHistory
    // was fixed to reuse an existing row instead of always inserting a new
    // one (e.g. when a live ingestion run and a later recompute-range both
    // priced the same player on the same date). For every (player, date)
    // pair with more than one row, keeps only the most recently recorded one
    // and deletes the rest -- done entirely in the database via a single
    // native query, so it never loads the (potentially huge) table into
    // Java memory the way a findAll()-based cleanup would.
    @Modifying
    @Transactional
    @Query(value =
            "DELETE FROM price_history WHERE id IN (" +
            "  SELECT id FROM (" +
            "    SELECT id, ROW_NUMBER() OVER (PARTITION BY player_id, game_date ORDER BY recorded_at DESC) AS rn " +
            "    FROM price_history" +
            "  ) ranked WHERE ranked.rn > 1" +
            ")",
            nativeQuery = true)
    int deduplicatePriceHistory();

    interface PlayerPriceRange {
        Long getPlayerId();
        Double getMaxPrice();
        Double getMinPrice();
    }
}