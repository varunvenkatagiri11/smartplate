package com.smartplate.smartplate.controller;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/menu")
@CrossOrigin("*")
@RequiredArgsConstructor
public class MenuController {

    private final JdbcTemplate jdbc;

    @GetMapping
    public List<Map<String, Object>> getMenu(
            @RequestParam int hallId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) String mealPeriod,
            @RequestParam(required = false) Boolean isVegan,
            @RequestParam(required = false) Boolean isMeatless,
            @RequestParam(required = false) Boolean isHalal,
            @RequestParam(required = false) Boolean isGlutenFriendly,
            @RequestParam(required = false) Boolean excludeMilk,
            @RequestParam(required = false) Boolean excludeWheat,
            @RequestParam(required = false) Boolean excludeNuts,
            @RequestParam(defaultValue = "200") int limit) {

        LocalDate targetDate = date != null ? date : LocalDate.now();

        // Column names use underscores so they match the TypeScript MenuItem interface exactly.
        // No AS aliases needed — Postgres returns snake_case which is what the frontend expects.
        StringBuilder sql = new StringBuilder("""
            SELECT
                mi.id,
                mi.name,
                mi.description,
                mi.image_url,
                mi.calories,
                mi.g_protein,
                mi.g_carbs,
                mi.g_fat,
                mi.g_fiber,
                mi.mg_sodium,
                mi.mg_cholesterol,
                mi.mg_calcium,
                mi.is_vegan,
                mi.is_meatless,
                mi.is_halal,
                mi.is_gluten_friendly,
                mi.is_heart_healthy,
                mi.contains_milk,
                mi.contains_eggs,
                mi.contains_wheat,
                mi.contains_peanuts,
                mi.contains_tree_nuts,
                mi.contains_soy,
                mi.contains_fish,
                mi.contains_shellfish,
                mi.contains_sesame,
                mi.serving_size_amount,
                mi.serving_size_unit,
                mi.ingredients,
                s.display_name       AS station,
                dh.meal_period,
                dh.name              AS hall_name
            FROM menu_availability ma
            JOIN menu_items mi        ON mi.id = ma.item_id
            JOIN dining_halls dh      ON dh.id = ma.dining_hall_id
            LEFT JOIN stations s      ON s.id  = ma.station_id
            WHERE dh.school_id = ?
              AND ma.available_date = ?
            """);

        java.util.List<Object> params = new java.util.ArrayList<>();
        params.add(hallId);
        params.add(targetDate);

        if (mealPeriod != null)                    { sql.append(" AND dh.meal_period = ?"); params.add(mealPeriod); }
        if (Boolean.TRUE.equals(isVegan))          sql.append(" AND mi.is_vegan = true");
        if (Boolean.TRUE.equals(isMeatless))       sql.append(" AND mi.is_meatless = true");
        if (Boolean.TRUE.equals(isHalal))          sql.append(" AND mi.is_halal = true");
        if (Boolean.TRUE.equals(isGlutenFriendly)) sql.append(" AND mi.is_gluten_friendly = true");
        if (Boolean.TRUE.equals(excludeMilk))      sql.append(" AND mi.contains_milk = false");
        if (Boolean.TRUE.equals(excludeWheat))     sql.append(" AND mi.contains_wheat = false");
        if (Boolean.TRUE.equals(excludeNuts))      sql.append(" AND mi.contains_peanuts = false AND mi.contains_tree_nuts = false");

        sql.append(" ORDER BY s.display_name, mi.name LIMIT ?");
        params.add(limit);

        return jdbc.queryForList(sql.toString(), params.toArray());
    }

    @GetMapping("/halls")
    public List<Map<String, Object>> getHalls() {
        return jdbc.queryForList("""
            SELECT school_id AS hall_id, name, meal_period
            FROM dining_halls
            ORDER BY name, meal_period
            """);
    }
}