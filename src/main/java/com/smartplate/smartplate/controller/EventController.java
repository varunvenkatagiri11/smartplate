package com.smartplate.smartplate.controller;

import com.smartplate.smartplate.service.EventIngestionService;
import com.smartplate.smartplate.service.EventIngestionService.IngestRequest;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * EventController — updated to use authenticated userId from JWT.
 *
 * The userId is no longer taken from the request body (that would be
 * insecure — anyone could claim to be any user). Instead we read it
 * from the request attribute set by JwtFilter after token validation.
 *
 * If no token is present, we fall back to userId=1 for now so
 * unauthenticated browsing still works during development.
 */
@RestController
@RequestMapping("/api/v1/events")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class EventController {

    private final EventIngestionService eventService;

    @PostMapping
    public ResponseEntity<Map<String, Object>> ingest(
            @RequestBody IngestRequest req,
            HttpServletRequest httpRequest) {

        // Read userId from JWT token (set by JwtFilter)
        // Falls back to the userId in the request body if not authenticated
        Integer authenticatedUserId = (Integer) httpRequest.getAttribute("userId");
        int userId = authenticatedUserId != null ? authenticatedUserId : req.userId();

        // Rebuild the request with the authenticated userId
        IngestRequest authReq = new IngestRequest(
            userId,
            req.itemId(),
            req.diningHallId(),
            req.eventType(),
            req.ratingValue()
        );

        String entryId = eventService.ingest(authReq);
        return ResponseEntity.ok(Map.of("status", "ok", "streamId", entryId));
    }
}
