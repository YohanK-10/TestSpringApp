package com.atlasmind.atlaswatch.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class RecommendationRequestDto {
    @Size(max = 5, message = "moods cannot contain more than 5 entries.")
    private List<String> moods;

    @Pattern(
            regexp = "(?i)^(any|short|medium|long)$",
            message = "runtimePreference must be any, short, medium, or long."
    )
    private String runtimePreference;

    @Size(max = 7, message = "releaseEras cannot contain more than 7 entries.")
    private List<@Pattern(
            regexp = "(?i)^(any|pre-1980|1980s|1990s|2000s|2010s|2020s)$",
            message = "releaseEras entries must be any, pre-1980, 1980s, 1990s, 2000s, 2010s, or 2020s."
    ) String> releaseEras;

    @Min(value = 1, message = "Recommendation limit must be at least 1.")
    @Max(value = 10, message = "Recommendation limit cannot be more than 10.")
    private Integer limit;

    @Size(max = 64, message = "refreshToken cannot be longer than 64 characters.")
    private String refreshToken;

    @Size(max = 5, message = "starterGenres cannot contain more than 5 entries.")
    private List<String> starterGenres;

    @Size(max = 10, message = "starterKeywords cannot contain more than 10 entries.")
    private List<String> starterKeywords;

    @Size(max = 5, message = "seedTmdbIds cannot contain more than 5 entries.")
    private List<Integer> seedTmdbIds;

    @Size(max = 50, message = "seenTmdbIds cannot contain more than 50 entries.")
    private List<@Positive(message = "seenTmdbIds entries must be positive.") Integer> seenTmdbIds;

    public RecommendationRequestDto(List<String> moods, String runtimePreference, Integer limit) {
        this.moods = moods;
        this.runtimePreference = runtimePreference;
        this.limit = limit;
    }
}

