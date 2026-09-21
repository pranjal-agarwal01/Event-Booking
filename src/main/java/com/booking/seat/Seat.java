package com.booking.seat;

import com.booking.event.Event;
import jakarta.persistence.*;

@Entity
@Table(name = "seats",
        uniqueConstraints = @UniqueConstraint(name = "uq_seat_per_event",
                columnNames = {"event_id", "seat_number"}))
public class Seat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * LAZY so loading a seat does not silently drag the whole event along.
     * Combined with open-in-view=false, this forces you to be explicit about
     * what you load - which is the point.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @Column(name = "seat_number", nullable = false, length = 10)
    private String seatNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SeatStatus status = SeatStatus.AVAILABLE;

    /**
     * Optimistic lock. Hibernate adds "WHERE version = ?" to every UPDATE and
     * throws OptimisticLockingFailureException if the row moved underneath us.
     * Unused until week 3 - this is the hook the booking flow hangs on.
     */
    @Version
    @Column(nullable = false)
    private Long version;

    protected Seat() {
        // required by JPA
    }

    public Seat(Event event, String seatNumber) {
        this.event = event;
        this.seatNumber = seatNumber;
    }

    public Long getId() {
        return id;
    }

    public Event getEvent() {
        return event;
    }

    public String getSeatNumber() {
        return seatNumber;
    }

    public SeatStatus getStatus() {
        return status;
    }

    public void setStatus(SeatStatus status) {
        this.status = status;
    }

    public Long getVersion() {
        return version;
    }
}
