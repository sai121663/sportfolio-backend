package com.example.demo.trading;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/trade")
public class TradingController {

    private final TradingService tradingService;

    public TradingController(TradingService tradingService) {
        this.tradingService = tradingService;
    }

    @PostMapping("/buy")
    public Trade buy(@RequestParam Long userId, @RequestParam Long playerId, @RequestParam int quantity) {
        return tradingService.buy(userId, playerId, quantity);
    }

    @PostMapping("/sell")
    public Trade sell(@RequestParam Long userId, @RequestParam Long playerId, @RequestParam int quantity) {
        return tradingService.sell(userId, playerId, quantity);
    }
}
