package com.smartplate.smartplate.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * DTOs that map exactly to the Nutrislice JSON response structure.
 *
 * Confirmed field names from live UGA API response (hall 44401, 2026-04-26).
 * Any field not listed here is ignored via @JsonIgnoreProperties(ignoreUnknown = true).
 */
public class NutrisliceDto {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WeekResponse(
        @JsonProperty("start_date")    String startDate,
        @JsonProperty("menu_type_id")  int menuTypeId,
        @JsonProperty("id")            int menuId,
        @JsonProperty("days")          List<DayMenu> days
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DayMenu(
        @JsonProperty("date")         String date,           // "2026-04-26"
        @JsonProperty("menu_items")   List<MenuItem> menuItems,
        @JsonProperty("menu_info")    Map<String, MenuInfo> menuInfo   // menu_id → section info
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MenuInfo(
        @JsonProperty("position")        int position,
        @JsonProperty("section_options") SectionOptions sectionOptions
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SectionOptions(
        @JsonProperty("display_name") String displayName   // "Tanyard Grill", "Taqueria", etc.
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MenuItem(
        @JsonProperty("id")                  long id,
        @JsonProperty("menu_id")             int menuId,        // links to menu_info keys
        @JsonProperty("is_section_title")    boolean isSectionTitle,
        @JsonProperty("is_station_header")   boolean isStationHeader,
        @JsonProperty("food")                Food food,
        @JsonProperty("serving_size_amount") Double servingSizeAmount,
        @JsonProperty("serving_size_unit")   String servingSizeUnit
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Food(
        @JsonProperty("id")                   int id,            // STABLE across days — use as dedup key
        @JsonProperty("name")                 String name,
        @JsonProperty("description")          String description,
        @JsonProperty("ingredients")          String ingredients,
        @JsonProperty("image_url")            String imageUrl,
        @JsonProperty("has_nutrition_info")   boolean hasNutritionInfo,
        @JsonProperty("rounded_nutrition_info") RoundedNutritionInfo nutritionInfo,
        @JsonProperty("serving_size_info")    ServingSizeInfo servingSizeInfo,
        @JsonProperty("icons")                Icons icons
    ) {}

    /**
     * Maps to food.rounded_nutrition_info.
     * All fields confirmed present in live API; some may be null (e.g. mg_vitamin_c).
     * Use Float (boxed) to allow null safely.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RoundedNutritionInfo(
        @JsonProperty("calories")         Float calories,
        @JsonProperty("g_protein")        Float gProtein,
        @JsonProperty("g_carbs")          Float gCarbs,
        @JsonProperty("g_fat")            Float gFat,
        @JsonProperty("g_fiber")          Float gFiber,
        @JsonProperty("g_saturated_fat")  Float gSaturatedFat,
        @JsonProperty("g_trans_fat")      Float gTransFat,
        @JsonProperty("g_sugar")          Float gSugar,
        @JsonProperty("mg_sodium")        Float mgSodium,
        @JsonProperty("mg_cholesterol")   Float mgCholesterol,
        @JsonProperty("mg_calcium")       Float mgCalcium
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ServingSizeInfo(
        @JsonProperty("serving_size_amount") String servingSizeAmount,
        @JsonProperty("serving_size_unit")   String servingSizeUnit
    ) {}

    /**
     * food.icons.food_icons — each icon has a slug.
     *
     * behavior=2 (positive diet labels):
     *   vegan, meatless, halal, gluten-friendly, heart-healthy, taste-of-home
     *
     * behavior=1 (allergen warnings, is_filter=true):
     *   milk, eggs, wheat, peanuts, tree-nuts, soybeans, fish, crustacean-shellfish, sesame
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Icons(
        @JsonProperty("food_icons") List<FoodIcon> foodIcons
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FoodIcon(
        @JsonProperty("slug")     String slug,      // machine-readable, stable
        @JsonProperty("name")     String name,      // display name
        @JsonProperty("enabled")  boolean enabled,
        @JsonProperty("behavior") int behavior      // 1=allergen, 2=positive label
    ) {}
}
