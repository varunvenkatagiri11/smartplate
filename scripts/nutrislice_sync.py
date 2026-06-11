#!/usr/bin/env python3
"""
SmartPlate — Nutrislice Sync Job
Fetches current week's menus for all 5 UGA dining halls × 2 meal periods.
  menu_type 13832 = breakfast
  menu_type 13833 = lunch/dinner

Run:
  DATABASE_URL="postgresql://smartplate:smartplate@127.0.0.1:5432/smartplate" python3 nutrislice_sync.py
"""

import os
import logging
import requests
import psycopg2
from datetime import date, timedelta

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s %(levelname)s %(message)s'
)
log = logging.getLogger(__name__)
"""
Configure logging:
- Show INFO, WARNING, ERROR, and CRITICAL messages
- Hide DEBUG messages
- Format logs like:
  2026-05-30 10:15:22,123 INFO Fetching menus
"""

# ── Config ───────────────────────────────────────────────────────────────────
DB_URL = os.getenv("DATABASE_URL", "postgresql://smartplate:smartplate@127.0.0.1:5432/smartplate")
NUTRISLICE_BASE = "https://uga.api.nutrislice.com/menu/api/weeks/school"

# Each hall gets synced twice — once per meal period
DINING_HALLS = [
    {"school_id": 44401, "name": "Bolton Dining Commons"},
    {"school_id": 44402, "name": "O-House Dining"},
    {"school_id": 44403, "name": "Snelling Dining Commons"},
    {"school_id": 44404, "name": "Niche"},
    {"school_id": 44405, "name": "Village Summit"},
]

MENU_TYPES = [
    {"menu_type_id": 13832, "meal_period": "breakfast"},
    {"menu_type_id": 13833, "meal_period": "lunch_dinner"},
]

# ── Fetch ────────────────────────────────────────────────────────────────────
def fetch_week(school_id: int, menu_type_id: int, monday: date) -> dict:
    url = (f"{NUTRISLICE_BASE}/{school_id}/menu-type/{menu_type_id}"
           f"/{monday.year}/{monday.month:02d}/{monday.day:02d}/?format=json")
    log.info(f"Fetching {url}")
    r = requests.get(url, timeout=15, headers={"Accept": "application/json"})
    r.raise_for_status()
    return r.json()

def this_monday() -> date:
    today = date.today()
    return today - timedelta(days=today.weekday())

# ── Parse ────────────────────────────────────────────────────────────────────
def parse_icons(icons_obj: dict) -> dict:
    """
    behavior=2 → positive diet label (vegan, halal, etc.)
    behavior=1 → allergen warning (milk, wheat, etc.)
    Only process icons where enabled=True.
    """
    result = {
        "is_vegan": False, "is_meatless": False, "is_halal": False,
        "is_gluten_friendly": False, "is_heart_healthy": False,
        "contains_milk": False, "contains_eggs": False, "contains_wheat": False,
        "contains_peanuts": False, "contains_tree_nuts": False, "contains_soy": False,
        "contains_fish": False, "contains_shellfish": False, "contains_sesame": False,
    }
    for icon in icons_obj.get("food_icons", []):
        if not icon.get("enabled"):
            continue
        slug = icon.get("slug", "")
        if   slug == "vegan":                   result["is_vegan"]           = True
        elif slug == "meatless":                result["is_meatless"]        = True
        elif slug == "halal":                   result["is_halal"]           = True
        elif slug == "gluten-friendly":         result["is_gluten_friendly"] = True
        elif slug == "heart-healthy":           result["is_heart_healthy"]   = True
        elif slug == "milk":                    result["contains_milk"]      = True
        elif slug == "eggs":                    result["contains_eggs"]      = True
        elif slug == "wheat":                   result["contains_wheat"]     = True
        elif slug == "peanuts":                 result["contains_peanuts"]   = True
        elif slug == "tree-nuts":               result["contains_tree_nuts"] = True
        elif slug == "soybeans":                result["contains_soy"]       = True
        elif slug == "fish":                    result["contains_fish"]      = True
        elif slug == "crustacean-shellfish":    result["contains_shellfish"] = True
        elif slug == "sesame":                  result["contains_sesame"]    = True
    return result

def parse_food(food: dict) -> dict:
    rni  = food.get("rounded_nutrition_info") or {}
    ssi  = food.get("serving_size_info") or {}
    icons = parse_icons(food.get("icons") or {})
    return {
        "nutrislice_food_id":  food["id"],
        "name":                food.get("name", "").strip(),
        "description":         food.get("description", "").strip() or None,
        "ingredients":         food.get("ingredients", "").strip() or None,
        "image_url":           food.get("image_url") or None,
        "calories":            rni.get("calories"),
        "g_protein":           rni.get("g_protein"),
        "g_carbs":             rni.get("g_carbs"),
        "g_fat":               rni.get("g_fat"),
        "g_fiber":             rni.get("g_fiber"),
        "g_saturated_fat":     rni.get("g_saturated_fat"),
        "g_trans_fat":         rni.get("g_trans_fat"),
        "g_sugar":             rni.get("g_sugar"),
        "mg_sodium":           rni.get("mg_sodium"),
        "mg_cholesterol":      rni.get("mg_cholesterol"),
        "mg_calcium":          rni.get("mg_calcium"),
        "serving_size_amount": str(ssi["serving_size_amount"]) if ssi.get("serving_size_amount") else None,
        "serving_size_unit":   ssi.get("serving_size_unit") or None,
        **icons,
    }

# ── Sync ─────────────────────────────────────────────────────────────────────
def sync_hall_meal(conn, school_id: int, menu_type_id: int, week_data: dict):
    with conn.cursor() as cur:
        # Look up the dining_hall row for this school_id + menu_type_id combo
        cur.execute(
            "SELECT id FROM dining_halls WHERE school_id=%s AND menu_type_id=%s",
            (school_id, menu_type_id)
        )
        row = cur.fetchone()
        if not row:
            log.warning(f"No dining_halls row for school_id={school_id} menu_type_id={menu_type_id}")
            return 0, 0
        hall_db_id = row[0]

        # Upsert all stations seen across the week
        all_menu_info = {}
        for day in week_data.get("days", []):
            all_menu_info.update(day.get("menu_info", {}))

        for menu_id_str, info in all_menu_info.items():
            menu_id = int(menu_id_str)
            display_name = info.get("section_options", {}).get("display_name", "Unknown")
            cur.execute("""
                INSERT INTO stations (menu_id, dining_hall_id, display_name)
                VALUES (%s, %s, %s)
                ON CONFLICT (menu_id) DO UPDATE SET display_name = EXCLUDED.display_name
            """, (menu_id, hall_db_id, display_name))

        items_upserted = 0
        avail_upserted = 0

        for day in week_data.get("days", []):
            available_date = day.get("date")
            if not available_date:
                continue

            for menu_item in day.get("menu_items", []):
                # Skip section headers / non-food rows
                if menu_item.get("is_section_title") or menu_item.get("is_station_header"):
                    continue

                food = menu_item.get("food")
                if not food or not food.get("id"):
                    continue

                food_row = parse_food(food)

                cur.execute("""
                    INSERT INTO menu_items (
                        nutrislice_food_id, name, description, ingredients, image_url,
                        calories, g_protein, g_carbs, g_fat, g_fiber,
                        g_saturated_fat, g_trans_fat, g_sugar, mg_sodium,
                        mg_cholesterol, mg_calcium,
                        is_vegan, is_meatless, is_halal, is_gluten_friendly, is_heart_healthy,
                        contains_milk, contains_eggs, contains_wheat, contains_peanuts,
                        contains_tree_nuts, contains_soy, contains_fish,
                        contains_shellfish, contains_sesame,
                        serving_size_amount, serving_size_unit, last_seen_at
                    ) VALUES (
                        %(nutrislice_food_id)s, %(name)s, %(description)s, %(ingredients)s, %(image_url)s,
                        %(calories)s, %(g_protein)s, %(g_carbs)s, %(g_fat)s, %(g_fiber)s,
                        %(g_saturated_fat)s, %(g_trans_fat)s, %(g_sugar)s, %(mg_sodium)s,
                        %(mg_cholesterol)s, %(mg_calcium)s,
                        %(is_vegan)s, %(is_meatless)s, %(is_halal)s, %(is_gluten_friendly)s, %(is_heart_healthy)s,
                        %(contains_milk)s, %(contains_eggs)s, %(contains_wheat)s, %(contains_peanuts)s,
                        %(contains_tree_nuts)s, %(contains_soy)s, %(contains_fish)s,
                        %(contains_shellfish)s, %(contains_sesame)s,
                        %(serving_size_amount)s, %(serving_size_unit)s, NOW()
                    )
                    ON CONFLICT (nutrislice_food_id) DO UPDATE SET
                        name                = EXCLUDED.name,
                        description         = EXCLUDED.description,
                        ingredients         = EXCLUDED.ingredients,
                        image_url           = EXCLUDED.image_url,
                        calories            = EXCLUDED.calories,
                        g_protein           = EXCLUDED.g_protein,
                        g_carbs             = EXCLUDED.g_carbs,
                        g_fat               = EXCLUDED.g_fat,
                        g_fiber             = EXCLUDED.g_fiber,
                        g_saturated_fat     = EXCLUDED.g_saturated_fat,
                        g_trans_fat         = EXCLUDED.g_trans_fat,
                        g_sugar             = EXCLUDED.g_sugar,
                        mg_sodium           = EXCLUDED.mg_sodium,
                        mg_cholesterol      = EXCLUDED.mg_cholesterol,
                        mg_calcium          = EXCLUDED.mg_calcium,
                        is_vegan            = EXCLUDED.is_vegan,
                        is_meatless         = EXCLUDED.is_meatless,
                        is_halal            = EXCLUDED.is_halal,
                        is_gluten_friendly  = EXCLUDED.is_gluten_friendly,
                        is_heart_healthy    = EXCLUDED.is_heart_healthy,
                        contains_milk       = EXCLUDED.contains_milk,
                        contains_eggs       = EXCLUDED.contains_eggs,
                        contains_wheat      = EXCLUDED.contains_wheat,
                        contains_peanuts    = EXCLUDED.contains_peanuts,
                        contains_tree_nuts  = EXCLUDED.contains_tree_nuts,
                        contains_soy        = EXCLUDED.contains_soy,
                        contains_fish       = EXCLUDED.contains_fish,
                        contains_shellfish  = EXCLUDED.contains_shellfish,
                        contains_sesame     = EXCLUDED.contains_sesame,
                        serving_size_amount = EXCLUDED.serving_size_amount,
                        serving_size_unit   = EXCLUDED.serving_size_unit,
                        last_seen_at        = NOW()
                    RETURNING id
                """, food_row)
                item_db_id = cur.fetchone()[0]
                items_upserted += 1

                # Resolve station from menu_id on the item
                item_menu_id = menu_item.get("menu_id")
                station_db_id = None
                if item_menu_id:
                    cur.execute("SELECT id FROM stations WHERE menu_id=%s", (item_menu_id,))
                    sr = cur.fetchone()
                    if sr:
                        station_db_id = sr[0]

                cur.execute("""
                    INSERT INTO menu_availability (item_id, dining_hall_id, station_id, available_date)
                    VALUES (%s, %s, %s, %s)
                    ON CONFLICT (item_id, dining_hall_id, available_date) DO NOTHING
                """, (item_db_id, hall_db_id, station_db_id, available_date))
                avail_upserted += 1

        conn.commit()
        return items_upserted, avail_upserted

# ── Entry point ───────────────────────────────────────────────────────────────
def main():
    monday = this_monday()
    log.info(f"Syncing week of {monday}")

    conn = psycopg2.connect(DB_URL)
    try:
        for hall in DINING_HALLS:
            for mt in MENU_TYPES:
                try:
                    week_data = fetch_week(hall["school_id"], mt["menu_type_id"], monday)
                    items, avail = sync_hall_meal(conn, hall["school_id"], mt["menu_type_id"], week_data)
                    log.info(f"{hall['name']} [{mt['meal_period']}]: {items} items, {avail} availability rows")
                except Exception as e:
                    conn.rollback()
                    log.error(f"Failed: {hall['name']} {mt['meal_period']}: {e}")
                import time; time.sleep(0.5)
    finally:
        conn.close()
    log.info("Sync complete")

if __name__ == "__main__":
    main()