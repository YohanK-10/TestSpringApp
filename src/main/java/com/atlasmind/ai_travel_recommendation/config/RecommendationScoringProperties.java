package com.atlasmind.ai_travel_recommendation.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "atlaswatch.recommendation.scoring")
public class RecommendationScoringProperties {

    private double genreAffinityWeight = 0.24;
    private double moodMatchWeight = 0.18;
    private double runtimeMatchWeight = 0.14;
    private double qualityWeight = 0.18;
    private double popularityWeight = 0.08;
    private double watchlistBoostWeight = 0.10;
    private double freshnessWeight = 0.08;
    private double watchlistAgeWeight = 0.12;
    private double sourceCountWeight = 0.10;
    private double dislikedGenrePenaltyWeight = 0.18;
    private double diversityPenaltyWeight = 0.15;

    private double popularitySaturation = 200.0;
    private double freshnessWindowYears = 15.0;
    private int watchlistAgeSaturationDays = 180;
    private double positiveGenreReasonThreshold = 0.25;
    private double strongDislikeThreshold = 0.35;
}
