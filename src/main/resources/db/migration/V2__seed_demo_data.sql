-- Demo data so Swagger has something to show on first run.
INSERT INTO events (name, venue, starts_at, total_seats) VALUES
    ('Coldplay - Music of the Spheres', 'DY Patil Stadium, Mumbai', now() + INTERVAL '30 days', 20),
    ('Java Conference 2026',            'BIEC, Bengaluru',          now() + INTERVAL '60 days', 10);

-- Seats A1..A10 / B1..B10 for event 1, A1..A10 for event 2.
INSERT INTO seats (event_id, seat_number)
SELECT 1, 'A' || g FROM generate_series(1, 10) g;
INSERT INTO seats (event_id, seat_number)
SELECT 1, 'B' || g FROM generate_series(1, 10) g;
INSERT INTO seats (event_id, seat_number)
SELECT 2, 'A' || g FROM generate_series(1, 10) g;
