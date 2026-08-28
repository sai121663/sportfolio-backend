package com.example.demo.trading;

import com.example.demo.player.Player;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface WatchlistItemRepository extends JpaRepository<WatchlistItem, Long> {
    List<WatchlistItem> findByUser(User user);
    Optional<WatchlistItem> findByUserAndPlayer(User user, Player player);
    void deleteByUserAndPlayer(User user, Player player);
}