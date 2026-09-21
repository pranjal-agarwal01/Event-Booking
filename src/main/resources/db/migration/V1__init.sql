-- Week 1 schema: events and their seats.
-- Users and bookings arrive in later migrations (V3, V4) so you practise the
-- real Flyway workflow: never edit an applied migration, always add a new one.

CREATE TABLE events (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(200) NOT NULL,
    venue       VARCHAR(200) NOT NULL,
    starts_at   TIMESTAMPTZ  NOT NULL,
    total_seats INTEGER      NOT NULL CHECK (total_seats > 0),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE seats (
    id          BIGSERIAL PRIMARY KEY,
    event_id    BIGINT      NOT NULL REFERENCES events (id) ON DELETE CASCADE,
    seat_number VARCHAR(10) NOT NULL,
    status      VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE',
    -- Optimistic locking column. Hibernate bumps this on every update and fails
    -- the write if another transaction changed the row first. This single column
    -- is what stops two users booking the same seat in week 3.
    version     BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uq_seat_per_event UNIQUE (event_id, seat_number),
    CONSTRAINT ck_seat_status CHECK (status IN ('AVAILABLE', 'HELD', 'BOOKED'))
);

CREATE INDEX idx_seats_event_id ON seats (event_id);
CREATE INDEX idx_seats_event_status ON seats (event_id, status);
