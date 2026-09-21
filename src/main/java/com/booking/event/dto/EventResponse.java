package com.booking.event.dto;

import com.booking.event.Event;
import java.time.Instant;

public record EventResponse(
        Long id,
        String name,
        String venue,
        Instant startsAt,
        int totalSeats,
        long availableSeats) {

    public static EventResponse from(Event event, long availableSeats) {
        return new EventResponse(event.getId(), event.getName(), event.getVenue(),
                event.getStartsAt(), event.getTotalSeats(), availableSeats);
    }
}
