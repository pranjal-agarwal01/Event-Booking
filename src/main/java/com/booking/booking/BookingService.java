package com.booking.booking;

import com.booking.booking.dto.BookingResponse;
import com.booking.booking.dto.CreateBookingRequest;
import com.booking.common.ConflictException;
import com.booking.common.ForbiddenException;
import com.booking.common.NotFoundException;
import com.booking.event.Event;
import com.booking.event.EventRepository;
import com.booking.seat.Seat;
import com.booking.seat.SeatRepository;
import com.booking.seat.SeatStatus;
import com.booking.user.Role;
import com.booking.user.User;
import com.booking.user.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The interesting part of this project.
 *
 * Two users clicking "book" on the last seat at the same instant must not both
 * succeed. Two things make that true:
 *
 *  1. A locking strategy on the seat rows (optimistic @Version, or pessimistic
 *     SELECT ... FOR UPDATE), so only one transaction can flip a seat to HELD.
 *  2. An idempotency key with a UNIQUE constraint, so a double-clicked button
 *     or a client retry does not create a second booking.
 */
@Service
public class BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);

    private final BookingRepository bookingRepository;
    private final SeatRepository seatRepository;
    private final EventRepository eventRepository;
    private final UserRepository userRepository;
    private final LockingStrategy lockingStrategy;
    private final Duration holdDuration;

    public BookingService(BookingRepository bookingRepository,
                          SeatRepository seatRepository,
                          EventRepository eventRepository,
                          UserRepository userRepository,
                          @Value("${booking.locking-strategy}") LockingStrategy lockingStrategy,
                          @Value("${booking.hold-minutes}") long holdMinutes) {
        this.bookingRepository = bookingRepository;
        this.seatRepository = seatRepository;
        this.eventRepository = eventRepository;
        this.userRepository = userRepository;
        this.lockingStrategy = lockingStrategy;
        this.holdDuration = Duration.ofMinutes(holdMinutes);
    }

    @Transactional
    public BookingResponse create(Long userId, CreateBookingRequest request, String idempotencyKey) {
        // Fast path: this exact request already produced a booking. Returning it
        // unchanged is what makes the endpoint safe to retry.
        if (idempotencyKey != null) {
            var existing = bookingRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                log.debug("Idempotency key {} already used, returning existing booking", idempotencyKey);
                return BookingResponse.from(existing.get());
            }
        }

        Event event = eventRepository.findById(request.eventId())
                .orElseThrow(() -> new NotFoundException("Event", request.eventId()));
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User", userId));

        Set<Long> requestedIds = new LinkedHashSet<>(request.seatIds());
        List<Seat> seats = lockingStrategy == LockingStrategy.PESSIMISTIC
                ? seatRepository.findForBookingWithLock(requestedIds, event.getId())
                : seatRepository.findForBooking(requestedIds, event.getId());

        if (seats.size() != requestedIds.size()) {
            throw new NotFoundException("Seat(s) for this event", requestedIds);
        }

        List<String> unavailable = seats.stream()
                .filter(seat -> seat.getStatus() != SeatStatus.AVAILABLE)
                .map(Seat::getSeatNumber)
                .toList();
        if (!unavailable.isEmpty()) {
            throw new ConflictException("Seats already taken: " + String.join(", ", unavailable));
        }

        seats.forEach(seat -> seat.setStatus(SeatStatus.HELD));

        Booking booking = new Booking(user, event, new LinkedHashSet<>(seats),
                idempotencyKey, Instant.now().plus(holdDuration));
        bookingRepository.save(booking);

        try {
            // Force the UPDATEs now instead of at commit, so the version check (or
            // the unique constraint) fails here where it can be translated into a
            // sensible HTTP status instead of a 500 at commit time.
            bookingRepository.flush();
        } catch (OptimisticLockingFailureException ex) {
            // Someone else flipped one of these seats between our read and our write.
            log.debug("Optimistic lock lost for seats {}", requestedIds);
            throw new ConflictException("Seat was taken by another booking, please pick another");
        } catch (DataIntegrityViolationException ex) {
            // Two requests with the same Idempotency-Key raced past the fast path.
            log.debug("Idempotency key {} collided", idempotencyKey);
            throw new ConflictException("A booking with this Idempotency-Key is already in progress");
        }

        log.info("Booking {} created for user {} holding {} seat(s)",
                booking.getReference(), userId, seats.size());
        return BookingResponse.from(booking);
    }

    @Transactional
    public BookingResponse confirm(Long userId, Role role, UUID reference) {
        Booking booking = loadOwned(userId, role, reference);
        try {
            booking.confirm();
        } catch (IllegalStateException ex) {
            throw new ConflictException(ex.getMessage());
        }
        return BookingResponse.from(booking);
    }

    @Transactional
    public BookingResponse cancel(Long userId, Role role, UUID reference) {
        Booking booking = loadOwned(userId, role, reference);
        try {
            booking.cancel();
        } catch (IllegalStateException ex) {
            throw new ConflictException(ex.getMessage());
        }
        return BookingResponse.from(booking);
    }

    @Transactional(readOnly = true)
    public BookingResponse getByReference(Long userId, Role role, UUID reference) {
        return BookingResponse.from(loadOwned(userId, role, reference));
    }

    @Transactional(readOnly = true)
    public Page<BookingResponse> listMine(Long userId, Pageable pageable) {
        return bookingRepository.findByUserId(userId, pageable).map(BookingResponse::from);
    }

    /** A user may only touch their own bookings; an admin may touch any. */
    private Booking loadOwned(Long userId, Role role, UUID reference) {
        Booking booking = bookingRepository.findByReference(reference)
                .orElseThrow(() -> new NotFoundException("Booking", reference));

        if (role != Role.ADMIN && !booking.getUser().getId().equals(userId)) {
            throw new ForbiddenException("This booking belongs to another user");
        }
        return booking;
    }
}
