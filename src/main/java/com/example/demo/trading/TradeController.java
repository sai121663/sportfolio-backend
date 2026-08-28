package com.example.demo.trading;

import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/trades")
public class TradeController {

    private final UserRepository userRepository;
    private final TradeRepository tradeRepository;

    public TradeController(UserRepository userRepository, TradeRepository tradeRepository) {
        this.userRepository = userRepository;
        this.tradeRepository = tradeRepository;
    }

    @GetMapping("/{userId}")
    public List<TradeHistoryDto> getTradeHistory(@PathVariable Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        return tradeRepository.findByUserOrderByTimestampDesc(user).stream()
                .map(t -> {
                    TradeHistoryDto dto = new TradeHistoryDto();
                    dto.id = t.getId();
                    dto.playerId = t.getPlayer().getId();
                    dto.playerName = t.getPlayer().getName();
                    dto.team = t.getPlayer().getTeam();
                    dto.imageUrl = t.getPlayer().getImageUrl();
                    dto.type = t.getType();
                    dto.quantity = t.getQuantity();
                    dto.pricePerUnit = t.getPricePerUnit();
                    dto.total = t.getQuantity() != null && t.getPricePerUnit() != null
                            ? t.getQuantity() * t.getPricePerUnit() : null;
                    dto.timestamp = t.getTimestamp();
                    return dto;
                })
                .collect(Collectors.toList());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public org.springframework.http.ResponseEntity<String> handleBadRequest(IllegalArgumentException e) {
        return org.springframework.http.ResponseEntity.badRequest().body(e.getMessage());
    }

    public static class TradeHistoryDto {
        public Long id;
        public Long playerId;
        public String playerName;
        public String team;
        public String imageUrl;
        public String type;
        public Double quantity;
        public Double pricePerUnit;
        public Double total;
        public Instant timestamp;
    }
}