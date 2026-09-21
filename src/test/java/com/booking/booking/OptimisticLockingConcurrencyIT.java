package com.booking.booking;

import org.springframework.test.context.TestPropertySource;

/** Conflicts are detected by the @Version column and fail fast. */
@TestPropertySource(properties = "booking.locking-strategy=OPTIMISTIC")
class OptimisticLockingConcurrencyIT extends AbstractBookingConcurrencyTest {
}
