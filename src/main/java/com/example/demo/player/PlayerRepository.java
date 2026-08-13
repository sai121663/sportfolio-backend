package com.example.demo.player;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.List;

public interface PlayerRepository extends JpaRepository<Player, Long> {
    Optional<Player> findByExternalId(String externalId);
    List<Player> findBySport(String sport);
}