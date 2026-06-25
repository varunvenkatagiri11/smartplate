package com.smartplate.smartplate.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendationService {

    private static final int    COLD_START_THRESHOLD = 3;
    private static final int    SESSION_LOOKBACK      = 10;
    private static final double W_COOCCUR   = 0.5;
    private static final double W_NUTRITION = 0.3;
    private static final double W_TRENDING  = 0.2;

    private final RedisTemplate<String, String> redis;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    @Value("${ml.reranker.url:http://localhost:8001}")
    private String rerankerUrl;

    // ── 1. Trending ──────────────────────────────────────────────────────────

    public List<Map<String, Object>> getTrending(int hallId, int daypart, int limit) {
        String key = "trending:" + hallId + ":" + daypart;
        Set<String> itemIds = redis.opsForZSet().reverseRange(key, 0, limit - 1);
        if (itemIds == null || itemIds.isEmpty()) return List.of();
        return hydrateItems(new ArrayList<>(itemIds), hallId);
    }

    // ── 2. Similar ("People Also Ate") ──────────────────────────────────────

    public List<Map<String, Object>> getSimilar(int itemId, int hallId, int limit) {
        String key = "cooccur:" + itemId;
        Set<String> neighbors = redis.opsForZSet().reverseRange(key, 0, limit * 3L - 1);
        if (neighbors == null || neighbors.isEmpty()) return List.of();

        List<String> available = filterAvailableToday(new ArrayList<>(neighbors), hallId);
        List<String> deduped   = deduplicate(available, List.of(String.valueOf(itemId)));
        return hydrateItems(deduped.stream().limit(limit).collect(Collectors.toList()), hallId);
    }

    // ── 3. For You ───────────────────────────────────────────────────────────

    public List<Map<String, Object>> getForYou(int userId, int hallId, int daypart, int limit) {
        String cacheKey = "recs:foryou:" + userId;
        String cached   = redis.opsForValue().get(cacheKey);
        if (cached != null) {
            try {
                return objectMapper.readValue(cached, List.class);
            } catch (Exception ignored) {}
        }

        int interactionCount = countInteractions(userId);
        List<Map<String, Object>> result;

        if (interactionCount < COLD_START_THRESHOLD) {
            result = getTrending(hallId, daypart, limit);
        } else {
            result = buildPersonalized(userId, hallId, daypart, limit);
            if (result.size() < limit) {
                List<Map<String, Object>> trending = getTrending(hallId, daypart, limit - result.size());
                Set<Object> seen = result.stream().map(m -> m.get("id")).collect(Collectors.toSet());
                trending.stream()
                    .filter(m -> !seen.contains(m.get("id")))
                    .forEach(result::add);
            }
        }

        try {
            redis.opsForValue().set(cacheKey, objectMapper.writeValueAsString(result), 2, TimeUnit.MINUTES);
        } catch (Exception ignored) {}

        return result;
    }

    private List<Map<String, Object>> buildPersonalized(int userId, int hallId, int daypart, int limit) {
        List<String> sessionItems = getSession(userId);
        if (sessionItems.isEmpty()) return getTrending(hallId, daypart, limit);

        // Gather co-occurrence candidates with scores
        Map<String, Double> cooccurScores = new HashMap<>();
        for (String seedItemId : sessionItems.subList(0, Math.min(SESSION_LOOKBACK, sessionItems.size()))) {
            Set<org.springframework.data.redis.core.ZSetOperations.TypedTuple<String>> neighbors =
                redis.opsForZSet().reverseRangeWithScores("cooccur:" + seedItemId, 0, 29);
            if (neighbors == null) continue;
            for (var entry : neighbors) {
                if (entry.getValue() == null) continue;
                cooccurScores.merge(entry.getValue(),
                    (entry.getScore() == null ? 0 : entry.getScore()), Double::sum);
            }
        }

        if (cooccurScores.isEmpty()) return getTrending(hallId, daypart, limit);

        List<String> candidates = filterAvailableToday(new ArrayList<>(cooccurScores.keySet()), hallId);
        candidates = deduplicate(candidates, sessionItems);
        candidates = applyDietaryFilter(candidates, userId);

        if (candidates.isEmpty()) return getTrending(hallId, daypart, limit);

        double maxCooccur = cooccurScores.values().stream().mapToDouble(Double::doubleValue).max().orElse(1.0);
        double[] userMacros = getUserMacroProfile(userId);
        Map<String, Double> trendingScores = getTrendingScores(hallId, daypart, candidates);
        double maxTrend = trendingScores.values().stream().mapToDouble(Double::doubleValue).max().orElse(1.0);

        // Blend scores and rank
        Map<String, Double> blended = new HashMap<>();
        for (String candidateId : candidates) {
            double coScore    = (cooccurScores.getOrDefault(candidateId, 0.0) / maxCooccur) * W_COOCCUR;
            double nutrScore  = getNutritionSimilarity(Integer.parseInt(candidateId), userMacros) * W_NUTRITION;
            double trendScore = (trendingScores.getOrDefault(candidateId, 0.0) / Math.max(maxTrend, 1.0)) * W_TRENDING;
            blended.put(candidateId, coScore + nutrScore + trendScore);
        }

        List<String> ranked = blended.entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .limit(limit * 2L)   // fetch extra so reranker has more to work with
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());

        List<Map<String, Object>> hydrated = hydrateItems(ranked, hallId);

        // Attach runtime signals needed by the reranker
        for (int i = 0; i < hydrated.size(); i++) {
            Map<String, Object> item = hydrated.get(i);
            String itemId = String.valueOf(item.get("id"));
            item.put("upstream_rank",   i);
            item.put("cooccur_score",   cooccurScores.getOrDefault(itemId, 0.0));
            item.put("trending_score",  trendingScores.getOrDefault(itemId, 0.0));
            item.put("item_global_avg_rating", getGlobalAvgRating(Integer.parseInt(itemId)));
        }

        List<Map<String, Object>> reranked = callReranker(hydrated, userId);
        return reranked.stream().limit(limit).collect(Collectors.toList());
    }

    // ── ML reranker ──────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> callReranker(List<Map<String, Object>> candidates, int userId) {
        try {
            Map<String, Object> userProfile = buildUserProfile(userId);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("candidates",    candidates);
            body.put("user_profile",  userProfile);

            RestClient client = RestClient.create();
            Map<String, Object> response = client.post()
                .uri(rerankerUrl + "/rerank")
                .header("Content-Type", "application/json")
                .body(objectMapper.writeValueAsString(body))
                .retrieve()
                .body(Map.class);

            if (response != null && response.containsKey("items")) {
                return (List<Map<String, Object>>) response.get("items");
            }
        } catch (Exception e) {
            log.warn("Reranker unavailable ({}), using upstream order", e.getMessage());
        }
        return candidates;
    }

    private Map<String, Object> buildUserProfile(int userId) {
        Map<String, Object> profile = new HashMap<>();
        try {
            Map<String, Object> prefs = jdbc.queryForMap(
                "SELECT pref_vegan, pref_meatless, pref_halal, pref_gluten_free FROM users WHERE id = ?", userId);
            profile.putAll(prefs);
        } catch (Exception ignored) {}

        try {
            Map<String, Object> macros = jdbc.queryForMap("""
                SELECT AVG(mi.calories)  AS avg_calories,
                       AVG(mi.g_protein) AS avg_protein,
                       AVG(mi.g_carbs)   AS avg_carbs,
                       AVG(mi.g_fat)     AS avg_fat
                FROM events e
                JOIN menu_items mi ON mi.id = e.item_id
                WHERE e.user_id = ? AND e.event_type IN ('favorite', 'rate')
                """, userId);
            profile.putAll(macros);
        } catch (Exception ignored) {}

        return profile;
    }

    private double getGlobalAvgRating(int itemId) {
        try {
            Double avg = jdbc.queryForObject(
                "SELECT AVG(rating_value) FROM events WHERE item_id = ? AND event_type = 'rate' AND rating_value IS NOT NULL",
                Double.class, itemId);
            return avg == null ? 0.0 : avg;
        } catch (Exception e) {
            return 0.0;
        }
    }

    // ── Nutrition cosine similarity ──────────────────────────────────────────

    private double getNutritionSimilarity(int itemId, double[] userVector) {
        if (userVector == null) return 0.0;
        try {
            Map<String, Object> row = jdbc.queryForMap(
                "SELECT g_protein, g_carbs, g_fat, g_fiber FROM menu_items WHERE id = ?", itemId);
            double[] itemVec = {
                toDouble(row.get("g_protein")),
                toDouble(row.get("g_carbs")),
                toDouble(row.get("g_fat")),
                toDouble(row.get("g_fiber"))
            };
            return cosineSimilarity(userVector, itemVec);
        } catch (Exception e) {
            return 0.0;
        }
    }

    private double[] getUserMacroProfile(int userId) {
        try {
            Map<String, Object> row = jdbc.queryForMap("""
                SELECT AVG(mi.g_protein) AS p, AVG(mi.g_carbs) AS c,
                       AVG(mi.g_fat) AS f, AVG(mi.g_fiber) AS fi
                FROM ratings r
                JOIN menu_items mi ON mi.id = r.item_id
                WHERE r.user_id = ? AND r.score >= 3.5
                """, userId);
            if (row.get("p") == null) return null;
            return new double[]{toDouble(row.get("p")), toDouble(row.get("c")),
                                toDouble(row.get("f")), toDouble(row.get("fi"))};
        } catch (Exception e) {
            return null;
        }
    }

    private double cosineSimilarity(double[] a, double[] b) {
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot   += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        return (normA == 0 || normB == 0) ? 0.0 : dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private List<String> getSession(int userId) {
        List<String> session = redis.opsForList().range("session:" + userId, 0, -1);
        return session == null ? List.of() : session;
    }

    private int countInteractions(int userId) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM events WHERE user_id = ?", Integer.class, userId);
        return count == null ? 0 : count;
    }

    private List<String> filterAvailableToday(List<String> itemIds, int hallId) {
        if (itemIds.isEmpty()) return List.of();
        String placeholders = itemIds.stream().map(id -> "?").collect(Collectors.joining(","));
        List<Object> params = new ArrayList<>(itemIds);
        params.add(LocalDate.now().toString());
        params.add(hallId);
        return jdbc.queryForList(
            "SELECT item_id::text FROM menu_availability WHERE item_id::text IN (" + placeholders
            + ") AND available_date = ?::date AND dining_hall_id = ?",
            String.class, params.toArray());
    }

    private List<String> applyDietaryFilter(List<String> itemIds, int userId) {
        if (itemIds.isEmpty()) return List.of();
        try {
            Map<String, Object> prefs = jdbc.queryForMap(
                "SELECT pref_vegan, pref_meatless, pref_halal, pref_gluten_free, " +
                "avoid_milk, avoid_eggs, avoid_peanuts, avoid_tree_nuts, " +
                "avoid_soy, avoid_fish, avoid_shellfish, avoid_sesame, avoid_wheat " +
                "FROM users WHERE id = ?", userId);

            String where = buildDietaryWhere(prefs);
            if (where.isEmpty()) return itemIds;

            String placeholders = itemIds.stream().map(id -> "?").collect(Collectors.joining(","));
            return jdbc.queryForList(
                "SELECT id::text FROM menu_items WHERE id IN (" + placeholders + ") AND " + where,
                String.class, itemIds.toArray());
        } catch (Exception e) {
            return itemIds;
        }
    }

    private String buildDietaryWhere(Map<String, Object> prefs) {
        List<String> clauses = new ArrayList<>();
        if (Boolean.TRUE.equals(prefs.get("pref_vegan")))       clauses.add("is_vegan = true");
        if (Boolean.TRUE.equals(prefs.get("pref_meatless")))     clauses.add("is_meatless = true");
        if (Boolean.TRUE.equals(prefs.get("pref_halal")))        clauses.add("is_halal = true");
        if (Boolean.TRUE.equals(prefs.get("pref_gluten_free")))  clauses.add("is_gluten_friendly = true");
        if (Boolean.TRUE.equals(prefs.get("avoid_milk")))        clauses.add("contains_milk = false");
        if (Boolean.TRUE.equals(prefs.get("avoid_eggs")))        clauses.add("contains_eggs = false");
        if (Boolean.TRUE.equals(prefs.get("avoid_peanuts")))     clauses.add("contains_peanuts = false");
        if (Boolean.TRUE.equals(prefs.get("avoid_tree_nuts")))   clauses.add("contains_tree_nuts = false");
        if (Boolean.TRUE.equals(prefs.get("avoid_soy")))         clauses.add("contains_soy = false");
        if (Boolean.TRUE.equals(prefs.get("avoid_fish")))        clauses.add("contains_fish = false");
        if (Boolean.TRUE.equals(prefs.get("avoid_shellfish")))   clauses.add("contains_shellfish = false");
        if (Boolean.TRUE.equals(prefs.get("avoid_sesame")))      clauses.add("contains_sesame = false");
        if (Boolean.TRUE.equals(prefs.get("avoid_wheat")))       clauses.add("contains_wheat = false");
        return String.join(" AND ", clauses);
    }

    private Map<String, Double> getTrendingScores(int hallId, int daypart, List<String> itemIds) {
        Set<org.springframework.data.redis.core.ZSetOperations.TypedTuple<String>> all =
            redis.opsForZSet().reverseRangeWithScores("trending:" + hallId + ":" + daypart, 0, -1);
        Map<String, Double> scores = new HashMap<>();
        if (all == null) return scores;
        Set<String> candidates = new HashSet<>(itemIds);
        for (var entry : all) {
            if (entry.getValue() != null && candidates.contains(entry.getValue())) {
                scores.put(entry.getValue(), entry.getScore() == null ? 0.0 : entry.getScore());
            }
        }
        return scores;
    }

    private List<String> deduplicate(List<String> candidates, List<String> exclude) {
        Set<String> excluded = new HashSet<>(exclude);
        return candidates.stream().filter(id -> !excluded.contains(id)).distinct().collect(Collectors.toList());
    }

    private List<Map<String, Object>> hydrateItems(List<String> itemIds, int hallId) {
        if (itemIds.isEmpty()) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (String id : itemIds) {
            String cached = redis.opsForValue().get("item:hot:" + id);
            if (cached != null) {
                try {
                    result.add(objectMapper.readValue(cached, Map.class));
                    continue;
                } catch (Exception ignored) {}
            }
            try {
                Map<String, Object> row = jdbc.queryForMap("""
                    SELECT mi.id, mi.name, s.display_name AS station,
                           mi.image_url, mi.calories, mi.g_protein, mi.g_carbs,
                           mi.g_fat, mi.g_fiber, mi.is_vegan, mi.is_meatless,
                           mi.is_halal, mi.is_gluten_friendly
                    FROM menu_items mi
                    LEFT JOIN menu_availability ma ON ma.item_id = mi.id
                        AND ma.dining_hall_id = ?
                        AND ma.available_date = CURRENT_DATE
                    LEFT JOIN stations s ON s.id = ma.station_id
                    WHERE mi.id = ?
                    LIMIT 1
                    """, hallId, Integer.parseInt(id));
                result.add(new HashMap<>(row));   // mutable copy so callers can add extra fields
            } catch (Exception e) {
                log.warn("Failed to hydrate item {}: {}", id, e.getMessage());
            }
        }
        return result;
    }

    private double toDouble(Object val) {
        if (val == null) return 0.0;
        return ((Number) val).doubleValue();
    }
}
