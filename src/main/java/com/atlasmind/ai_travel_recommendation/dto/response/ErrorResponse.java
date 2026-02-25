package com.atlasmind.ai_travel_recommendation.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Getter
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL) // Does not include a field if it is null in the JSON. Makes handling optional fields easier and cleaner.
public class ErrorResponse {
    private final int status;
    private final String error;
    private final String message;
    private final LocalDateTime timeStamp;
}
