package com.booking.seat;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    List<Seat> findByEventIdOrderByIdAsc(Long eventId);

    long countByEventIdAndStatus(Long eventId, SeatStatus status);

    boolean existsByEventId(Long eventId);

    /**
     * Optimistic path: a plain read. Conflicts are detected at flush time by the
     * @Version column, and the loser gets an OptimisticLockingFailureException.
     */
    @Query("select s from Seat s where s.id in :ids and s.event.id = :eventId order by s.id")
    List<Seat> findForBooking(@Param("ids") Collection<Long> ids, @Param("eventId") Long eventId);

    /**
     * Pessimistic path: SELECT ... FOR UPDATE. Competing transactions block here
     * instead of failing later.
     *
     * The ORDER BY is not cosmetic: two transactions locking overlapping seat sets
     * in different orders can deadlock. Locking in a consistent order prevents it.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Seat s where s.id in :ids and s.event.id = :eventId order by s.id")
    List<Seat> findForBookingWithLock(@Param("ids") Collection<Long> ids,
                                      @Param("eventId") Long eventId);
}
