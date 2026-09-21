package com.booking.booking;

import static org.assertj.core.api.Assertions.assertThat;

import com.booking.booking.dto.CreateBookingRequest;
import com.booking.seat.Seat;
import com.booking.seat.SeatStatus;
import com.booking.support.AbstractIntegrationTest;
import com.booking.user.Role;
import com.booking.user.User;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * An abandoned checkout must not hold a seat forever. The job is invoked directly
 * here rather than waiting on the scheduler.
 */
class BookingExpiryJobIT extends AbstractIntegrationTest {

    @Autowired
    private BookingService bookingService;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private BookingExpiryJob expiryJob;

    private Seat seat;
    private User user;
    private UUID reference;

    @BeforeEach
    void setUp() {
        resetDatabase();
        var event = createEventWithSeats("Abandoned Checkout Show", 2);
        seat = seatsOf(event).get(0);
        user = createUser("lapsed@test.dev", "password123", Role.USER);

        reference = bookingService.create(user.getId(),
                new CreateBookingRequest(event.getId(), List.of(seat.getId())), null).reference();
    }

    @Test
    @DisplayName("a hold past its expiry is expired and its seats released")
    void expiresStaleHold() {
        assertThat(seatStatus()).isEqualTo(SeatStatus.HELD);

        // Nothing is stale yet, so the job must leave it alone.
        expiryJob.releaseExpiredHolds();
        assertThat(statusOf()).isEqualTo(BookingStatus.PENDING);
        assertThat(seatStatus()).isEqualTo(SeatStatus.HELD);

        // Backdate the hold, then run the job for real.
        jdbcTemplate.update("UPDATE bookings SET expires_at = now() - INTERVAL '1 minute'");
        expiryJob.releaseExpiredHolds();

        assertThat(statusOf()).isEqualTo(BookingStatus.EXPIRED);
        assertThat(seatStatus()).isEqualTo(SeatStatus.AVAILABLE);
    }

    @Test
    @DisplayName("confirmed bookings are never touched by the job")
    void leavesConfirmedAlone() {
        bookingService.confirm(user.getId(), Role.USER, reference);

        jdbcTemplate.update("UPDATE bookings SET expires_at = now() - INTERVAL '1 minute'");
        expiryJob.releaseExpiredHolds();

        assertThat(statusOf()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(seatStatus()).isEqualTo(SeatStatus.BOOKED);
    }

    private BookingStatus statusOf() {
        return bookingRepository.findByReference(reference).orElseThrow().getStatus();
    }

    private SeatStatus seatStatus() {
        return seatRepository.findById(seat.getId()).orElseThrow().getStatus();
    }
}
