package com.booking.event;

import com.booking.common.NotFoundException;
import com.booking.event.dto.CreateEventRequest;
import com.booking.event.dto.EventResponse;
import com.booking.seat.Seat;
import com.booking.seat.SeatRepository;
import com.booking.seat.SeatStatus;
import com.booking.seat.dto.SeatResponse;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business logic lives here, not in the controller. The controller's only job is
 * HTTP; this class knows what an event is.
 */
@Service
public class EventService {

    private static final int SEATS_PER_ROW = 10;

    private final EventRepository eventRepository;
    private final SeatRepository seatRepository;

    // Constructor injection - no @Autowired needed on a single constructor, and it
    // makes the dependencies obvious and the class testable without Spring.
    public EventService(EventRepository eventRepository, SeatRepository seatRepository) {
        this.eventRepository = eventRepository;
        this.seatRepository = seatRepository;
    }

    @Transactional
    public EventResponse create(CreateEventRequest request) {
        Event event = new Event(request.name(), request.venue(),
                request.startsAt(), request.totalSeats());

        for (int i = 0; i < request.totalSeats(); i++) {
            char row = (char) ('A' + (i / SEATS_PER_ROW));
            int number = (i % SEATS_PER_ROW) + 1;
            event.addSeat(new Seat(event, row + String.valueOf(number)));
        }

        Event saved = eventRepository.save(event);
        return EventResponse.from(saved, saved.getTotalSeats());
    }

    @Transactional(readOnly = true)
    public Page<EventResponse> list(Pageable pageable) {
        return eventRepository.findAll(pageable)
                .map(event -> EventResponse.from(event, countAvailable(event.getId())));
    }

    @Transactional(readOnly = true)
    public EventResponse getById(Long id) {
        Event event = eventRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Event", id));
        return EventResponse.from(event, countAvailable(id));
    }

    @Transactional(readOnly = true)
    public List<SeatResponse> getSeats(Long eventId) {
        if (!eventRepository.existsById(eventId)) {
            throw new NotFoundException("Event", eventId);
        }
        return seatRepository.findByEventIdOrderByIdAsc(eventId).stream()
                .map(SeatResponse::from)
                .toList();
    }

    /** A COUNT query, not seats.size() - loading 260 rows to count them is the N+1 trap. */
    private long countAvailable(Long eventId) {
        return seatRepository.countByEventIdAndStatus(eventId, SeatStatus.AVAILABLE);
    }
}
