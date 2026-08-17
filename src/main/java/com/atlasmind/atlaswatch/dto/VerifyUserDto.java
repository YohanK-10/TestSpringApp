package com.atlasmind.atlaswatch.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class VerifyUserDto {
    @Email(message = "Email must be valid.")
    @Size(max = 254, message = "Email cannot be longer than 254 characters.")
    private String email;

    @Size(min = 3, max = 30, message = "Username must be between 3 and 30 characters.")
    private String username;

    @NotBlank(message = "Verification code is required.")
    @Pattern(regexp = "^\\d{6}$", message = "Verification code must contain exactly 6 digits.")
    private String verificationCode;

    @AssertTrue(message = "Email or username is required.")
    public boolean isIdentityProvided() {
        return (email != null && !email.isBlank()) || (username != null && !username.isBlank());
    }
}

