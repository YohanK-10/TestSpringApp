package com.atlasmind.atlaswatch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class LoginUserDto {
    @NotBlank(message = "Username or email is required.")
    @Size(max = 254, message = "Username or email is too long.")
    private String loginInfo; // Can be username or email

    @NotBlank(message = "Password is required.")
    @Size(max = 128, message = "Password cannot be longer than 128 characters.")
    private String password;
}

