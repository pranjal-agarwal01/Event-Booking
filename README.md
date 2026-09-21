# Event Booking API

A Spring Boot REST API for event seat booking, built around the problem most
booking projects quietly skip: **two people clicking "book" on the last seat at
the same millisecond.**

Exactly one of them gets the seat. There is a 50-thread test that proves it, run
against a real PostgreSQL, for two different locking strategies.

---

## Stack

| Concern | Choice |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 3.5.3 |
| Security | Spring Security + JWT (HMAC-SHA), BCrypt, refresh-token rotation |
| Persistence | Spring Data JPA / Hibernate |
| Database | PostgreSQL 16 |
| Migrations | Flyway |
| Docs | springdoc-openapi (Swagger UI) |
| Tests | JUnit 5, Mockito, Testcontainers |
| Packaging | Multi-stage Docker build, non-root runtime |
| Hosting | Render (Blueprint in `render.yaml`) |

## Quick start

```bash
docker compose up -d      # Postgres on :5432
mvn spring-boot:run       # API on :8080
```

Or run the whole thing in containers:

```bash
docker compose --profile app up -d --build
```

- **Swagger UI** — http://localhost:8080/swagger-ui.html
- **Health** — http://localhost:8080/actuator/health

Two demo accounts are seeded, both with password `password123`:

| Email | Role |
|---|---|
| `admin@booking.dev` | ADMIN — can create events |
| `user@booking.dev` | USER — can book seats |

```bash
curl -s -X POST localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"user@booking.dev","password":"password123"}'
```

## Configuration

Everything environment-specific comes from environment variables, with local
defaults that match `docker-compose.yml`:

| Variable | Default | Purpose |
|---|---|---|
| `PORT` | `8080` | HTTP port (Render sets this) |
| `DB_HOST` / `DB_PORT` / `DB_NAME` | `localhost` / `5432` / `bookingdb` | PostgreSQL location |
| `DB_USERNAME` / `DB_PASSWORD` | `booking` / `booking` | PostgreSQL credentials |
| `JWT_SECRET` | dev-only value | Base64, at least 32 bytes. **Must** be set outside local dev |
| `LOGGING_LEVEL_COM_BOOKING` | `DEBUG` | Application log level |

## Deploying to Render

The repo includes a [`render.yaml`](render.yaml) Blueprint that creates a free
PostgreSQL database and a Docker web service, wires the database credentials into
the service, and generates a random `JWT_SECRET`.

1. Push this repository to GitHub.
2. In the Render dashboard: **New → Blueprint**, and select the repository.
3. Approve the two resources it proposes. The first build takes several minutes.
4. Open `https://<your-service>.onrender.com/swagger-ui.html`.

Flyway runs the migrations on first boot, so the database arrives with the demo
events and accounts already in it.

Things to know about the free tier:

- **The service sleeps after ~15 minutes idle** and takes up to a minute to wake.
  Open the URL yourself a minute before anyone else looks at it.
- **Free databases expire** after a fixed period (check Render's current terms);
  the Blueprint can recreate one, and the migrations reseed it.
- Render allows one free database per workspace, so the Blueprint fails if you
  already have one.

## API

| Method | Path | Access | Notes |
|---|---|---|---|
| `POST` | `/api/v1/auth/register` | public | Creates a USER account |
| `POST` | `/api/v1/auth/login` | public | Returns access + refresh token |
| `POST` | `/api/v1/auth/refresh` | public | Rotates the refresh token |
| `POST` | `/api/v1/auth/logout` | public | Revokes a refresh token |
| `GET` | `/api/v1/events` | public | Paged, sorted by start time |
| `GET` | `/api/v1/events/{id}` | public | Includes live available-seat count |
| `GET` | `/api/v1/events/{id}/seats` | public | Seat map with per-seat status |
| `POST` | `/api/v1/events` | **ADMIN** | Creates event + auto-generates seats |
| `POST` | `/api/v1/bookings` | authenticated | Holds seats; accepts `Idempotency-Key` |
| `POST` | `/api/v1/bookings/{ref}/confirm` | owner/admin | PENDING → CONFIRMED |
| `POST` | `/api/v1/bookings/{ref}/cancel` | owner/admin | → CANCELLED, seats released |
| `GET` | `/api/v1/bookings/me` | authenticated | The caller's bookings, paged |
| `GET` | `/api/v1/bookings/{ref}` | owner/admin | One booking |

Every error, from any endpoint, has the same shape:

```json
{
  "timestamp": "2026-09-18T12:00:00Z",
  "status": 409,
  "error": "Conflict",
  "message": "Seats already taken: A4",
  "path": "/api/v1/bookings",
  "fieldErrors": null
}
```

## Domain model

```
events ──< seats
   │         │
   │         │ (booking_seats)
   └──< bookings >──┘
          │
        users ──< refresh_tokens
```

Seats move `AVAILABLE → HELD → BOOKED`, and back to `AVAILABLE` on cancel or
expiry. Bookings move `PENDING → CONFIRMED`, or `→ CANCELLED` / `→ EXPIRED`.
Invalid transitions (confirming a cancelled booking) return **409**, not 500 —
the rules live on the `Booking` entity itself, not scattered across the service.

---

## The concurrency problem

A naive booking endpoint does this:

```java
if (seat.getStatus() == AVAILABLE) {   // thread A reads AVAILABLE
    seat.setStatus(HELD);              // thread B also read AVAILABLE
    save(seat);                        // both write. Two people, one seat.
}
```

The window between the check and the write is small, but on a popular event it
is hit constantly. Two defences are in place.

### 1. Locking the seat row

Switchable with `booking.locking-strategy`, so both can be benchmarked against
the same test.

**`OPTIMISTIC`** (default) — `Seat` carries a `@Version` column. Hibernate turns
every update into:

```sql
UPDATE seats SET status = 'HELD', version = 4 WHERE id = 12 AND version = 3;
```

If another transaction already moved the row, `version = 3` matches nothing, the
update affects 0 rows, and Hibernate raises `OptimisticLockingFailureException`.
The service translates that into **409 Conflict**. Nobody blocks, and the loser
finds out immediately.

**`PESSIMISTIC`** — the seats are read with `SELECT ... FOR UPDATE`. Competing
transactions block at the read instead of failing at the write, then see `HELD`
and get the same 409. The repository query is `ORDER BY s.id` deliberately: two
transactions locking overlapping seat sets in different orders can deadlock;
locking in a consistent order removes that.

The trade-off, which is the real interview answer: optimistic is faster when
conflicts are rare because nothing waits, and worse when they are common because
work gets thrown away. Pessimistic is the opposite. A seat booking system is
mostly low-contention with brief violent spikes, which is why it is worth having
both and being able to say why you picked one.

**Why no retry loop?** When the optimistic check fails here, the seat is now
`HELD` by someone else — retrying just fails again with a different message. A
retry only helps when the conflicting transaction rolled back, which is rare
enough not to justify the complexity. Returning 409 and letting the client pick
another seat is the honest answer.

### 2. Idempotency keys

A double-clicked button, a flaky network, or a client retry must not create two
bookings. Clients send `Idempotency-Key: <something-unique>`; the column has a
`UNIQUE` constraint, and a repeat of the same key returns the original booking
instead of making a new one.

The application-level check is only the fast path — the database constraint is
what actually enforces it, because the check and the insert are not atomic.

### 3. Holds expire

A `PENDING` booking holds its seats for `booking.hold-minutes` (default 10). A
scheduled job releases anything past its deadline, so an abandoned checkout does
not remove a seat from sale forever. The scan matches the
`(status, expires_at)` index.

## Test evidence

```
mvn test
```

19 tests, the integration ones against real PostgreSQL via Testcontainers (not
H2 — the locking behaviour under test is exactly what differs between databases).

| Test | What it proves |
|---|---|
| `OptimisticLockingConcurrencyIT` | 50 threads, 1 seat, `@Version` → exactly 1 winner |
| `PessimisticLockingConcurrencyIT` | Same, with `SELECT ... FOR UPDATE` → exactly 1 winner |
| `BookingFlowIT` | hold → confirm → cancel, seat statuses follow; duplicate `Idempotency-Key` returns the same booking; double-book returns 409; illegal transitions return 409 |
| `AuthAndAccessControlIT` | 401 anonymous, 403 for USER on admin routes, refresh rotation invalidates the old token, login never reveals whether an email exists |
| `BookingExpiryJobIT` | Stale holds are released; confirmed bookings are never touched |
| `EventControllerTest` | Validation errors, 404s and malformed path variables all return the standard error shape |
| `EventServiceTest` | Seat-label generation, pure unit test, no Spring |

The concurrency test lines all 50 threads up behind a `CountDownLatch` so they
hit the database together, then asserts one success, 49 clean rejections, zero
unexpected exceptions, one booking row, and one `HELD` seat.

---

## Design decisions

- **Flyway owns the schema; `ddl-auto: validate`.** Hibernate only checks that the
  entities match and refuses to boot if they drift. `ddl-auto: update` silently
  mutates production schemas and cannot be reviewed.
- **`open-in-view: false`.** The persistence context closes when the service
  method returns, so a lazy load can never fire during JSON serialization. DTOs
  are built inside the transaction, on purpose.
- **DTOs at every boundary.** No entity is ever accepted from, or returned to, a
  client. Request shape and table shape change for different reasons.
- **Bookings are addressed by UUID**, not by their primary key. Sequential ids
  leak volume and invite enumeration.
- **State transitions live on the entity** (`Booking.confirm()`, `.cancel()`,
  `.expire()`), so an invalid transition is impossible to express, rather than
  being a rule the service is trusted to remember.
- **Access tokens are short (15 min) and unstored; refresh tokens are long (7
  days) and stored.** A JWT cannot be revoked, so it must expire quickly; the
  refresh token lives in the database precisely so it *can* be revoked, and it
  rotates on every use.
- **Counts come from `COUNT` queries**, and booking lookups use `@EntityGraph` to
  fetch seats in one query. Both exist to avoid N+1.
- **Login failures never say which part was wrong.** "Invalid email or password"
  for both cases, so the endpoint cannot be used to enumerate accounts.
- **Configuration is environment-driven** (twelve-factor style): the same image
  runs locally, in Compose, and on Render with only environment variables changing.

## Known limitations

Worth reading before an interview — being able to name these is worth more than
pretending they do not exist.

1. **Concurrent requests with the same `Idempotency-Key` return 409**, rather
   than both receiving the same booking. The second request loses the unique
   constraint race and its transaction is already doomed, so it cannot re-read
   the winner in the same transaction. The fix is to catch the violation outside
   the transaction and re-query in a new one; that is a second bean and was left
   out to keep the flow readable.
2. **No payment step.** `confirm` is where a payment gateway callback would go.
3. **The expiry job assumes a single instance.** Two instances would both scan
   and race. Fixing it properly means `ShedLock` or a database advisory lock.
4. **No rate limiting** on login, so the API is brute-forceable.
5. **Refresh tokens are never garbage collected** — revoked rows accumulate.
6. **The seat map is returned in full**, which is fine at 260 seats and wrong at
   stadium scale.
7. **The demo accounts are seeded everywhere, including a deployed instance.**
   That is deliberate for a portfolio demo — anyone can try it — but it means
   anyone can also log in as the demo admin and create events.

## Questions you should be able to answer about this code

If you built this to learn, these are the follow-ups an interviewer will reach
for. Each one has a real answer in the code — go find it before you need it.

- Why does `Seat` have a `@Version` column, and what SQL does it change?
- What happens if two transactions both read a seat as `AVAILABLE`?
- Optimistic vs pessimistic locking — when would you switch?
- Why is `ORDER BY s.id` in the pessimistic query not cosmetic?
- What is `@Transactional` actually doing, and why does calling a `@Transactional`
  method from inside the same class not work?
- Why `ddl-auto: validate` instead of `update`?
- What is the N+1 problem, and where would it have appeared here?
- Why is `open-in-view: false`, and what breaks if you flip it?
- Why can a JWT not be revoked, and how does the refresh token work around it?
- Why store a BCrypt hash instead of a SHA-256 one?
- Why does the API return 409 rather than 400 when a seat is taken?

## Roadmap

- [x] Schema, events, seat maps, validation, uniform error handling, Swagger
- [x] Users, JWT auth with roles, refresh-token rotation
- [x] Booking flow with optimistic + pessimistic locking and idempotency keys
- [x] Hold expiry job, Dockerfile, container compose profile
- [x] Render Blueprint for one-click deployment
- [ ] Payment step on confirm
- [ ] Redis cache for seat maps; rate limiting on auth
- [ ] `ShedLock` so the expiry job is safe on multiple instances

## Troubleshooting

- **Connection refused on startup** — Postgres is not up. `docker compose ps`.
- **Schema validation error on boot** — an entity and its migration disagree. Add
  a *new* migration; never edit one that has already been applied.
- **Tests fail with "Could not find a valid Docker environment"** — Docker Desktop
  is not running. Testcontainers needs it.
- **Render deploy is healthy but Swagger calls fail** — check the Swagger server
  URL uses `https://`; `server.forward-headers-strategy: framework` handles this.
- **ByteBuddy error on a very new JDK** — run with
  `-Dnet.bytebuddy.experimental=true`, or use JDK 21.
