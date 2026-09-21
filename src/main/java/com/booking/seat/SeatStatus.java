package com.booking.seat;

public enum SeatStatus {
    /** Nobody holds it. */
    AVAILABLE,
    /** Reserved by a PENDING booking; released if the booking expires. (Week 3) */
    HELD,
    /** Paid for and confirmed. (Week 3) */
    BOOKED
}
