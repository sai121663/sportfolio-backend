package com.example.demo.trading;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

@Component
public class AuthFilter extends OncePerRequestFilter {

    private final AuthTokenRepository authTokenRepository;

    public AuthFilter(AuthTokenRepository authTokenRepository) {
        this.authTokenRepository = authTokenRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getRequestURI();
        boolean isProtected = path.startsWith("/portfolio/") || path.startsWith("/trade");

        if (!isProtected) {
            filterChain.doFilter(request, response);
            return;
        }

        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Missing Authorization header");
            return;
        }

        String tokenValue = header.substring("Bearer ".length());
        Optional<AuthToken> authToken = authTokenRepository.findByToken(tokenValue);
        if (authToken.isEmpty()) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid or expired session");
            return;
        }

        Long authenticatedUserId = authToken.get().getUser().getId();
        Long requestedUserId = extractRequestedUserId(request, path);

        if (requestedUserId != null && !requestedUserId.equals(authenticatedUserId)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "This session does not have access to that account");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private Long extractRequestedUserId(HttpServletRequest request, String path) {
        try {
            if (path.startsWith("/portfolio/")) {
                return Long.parseLong(path.substring("/portfolio/".length()));
            }
            if (path.startsWith("/trade")) {
                String userIdParam = request.getParameter("userId");
                return userIdParam != null ? Long.parseLong(userIdParam) : null;
            }
        } catch (NumberFormatException ignored) {
        }
        return null;
    }
}