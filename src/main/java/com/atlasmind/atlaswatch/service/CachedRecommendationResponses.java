package com.atlasmind.atlaswatch.service;

import com.atlasmind.atlaswatch.dto.response.RecommendationResponseDto;

import java.util.List;

public class CachedRecommendationResponses {

    private List<RecommendationResponseDto> responses = List.of();

    public CachedRecommendationResponses() {
    }

    public CachedRecommendationResponses(List<RecommendationResponseDto> responses) {
        setResponses(responses);
    }

    public List<RecommendationResponseDto> getResponses() {
        return responses;
    }

    public void setResponses(List<RecommendationResponseDto> responses) {
        this.responses = responses == null ? List.of() : List.copyOf(responses);
    }
}
