// src/main/java/com/example/demo/trading/Holding.java
package com.example.demo.trading;

import com.example.demo.player.Player;
import jakarta.persistence.*;

@Entity
public class Holding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    private User user;

    @ManyToOne
    private Player player;

    private Double quantity;

    // Weighted-average price per share paid across all open BUYs for this holding.
    // Unchanged by SELLs (average-cost-basis method); reset when the holding is
    // closed out to zero (see TradingService.sell()).
    private Double avgCostBasis;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }

    public Player getPlayer() { return player; }
    public void setPlayer(Player player) { this.player = player; }

    public Double getQuantity() { return quantity; }
    public void setQuantity(Double quantity) { this.quantity = quantity; }

    public Double getAvgCostBasis() { return avgCostBasis; }
    public void setAvgCostBasis(Double avgCostBasis) { this.avgCostBasis = avgCostBasis; }
}