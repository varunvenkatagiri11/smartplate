package com.smartplate.smartplate.consumer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartplate.smartplate.service.EventIngestionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class SessionConsumer {

    private final RedisTemplate<String, String> redis;
    private final ObjectMapper objectMapper;

    @KafkaListener(
        topics = EventIngestionService.TOPIC,
        groupId = "session-group",
        concurrency = "1"
    )
    public void consume(String message) {
        try {
            Map<String, String> fields = objectMapper.readValue(message, new TypeReference<>() {});
            String userId = fields.getOrDefault("userId", "");
            String itemId = fields.getOrDefault("itemId", "");
            if (userId.isEmpty() || itemId.isEmpty()) return;

            String sessionKey = "session:" + userId;
            redis.opsForList().leftPush(sessionKey, itemId);
            redis.opsForList().trim(sessionKey, 0, 19);
        } catch (Exception e) {
            log.error("SessionConsumer error: {}", e.getMessage());
        }
    }
}
