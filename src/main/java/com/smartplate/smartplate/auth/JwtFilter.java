package com.smartplate.smartplate.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * JwtFilter — runs on every incoming HTTP request.
 *
 * It looks for a Bearer token in the Authorization header:
 *   Authorization: Bearer eyJhbGci...
 *
 * If the token is valid:
 * - Extracts userId and email
 * - Stores them in the Spring Security context
 * - Stores userId as a request attribute so controllers can read it
 *
 * If no token or invalid token:
 * - Request continues unauthenticated
 * - Public endpoints (login, register) still work
 * - Protected endpoints return 401
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");

        // Check if header exists and starts with "Bearer "
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7); // strip "Bearer "

            if (jwtUtil.isValid(token)) {
                int userId = jwtUtil.extractUserId(token);
                String email = jwtUtil.extractEmail(token);

                // Store userId as request attribute — controllers read this
                request.setAttribute("userId", userId);
                request.setAttribute("email", email);

                // Tell Spring Security this request is authenticated
                // Empty authorities list — we don't use roles yet
                UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken(email, null, List.of());
                SecurityContextHolder.getContext().setAuthentication(auth);

                log.debug("Authenticated request from userId={}", userId);
            }
        }

        // Always continue the filter chain — auth is optional for public endpoints
        chain.doFilter(request, response);
    }
}
