-- Week 3: bookings. A booking holds one or more seats for one event.

CREATE TABLE bookings (
    id              BIGSERIAL PRIMARY KEY,
    reference       UUID        NOT NULL UNIQUE,
    user_id         BIGINT      NOT NULL REFERENCES users (id),
    event_id        BIGINT      NOT NULL REFERENCES events (id),
    status          VARCHAR(20) NOT NULL,
    -- Client-supplied key. The UNIQUE constraint is what actually enforces
    -- idempotency; the application check in front of it is just the fast path.
    idempotency_key VARCHAR(100) UNIQUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ,
    confirmed_at    TIMESTAMPTZ,
    CONSTRAINT ck_booking_status
        CHECK (status IN ('PENDING', 'CONFIRMED', 'CANCELLED', 'EXPIRED'))
);

CREATE TABLE booking_seats (
    booking_id BIGINT NOT NULL REFERENCES bookings (id) ON DELETE CASCADE,
    seat_id    BIGINT NOT NULL REFERENCES seats (id),
    PRIMARY KEY (booking_id, seat_id)
);

CREATE INDEX idx_bookings_user ON bookings (user_id);
-- The expiry job scans on exactly this pair, so index it.
CREATE INDEX idx_bookings_status_expires ON bookings (status, expires_at);
