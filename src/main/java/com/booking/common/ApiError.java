package com.booking.common;

import java.time.Instant;
import java.util.Map;

/**
 * One error shape for the whole API. Every failure a client sees looks like this,
 * which is the difference between an API and a pile of endpoints.
 */
public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        Map<String, String> fieldErrors) {

    public static ApiError of(int status, String error, String message, String path) {
        return new ApiError(Instant.now(), status, error, message, path, null);
    }
}
