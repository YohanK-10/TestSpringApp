package com.atlasmind.atlaswatch.dto;

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
public class PasswordResetConfirmDto {
    @NotBlank(message = "Email is required.")
    @Email(message = "Email must be valid.")
    @Size(max = 254, message = "Email cannot be longer than 254 characters.")
    private String email;

    @NotBlank(message = "Reset code is required.")
    @Pattern(regexp = "^\\d{6}$", message = "Reset code must contain exactly 6 digits.")
    private String resetCode;

    @NotBlank(message = "New password is required.")
    @Size(min = 8, max = 128, message = "New password must be between 8 and 128 characters.")
    private String newPassword;
}

