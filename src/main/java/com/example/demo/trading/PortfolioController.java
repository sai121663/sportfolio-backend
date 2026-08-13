package com.example.demo.trading;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PortfolioController {

    private final PortfolioService portfolioService;

    public PortfolioController(PortfolioService portfolioService) {
        this.portfolioService = portfolioService;
    }

    @GetMapping("/portfolio/{userId}")
    public PortfolioResponse getPortfolio(@PathVariable Long userId) {
        return portfolioService.getPortfolio(userId);
    }
}
