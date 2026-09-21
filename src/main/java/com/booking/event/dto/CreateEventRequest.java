package com.booking.event.dto;

import jakarta.validation.constraints.*;
import java.time.Instant;

/**
 * What a client is allowed to send. Deliberately not the Event entity - never
 * let an HTTP body bind straight onto a persisted object.
 */
public record CreateEventRequest(

        @NotBlank(message = "name is required")
        @Size(max = 200)
        String name,

        @NotBlank(message = "venue is required")
        @Size(max = 200)
        String venue,

        @NotNull(message = "startsAt is required")
        @Future(message = "startsAt must be in the future")
        Instant startsAt,

        @Min(value = 1, message = "totalSeats must be at least 1")
        @Max(value = 260, message = "totalSeats cannot exceed 260 (rows A-Z x 10)")
        int totalSeats) {
}
