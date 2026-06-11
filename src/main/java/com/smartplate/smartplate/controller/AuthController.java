package com.smartplate.smartplate.controller;

import com.smartplate.smartplate.auth.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * AuthController — handles user registration and login.
 *
 * POST /api/v1/auth/register — create a new account
 * POST /api/v1/auth/login    — get a JWT token
 * GET  /api/v1/auth/me       — get current user info (requires token)
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
@CrossOrigin("*")
@RequiredArgsConstructor
public class AuthController {

    private final JdbcTemplate jdbc;
    private final JwtUtil jwtUtil;
    private final PasswordEncoder passwordEncoder;

    /**
     * Register a new user.
     *
     * Request body:
     * {
     *   "email": "student@uga.edu",
     *   "password": "mypassword",
     *   "prefVegan": false,
     *   "prefHalal": true,
     *   "prefMeatless": false,
     *   "prefGlutenFree": false,
     *   "avoidPeanuts": false,
     *   "avoidTreeNuts": false,
     *   "avoidMilk": false,
     *   "avoidEggs": false,
     *   "avoidWheat": false,
     *   "avoidSoy": false,
     *   "avoidFish": false,
     *   "avoidShellfish": false,
     *   "avoidSesame": false
     * }
     *
     * Response:
     * { "token": "eyJhbGci...", "userId": 1, "email": "student@uga.edu" }
     */
    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(@RequestBody Map<String, Object> body) {
        String email = (String) body.get("email");
        String password = (String) body.get("password");

        if (email == null || password == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Email and password required"));
        }

        // Check if email already exists
        Integer existing = jdbc.queryForObject(
            "SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, email);
        if (existing != null && existing > 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "Email already registered"));
        }

        // Hash the password — never store plaintext
        String hashedPassword = passwordEncoder.encode(password);

        // Extract dietary preferences from request body
        boolean prefVegan       = getBool(body, "prefVegan");
        boolean prefMeatless    = getBool(body, "prefMeatless");
        boolean prefHalal       = getBool(body, "prefHalal");
        boolean prefGlutenFree  = getBool(body, "prefGlutenFree");
        boolean avoidMilk       = getBool(body, "avoidMilk");
        boolean avoidEggs       = getBool(body, "avoidEggs");
        boolean avoidPeanuts    = getBool(body, "avoidPeanuts");
        boolean avoidTreeNuts   = getBool(body, "avoidTreeNuts");
        boolean avoidSoy        = getBool(body, "avoidSoy");
        boolean avoidFish       = getBool(body, "avoidFish");
        boolean avoidShellfish  = getBool(body, "avoidShellfish");
        boolean avoidSesame     = getBool(body, "avoidSesame");
        boolean avoidWheat      = getBool(body, "avoidWheat");

        // Insert user and return their new ID
        Integer userId = jdbc.queryForObject("""
            INSERT INTO users (
                email, password_hash,
                pref_vegan, pref_meatless, pref_halal, pref_gluten_free,
                avoid_milk, avoid_eggs, avoid_peanuts, avoid_tree_nuts,
                avoid_soy, avoid_fish, avoid_shellfish, avoid_sesame, avoid_wheat
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            RETURNING id
            """,
            Integer.class,
            email, hashedPassword,
            prefVegan, prefMeatless, prefHalal, prefGlutenFree,
            avoidMilk, avoidEggs, avoidPeanuts, avoidTreeNuts,
            avoidSoy, avoidFish, avoidShellfish, avoidSesame, avoidWheat
        );

        if (userId == null) {
            return ResponseEntity.internalServerError().body(Map.of("error", "Registration failed"));
        }

        String token = jwtUtil.generateToken(userId, email);
        log.info("New user registered: {} (id={})", email, userId);

        return ResponseEntity.ok(Map.of(
            "token", token,
            "userId", userId,
            "email", email
        ));
    }

    /**
     * Login with email and password.
     * Returns a JWT token if credentials are valid.
     */
    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, Object> body) {
        String email = (String) body.get("email");
        String password = (String) body.get("password");

        if (email == null || password == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Email and password required"));
        }

        // Look up user by email
        List<Map<String, Object>> users = jdbc.queryForList(
            "SELECT id, password_hash FROM users WHERE email = ?", email);

        if (users.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid credentials"));
        }

        Map<String, Object> user = users.get(0);
        String storedHash = (String) user.get("password_hash");
        int userId = ((Number) user.get("id")).intValue();

        // BCrypt comparison — checks plaintext against stored hash
        if (!passwordEncoder.matches(password, storedHash)) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid credentials"));
        }

        String token = jwtUtil.generateToken(userId, email);
        log.info("User logged in: {} (id={})", email, userId);

        return ResponseEntity.ok(Map.of(
            "token", token,
            "userId", userId,
            "email", email
        ));
    }

    /**
     * Get current user's profile.
     * Requires Authorization: Bearer <token>
     */
    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(jakarta.servlet.http.HttpServletRequest request) {
        Integer userId = (Integer) request.getAttribute("userId");
        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        }

        List<Map<String, Object>> users = jdbc.queryForList(
            """
            SELECT id, email,
                   pref_vegan, pref_meatless, pref_halal, pref_gluten_free,
                   avoid_milk, avoid_eggs, avoid_peanuts, avoid_tree_nuts,
                   avoid_soy, avoid_fish, avoid_shellfish, avoid_sesame, avoid_wheat
            FROM users WHERE id = ?
            """, userId);

        if (users.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "User not found"));
        }

        return ResponseEntity.ok(users.get(0));
    }

    private boolean getBool(Map<String, Object> body, String key) {
        Object val = body.get(key);
        if (val instanceof Boolean b) return b;
        if (val instanceof String s) return Boolean.parseBoolean(s);
        return false;
    }
}
