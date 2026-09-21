package com.booking.seat.dto;

import com.booking.seat.Seat;
import com.booking.seat.SeatStatus;

public record SeatResponse(Long id, String seatNumber, SeatStatus status) {

    public static SeatResponse from(Seat seat) {
        return new SeatResponse(seat.getId(), seat.getSeatNumber(), seat.getStatus());
    }
}
