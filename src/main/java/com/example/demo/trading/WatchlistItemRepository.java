package com.example.demo.trading;

import com.example.demo.player.Player;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Optional;

public interface WatchlistItemRepository extends JpaRepository<WatchlistItem, Long> {
    List<WatchlistItem> findByUser(User user);
    Optional<WatchlistItem> findByUserAndPlayer(User user, Player player);

    // Derived delete methods like this one need an active transaction to
    // actually execute -- without @Transactional here, Spring Data JPA
    // throws (no transaction bound to the current thread), which surfaced
    // as a 500 on every /watchlist/remove call.
    @Transactional
    void deleteByUserAndPlayer(User user, Player player);
}