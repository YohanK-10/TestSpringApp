package com.atlasmind.ai_travel_recommendation.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class VerifyUserDto {
    private String email;
    private String username;
    private String verificationCode;
}
