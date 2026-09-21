package com.booking.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(

        @NotBlank @Email(message = "must be a valid email address")
        String email,

        @NotBlank
        @Size(min = 8, max = 72, message = "password must be 8-72 characters")
        String password,

        @NotBlank
        @Size(max = 150)
        String fullName) {
}
