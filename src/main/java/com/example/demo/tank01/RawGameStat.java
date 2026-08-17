// RawGameStat.java
package com.example.demo.tank01;

import com.example.demo.player.Player;
import jakarta.persistence.*;
import java.time.Instant;

// A permanent, reset-proof archive of every raw box score fetched from
// Tank01. Unlike PriceHistory (which /admin/reset-pricing wipes on purpose,
// since it's meant to be recomputed), this table is never touched by a
// pricing reset -- it exists purely so pricing logic can be re-run from
// scratch as many times as you want while testing formula changes, without
// spending Tank01 API quota re-fetching the same real games over and over.
@Entity
public class RawGameStat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    private Player player;

    private String gameDate;
    private Double fantasyPoints;

    @Column(columnDefinition = "TEXT")
    private String rawStatsJson;

    private Instant fetchedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Player getPlayer() { return player; }
    public void setPlayer(Player player) { this.player = player; }

    public String getGameDate() { return gameDate; }
    public void setGameDate(String gameDate) { this.gameDate = gameDate; }

    public Double getFantasyPoints() { return fantasyPoints; }
    public void setFantasyPoints(Double fantasyPoints) { this.fantasyPoints = fantasyPoints; }

    public String getRawStatsJson() { return rawStatsJson; }
    public void setRawStatsJson(String rawStatsJson) { this.rawStatsJson = rawStatsJson; }

    public Instant getFetchedAt() { return fetchedAt; }
    public void setFetchedAt(Instant fetchedAt) { this.fetchedAt = fetchedAt; }
}