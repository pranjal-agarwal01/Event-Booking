package com.booking.booking;

/**
 * How the booking flow keeps two users off the same seat. Switchable via
 * booking.locking-strategy so both can be benchmarked against the same test.
 */
public enum LockingStrategy {
    /** @Version column; conflicts fail fast at flush. Best when contention is rare. */
    OPTIMISTIC,
    /** SELECT ... FOR UPDATE; conflicts wait. Best when contention is common. */
    PESSIMISTIC
}
