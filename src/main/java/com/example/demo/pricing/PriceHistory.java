package com.example.demo.pricing;

import com.example.demo.player.Player;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
public class PriceHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    private Player player;

    private Double price;
    private Double fantasyPoints;
    private String gameDate;
    private Instant recordedAt;

    // The full raw stat line Tank01 sent for this player's game (hits, walks,
    // home runs, innings pitched, etc.), stored as a JSON string. Used later to
    // reverse-engineer Tank01's exact fantasy scoring formula by comparing many
    // real stat lines against their known fantasyPoints totals. TEXT instead of
    // the default varchar(255) since a full stat line can be a decent chunk of JSON.
    @Column(columnDefinition = "TEXT")
    private String rawStatsJson;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Player getPlayer() { return player; }
    public void setPlayer(Player player) { this.player = player; }

    public Double getPrice() { return price; }
    public void setPrice(Double price) { this.price = price; }

    public Double getFantasyPoints() { return fantasyPoints; }
    public void setFantasyPoints(Double fantasyPoints) { this.fantasyPoints = fantasyPoints; }

    public String getGameDate() { return gameDate; }
    public void setGameDate(String gameDate) { this.gameDate = gameDate; }

    public Instant getRecordedAt() { return recordedAt; }
    public void setRecordedAt(Instant recordedAt) { this.recordedAt = recordedAt; }

    public String getRawStatsJson() { return rawStatsJson; }
    public void setRawStatsJson(String rawStatsJson) { this.rawStatsJson = rawStatsJson; }
}