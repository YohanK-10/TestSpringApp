package com.atlasmind.atlaswatch.controller;

import com.atlasmind.atlaswatch.dto.request.RecommendationRequestDto;
import com.atlasmind.atlaswatch.dto.request.SoloRecommendationRequestDto;
import com.atlasmind.atlaswatch.dto.response.RecommendationResponseDto;
import com.atlasmind.atlaswatch.dto.response.SoloRecommendationResponseDto;
import com.atlasmind.atlaswatch.models.User;
import com.atlasmind.atlaswatch.service.RecommendationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/recommendations")
@RequiredArgsConstructor
@Validated
public class RecommendationController {

    private final RecommendationService recommendationService;

    @PostMapping
    public ResponseEntity<List<RecommendationResponseDto>> getRecommendations(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody RecommendationRequestDto request
    ) {
        return ResponseEntity.ok(recommendationService.getRecommendations(user, request));
    }

    @GetMapping("/cold-start")
    public ResponseEntity<List<RecommendationResponseDto>> getColdStartRecommendations(
            @Valid @ModelAttribute RecommendationRequestDto request
    ) {
        return ResponseEntity.ok(recommendationService.getColdStartRecommendations(request));
    }

    @Deprecated(forRemoval = false)
    @PostMapping("/solo")
    public ResponseEntity<List<SoloRecommendationResponseDto>> getSoloRecommendations(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody SoloRecommendationRequestDto request
    ) {
        return ResponseEntity.ok(recommendationService.getSoloRecommendations(user, request));
    }
}

