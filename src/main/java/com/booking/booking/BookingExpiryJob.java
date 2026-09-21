package com.booking.booking;

import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Releases seats held by PENDING bookings that were never confirmed.
 *
 * Without this, one abandoned checkout removes a seat from sale forever. The scan
 * hits the (status, expires_at) index, so it stays cheap as the table grows.
 */
@Component
public class BookingExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(BookingExpiryJob.class);

    private final BookingRepository bookingRepository;

    public BookingExpiryJob(BookingRepository bookingRepository) {
        this.bookingRepository = bookingRepository;
    }

    @Scheduled(fixedDelayString = "${booking.expiry-job-interval-ms}")
    @Transactional
    public void releaseExpiredHolds() {
        List<Booking> expired = bookingRepository
                .findByStatusAndExpiresAtBefore(BookingStatus.PENDING, Instant.now());

        if (expired.isEmpty()) {
            return;
        }

        expired.forEach(Booking::expire);
        log.info("Expired {} booking(s) and released their seats", expired.size());
    }
}
