package com.example.demo.trading;

import org.springframework.stereotype.Service;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class PortfolioService {

    private final UserRepository userRepository;
    private final HoldingRepository holdingRepository;

    public PortfolioService(UserRepository userRepository, HoldingRepository holdingRepository) {
        this.userRepository = userRepository;
        this.holdingRepository = holdingRepository;
    }

    public PortfolioResponse getPortfolio(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        List<Holding> holdings = holdingRepository.findByUser(user);

        List<HoldingView> holdingViews = holdings.stream().map(h -> {
            HoldingView view = new HoldingView();
            view.setPlayerId(h.getPlayer().getId());
            view.setPlayerName(h.getPlayer().getName());
            view.setTeam(h.getPlayer().getTeam());
            view.setSport(h.getPlayer().getSport());
            view.setImageUrl(h.getPlayer().getImageUrl());
            view.setQuantity(h.getQuantity());
            view.setBoughtAt(h.getAvgCostBasis());
            view.setCurrentPrice(h.getPlayer().getPrice());
            view.setPositionValue(h.getQuantity() * h.getPlayer().getPrice());
            return view;
        }).collect(Collectors.toList());

        double totalPortfolioValue = holdingViews.stream()
                .mapToDouble(HoldingView::getPositionValue)
                .sum();

        PortfolioResponse response = new PortfolioResponse();
        response.setUsername(user.getUsername());
        response.setCashBalance(user.getCashBalance());
        response.setHoldings(holdingViews);
        response.setTotalPortfolioValue(totalPortfolioValue);
        response.setTotalAccountValue(user.getCashBalance() + totalPortfolioValue);
        return response;
    }
}