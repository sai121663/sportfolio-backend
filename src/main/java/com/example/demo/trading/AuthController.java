// AuthController.java
package com.example.demo.trading;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private static final double STARTING_CASH_BALANCE = 10000.0;

    private final UserRepository userRepository;
    private final AuthTokenRepository authTokenRepository;
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${google.client.id}")
    private String googleClientId;

    public AuthController(UserRepository userRepository, AuthTokenRepository authTokenRepository) {
        this.userRepository = userRepository;
        this.authTokenRepository = authTokenRepository;
    }

    @PostMapping("/google")
    public AuthResponse googleSignIn(@RequestBody GoogleSignInRequest request) {
        if (request.idToken == null || request.idToken.isBlank()) {
            throw new IllegalArgumentException("Missing idToken");
        }

        GoogleTokenInfo tokenInfo;
        try {
            String url = "https://oauth2.googleapis.com/tokeninfo?id_token=" + request.idToken;
            tokenInfo = restTemplate.getForObject(url, GoogleTokenInfo.class);
        } catch (RestClientException e) {
            // Google itself rejected the token (expired, malformed, etc.) --
            // surface that clearly instead of letting it crash as a raw 500.
            throw new IllegalArgumentException("Google rejected this sign-in token: " + e.getMessage());
        }

        if (tokenInfo == null || tokenInfo.sub == null) {
            throw new IllegalArgumentException("Invalid or expired Google token");
        }
        if (tokenInfo.aud == null || !tokenInfo.aud.equals(googleClientId)) {
            throw new IllegalArgumentException("Token was not issued for this app (aud=" + tokenInfo.aud + ")");
        }

        User user = userRepository.findByGoogleId(tokenInfo.sub)
                .orElseGet(() -> {
                    User u = new User();
                    u.setGoogleId(tokenInfo.sub);
                    u.setEmail(tokenInfo.email);
                    u.setUsername(
                            tokenInfo.name != null && !tokenInfo.name.isBlank()
                                    ? tokenInfo.name
                                    : (tokenInfo.email != null ? tokenInfo.email : "Player " + tokenInfo.sub)
                    );
                    u.setCashBalance(STARTING_CASH_BALANCE);
                    return userRepository.save(u);
                });

        AuthToken authToken = new AuthToken();
        authToken.setToken(UUID.randomUUID().toString());
        authToken.setUser(user);
        authToken.setCreatedAt(Instant.now());
        authTokenRepository.save(authToken);

        AuthResponse response = new AuthResponse();
        response.token = authToken.getToken();
        response.userId = user.getId();
        response.username = user.getUsername();
        response.cashBalance = user.getCashBalance();
        return response;
    }

    // Turns our own validation failures into a clean 400 with the actual
    // reason, instead of Spring's default raw 500 for any uncaught exception.
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
    }

    public static class GoogleSignInRequest {
        public String idToken;
    }

    public static class AuthResponse {
        public String token;
        public Long userId;
        public String username;
        public Double cashBalance;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GoogleTokenInfo {
        public String sub;
        public String email;
        public String name;
        public String aud;
    }
}