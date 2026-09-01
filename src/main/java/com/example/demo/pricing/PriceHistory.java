package com.example.demo.pricing;

import com.example.demo.player.Player;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(indexes = {
        @Index(name = "idx_price_history_player_gamedate", columnList = "player_id, game_date"),
        // Added alongside the switch to a date-only (no player filter) query
        // for building /players cards -- the composite index above can't be
        // used efficiently for a plain "game_date >= ?" scan since player_id
        // is its leading column.
        @Index(name = "idx_price_history_gamedate", columnList = "game_date")
})
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