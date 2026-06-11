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

@Slf4j
@Component
@RequiredArgsConstructor
public class SessionConsumer {

    private static final String GROUP    = "session-group";
    private static final String CONSUMER = "session-consumer-1";

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
                String userId = str(fields, "userId");
                String itemId = str(fields, "itemId");

                String sessionKey = "session:" + userId;
                redis.opsForList().leftPush(sessionKey, itemId);
                redis.opsForList().trim(sessionKey, 0, 19);

                redis.opsForStream().acknowledge(EventIngestionService.STREAM_KEY, GROUP, record.getId());
            }
        } catch (Exception e) {
            log.error("SessionConsumer error: {}", e.getMessage());
        }
    }

    private String str(Map<Object, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? "" : v.toString();
    }
}
