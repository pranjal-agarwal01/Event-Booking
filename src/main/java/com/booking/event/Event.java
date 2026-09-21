package com.booking.event;

import com.booking.seat.Seat;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "events")
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false, length = 200)
    private String venue;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "total_seats", nullable = false)
    private int totalSeats;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    /** Cascade so saving an event persists the seats generated with it. */
    @OneToMany(mappedBy = "event", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Seat> seats = new ArrayList<>();

    protected Event() {
        // required by JPA
    }

    public Event(String name, String venue, Instant startsAt, int totalSeats) {
        this.name = name;
        this.venue = venue;
        this.startsAt = startsAt;
        this.totalSeats = totalSeats;
    }

    public void addSeat(Seat seat) {
        seats.add(seat);
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getVenue() {
        return venue;
    }

    public Instant getStartsAt() {
        return startsAt;
    }

    public int getTotalSeats() {
        return totalSeats;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<Seat> getSeats() {
        return seats;
    }
}
