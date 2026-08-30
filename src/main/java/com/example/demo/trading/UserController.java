package com.example.demo.trading;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

    @GetMapping("/{id}")
    public User getUser(@PathVariable Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("User not found: " + id));
    }

    @GetMapping("/{id}/holdings")
    public PortfolioResponse getHoldings(@PathVariable Long id) {
        return portfolioService.getPortfolio(id);
    }

    // Lets a user change the username Google handed over by default (their
    // real name or email) right after signing in for the first time -- see
    // AuthController's isNewUser flag, which the frontend uses to decide
    // when to show that prompt. Protected by AuthFilter, same as
    // /portfolio/{userId} -- a session can only rename its own account.
    @PostMapping("/{id}/username")
    public User updateUsername(@PathVariable Long id, @RequestBody UpdateUsernameRequest request) {
        if (request.username == null || request.username.isBlank()) {
            throw new IllegalArgumentException("Username cannot be blank");
        }
        User user = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + id));
        user.setUsername(request.username.trim());
        return userRepository.save(user);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
    }

    public static class UpdateUsernameRequest {
        public String username;
    }
}