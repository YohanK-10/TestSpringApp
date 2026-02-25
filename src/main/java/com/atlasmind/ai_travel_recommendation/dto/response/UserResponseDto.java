package com.atlasmind.ai_travel_recommendation.dto.response;

import com.atlasmind.ai_travel_recommendation.models.User;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/**
 * What the API sends to the client when returning user data.
 * This is the SAFE version of User. Compare:
 *   User entity:         id, username, email, password, verificationCode, enable, locked, ...
 *   UserResponseDto:     id, username, email  ← only what the client needs
 * The static fromUser() factory method converts a User entity to this DTO.
 * This is a common pattern — it keeps the conversion logic in one place.
 * WHY not just add @JsonIgnore on the password field in User?
 * Because @JsonIgnore is fragile. If you use the User entity in a different
 * context where you DO need the password (like an admin API), the ignore
 * annotation gets in the way. DTOs give you explicit control over each API's response shape.
 */
@Getter
@AllArgsConstructor
@Builder
public class UserResponseDto {
    private final Long id;
    private final String email;
    private final String username;

    public static UserResponseDto fromUser(User user) {
        return UserResponseDto.builder()
                .id(user.getId())
                .email(user.getEmail())
                .username(user.getUsername())
                .build();
    }
}
