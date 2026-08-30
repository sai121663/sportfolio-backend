package com.example.demo.trading;

public class LeaderboardEntry {
    private int rank;
    private Long userId;
    private String username;
    private Double cashBalance;
    private Double totalPortfolioValue; // holdings only, mirrors PortfolioResponse
    private Double totalAccountValue;   // cash + holdings -- what the ranking is sorted by

    public int getRank() { return rank; }
    public void setRank(int rank) { this.rank = rank; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public Double getCashBalance() { return cashBalance; }
    public void setCashBalance(Double cashBalance) { this.cashBalance = cashBalance; }

    public Double getTotalPortfolioValue() { return totalPortfolioValue; }
    public void setTotalPortfolioValue(Double totalPortfolioValue) { this.totalPortfolioValue = totalPortfolioValue; }

    public Double getTotalAccountValue() { return totalAccountValue; }
    public void setTotalAccountValue(Double totalAccountValue) { this.totalAccountValue = totalAccountValue; }
}
