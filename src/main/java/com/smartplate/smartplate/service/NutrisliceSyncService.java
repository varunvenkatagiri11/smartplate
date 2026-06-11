package com.smartplate.smartplate.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartplate.smartplate.dto.NutrisliceDto;
import com.smartplate.smartplate.dto.NutrisliceDto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class NutrisliceSyncService {

    private static final String BASE_URL = "https://uga.api.nutrislice.com/menu/api/weeks/school";

    // Each hall synced twice — breakfast (13832) and lunch/dinner (13833)
    private static final List<Map<String, Object>> HALLS = List.of(
            Map.of("schoolId", 44401, "name", "Bolton Dining Commons"),
            Map.of("schoolId", 44402, "name", "O-House Dining"),
            Map.of("schoolId", 44403, "name", "Snelling Dining Commons"),
            Map.of("schoolId", 44404, "name", "Niche"),
            Map.of("schoolId", 44405, "name", "Village Summit")
    );

    private static final List<Map<String, Object>> MENU_TYPES = List.of(
            Map.of("menuTypeId", 13832, "mealPeriod", "breakfast"),
            Map.of("menuTypeId", 13833, "mealPeriod", "lunch_dinner")
    );

    private final RestTemplate restTemplate;
    private final JdbcTemplate jdbc;
    private final RedisTemplate<String, String> redis;
    private final ObjectMapper objectMapper;

    @Scheduled(cron = "0 0 6 * * *", zone = "America/New_York")
    public void syncAll() {
        LocalDate monday = LocalDate.now().with(DayOfWeek.MONDAY);
        log.info("Starting Nutrislice sync for week of {}", monday);

        for (Map<String, Object> hall : HALLS) {
            for (Map<String, Object> mt : MENU_TYPES) {
                try {
                    syncHallMeal(hall, mt, monday);
                } catch (Exception e) {
                    log.error("Failed: {} {}: {}", hall.get("name"), mt.get("mealPeriod"), e.getMessage());
                }
            }
        }
        log.info("Nutrislice sync complete");
    }

    private void syncHallMeal(Map<String, Object> hall, Map<String, Object> mt, LocalDate monday) throws Exception {
        int schoolId   = (int) hall.get("schoolId");
        int menuTypeId = (int) mt.get("menuTypeId");

        String url = String.format("%s/%d/menu-type/%d/%d/%02d/%02d/?format=json",
                BASE_URL, schoolId, menuTypeId,
                monday.getYear(), monday.getMonthValue(), monday.getDayOfMonth());

        log.info("Fetching {}", url);
        WeekResponse week = restTemplate.getForObject(url, WeekResponse.class);
        if (week == null || week.days() == null) return;

        // Look up hall row by school_id + menu_type_id (two rows per physical hall)
        Integer hallDbId;
        try {
            hallDbId = jdbc.queryForObject(
                    "SELECT id FROM dining_halls WHERE school_id = ? AND menu_type_id = ?",
                    Integer.class, schoolId, menuTypeId);
        } catch (Exception e) {
            log.warn("No DB row for school_id={} menu_type_id={}", schoolId, menuTypeId);
            return;
        }

        // Collect all stations from menu_info across all days
        Map<Integer, String> menuIdToStation = new HashMap<>();
        for (DayMenu day : week.days()) {
            if (day.menuInfo() == null) continue;
            day.menuInfo().forEach((menuIdStr, info) -> {
                int menuId = Integer.parseInt(menuIdStr);
                menuIdToStation.put(menuId, info.sectionOptions().displayName());
            });
        }

        // Upsert stations
        for (Map.Entry<Integer, String> entry : menuIdToStation.entrySet()) {
            jdbc.update("""
                INSERT INTO stations (menu_id, dining_hall_id, display_name)
                VALUES (?, ?, ?)
                ON CONFLICT (menu_id) DO UPDATE SET display_name = EXCLUDED.display_name
                """, entry.getKey(), hallDbId, entry.getValue());
        }

        int itemCount = 0;
        for (DayMenu day : week.days()) {
            if (day.menuItems() == null) continue;
            LocalDate availDate = LocalDate.parse(day.date());

            for (MenuItem menuItem : day.menuItems()) {
                if (menuItem.isSectionTitle() || menuItem.isStationHeader()) continue;
                Food food = menuItem.food();
                if (food == null) continue;

                Integer itemDbId = upsertMenuItem(food);
                Integer stationDbId = getStationId(menuItem.menuId());

                jdbc.update("""
                    INSERT INTO menu_availability (item_id, dining_hall_id, station_id, available_date)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT (item_id, dining_hall_id, available_date) DO NOTHING
                    """, itemDbId, hallDbId, stationDbId, availDate);

                // Prime Redis cache for today's items
                if (availDate.equals(LocalDate.now()) || availDate.equals(LocalDate.now().plusDays(1))) {
                    String json = objectMapper.writeValueAsString(
                            buildCachePayload(food, itemDbId, menuIdToStation.get(menuItem.menuId())));
                    redis.opsForValue().set("item:hot:" + itemDbId, json, 36, TimeUnit.HOURS);
                }
                itemCount++;
            }
        }
        log.info("{} [{}]: {} items synced", hall.get("name"), mt.get("mealPeriod"), itemCount);
    }

    private Integer upsertMenuItem(Food food) {
        RoundedNutritionInfo n = food.nutritionInfo();
        Map<String, Boolean> flags = parseIcons(food.icons());

        return jdbc.queryForObject("""
            INSERT INTO menu_items (
                nutrislice_food_id, name, description, ingredients, image_url,
                calories, g_protein, g_carbs, g_fat, g_fiber,
                g_saturated_fat, g_trans_fat, g_sugar, mg_sodium, mg_cholesterol, mg_calcium,
                is_vegan, is_meatless, is_halal, is_gluten_friendly, is_heart_healthy,
                contains_milk, contains_eggs, contains_wheat, contains_peanuts,
                contains_tree_nuts, contains_soy, contains_fish, contains_shellfish, contains_sesame,
                serving_size_amount, serving_size_unit, last_seen_at
            ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,NOW())
            ON CONFLICT (nutrislice_food_id) DO UPDATE SET
                name = EXCLUDED.name, calories = EXCLUDED.calories,
                g_protein = EXCLUDED.g_protein, g_carbs = EXCLUDED.g_carbs,
                g_fat = EXCLUDED.g_fat, g_fiber = EXCLUDED.g_fiber,
                is_vegan = EXCLUDED.is_vegan, is_meatless = EXCLUDED.is_meatless,
                is_halal = EXCLUDED.is_halal, is_gluten_friendly = EXCLUDED.is_gluten_friendly,
                contains_milk = EXCLUDED.contains_milk, contains_wheat = EXCLUDED.contains_wheat,
                last_seen_at = NOW()
            RETURNING id
            """,
                Integer.class,
                food.id(), food.name(), nullIfBlank(food.description()),
                nullIfBlank(food.ingredients()), food.imageUrl(),
                n == null ? null : n.calories(), n == null ? null : n.gProtein(),
                n == null ? null : n.gCarbs(),   n == null ? null : n.gFat(),
                n == null ? null : n.gFiber(),   n == null ? null : n.gSaturatedFat(),
                n == null ? null : n.gTransFat(),n == null ? null : n.gSugar(),
                n == null ? null : n.mgSodium(), n == null ? null : n.mgCholesterol(),
                n == null ? null : n.mgCalcium(),
                flags.get("is_vegan"), flags.get("is_meatless"), flags.get("is_halal"),
                flags.get("is_gluten_friendly"), flags.get("is_heart_healthy"),
                flags.get("contains_milk"), flags.get("contains_eggs"), flags.get("contains_wheat"),
                flags.get("contains_peanuts"), flags.get("contains_tree_nuts"), flags.get("contains_soy"),
                flags.get("contains_fish"), flags.get("contains_shellfish"), flags.get("contains_sesame"),
                food.servingSizeInfo() == null ? null : food.servingSizeInfo().servingSizeAmount(),
                food.servingSizeInfo() == null ? null : food.servingSizeInfo().servingSizeUnit()
        );
    }

    private Map<String, Boolean> parseIcons(Icons icons) {
        Map<String, Boolean> flags = new HashMap<>();
        String[] keys = {"is_vegan","is_meatless","is_halal","is_gluten_friendly","is_heart_healthy",
                "contains_milk","contains_eggs","contains_wheat","contains_peanuts",
                "contains_tree_nuts","contains_soy","contains_fish","contains_shellfish","contains_sesame"};
        for (String k : keys) flags.put(k, false);
        if (icons == null || icons.foodIcons() == null) return flags;

        for (FoodIcon icon : icons.foodIcons()) {
            if (!icon.enabled()) continue;
            switch (icon.slug()) {
                case "vegan"                -> flags.put("is_vegan", true);
                case "meatless"             -> flags.put("is_meatless", true);
                case "halal"                -> flags.put("is_halal", true);
                case "gluten-friendly"      -> flags.put("is_gluten_friendly", true);
                case "heart-healthy"        -> flags.put("is_heart_healthy", true);
                case "milk"                 -> flags.put("contains_milk", true);
                case "eggs"                 -> flags.put("contains_eggs", true);
                case "wheat"                -> flags.put("contains_wheat", true);
                case "peanuts"              -> flags.put("contains_peanuts", true);
                case "tree-nuts"            -> flags.put("contains_tree_nuts", true);
                case "soybeans"             -> flags.put("contains_soy", true);
                case "fish"                 -> flags.put("contains_fish", true);
                case "crustacean-shellfish" -> flags.put("contains_shellfish", true);
                case "sesame"               -> flags.put("contains_sesame", true);
            }
        }
        return flags;
    }

    private Integer getStationId(int menuId) {
        try {
            return jdbc.queryForObject("SELECT id FROM stations WHERE menu_id = ?", Integer.class, menuId);
        } catch (Exception e) { return null; }
    }

    private Map<String, Object> buildCachePayload(Food food, int dbId, String stationName) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", dbId); m.put("name", food.name());
        m.put("station", stationName); m.put("imageUrl", food.imageUrl());
        if (food.nutritionInfo() != null) {
            RoundedNutritionInfo n = food.nutritionInfo();
            m.put("calories", n.calories()); m.put("protein", n.gProtein());
            m.put("carbs", n.gCarbs());     m.put("fat", n.gFat());
            m.put("fiber", n.gFiber());
        }
        return m;
    }

    private String nullIfBlank(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}