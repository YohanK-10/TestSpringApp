package com.atlasmind.atlaswatch.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class SoloRecommendationRequestDto {

    /** Legacy single-mood field — kept for backward compatibility. */
    private String mood;

    private List<String> moods;

    @Pattern(
            regexp = "(?i)^(any|short|medium|long)$",
            message = "runtimePreference must be any, short, medium, or long."
    )
    private String runtimePreference;

    @Min(value = 1, message = "Recommendation limit must be at least 1.")
    @Max(value = 10, message = "Recommendation limit cannot be more than 10.")
    private Integer limit;
}

