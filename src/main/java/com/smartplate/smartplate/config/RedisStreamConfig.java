package com.smartplate.smartplate.config;

import com.smartplate.smartplate.service.EventIngestionService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamInfo;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Creates Redis Stream consumer groups on startup if they don't exist.
 * Must run before any consumer tries to read from the stream.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisStreamConfig {

    private final RedisTemplate<String, String> redis;

    private static final String STREAM = EventIngestionService.STREAM_KEY;
    private static final String[] GROUPS = {
            "trending-group",
            "cooccur-group",
            "session-group"
    };

    @PostConstruct
    public void createConsumerGroups() {
        // Ensure the stream key exists (XADD a dummy entry if not)
        if (!Boolean.TRUE.equals(redis.hasKey(STREAM))) {
            redis.opsForStream().add(STREAM, java.util.Map.of("init", "true"));
            log.info("Created Redis stream: {}", STREAM);
        }

        for (String group : GROUPS) {
            try {
                redis.opsForStream().createGroup(STREAM, ReadOffset.from("0"), group);
                log.info("Created consumer group: {}", group);
            } catch (Exception e) {
                // Group already exists — this is fine on restart
                if (e.getMessage() != null && e.getMessage().contains("BUSYGROUP")) {
                    log.debug("Consumer group already exists: {}", group);
                } else {
                    log.warn("Could not create group {}: {}", group, e.getMessage());
                }
            }
        }
    }
}