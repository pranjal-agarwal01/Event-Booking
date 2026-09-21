package com.booking.support;

import com.booking.event.Event;
import com.booking.event.EventRepository;
import com.booking.seat.Seat;
import com.booking.seat.SeatRepository;
import com.booking.user.Role;
import com.booking.user.User;
import com.booking.user.UserRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base for integration tests: a real PostgreSQL in Docker, with real Flyway
 * migrations applied. H2 would not do - the locking behaviour under test is
 * exactly the part that differs between databases.
 *
 * The container is a singleton started once for the whole suite, rather than one
 * per test class, because starting Postgres costs a few seconds.
 */
@SpringBootTest
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // Keep the scheduled job out of the way; tests invoke it directly.
        registry.add("booking.expiry-job-interval-ms", () -> "3600000");
    }

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected EventRepository eventRepository;

    @Autowired
    protected SeatRepository seatRepository;

    @Autowired
    protected UserRepository userRepository;

    @Autowired
    protected PasswordEncoder passwordEncoder;

    protected void resetDatabase() {
        jdbcTemplate.execute("""
                TRUNCATE booking_seats, bookings, refresh_tokens, seats, events, users
                RESTART IDENTITY CASCADE
                """);
    }

    /** Commits immediately, so other threads and transactions can see it. */
    protected Event createEventWithSeats(String name, int seatCount) {
        Event event = new Event(name, "Test Venue",
                Instant.now().plus(30, ChronoUnit.DAYS), seatCount);
        for (int i = 0; i < seatCount; i++) {
            char row = (char) ('A' + (i / 10));
            event.addSeat(new Seat(event, row + String.valueOf((i % 10) + 1)));
        }
        return eventRepository.save(event);
    }

    protected List<Seat> seatsOf(Event event) {
        return seatRepository.findByEventIdOrderByIdAsc(event.getId());
    }

    protected User createUser(String email, String rawPassword, Role role) {
        return userRepository.save(
                new User(email, passwordEncoder.encode(rawPassword), "Test " + role, role));
    }
}
