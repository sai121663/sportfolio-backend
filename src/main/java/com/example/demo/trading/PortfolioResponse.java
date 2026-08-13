package com.example.demo.trading;

import java.util.List;

public class PortfolioResponse {

    private String username;
    private Double cashBalance;
    private List<HoldingView> holdings;
    private Double totalPortfolioValue;
    private Double totalAccountValue;

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public Double getCashBalance() { return cashBalance; }
    public void setCashBalance(Double cashBalance) { this.cashBalance = cashBalance; }

    public List<HoldingView> getHoldings() { return holdings; }
    public void setHoldings(List<HoldingView> holdings) { this.holdings = holdings; }

    public Double getTotalPortfolioValue() { return totalPortfolioValue; }
    public void setTotalPortfolioValue(Double totalPortfolioValue) { this.totalPortfolioValue = totalPortfolioValue; }

    public Double getTotalAccountValue() { return totalAccountValue; }
    public void setTotalAccountValue(Double totalAccountValue) { this.totalAccountValue = totalAccountValue; }
}