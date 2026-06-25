"""
Feature engineering for the reranker.

Each candidate item is represented by:
  - Item features: calories, protein, carbs, fat, fiber (normalized)
  - User-item affinity: co-occurrence score, trending score
  - Dietary match: booleans for vegan/halal/gluten-friendly vs user prefs
  - Position: rank from the upstream recommender (lower = stronger signal)
"""

import pandas as pd


def build_features(candidates: list[dict], user_profile: dict) -> pd.DataFrame:
    """
    candidates: list of dicts with keys:
        id, name, calories, g_protein, g_carbs, g_fat, g_fiber,
        is_vegan, is_meatless, is_halal, is_gluten_friendly,
        cooccur_score, trending_score, upstream_rank
    user_profile: dict with keys:
        pref_vegan, pref_meatless, pref_halal, pref_gluten_free,
        avg_calories, avg_protein, avg_carbs, avg_fat
    """
    rows = []
    for item in candidates:
        cal      = item.get("calories") or 0
        protein  = item.get("g_protein") or 0
        carbs    = item.get("g_carbs") or 0
        fat      = item.get("g_fat") or 0
        fiber    = item.get("g_fiber") or 0

        avg_cal  = user_profile.get("avg_calories") or 500
        avg_prot = user_profile.get("avg_protein") or 20

        rows.append({
            "item_id":               item["id"],
            # Nutrition deltas vs user's average (how close to their taste)
            "cal_delta":             abs(cal - avg_cal) / max(avg_cal, 1),
            "protein_delta":         abs(protein - avg_prot) / max(avg_prot, 1),
            # Raw nutrition
            "calories":              cal,
            "g_protein":             protein,
            "g_carbs":               carbs,
            "g_fat":                 fat,
            "g_fiber":               fiber,
            # Behavioral signals from upstream
            "cooccur_score":         item.get("cooccur_score") or 0.0,
            "trending_score":        item.get("trending_score") or 0.0,
            "upstream_rank":         item.get("upstream_rank", 99),
            # Global item quality — avg rating across all users (0 if unrated)
            "item_global_avg_rating": item.get("item_global_avg_rating") or 0.0,
            # Dietary match (1 = user wants this and item has it)
            "vegan_match":           int(user_profile.get("pref_vegan") and item.get("is_vegan")),
            "meatless_match":        int(user_profile.get("pref_meatless") and item.get("is_meatless")),
            "halal_match":           int(user_profile.get("pref_halal") and item.get("is_halal")),
            "gluten_match":          int(user_profile.get("pref_gluten_free") and item.get("is_gluten_friendly")),
        })

    return pd.DataFrame(rows)


FEATURE_COLS = [
    "cal_delta", "protein_delta",
    "calories", "g_protein", "g_carbs", "g_fat", "g_fiber",
    "cooccur_score", "trending_score", "upstream_rank",
    "item_global_avg_rating",
    "vegan_match", "meatless_match", "halal_match", "gluten_match",
]
