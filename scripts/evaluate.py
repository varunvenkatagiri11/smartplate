"""
SmartPlate Evaluation Script
==============================
Measures recommendation quality and latency against live API endpoints.

Usage:
    python scripts/evaluate.py                        # defaults
    python scripts/evaluate.py --users 50 --api http://localhost:8080
    python scripts/evaluate.py --users 100 --latency-requests 2000

Requires: pip install requests numpy psycopg2-binary
"""

import argparse
import random
import time
import statistics
import sys
from concurrent.futures import ThreadPoolExecutor, as_completed

import psycopg2
import requests
import numpy as np

# ── Config ────────────────────────────────────────────────────────────────────

HALL_IDS   = [44401, 44402, 44403, 44404, 44405]
DAYPARTS   = [0, 1, 2]   # 0=breakfast, 1=lunch, 2=dinner

# Maps Nutrislice school_id → database dining_halls.id (rows 1-10, two per hall)
SCHOOL_ID_TO_DB_ID = {
    44401: 1,
    44402: 3,
    44403: 5,
    44404: 7,
    44405: 9,
}

DIET_PROFILES = [
    {"pref_vegan": False, "pref_meatless": False, "pref_halal": False, "pref_gluten_free": False},
    {"pref_vegan": True,  "pref_meatless": True,  "pref_halal": False, "pref_gluten_free": False},
    {"pref_vegan": False, "pref_meatless": False, "pref_halal": True,  "pref_gluten_free": False},
    {"pref_vegan": False, "pref_meatless": True,  "pref_halal": False, "pref_gluten_free": True},
    {"pref_vegan": False, "pref_meatless": False, "pref_halal": False, "pref_gluten_free": True},
]

# ── Helpers ───────────────────────────────────────────────────────────────────

_event_errors = []

def post_event(api, user_id, item_id, hall_id, event_type, rating=None):
    payload = {
        "userId":      user_id,
        "itemId":      item_id,
        "diningHallId": hall_id,
        "eventType":   event_type,
        "ratingValue": rating,
    }
    try:
        r = requests.post(f"{api}/api/v1/events", json=payload, timeout=5)
        if r.status_code != 200 and len(_event_errors) < 3:
            _event_errors.append(f"userId={user_id} itemId={item_id}: HTTP {r.status_code} {r.text[:120]}")
        return r.status_code == 200
    except Exception as e:
        if len(_event_errors) < 3:
            _event_errors.append(str(e))
        return False


def get_menu(api, hall_id, meal_period=None):
    params = {"hallId": hall_id, "limit": 200}
    if meal_period:
        params["mealPeriod"] = meal_period
    try:
        r = requests.get(f"{api}/api/v1/menu", params=params, timeout=10)
        return r.json() if r.status_code == 200 else []
    except Exception:
        return []


def get_trending(api, hall_id, daypart, limit=10):
    try:
        r = requests.get(f"{api}/api/v1/recommendations/trending",
                         params={"hallId": hall_id, "daypart": daypart, "limit": limit},
                         timeout=5)
        return r.json() if r.status_code == 200 else []
    except Exception:
        return []


def get_foryou(api, user_id, hall_id, daypart, limit=10):
    try:
        r = requests.get(f"{api}/api/v1/recommendations/foryou",
                         params={"userId": user_id, "hallId": hall_id,
                                 "daypart": daypart, "limit": limit},
                         timeout=5)
        return r.json() if r.status_code == 200 else []
    except Exception:
        return []


def item_matches_prefs(item, prefs):
    """Returns True if item is compatible with user dietary preferences."""
    if prefs.get("pref_vegan")      and not item.get("is_vegan"):       return False
    if prefs.get("pref_meatless")   and not item.get("is_meatless"):    return False
    if prefs.get("pref_halal")      and not item.get("is_halal"):       return False
    if prefs.get("pref_gluten_free") and not item.get("is_gluten_friendly"): return False
    return True


# ── User registration ─────────────────────────────────────────────────────────

def register_synthetic_users(db_url, base_user_id, n_users):
    """
    Upsert synthetic users into the users table so FK constraints don't reject
    their events. Uses ON CONFLICT DO NOTHING so re-runs are idempotent.
    """
    conn = psycopg2.connect(db_url)
    cur  = conn.cursor()
    rows = []
    for i in range(n_users):
        uid   = base_user_id + i
        prefs = DIET_PROFILES[i % len(DIET_PROFILES)]
        email = f"synthetic_user_{uid}@eval.smartplate.local"
        rows.append((uid, email,
                     prefs["pref_vegan"], prefs["pref_meatless"],
                     prefs["pref_halal"], prefs["pref_gluten_free"]))

    cur.executemany("""
        INSERT INTO users (id, email, pref_vegan, pref_meatless, pref_halal, pref_gluten_free)
        VALUES (%s, %s, %s, %s, %s, %s)
        ON CONFLICT (id) DO NOTHING
    """, rows)
    conn.commit()
    cur.close()
    conn.close()
    return [base_user_id + i for i in range(n_users)]


# ── Part 1: Traffic Simulator ─────────────────────────────────────────────────

def simulate_user(api, user_id, prefs, menu_cache):
    """
    Simulate one user's full day of interactions.
    Returns count of events fired.
    """
    events = 0
    hall_id    = random.choice(HALL_IDS)
    db_hall_id = SCHOOL_ID_TO_DB_ID.get(hall_id, 1)

    # Morning: breakfast
    breakfast_items = menu_cache.get((hall_id, "breakfast"), [])
    compatible = [i for i in breakfast_items if item_matches_prefs(i, prefs)]
    if not compatible:
        compatible = breakfast_items  # fall back if no matches

    for item in random.sample(compatible, min(5, len(compatible))):
        iid = item.get("id") or item.get("item_id")
        if not iid:
            continue
        post_event(api, user_id, iid, db_hall_id, "view")
        events += 1
        if random.random() < 0.4:
            post_event(api, user_id, iid, db_hall_id, "click")
            events += 1
        if random.random() < 0.2:
            rating = random.choices([3, 4, 5], weights=[2, 3, 5])[0]
            post_event(api, user_id, iid, db_hall_id, "rate", float(rating))
            events += 1
        if random.random() < 0.1:
            post_event(api, user_id, iid, db_hall_id, "favorite")
            events += 1

    # Afternoon: lunch/dinner
    lunch_items = menu_cache.get((hall_id, "lunch_dinner"), [])
    compatible = [i for i in lunch_items if item_matches_prefs(i, prefs)]
    if not compatible:
        compatible = lunch_items

    for item in random.sample(compatible, min(8, len(compatible))):
        iid = item.get("id") or item.get("item_id")
        if not iid:
            continue
        post_event(api, user_id, iid, db_hall_id, "view")
        events += 1
        if random.random() < 0.5:
            post_event(api, user_id, iid, db_hall_id, "click")
            events += 1
        if random.random() < 0.3:
            rating = random.choices([2, 3, 4, 5], weights=[1, 2, 4, 5])[0]
            post_event(api, user_id, iid, db_hall_id, "rate", float(rating))
            events += 1
        if random.random() < 0.15:
            post_event(api, user_id, iid, db_hall_id, "favorite")
            events += 1

    return events


def run_simulation(api, n_users, base_user_id):
    print(f"\n[Part 1] Simulating {n_users} users (starting at userId={base_user_id})…")

    # Pre-fetch menus once (avoid N×M API calls)
    print("  Fetching menus for all halls…", end=" ", flush=True)
    menu_cache = {}
    for hall_id in HALL_IDS:
        for meal in ("breakfast", "lunch_dinner"):
            items = get_menu(api, hall_id, meal)
            menu_cache[(hall_id, meal)] = items
    total_items = sum(len(v) for v in menu_cache.values())
    print(f"done ({total_items} item-slots loaded)")

    if total_items == 0:
        print("  ⚠  No menu items found — run the Nutrislice sync first.")
        return 0, []

    total_events = 0
    user_ids = []
    with ThreadPoolExecutor(max_workers=10) as pool:
        futures = {}
        for i in range(n_users):
            uid   = base_user_id + i
            prefs = DIET_PROFILES[i % len(DIET_PROFILES)]
            f = pool.submit(simulate_user, api, uid, prefs, menu_cache)
            futures[f] = uid

        for done, f in enumerate(as_completed(futures), 1):
            uid = futures[f]
            count = f.result()
            total_events += count
            user_ids.append(uid)
            if done % 10 == 0 or done == n_users:
                print(f"  {done}/{n_users} users simulated ({total_events} events so far)")

    print(f"  ✓ Simulation complete — {total_events} events written")
    if _event_errors:
        print(f"  ⚠  First event errors (showing up to 3):")
        for err in _event_errors:
            print(f"     {err}")
    return total_events, user_ids


# ── Part 2: Precision@K ───────────────────────────────────────────────────────

def measure_precision(api, user_ids, k=10):
    """
    For each user:
      1. Get top-K algorithmic recommendations
      2. Get top-K LightGBM reranked recommendations (via /foryou — which calls ML service)
      3. Simulate a follow-up session and see which items they interact with
      4. Precision@K = fraction of top-K recs that appear in follow-up interactions
    """
    print(f"\n[Part 2] Measuring Precision@{k} over {len(user_ids)} users…")

    algo_precisions = []
    lgbm_precisions = []

    sample = random.sample(user_ids, min(30, len(user_ids)))

    for uid in sample:
        hall_id    = random.choice(HALL_IDS)
        db_hall_id = SCHOOL_ID_TO_DB_ID.get(hall_id, 1)
        daypart    = random.choice(DAYPARTS)

        # Algorithmic: trending (uses db_hall_id — Redis keys built from dining_halls.id)
        algo_recs = get_trending(api, db_hall_id, daypart, limit=k)
        algo_ids  = {r.get("id") or r.get("item_id") for r in algo_recs if r}

        # Personalized: for-you (uses db_hall_id — same Redis key convention)
        lgbm_recs = get_foryou(api, uid, db_hall_id, daypart, limit=k)
        lgbm_ids  = {r.get("id") or r.get("item_id") for r in lgbm_recs if r}

        # Simulate follow-up: user interacts with some items at this hall
        meal = "breakfast" if daypart == 0 else "lunch_dinner"
        items = get_menu(api, hall_id, meal)  # hall_id for Nutrislice menu lookup
        if not items:
            continue

        follow_up_sample = random.sample(items, min(15, len(items)))
        interacted = set()
        for item in follow_up_sample:
            iid = item.get("id") or item.get("item_id")
            if not iid:
                continue
            if random.random() < 0.35:   # 35% click/favorite rate on exposed items
                post_event(api, uid, iid, db_hall_id, "click")
                interacted.add(iid)
            if random.random() < 0.12:
                post_event(api, uid, iid, db_hall_id, "favorite")
                interacted.add(iid)

        if not interacted:
            continue

        if algo_ids:
            algo_precisions.append(len(algo_ids & interacted) / k)
        if lgbm_ids:
            lgbm_precisions.append(len(lgbm_ids & interacted) / k)

    p_algo = statistics.mean(algo_precisions) if algo_precisions else 0.0
    p_lgbm = statistics.mean(lgbm_precisions) if lgbm_precisions else 0.0
    return p_algo, p_lgbm


# ── Part 3: Latency ───────────────────────────────────────────────────────────

def measure_latency(api, n_requests, user_ids):
    print(f"\n[Part 3] Firing {n_requests} concurrent recommendation requests…")

    db_hall_ids = list(SCHOOL_ID_TO_DB_ID.values())
    endpoints = [
        lambda: requests.get(f"{api}/api/v1/recommendations/trending",
                             params={"hallId": random.choice(db_hall_ids),
                                     "daypart": random.choice(DAYPARTS), "limit": 10},
                             timeout=10),
        lambda: requests.get(f"{api}/api/v1/recommendations/foryou",
                             params={"userId": random.choice(user_ids),
                                     "hallId": random.choice(db_hall_ids),
                                     "daypart": random.choice(DAYPARTS), "limit": 10},
                             timeout=10),
        lambda: requests.get(f"{api}/api/v1/menu",
                             params={"hallId": random.choice(HALL_IDS), "limit": 50},
                             timeout=10),
    ]

    latencies_ms = []
    errors = 0

    def fire():
        fn = random.choice(endpoints)
        t0 = time.perf_counter()
        try:
            r = fn()
            elapsed = (time.perf_counter() - t0) * 1000
            return elapsed if r.status_code == 200 else None
        except Exception:
            return None

    with ThreadPoolExecutor(max_workers=50) as pool:
        futures = [pool.submit(fire) for _ in range(n_requests)]
        for done, f in enumerate(as_completed(futures), 1):
            result = f.result()
            if result is not None:
                latencies_ms.append(result)
            else:
                errors += 1
            if done % 200 == 0:
                print(f"  {done}/{n_requests} requests done…")

    if not latencies_ms:
        print("  ✗ All requests failed — is the API running?")
        return None

    latencies_ms.sort()
    return {
        "count":   len(latencies_ms),
        "errors":  errors,
        "median":  statistics.median(latencies_ms),
        "p95":     np.percentile(latencies_ms, 95),
        "p99":     np.percentile(latencies_ms, 99),
        "max":     max(latencies_ms),
    }


# ── Main ──────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description="SmartPlate evaluation suite")
    parser.add_argument("--api",              default="http://localhost:8080",
                        help="Spring Boot API base URL")
    parser.add_argument("--db",               default="postgresql://smartplate:smartplate@127.0.0.1:5432/smartplate",
                        help="Postgres connection URL (needed to register synthetic users)")
    parser.add_argument("--users",            type=int, default=50,
                        help="Number of synthetic users to simulate")
    parser.add_argument("--base-user-id",     type=int, default=1000,
                        help="Starting userId for synthetic users (avoid colliding with real users)")
    parser.add_argument("--latency-requests", type=int, default=1000,
                        help="Number of requests for latency measurement")
    parser.add_argument("--skip-simulation",  action="store_true",
                        help="Skip traffic simulation (use existing DB data)")
    parser.add_argument("--skip-latency",     action="store_true",
                        help="Skip latency measurement")
    parser.add_argument("--seed",             type=int, default=42)
    args = parser.parse_args()

    random.seed(args.seed)
    np.random.seed(args.seed)

    print("=" * 52)
    print("       SmartPlate Evaluation Suite")
    print("=" * 52)
    print(f"  API:              {args.api}")
    print(f"  Synthetic users:  {args.users}")
    print(f"  Latency requests: {args.latency_requests}")

    # Verify API is reachable
    try:
        r = requests.get(f"{args.api}/api/v1/menu/halls", timeout=5)
        halls = r.json()
        print(f"  API reachable:    ✓ ({len(halls)} hall rows)")
    except Exception as e:
        print(f"\n✗ Cannot reach API at {args.api}: {e}")
        print("  Make sure the Spring Boot app is running.")
        sys.exit(1)

    # ── Register synthetic users in DB (required before events can be written)
    print(f"\n[Setup] Registering {args.users} synthetic users in DB…", end=" ", flush=True)
    try:
        register_synthetic_users(args.db, args.base_user_id, args.users)
        print("✓")
    except Exception as e:
        print(f"\n✗ Could not register users: {e}")
        print("  Check --db connection string. Events will fail FK constraints without this.")
        sys.exit(1)

    # ── Part 1: Simulate
    if args.skip_simulation:
        print("\n[Part 1] Skipping simulation (--skip-simulation)")
        user_ids = list(range(args.base_user_id, args.base_user_id + args.users))
        total_events = 0
    else:
        total_events, user_ids = run_simulation(args.api, args.users, args.base_user_id)

    if not user_ids:
        user_ids = list(range(args.base_user_id, args.base_user_id + args.users))

    # Brief pause so Redis consumers process the stream before we measure
    if total_events > 0:
        print("\n  Waiting 3s for stream consumers to process events…")
        time.sleep(3)

    # ── Part 2: Precision
    p_algo, p_lgbm = measure_precision(args.api, user_ids)

    # ── Part 3: Latency
    if args.skip_latency:
        print("\n[Part 3] Skipping latency measurement (--skip-latency)")
        lat = None
    else:
        lat = measure_latency(args.api, args.latency_requests, user_ids)

    # ── Results
    improvement = ((p_lgbm - p_algo) / p_algo * 100) if p_algo > 0 else 0.0
    p95_ok = lat is not None and lat["p95"] < 50

    print("\n" + "=" * 52)
    print("       SmartPlate Evaluation Results")
    print("=" * 52)
    print(f"  Synthetic users:        {args.users}")
    print(f"  Total events generated: {total_events:,}")
    print()
    print("  Recommendation Quality:")
    print(f"    Precision@10 (algorithmic):  {p_algo:.2f}")
    print(f"    Precision@10 (LightGBM):     {p_lgbm:.2f}")
    if improvement >= 0:
        print(f"    Improvement:                 +{improvement:.0f}%")
    else:
        print(f"    Improvement:                 {improvement:.0f}%")
    print()

    if lat:
        print(f"  Latency ({lat['count']:,} successful / {lat['errors']} errors):")
        print(f"    Median:  {lat['median']:.0f}ms")
        print(f"    p95:     {lat['p95']:.0f}ms")
        print(f"    p99:     {lat['p99']:.0f}ms")
        print(f"    Max:     {lat['max']:.0f}ms")
        print()
        if p95_ok:
            print("  All targets met ✓ (p95 < 50ms)")
        else:
            print(f"  ✗ p95 target missed ({lat['p95']:.0f}ms > 50ms)")
    print("=" * 52)


if __name__ == "__main__":
    main()
