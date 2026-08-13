package com.example.demo.trading;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/users")
public class UserController {

    private final UserRepository userRepository;
    private final PortfolioService portfolioService;

    public UserController(UserRepository userRepository, PortfolioService portfolioService) {
        this.userRepository = userRepository;
        this.portfolioService = portfolioService;
    }

    @PostMapping
    public User createUser(@RequestParam String username) {
        User user = new User();
        user.setUsername(username);
        user.setCashBalance(10000.0);
        return userRepository.save(user);
    }

    @GetMapping("/{id}")
    public User getUser(@PathVariable Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("User not found: " + id));
    }

    @GetMapping("/{id}/holdings")
    public PortfolioResponse getHoldings(@PathVariable Long id) {
        return portfolioService.getPortfolio(id);
    }
}