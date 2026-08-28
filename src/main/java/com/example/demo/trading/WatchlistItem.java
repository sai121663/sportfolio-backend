package com.example.demo.trading;

import com.example.demo.player.Player;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
public class WatchlistItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    private User user;

    @ManyToOne
    private Player player;

    private Instant addedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }

    public Player getPlayer() { return player; }
    public void setPlayer(Player player) { this.player = player; }

    public Instant getAddedAt() { return addedAt; }
    public void setAddedAt(Instant addedAt) { this.addedAt = addedAt; }
}