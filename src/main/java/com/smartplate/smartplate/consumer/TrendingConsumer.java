package com.smartplate.smartplate.consumer;

import com.smartplate.smartplate.service.EventIngestionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.*;
import java.util.List;
import java.util.Map;

/**
 * Reads from the Redis Stream and updates the trending sorted sets.
 *
 * Key: trending:{hallId}:{daypart}
 *   where daypart: 0=breakfast (6-11), 1=lunch (11-16), 2=dinner (16-22)
 *
 * Score formula: base_weight × e^(-0.005 × minutes_since_event)
 *   λ=0.005 → half-life ~2.3 hours, so morning rush doesn't dominate dinner
 *
 * Cleanup job runs every 10 minutes and removes items with score < 0.01
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TrendingConsumer {

    private static final String GROUP    = "trending-group";
    private static final String CONSUMER = "trending-consumer-1";
    private static final double LAMBDA   = 0.005;  // decay constant

    private final RedisTemplate<String, String> redis;

    @Scheduled(fixedDelay = 500)  // poll every 500ms
    public void consume() {
        try {
            List<MapRecord<String, Object, Object>> records = redis.opsForStream()
                .read(Consumer.from(GROUP, CONSUMER),
                      StreamReadOptions.empty().count(50),
                      StreamOffset.create(EventIngestionService.STREAM_KEY, ReadOffset.lastConsumed()));

            if (records == null) return;

            for (MapRecord<String, Object, Object> record : records) {
                Map<Object, Object> fields = record.getValue();
                processEvent(fields);
                redis.opsForStream().acknowledge(EventIngestionService.STREAM_KEY, GROUP, record.getId());
            }
        } catch (Exception e) {
            log.error("TrendingConsumer error: {}", e.getMessage());
        }
    }

    private void processEvent(Map<Object, Object> fields) {
        String ts = str(fields, "ts");
        if (ts.isEmpty()) return;  // skip init/dummy entries

        String eventType = str(fields, "eventType");
        String itemId    = str(fields, "itemId");
        String hallId    = str(fields, "hallId");
        String ratingVal = str(fields, "ratingValue");

        double baseWeight = switch (eventType) {
            case "view"     -> 1.0;
            case "click"    -> 2.0;
            case "rate"     -> parseRating(ratingVal) * 0.6;
            case "favorite" -> 5.0;
            default         -> 0.0;
        };
        if (baseWeight == 0.0) return;

        long minutesAgo = (System.currentTimeMillis() - Long.parseLong(ts)) / 60_000;
        double score    = baseWeight * Math.exp(-LAMBDA * minutesAgo);

        int daypart     = currentDaypart();
        String trendKey = "trending:" + hallId + ":" + daypart;

        redis.opsForZSet().incrementScore(trendKey, itemId, score);
    }

    /** Remove decayed-out items every 10 minutes to keep sets bounded. */
    @Scheduled(fixedDelay = 600_000)
    public void cleanup() {
        // Remove items with score below threshold from all trending keys
        // ZREMRANGEBYSCORE key -inf 0.01
        // In practice: iterate known hall/daypart combos
        for (int hallId = 1; hallId <= 5; hallId++) {
            for (int daypart = 0; daypart <= 2; daypart++) {
                String key = "trending:" + hallId + ":" + daypart;
                Long removed = redis.opsForZSet().removeRangeByScore(key, Double.NEGATIVE_INFINITY, 0.01);
                if (removed != null && removed > 0) {
                    log.debug("Cleaned {} stale entries from {}", removed, key);
                }
            }
        }
    }

    private int currentDaypart() {
        int hour = LocalTime.now(ZoneId.of("America/New_York")).getHour();
        if (hour < 11) return 0;       // breakfast
        if (hour < 16) return 1;       // lunch
        return 2;                       // dinner
    }

    private double parseRating(String val) {
        try { return Double.parseDouble(val); } catch (Exception e) { return 3.0; }
    }

    private String str(Map<Object, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? "" : v.toString();
    }
}
