package com.booking.booking;

public enum BookingStatus {
    /** Seats are HELD; the booking dies at expires_at unless confirmed. */
    PENDING,
    /** Paid/confirmed; seats are BOOKED. */
    CONFIRMED,
    /** Cancelled by the user; seats went back to AVAILABLE. */
    CANCELLED,
    /** The hold ran out before confirmation; seats went back to AVAILABLE. */
    EXPIRED
}
