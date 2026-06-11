package com.smartplate.smartplate.auth;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JwtUtil — creates and validates JWT tokens.
 *
 * A JWT has 3 parts separated by dots: header.payload.signature
 * - Header: algorithm used (HS256)
 * - Payload: claims (userId, email, expiry)
 * - Signature: HMAC of header+payload using our secret key
 *
 * We store userId in the "sub" (subject) claim so we can extract it
 * from every request without hitting the database.
 */
@Component
public class JwtUtil {

    // Secret key from application.yml — must be at least 32 chars for HS256
    @Value("${jwt.secret:smartplate-secret-key-change-in-production-min-32-chars}")
    private String secret;

    // Token validity — 7 days in milliseconds
    private static final long EXPIRY_MS = 7L * 24 * 60 * 60 * 1000;

    /**
     * Build the signing key from our secret string.
     * Keys.hmacShaKeyFor requires the secret as bytes.
     */
    private SecretKey key() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Generate a JWT token for a user.
     * @param userId  the database ID of the user
     * @param email   stored in the token for display purposes
     * @return signed JWT string e.g. "eyJhbGci..."
     */
    public String generateToken(int userId, String email) {
        return Jwts.builder()
            .subject(String.valueOf(userId))   // "sub" claim = userId
            .claim("email", email)             // custom claim
            .issuedAt(new Date())              // "iat" claim
            .expiration(new Date(System.currentTimeMillis() + EXPIRY_MS)) // "exp" claim
            .signWith(key())
            .compact();
    }

    /**
     * Extract the userId from a token.
     * Throws JwtException if token is invalid or expired.
     */
    public int extractUserId(String token) {
        String subject = Jwts.parser()
            .verifyWith(key())
            .build()
            .parseSignedClaims(token)
            .getPayload()
            .getSubject();
        return Integer.parseInt(subject);
    }

    /**
     * Extract the email from a token.
     */
    public String extractEmail(String token) {
        return Jwts.parser()
            .verifyWith(key())
            .build()
            .parseSignedClaims(token)
            .getPayload()
            .get("email", String.class);
    }

    /**
     * Validate a token — returns true if it parses without throwing.
     */
    public boolean isValid(String token) {
        try {
            Jwts.parser().verifyWith(key()).build().parseSignedClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }
}
