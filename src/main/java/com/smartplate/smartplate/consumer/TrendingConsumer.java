package com.smartplate.smartplate.consumer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartplate.smartplate.service.EventIngestionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class TrendingConsumer {

    private static final double LAMBDA = 0.005;

    private final RedisTemplate<String, String> redis;
    private final ObjectMapper objectMapper;

    @KafkaListener(
        topics = EventIngestionService.TOPIC,
        groupId = "trending-group",
        concurrency = "1"
    )
    public void consume(String message) {
        try {
            Map<String, String> fields = objectMapper.readValue(message, new TypeReference<>() {});
            processEvent(fields);
        } catch (Exception e) {
            log.error("TrendingConsumer error: {}", e.getMessage());
        }
    }

    private void processEvent(Map<String, String> fields) {
        String eventType = fields.getOrDefault("eventType", "");
        String itemId    = fields.getOrDefault("itemId", "");
        String hallId    = fields.getOrDefault("hallId", "");
        String ratingVal = fields.getOrDefault("ratingValue", "");
        String ts        = fields.getOrDefault("ts", "");

        if (itemId.isEmpty() || hallId.isEmpty() || ts.isEmpty()) return;

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

        String trendKey = "trending:" + hallId + ":" + currentDaypart();
        redis.opsForZSet().incrementScore(trendKey, itemId, score);
    }

    @Scheduled(fixedDelay = 600_000)
    public void cleanup() {
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
        if (hour < 11) return 0;
        if (hour < 16) return 1;
        return 2;
    }

    private double parseRating(String val) {
        try { return Double.parseDouble(val); } catch (Exception e) { return 3.0; }
    }
}
