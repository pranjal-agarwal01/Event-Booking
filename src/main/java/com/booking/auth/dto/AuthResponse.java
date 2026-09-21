package com.booking.auth.dto;

public record AuthResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresInSeconds) {

    public static AuthResponse of(String accessToken, String refreshToken, long expiresIn) {
        return new AuthResponse(accessToken, refreshToken, "Bearer", expiresIn);
    }
}
