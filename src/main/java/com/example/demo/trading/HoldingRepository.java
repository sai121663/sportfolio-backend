package com.example.demo.trading;

import com.example.demo.player.Player;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface HoldingRepository extends JpaRepository<Holding, Long> {
    Optional<Holding> findByUserAndPlayer(User user, Player player);
    List<Holding> findByUser(User user);
    List<Holding> findByPlayer(Player player);
    List<Holding> findByPlayerIn(List<Player> players);

}
