package com.smartplate.smartplate.consumer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartplate.smartplate.service.EventIngestionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class CoOccurrenceConsumer {

    private static final long MAX_NEIGHBORS = 500;

    private final RedisTemplate<String, String> redis;
    private final ObjectMapper objectMapper;

    @KafkaListener(
        topics = EventIngestionService.TOPIC,
        groupId = "cooccur-group",
        concurrency = "1"
    )
    public void consume(String message) {
        try {
            Map<String, String> fields = objectMapper.readValue(message, new TypeReference<>() {});
            String eventType = fields.getOrDefault("eventType", "");
            if (eventType.equals("rate") || eventType.equals("favorite")) {
                processCoOccurrence(fields);
            }
        } catch (Exception e) {
            log.error("CoOccurrenceConsumer error: {}", e.getMessage());
        }
    }

    private void processCoOccurrence(Map<String, String> fields) {
        String itemB  = fields.getOrDefault("itemId", "");
        String userId = fields.getOrDefault("userId", "");
        if (itemB.isEmpty() || userId.isEmpty()) return;

        List<String> session = redis.opsForList().range("session:" + userId, 0, 19);
        if (session == null || session.isEmpty()) return;

        for (String itemA : session) {
            if (itemA.equals(itemB)) continue;
            redis.opsForZSet().incrementScore("cooccur:" + itemB, itemA, 1.0);
            redis.opsForZSet().incrementScore("cooccur:" + itemA, itemB, 1.0);
            redis.opsForZSet().removeRange("cooccur:" + itemB, 0, -(MAX_NEIGHBORS + 1));
            redis.opsForZSet().removeRange("cooccur:" + itemA, 0, -(MAX_NEIGHBORS + 1));
        }
    }
}
