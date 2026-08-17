package com.atlasmind.atlaswatch.dto.response;

import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Getter
@AllArgsConstructor
@Builder
public class ReviewSummaryResponseDto {
    private final Double averageRating;
    private final Long totalRatings;
    private final Long writtenReviewCount;
    private final Map<Integer, Long> ratingDistribution;
}

