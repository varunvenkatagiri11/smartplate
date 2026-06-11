package com.smartplate.smartplate.consumer;

import com.smartplate.smartplate.service.EventIngestionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds item-item co-occurrence for "People Also Ate".
 *
 * Key: cooccur:{itemId}  → sorted set of neighbor item_ids by co-occurrence count
 * Capped at top-500 neighbors per item to bound memory.
 *
 * Only triggered on rate/favorite events (strong signals).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CoOccurrenceConsumer {

    private static final String GROUP    = "cooccur-group";
    private static final String CONSUMER = "cooccur-consumer-1";
    private static final long   MAX_NEIGHBORS = 500;

    private final RedisTemplate<String, String> redis;

    @Scheduled(fixedDelay = 500)
    public void consume() {
        try {
            List<MapRecord<String, Object, Object>> records = redis.opsForStream()
                    .read(Consumer.from(GROUP, CONSUMER),
                            StreamReadOptions.empty().count(50),
                            StreamOffset.create(EventIngestionService.STREAM_KEY, ReadOffset.lastConsumed()));

            if (records == null) return;

            for (MapRecord<String, Object, Object> record : records) {
                Map<Object, Object> fields = record.getValue();
                String eventType = str(fields, "eventType");

                // Only strong signals drive co-occurrence
                if (eventType.equals("rate") || eventType.equals("favorite")) {
                    processCoOccurrence(fields);
                }
                redis.opsForStream().acknowledge(EventIngestionService.STREAM_KEY, GROUP, record.getId());
            }
        } catch (Exception e) {
            log.error("CoOccurrenceConsumer error: {}", e.getMessage());
        }
    }

    private void processCoOccurrence(Map<Object, Object> fields) {
        String itemB   = str(fields, "itemId");
        String userId  = str(fields, "userId");

        // Get user's recent session
        List<String> session = redis.opsForList().range("session:" + userId, 0, 19);
        if (session == null || session.isEmpty()) return;

        // Increment co-occurrence scores pairwise
        for (String itemA : session) {
            if (itemA.equals(itemB)) continue;

            redis.opsForZSet().incrementScore("cooccur:" + itemB, itemA, 1.0);
            redis.opsForZSet().incrementScore("cooccur:" + itemA, itemB, 1.0);

            // Cap each set at top-500 to prevent unbounded growth
            redis.opsForZSet().removeRange("cooccur:" + itemB, 0, -(MAX_NEIGHBORS + 1));
            redis.opsForZSet().removeRange("cooccur:" + itemA, 0, -(MAX_NEIGHBORS + 1));
        }
    }

    private String str(Map<Object, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? "" : v.toString();
    }
}