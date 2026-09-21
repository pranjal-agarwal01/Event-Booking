package com.booking.booking.dto;

import com.booking.booking.Booking;
import com.booking.booking.BookingStatus;
import com.booking.seat.Seat;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public record BookingResponse(
        UUID reference,
        Long eventId,
        String eventName,
        BookingStatus status,
        List<String> seats,
        Instant createdAt,
        Instant expiresAt,
        Instant confirmedAt) {

    /** Built inside the transaction, so the lazy collections are still loadable. */
    public static BookingResponse from(Booking booking) {
        List<String> seatNumbers = booking.getSeats().stream()
                .map(Seat::getSeatNumber)
                .sorted(Comparator.naturalOrder())
                .toList();

        return new BookingResponse(
                booking.getReference(),
                booking.getEvent().getId(),
                booking.getEvent().getName(),
                booking.getStatus(),
                seatNumbers,
                booking.getCreatedAt(),
                booking.getExpiresAt(),
                booking.getConfirmedAt());
    }
}
