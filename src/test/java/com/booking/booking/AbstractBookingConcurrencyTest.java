package com.booking.booking;

import static org.assertj.core.api.Assertions.assertThat;

import com.booking.booking.dto.CreateBookingRequest;
import com.booking.common.ConflictException;
import com.booking.event.Event;
import com.booking.seat.Seat;
import com.booking.seat.SeatStatus;
import com.booking.support.AbstractIntegrationTest;
import com.booking.user.Role;
import com.booking.user.User;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The test this whole project exists to pass.
 *
 * 50 threads try to book the same single seat at the same moment. Exactly one
 * must win. Subclasses run this against each locking strategy.
 */
abstract class AbstractBookingConcurrencyTest extends AbstractIntegrationTest {

    private static final int THREADS = 50;

    @Autowired
    private BookingService bookingService;

    @Autowired
    private BookingRepository bookingRepository;

    private Event event;
    private Seat theOnlySeat;
    private List<User> users;

    @BeforeEach
    void setUp() {
        resetDatabase();
        event = createEventWithSeats("Sold Out Show", 1);
        theOnlySeat = seatsOf(event).get(0);
        users = java.util.stream.IntStream.range(0, THREADS)
                .mapToObj(i -> createUser("racer" + i + "@test.dev", "password123", Role.USER))
                .toList();
    }

    @Test
    @DisplayName("50 concurrent bookings for 1 seat: exactly one succeeds")
    void onlyOneBookingWins() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(THREADS);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger unexpected = new AtomicInteger();

        for (User user : users) {
            pool.submit(() -> {
                try {
                    // Line every thread up so they hit the database together.
                    startGate.await();
                    bookingService.create(user.getId(),
                            new CreateBookingRequest(event.getId(), List.of(theOnlySeat.getId())),
                            null);
                    succeeded.incrementAndGet();
                } catch (ConflictException expected) {
                    // Either the seat was already HELD, or we lost the version check.
                    rejected.incrementAndGet();
                } catch (Exception ex) {
                    unexpected.incrementAndGet();
                } finally {
                    finished.countDown();
                }
            });
        }

        startGate.countDown();
        assertThat(finished.await(60, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        assertThat(unexpected.get())
                .as("no thread should fail for a reason other than a booking conflict")
                .isZero();
        assertThat(succeeded.get()).as("exactly one booking wins").isEqualTo(1);
        assertThat(rejected.get()).as("everyone else is rejected cleanly").isEqualTo(THREADS - 1);

        // And the database agrees: one booking row, one held seat.
        assertThat(bookingRepository.count()).isEqualTo(1);
        assertThat(seatRepository.findById(theOnlySeat.getId()).orElseThrow().getStatus())
                .isEqualTo(SeatStatus.HELD);
    }
}
