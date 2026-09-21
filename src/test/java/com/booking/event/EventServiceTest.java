package com.booking.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.booking.event.dto.CreateEventRequest;
import com.booking.seat.Seat;
import com.booking.seat.SeatRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Plain unit test - no Spring context, no database. Runs in milliseconds because
 * the service takes its dependencies through the constructor.
 */
@ExtendWith(MockitoExtension.class)
class EventServiceTest {

    @Mock
    private EventRepository eventRepository;

    @Mock
    private SeatRepository seatRepository;

    @InjectMocks
    private EventService eventService;

    @Test
    @DisplayName("generates seat labels row-major: A1..A10 then B1")
    void generatesSeatLabels() {
        when(eventRepository.save(any(Event.class))).thenAnswer(call -> call.getArgument(0));

        eventService.create(new CreateEventRequest(
                "Test Event", "Test Venue", Instant.now().plus(10, ChronoUnit.DAYS), 12));

        ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
        verify(eventRepository).save(captor.capture());

        List<String> labels = captor.getValue().getSeats().stream()
                .map(Seat::getSeatNumber)
                .toList();

        assertThat(labels).hasSize(12);
        assertThat(labels.get(0)).isEqualTo("A1");
        assertThat(labels.get(9)).isEqualTo("A10");
        assertThat(labels.get(10)).isEqualTo("B1");
        assertThat(labels.get(11)).isEqualTo("B2");
    }
}
