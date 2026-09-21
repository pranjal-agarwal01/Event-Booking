package com.booking.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.booking.seat.SeatStatus;
import com.booking.support.AbstractIntegrationTest;
import com.booking.user.Role;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** End-to-end over real HTTP semantics: auth, booking, idempotency, state machine. */
@AutoConfigureMockMvc
class BookingFlowIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private Long eventId;
    private List<Long> seatIds;
    private String userToken;

    @BeforeEach
    void setUp() throws Exception {
        resetDatabase();
        createUser("flow@test.dev", "password123", Role.USER);

        var event = createEventWithSeats("Flow Test Show", 5);
        eventId = event.getId();
        seatIds = seatsOf(event).stream().map(s -> s.getId()).toList();

        userToken = login("flow@test.dev", "password123");
    }

    @Test
    @DisplayName("hold -> confirm moves seats to BOOKED")
    void holdThenConfirm() throws Exception {
        String reference = createBooking(List.of(seatIds.get(0), seatIds.get(1)), "key-confirm");

        assertThat(seatStatus(seatIds.get(0))).isEqualTo(SeatStatus.HELD);

        mockMvc.perform(post("/api/v1/bookings/" + reference + "/confirm")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        assertThat(seatStatus(seatIds.get(0))).isEqualTo(SeatStatus.BOOKED);
        assertThat(seatStatus(seatIds.get(1))).isEqualTo(SeatStatus.BOOKED);
    }

    @Test
    @DisplayName("cancel releases the seats back to AVAILABLE")
    void cancelReleasesSeats() throws Exception {
        String reference = createBooking(List.of(seatIds.get(2)), "key-cancel");

        mockMvc.perform(post("/api/v1/bookings/" + reference + "/cancel")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(seatStatus(seatIds.get(2))).isEqualTo(SeatStatus.AVAILABLE);
    }

    @Test
    @DisplayName("the same Idempotency-Key returns the same booking, not a second one")
    void idempotentCreate() throws Exception {
        String first = createBooking(List.of(seatIds.get(3)), "key-idem");
        String second = createBooking(List.of(seatIds.get(3)), "key-idem");

        assertThat(second).isEqualTo(first);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM bookings", Long.class)).isEqualTo(1L);
    }

    @Test
    @DisplayName("booking an already held seat returns 409")
    void doubleBookingRejected() throws Exception {
        createBooking(List.of(seatIds.get(4)), "key-first");

        mockMvc.perform(post("/api/v1/bookings")
                        .header("Authorization", "Bearer " + userToken)
                        .header("Idempotency-Key", "key-second")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingJson(List.of(seatIds.get(4)))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    @DisplayName("confirming an already cancelled booking returns 409")
    void invalidStateTransitionRejected() throws Exception {
        String reference = createBooking(List.of(seatIds.get(0)), "key-state");

        mockMvc.perform(post("/api/v1/bookings/" + reference + "/cancel")
                .header("Authorization", "Bearer " + userToken)).andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/bookings/" + reference + "/confirm")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isConflict());
    }

    private String createBooking(List<Long> seats, String idempotencyKey) throws Exception {
        String body = mockMvc.perform(post("/api/v1/bookings")
                        .header("Authorization", "Bearer " + userToken)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingJson(seats)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readTree(body).get("reference").asText();
    }

    private String bookingJson(List<Long> seats) throws Exception {
        return objectMapper.writeValueAsString(
                java.util.Map.of("eventId", eventId, "seatIds", seats));
    }

    private String login(String email, String password) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode json = objectMapper.readTree(body);
        return json.get("accessToken").asText();
    }

    private SeatStatus seatStatus(Long seatId) {
        return seatRepository.findById(seatId).orElseThrow().getStatus();
    }
}
