package com.booking.booking;

import org.springframework.test.context.TestPropertySource;

/** Conflicts are serialised by SELECT ... FOR UPDATE; losers wait, then see HELD. */
@TestPropertySource(properties = "booking.locking-strategy=PESSIMISTIC")
class PessimisticLockingConcurrencyIT extends AbstractBookingConcurrencyTest {
}
