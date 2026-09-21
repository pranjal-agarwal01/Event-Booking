package com.booking.booking;

import com.booking.event.Event;
import com.booking.seat.Seat;
import com.booking.seat.SeatStatus;
import com.booking.user.User;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "bookings")
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The public identifier. Sequential ids are never exposed to clients. */
    @Column(nullable = false, unique = true)
    private UUID reference;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BookingStatus status = BookingStatus.PENDING;

    @Column(name = "idempotency_key", length = 100)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "booking_seats",
            joinColumns = @JoinColumn(name = "booking_id"),
            inverseJoinColumns = @JoinColumn(name = "seat_id"))
    private Set<Seat> seats = new LinkedHashSet<>();

    protected Booking() {
        // required by JPA
    }

    public Booking(User user, Event event, Set<Seat> seats, String idempotencyKey, Instant expiresAt) {
        this.reference = UUID.randomUUID();
        this.user = user;
        this.event = event;
        this.seats = new LinkedHashSet<>(seats);
        this.idempotencyKey = idempotencyKey;
        this.expiresAt = expiresAt;
    }

    /** PENDING -> CONFIRMED. Seats move from HELD to BOOKED. */
    public void confirm() {
        requireStatus(BookingStatus.PENDING, "confirm");
        this.status = BookingStatus.CONFIRMED;
        this.confirmedAt = Instant.now();
        this.expiresAt = null;
        seats.forEach(seat -> seat.setStatus(SeatStatus.BOOKED));
    }

    /** PENDING or CONFIRMED -> CANCELLED. Seats are released. */
    public void cancel() {
        if (status != BookingStatus.PENDING && status != BookingStatus.CONFIRMED) {
            throw new IllegalStateException("Cannot cancel a booking that is " + status);
        }
        this.status = BookingStatus.CANCELLED;
        releaseSeats();
    }

    /** PENDING -> EXPIRED, driven by the scheduled job. */
    public void expire() {
        requireStatus(BookingStatus.PENDING, "expire");
        this.status = BookingStatus.EXPIRED;
        releaseSeats();
    }

    private void releaseSeats() {
        this.expiresAt = null;
        seats.forEach(seat -> seat.setStatus(SeatStatus.AVAILABLE));
    }

    private void requireStatus(BookingStatus required, String action) {
        if (status != required) {
            throw new IllegalStateException("Cannot " + action + " a booking that is " + status);
        }
    }

    public Long getId() {
        return id;
    }

    public UUID getReference() {
        return reference;
    }

    public User getUser() {
        return user;
    }

    public Event getEvent() {
        return event;
    }

    public BookingStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public Set<Seat> getSeats() {
        return seats;
    }
}
