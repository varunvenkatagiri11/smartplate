package com.smartplate.smartplate.controller;

import com.smartplate.smartplate.service.RecommendationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/recommendations")
@CrossOrigin("*")
@RequiredArgsConstructor
public class RecommendationController {

    private final RecommendationService recService;

    @GetMapping("/trending")
    public List<Map<String, Object>> trending(
            @RequestParam int hallId,
            @RequestParam(defaultValue = "1") int daypart,
            @RequestParam(defaultValue = "10") int limit) {
        return recService.getTrending(hallId, daypart, limit);
    }

    @GetMapping("/similar")
    public List<Map<String, Object>> similar(
            @RequestParam int itemId,
            @RequestParam int hallId,
            @RequestParam(defaultValue = "10") int limit) {
        return recService.getSimilar(itemId, hallId, limit);
    }

    @GetMapping("/foryou")
    public List<Map<String, Object>> forYou(
            @RequestParam int userId,
            @RequestParam int hallId,
            @RequestParam(defaultValue = "1") int daypart,
            @RequestParam(defaultValue = "10") int limit) {
        return recService.getForYou(userId, hallId, daypart, limit);
    }
}
