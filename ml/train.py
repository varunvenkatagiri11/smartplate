"""
Train a LightGBM ranker on historical event data.

Label: implicit relevance score per (user, item) pair:
  favorite → 3, rate≥4 → 2, rate<4 → 1, click → 0.5, view → 0

Run:
  python train.py --db postgresql://smartplate:smartplate@localhost:5432/smartplate
"""

import argparse
import pickle
import os

import lightgbm as lgb
import numpy as np
import pandas as pd
import psycopg2
from sklearn.model_selection import GroupShuffleSplit

from features import build_features, FEATURE_COLS


EVENT_WEIGHTS = {
    "favorite":   3.0,
    "rate":       None,   # handled separately using rating_value
    "click":      0.5,
    "view":       0.0,
    "unfavorite": -1.0,
}


def load_training_data(db_url: str, min_user_id: int, max_user_id: int) -> pd.DataFrame:
    conn = psycopg2.connect(db_url)
    cur  = conn.cursor()

    # Pull events with item features and user prefs
    cur.execute("""
        SELECT
            e.user_id, e.item_id, e.event_type, e.rating_value,
            mi.calories, mi.g_protein, mi.g_carbs, mi.g_fat, mi.g_fiber,
            mi.is_vegan, mi.is_meatless, mi.is_halal, mi.is_gluten_friendly,
            u.pref_vegan, u.pref_meatless, u.pref_halal, u.pref_gluten_free,
            COALESCE(r.avg_rating, 0.0) AS item_global_avg_rating
        FROM events e
        JOIN menu_items mi ON mi.id = e.item_id
        JOIN users u ON u.id = e.user_id
        LEFT JOIN (
            SELECT item_id, AVG(rating_value) AS avg_rating
            FROM events
            WHERE event_type = 'rate' AND rating_value IS NOT NULL
            GROUP BY item_id
        ) r ON r.item_id = e.item_id
        WHERE u.id BETWEEN %s AND %s
        ORDER BY e.user_id, e.created_at
    """, (min_user_id, max_user_id))
    rows = cur.fetchall()
    cols = [d[0] for d in cur.description]
    cur.close()
    conn.close()

    return pd.DataFrame(rows, columns=cols)


def compute_label(row) -> int:
    etype = row["event_type"]
    if etype == "rate" and row["rating_value"] is not None:
        # rating 1–5 → label 0–4 (integer gains required by lambdarank)
        return max(0, round(float(row["rating_value"])) - 1)
    weights = {"favorite": 4, "click": 1, "view": 0, "unfavorite": 0}
    return weights.get(etype, 0)


def train(db_url: str, output_path: str, min_user_id: int = 0, max_user_id: int = 999999):
    print("Loading training data…")
    df = load_training_data(db_url, min_user_id, max_user_id)
    if df.empty:
        print("No training data found — need at least some events in the DB.")
        return

    df["label"] = df.apply(compute_label, axis=1)

    # Build per-row user profile (avg macros from their rated/favorited items)
    user_profiles = (
        df[df["label"] >= 2]  # rate≥3 or favorite
        .groupby("user_id")
        .agg(avg_calories=("calories", "mean"), avg_protein=("g_protein", "mean"),
             avg_carbs=("g_carbs", "mean"), avg_fat=("g_fat", "mean"))
        .reset_index()
    )

    df = df.merge(user_profiles, on="user_id", how="left")
    df[["avg_calories", "avg_protein", "avg_carbs", "avg_fat"]] = \
        df[["avg_calories", "avg_protein", "avg_carbs", "avg_fat"]].fillna(0)

    # Build features row by row (reuse build_features logic inline for training)
    avg_cal_col  = df["avg_calories"].fillna(500)
    avg_prot_col = df["avg_protein"].fillna(20)

    df["cal_delta"]      = (df["calories"] - avg_cal_col).abs() / avg_cal_col.replace(0, 1)
    df["protein_delta"]  = (df["g_protein"] - avg_prot_col).abs() / avg_prot_col.replace(0, 1)
    df["cooccur_score"]          = 0.0   # not available at training time
    df["trending_score"]         = 0.0   # not available at training time
    df["upstream_rank"]          = 0
    # item_global_avg_rating is already in df from the SQL join
    df["vegan_match"]    = (df["pref_vegan"] & df["is_vegan"]).astype(int)
    df["meatless_match"] = (df["pref_meatless"] & df["is_meatless"]).astype(int)
    df["halal_match"]    = (df["pref_halal"] & df["is_halal"]).astype(int)
    df["gluten_match"]   = (df["pref_gluten_free"] & df["is_gluten_friendly"]).astype(int)

    X = df[FEATURE_COLS].values
    y = df["label"].values
    groups = df["user_id"].values

    # Group-aware train/val split (hold out some users entirely)
    splitter = GroupShuffleSplit(n_splits=1, test_size=0.2, random_state=42)
    train_idx, val_idx = next(splitter.split(X, y, groups))

    # Count queries per user for LambdaRank
    def query_counts(idx):
        return df.iloc[idx].groupby("user_id").size().values

    train_data = lgb.Dataset(X[train_idx], label=y[train_idx],
                             group=query_counts(train_idx),
                             feature_name=FEATURE_COLS)
    val_data   = lgb.Dataset(X[val_idx], label=y[val_idx],
                             group=query_counts(val_idx),
                             feature_name=FEATURE_COLS, reference=train_data)

    params = {
        "objective":    "lambdarank",
        "metric":       "ndcg",
        "ndcg_eval_at": [5, 10],
        "learning_rate": 0.05,
        "num_leaves":   31,
        "min_data_in_leaf": 5,
        "verbosity":    -1,
    }

    print("Training LightGBM ranker…")
    model = lgb.train(
        params,
        train_data,
        num_boost_round=200,
        valid_sets=[val_data],
        callbacks=[lgb.early_stopping(20), lgb.log_evaluation(20)],
    )

    with open(output_path, "wb") as f:
        pickle.dump(model, f)
    print(f"Model saved to {output_path}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--db", default=os.getenv("DATABASE_URL",
                        "postgresql://smartplate:smartplate@localhost:5432/smartplate"))
    parser.add_argument("--output", default="model.pkl")
    parser.add_argument("--min-user-id", type=int, default=0)
    parser.add_argument("--max-user-id", type=int, default=999999)
    args = parser.parse_args()
    train(args.db, args.output, args.min_user_id, args.max_user_id)
