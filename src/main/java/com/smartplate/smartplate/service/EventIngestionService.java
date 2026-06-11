package com.smartplate.smartplate.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * ─── Redis Key Reference ────────────────────────────────────────────────────
 *
 *  events:stream
 *      Redis Stream — all raw user interaction events. Consumer groups read from here.
 *
 *  trending:{hallId}:{daypart}         e.g. trending:1:lunch
 *      Sorted set — item_id → time-decayed score.
 *      Daypart: 0=breakfast 1=lunch 2=dinner
 *      ZREVRANGE gives trending feed in O(log N).
 *
 *  cooccur:{itemId}                    e.g. cooccur:42
 *      Sorted set — neighbor_item_id → co-occurrence count.
 *      "People Also Ate" neighbors. Capped at top 500.
 *
 *  session:{userId}                    e.g. session:7
 *      List — recent item_ids, newest first. LPUSH + LTRIM to 20.
 *
 *  item:hot:{itemDbId}                 e.g. item:hot:42
 *      String (JSON) — cached item details. TTL 36h, refreshed on sync.
 *
 *  recs:foryou:{userId}                e.g. recs:foryou:7
 *      String (JSON) — cached personalized rec list. TTL 2 min.
 *
 * ─── Event Types ────────────────────────────────────────────────────────────
 *  view     → base weight 1  (user saw the item in the feed)
 *  click    → base weight 2  (user tapped to see detail)
 *  rate     → base weight = rating * 0.6  (explicit signal, 0.5–5.0 stars)
 *  favorite → base weight 5  (strongest signal)
 *
 * ─── Time Decay ─────────────────────────────────────────────────────────────
 *  score = base_weight × e^(-λ × minutes_ago)   λ=0.005 → half-life ~2.3 hours
 *  Applied in TrendingConsumer, not here.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventIngestionService {

    public static final String STREAM_KEY = "events:stream";

    private final RedisTemplate<String, String> redis;
    private final JdbcTemplate jdbc;

    public record IngestRequest(
        int userId,
        int itemId,
        int diningHallId,
        String eventType,   // "view" | "click" | "rate" | "favorite"
        Float ratingValue   // only for eventType="rate", else null
    ) {}

    /**
     * Ingest a user interaction event.
     * 1. Async-persist to Postgres event log.
     * 2. Push to Redis Stream for consumer workers.
     * Returns the Redis stream entry ID.
     */
    public String ingest(IngestRequest req) {
        validateEventType(req.eventType());

        // Async Postgres write (fire-and-forget for latency)
        jdbc.update("""
            INSERT INTO events (user_id, item_id, dining_hall_id, event_type, rating_value, created_at)
            VALUES (?, ?, ?, ?, ?, NOW())
            """,
            req.userId(), req.itemId(), req.diningHallId(),
            req.eventType(), req.ratingValue());

        // Push to Redis Stream
        Map<String, String> fields = new java.util.LinkedHashMap<>();
        fields.put("userId",       String.valueOf(req.userId()));
        fields.put("itemId",       String.valueOf(req.itemId()));
        fields.put("hallId",       String.valueOf(req.diningHallId()));
        fields.put("eventType",    req.eventType());
        fields.put("ratingValue",  req.ratingValue() == null ? "" : String.valueOf(req.ratingValue()));
        fields.put("ts",           String.valueOf(System.currentTimeMillis()));

        MapRecord<String, String, String> record = StreamRecords.newRecord()
            .in(STREAM_KEY)
            .ofMap(fields);

        var entryId = redis.opsForStream().add(record);
        log.debug("Event {} → stream entry {}", req.eventType(), entryId);
        return entryId.toString();
    }

    private void validateEventType(String eventType) {
        if (!java.util.Set.of("view", "click", "rate", "favorite").contains(eventType)) {
            throw new IllegalArgumentException("Unknown event type: " + eventType);
        }
    }
}
