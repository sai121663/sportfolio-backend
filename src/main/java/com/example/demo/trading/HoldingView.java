// src/main/java/com/example/demo/trading/HoldingView.java
package com.example.demo.trading;

public class HoldingView {

    private Long playerId;
    private String playerName;
    private String team;
    private String sport;
    private String imageUrl;
    private Double quantity;
    private Double boughtAt;       // avg cost basis per share
    private Double currentPrice;
    private Double positionValue;

    public Long getPlayerId() { return playerId; }
    public void setPlayerId(Long playerId) { this.playerId = playerId; }

    public String getPlayerName() { return playerName; }
    public void setPlayerName(String playerName) { this.playerName = playerName; }

    public String getTeam() { return team; }
    public void setTeam(String team) { this.team = team; }

    public String getSport() { return sport; }
    public void setSport(String sport) { this.sport = sport; }

    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }

    public Double getQuantity() { return quantity; }
    public void setQuantity(Double quantity) { this.quantity = quantity; }

    public Double getBoughtAt() { return boughtAt; }
    public void setBoughtAt(Double boughtAt) { this.boughtAt = boughtAt; }

    public Double getCurrentPrice() { return currentPrice; }
    public void setCurrentPrice(Double currentPrice) { this.currentPrice = currentPrice; }

    public Double getPositionValue() { return positionValue; }
    public void setPositionValue(Double positionValue) { this.positionValue = positionValue; }
}