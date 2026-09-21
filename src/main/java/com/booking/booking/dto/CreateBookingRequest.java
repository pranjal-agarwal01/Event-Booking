package com.booking.booking.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CreateBookingRequest(

        @NotNull(message = "eventId is required")
        Long eventId,

        @NotEmpty(message = "at least one seatId is required")
        @Size(max = 10, message = "cannot book more than 10 seats at once")
        List<Long> seatIds) {
}
