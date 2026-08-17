package com.atlasmind.atlaswatch.dto;

import jakarta.validation.constraints.Email;
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
public class EmailOnlyDto {
    @NotBlank(message = "Email is required.")
    @Email(message = "Email must be valid.")
    @Size(max = 254, message = "Email cannot be longer than 254 characters.")
    private String email;
}

