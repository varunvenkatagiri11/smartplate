package com.smartplate.smartplate.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventIngestionService {

    public static final String TOPIC = "smartplate.events";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public record IngestRequest(
        @JsonProperty("userId")       int userId,
        @JsonProperty("itemId")       int itemId,
        @JsonProperty("diningHallId") int diningHallId,
        @JsonProperty("eventType")    String eventType,
        @JsonProperty("ratingValue")  Float ratingValue
    ) {}

    public void ingest(IngestRequest req) {
        validateEventType(req.eventType());

        jdbc.update("""
            INSERT INTO events (user_id, item_id, dining_hall_id, event_type, rating_value, created_at)
            VALUES (?, ?, ?, ?, ?, NOW())
            """,
            req.userId(), req.itemId(), req.diningHallId(),
            req.eventType(), req.ratingValue());

        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("userId",      String.valueOf(req.userId()));
        payload.put("itemId",      String.valueOf(req.itemId()));
        payload.put("hallId",      String.valueOf(req.diningHallId()));
        payload.put("eventType",   req.eventType());
        payload.put("ratingValue", req.ratingValue() == null ? "" : String.valueOf(req.ratingValue()));
        payload.put("ts",          String.valueOf(System.currentTimeMillis()));

        try {
            String json = objectMapper.writeValueAsString(payload);
            // Partition by userId so all events for a user land on the same partition
            kafkaTemplate.send(TOPIC, String.valueOf(req.userId()), json);
            log.debug("Event {} → Kafka topic {}", req.eventType(), TOPIC);
        } catch (Exception e) {
            log.error("Failed to publish event to Kafka: {}", e.getMessage());
        }
    }

    private void validateEventType(String eventType) {
        if (!Set.of("view", "click", "rate", "favorite", "unfavorite").contains(eventType)) {
            throw new IllegalArgumentException("Unknown event type: " + eventType);
        }
    }
}
