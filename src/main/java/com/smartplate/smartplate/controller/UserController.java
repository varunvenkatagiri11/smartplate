package com.smartplate.smartplate.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class UserController {

    private final JdbcTemplate jdbc;

    /**
     * Returns the item IDs the authenticated user currently has favorited.
     * "Currently favorited" = the most recent 'favorite' or 'unfavorite' event
     * for that item is 'favorite'.
     */
    @GetMapping("/me/favorites")
    public ResponseEntity<Map<String, Object>> getFavorites(HttpServletRequest req) {
        Integer userId = (Integer) req.getAttribute("userId");
        if (userId == null) return ResponseEntity.ok(Map.of("itemIds", List.of()));

        List<Integer> itemIds = jdbc.queryForList("""
            SELECT item_id FROM (
                SELECT DISTINCT ON (item_id) item_id, event_type
                FROM events
                WHERE user_id = ? AND event_type IN ('favorite', 'unfavorite')
                ORDER BY item_id, created_at DESC
            ) sub
            WHERE event_type = 'favorite'
            """, Integer.class, userId);

        return ResponseEntity.ok(Map.of("itemIds", itemIds));
    }

    /**
     * Returns full menu item details for every item the user currently has favorited.
     */
    @GetMapping("/me/favorites/items")
    public ResponseEntity<List<Map<String, Object>>> getFavoriteItems(HttpServletRequest req) {
        Integer userId = (Integer) req.getAttribute("userId");
        if (userId == null) return ResponseEntity.ok(List.of());

        List<Map<String, Object>> items = jdbc.queryForList("""
            SELECT mi.id, mi.name, mi.calories, mi.g_protein, mi.g_carbs, mi.g_fat,
                   mi.g_fiber, mi.mg_sodium, mi.is_vegan, mi.is_meatless, mi.is_halal,
                   mi.is_gluten_friendly, mi.is_heart_healthy, mi.contains_milk,
                   mi.contains_eggs, mi.contains_wheat, mi.contains_peanuts,
                   mi.contains_tree_nuts, mi.contains_soy, mi.contains_fish,
                   mi.contains_shellfish, mi.contains_sesame,
                   mi.serving_size_amount, mi.serving_size_unit
            FROM menu_items mi
            WHERE mi.id IN (
                SELECT item_id FROM (
                    SELECT DISTINCT ON (item_id) item_id, event_type
                    FROM events
                    WHERE user_id = ? AND event_type IN ('favorite', 'unfavorite')
                    ORDER BY item_id, created_at DESC
                ) sub
                WHERE event_type = 'favorite'
            )
            ORDER BY mi.name
            """, userId);

        return ResponseEntity.ok(items);
    }

    /**
     * Returns a map of item_id -> rating_value for the authenticated user.
     * Only the most recent rating per item is returned.
     */
    @GetMapping("/me/ratings")
    public ResponseEntity<Map<String, Object>> getRatings(HttpServletRequest req) {
        Integer userId = (Integer) req.getAttribute("userId");
        if (userId == null) return ResponseEntity.ok(Map.of("ratings", Map.of()));

        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT item_id, rating_value FROM (
                SELECT DISTINCT ON (item_id) item_id, rating_value
                FROM events
                WHERE user_id = ? AND event_type = 'rate'
                ORDER BY item_id, created_at DESC
            ) sub
            """, userId);

        Map<String, Object> ratings = new HashMap<>();
        for (var row : rows) {
            ratings.put(String.valueOf(row.get("item_id")), row.get("rating_value"));
        }
        return ResponseEntity.ok(Map.of("ratings", ratings));
    }
}
