// RawGameStatRepository.java
package com.example.demo.tank01;

import com.example.demo.player.Player;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RawGameStatRepository extends JpaRepository<RawGameStat, Long> {
    boolean existsByPlayerAndGameDate(Player player, String gameDate);
    List<RawGameStat> findByGameDateOrderByPlayerAsc(String gameDate);
    List<RawGameStat> findByPlayerOrderByGameDateAsc(Player player);
}