package com.booking.booking;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    /** Fetches the seats in the same query - otherwise this is an N+1. */
    @EntityGraph(attributePaths = {"seats", "event"})
    Optional<Booking> findByReference(UUID reference);

    Optional<Booking> findByIdempotencyKey(String idempotencyKey);

    @EntityGraph(attributePaths = {"seats", "event"})
    Page<Booking> findByUserId(Long userId, Pageable pageable);

    /** Used by the expiry job. Matches the (status, expires_at) index. */
    @EntityGraph(attributePaths = {"seats"})
    List<Booking> findByStatusAndExpiresAtBefore(BookingStatus status, Instant cutoff);
}
