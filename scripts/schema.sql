-- ============================================================
--  SmartPlate Database Schema
--  Updated with correct Nutrislice menu_type_ids
-- ============================================================

-- Dining halls
CREATE TABLE IF NOT EXISTS dining_halls (
    id              SERIAL PRIMARY KEY,
    school_id       INTEGER NOT NULL,
    name            VARCHAR(100) NOT NULL,
    menu_type_id    INTEGER NOT NULL,   -- 13832=breakfast, 13833=lunch/dinner
    meal_period     VARCHAR(20) NOT NULL, -- 'breakfast' | 'lunch_dinner'
    created_at      TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE (school_id, menu_type_id)
);

-- Station/section names from menu_info map
CREATE TABLE IF NOT EXISTS stations (
    id              SERIAL PRIMARY KEY,
    menu_id         INTEGER UNIQUE NOT NULL,
    dining_hall_id  INTEGER REFERENCES dining_halls(id),
    display_name    VARCHAR(100) NOT NULL
);

-- One row per unique food item (keyed by food.id from Nutrislice)
CREATE TABLE IF NOT EXISTS menu_items (
    id                  SERIAL PRIMARY KEY,
    nutrislice_food_id  INTEGER UNIQUE NOT NULL,
    name                VARCHAR(255) NOT NULL,
    description         TEXT,
    ingredients         TEXT,
    image_url           VARCHAR(500),

    -- Macros
    calories            FLOAT,
    g_protein           FLOAT,
    g_carbs             FLOAT,
    g_fat               FLOAT,
    g_fiber             FLOAT,
    g_saturated_fat     FLOAT,
    g_trans_fat         FLOAT,
    g_sugar             FLOAT,
    mg_sodium           FLOAT,
    mg_cholesterol      FLOAT,
    mg_calcium          FLOAT,

    -- Dietary tags (behavior=2)
    is_vegan            BOOLEAN DEFAULT FALSE,
    is_meatless         BOOLEAN DEFAULT FALSE,
    is_halal            BOOLEAN DEFAULT FALSE,
    is_gluten_friendly  BOOLEAN DEFAULT FALSE,
    is_heart_healthy    BOOLEAN DEFAULT FALSE,

    -- Allergens (behavior=1)
    contains_milk       BOOLEAN DEFAULT FALSE,
    contains_eggs       BOOLEAN DEFAULT FALSE,
    contains_wheat      BOOLEAN DEFAULT FALSE,
    contains_peanuts    BOOLEAN DEFAULT FALSE,
    contains_tree_nuts  BOOLEAN DEFAULT FALSE,
    contains_soy        BOOLEAN DEFAULT FALSE,
    contains_fish       BOOLEAN DEFAULT FALSE,
    contains_shellfish  BOOLEAN DEFAULT FALSE,
    contains_sesame     BOOLEAN DEFAULT FALSE,

    serving_size_amount VARCHAR(20),
    serving_size_unit   VARCHAR(20),

    created_at          TIMESTAMPTZ DEFAULT NOW(),
    last_seen_at        TIMESTAMPTZ DEFAULT NOW()
);

-- Which items appear on which date at which hall/station
CREATE TABLE IF NOT EXISTS menu_availability (
    id              SERIAL PRIMARY KEY,
    item_id         INTEGER REFERENCES menu_items(id),
    dining_hall_id  INTEGER REFERENCES dining_halls(id),
    station_id      INTEGER REFERENCES stations(id),
    available_date  DATE NOT NULL,
    UNIQUE (item_id, dining_hall_id, available_date)
);

-- Users
CREATE TABLE IF NOT EXISTS users (
    id               SERIAL PRIMARY KEY,
    email            VARCHAR(255) UNIQUE,
    pref_vegan       BOOLEAN DEFAULT FALSE,
    pref_meatless    BOOLEAN DEFAULT FALSE,
    pref_halal       BOOLEAN DEFAULT FALSE,
    pref_gluten_free BOOLEAN DEFAULT FALSE,
    avoid_milk       BOOLEAN DEFAULT FALSE,
    avoid_eggs       BOOLEAN DEFAULT FALSE,
    avoid_peanuts    BOOLEAN DEFAULT FALSE,
    avoid_tree_nuts  BOOLEAN DEFAULT FALSE,
    avoid_soy        BOOLEAN DEFAULT FALSE,
    avoid_fish       BOOLEAN DEFAULT FALSE,
    avoid_shellfish  BOOLEAN DEFAULT FALSE,
    avoid_sesame     BOOLEAN DEFAULT FALSE,
    avoid_wheat      BOOLEAN DEFAULT FALSE,
    created_at       TIMESTAMPTZ DEFAULT NOW()
);

-- Explicit ratings
CREATE TABLE IF NOT EXISTS ratings (
    id          SERIAL PRIMARY KEY,
    user_id     INTEGER REFERENCES users(id),
    item_id     INTEGER REFERENCES menu_items(id),
    score       FLOAT NOT NULL CHECK (score >= 0.5 AND score <= 5.0),
    created_at  TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE (user_id, item_id)
);

-- Event log
CREATE TABLE IF NOT EXISTS events (
    id              BIGSERIAL PRIMARY KEY,
    user_id         INTEGER REFERENCES users(id),
    item_id         INTEGER REFERENCES menu_items(id),
    dining_hall_id  INTEGER REFERENCES dining_halls(id),
    event_type      VARCHAR(20) NOT NULL,  -- 'view'|'click'|'rate'|'favorite'
    rating_value    FLOAT,
    created_at      TIMESTAMPTZ DEFAULT NOW()
);

-- Indexes
CREATE INDEX IF NOT EXISTS idx_events_user_id     ON events(user_id);
CREATE INDEX IF NOT EXISTS idx_events_item_id     ON events(item_id);
CREATE INDEX IF NOT EXISTS idx_events_created_at  ON events(created_at);
CREATE INDEX IF NOT EXISTS idx_availability_date  ON menu_availability(available_date);
CREATE INDEX IF NOT EXISTS idx_availability_hall  ON menu_availability(dining_hall_id, available_date);

-- Seed: each hall gets TWO rows — one per meal period
INSERT INTO dining_halls (school_id, name, menu_type_id, meal_period) VALUES
    (44401, 'Bolton Dining Commons',   13832, 'breakfast'),
    (44401, 'Bolton Dining Commons',   13833, 'lunch_dinner'),
    (44402, 'O-House Dining',          13832, 'breakfast'),
    (44402, 'O-House Dining',          13833, 'lunch_dinner'),
    (44403, 'Snelling Dining Commons', 13832, 'breakfast'),
    (44403, 'Snelling Dining Commons', 13833, 'lunch_dinner'),
    (44404, 'Niche',                   13832, 'breakfast'),
    (44404, 'Niche',                   13833, 'lunch_dinner'),
    (44405, 'Village Summit',          13832, 'breakfast'),
    (44405, 'Village Summit',          13833, 'lunch_dinner')
ON CONFLICT (school_id, menu_type_id) DO NOTHING;
