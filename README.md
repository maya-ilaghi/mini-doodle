# doodle

A small, high-performance meeting-scheduling service (a "mini Doodle"), built with
Spring Boot 3 and PostgreSQL. Users define available time slots in a personal
calendar, convert slots into meetings with a title, description and participants,
query their free/busy availability over a time frame, and the service guarantees a
person is never double-booked.

## Tech stack

| Concern            | Choice                                      |
|--------------------|---------------------------------------------|
| Language / runtime | Java 21, Spring Boot 3.3                     |
| Persistence        | PostgreSQL 16, Spring Data JPA / Hibernate   |
| Migrations         | Flyway (`ddl-auto: validate` — Flyway owns the schema) |
| API docs           | springdoc / OpenAPI (`/swagger-ui.html`)     |
| Metrics            | Micrometer → Prometheus (`/actuator/prometheus`) |
| Tests              | JUnit 5, Testcontainers                      |
| Packaging          | Multi-stage Dockerfile + Docker Compose      |

## Running locally

Requires Docker only — the build runs inside the container, so no local JDK or Maven
is needed.

```bash
docker compose up --build
```

This starts PostgreSQL, applies the Flyway migration on boot, and serves the app on
`http://localhost:8080`.

- Health:   `GET /actuator/health`
- Metrics:  `GET /actuator/prometheus`
- API docs: `http://localhost:8080/swagger-ui.html`

## Consuming the API

There is deliberately **no `/calendars` resource** — the calendar is an internal
domain concept only (see Design decisions). Clients work with users, slots and meetings.

```bash
# 1. Create a user (their calendar is created internally)
curl -X POST localhost:8080/api/v1/users \
  -H 'Content-Type: application/json' \
  -d '{"email":"ada@example.com","name":"Ada"}'
# -> { "id": "<userId>", "email": "ada@example.com", "name": "Ada" }

# 2. Create a free slot in that user's calendar
curl -X POST localhost:8080/api/v1/users/<userId>/slots \
  -H 'Content-Type: application/json' \
  -d '{"startTime":"2026-08-10T10:00:00Z","endTime":"2026-08-10T10:30:00Z"}'
# -> { "id": "<slotId>", ..., "status": "FREE" }

# 3. Book the slot as a meeting
curl -X POST localhost:8080/api/v1/slots/<slotId>/meeting \
  -H 'Content-Type: application/json' \
  -d '{"title":"Sync","description":"weekly","participants":["bob@example.com"]}'
# -> 201 Created; the slot is now BUSY

# 4. List a user's slots in a time window (paginated)
curl "localhost:8080/api/v1/users/<userId>/slots?from=2026-08-10T00:00:00Z&to=2026-08-11T00:00:00Z"

# 5. Aggregated free/busy availability over a time frame
curl "localhost:8080/api/v1/users/<userId>/slots/availability?from=2026-08-10T00:00:00Z&to=2026-08-11T00:00:00Z"
# -> { "from": "...", "to": "...", "free": [ ... ], "busy": [ ... ] }
```

Booking the same slot twice returns **409 Conflict** (`Slot is already booked`).
Creating an overlapping slot returns **409 Conflict** (`Slot overlaps an existing slot`).

### Endpoints

| Method | Path | Purpose |
|--------|------|---------|
| `POST`   | `/api/v1/users` | Create a user |
| `POST`   | `/api/v1/users/{userId}/slots` | Create a free slot |
| `GET`    | `/api/v1/users/{userId}/slots?from=&to=` | List slots in a window (paginated) |
| `GET`    | `/api/v1/users/{userId}/slots/availability?from=&to=` | Aggregated free/busy view |
| `PATCH`  | `/api/v1/users/{userId}/slots/{slotId}` | Modify a slot's times |
| `DELETE` | `/api/v1/users/{userId}/slots/{slotId}` | Delete a slot |
| `POST`   | `/api/v1/slots/{slotId}/meeting` | Book a slot as a meeting |

## Design decisions

**Calendar is an internal domain concept.** Every user owns exactly one calendar (the
aggregate that holds their slots and meetings), but it is never exposed in the API — a
deliberate reading of the brief, which names "calendar" as a domain term only. DTOs at
the web layer keep entities (and the calendar) from leaking to clients.

**No double-booking, enforced by the database.** Rather than check-then-insert in
application code (which races under concurrency), overlap is prevented in PostgreSQL with
a GiST exclusion constraint over a generated `tstzrange`:

```sql
during TSTZRANGE GENERATED ALWAYS AS (tstzrange(start_time, end_time, '[)')) STORED,
CONSTRAINT time_slot_no_overlap
    EXCLUDE USING gist (calendar_id WITH =, during WITH &&)
```

Two overlapping slots in the same calendar are physically impossible at the storage layer,
atomically, regardless of concurrency. Half-open ranges (`[)`) mean back-to-back slots
(10:00–10:30 and 10:30–11:00) do not conflict. The service catches the constraint
violation and translates it to `409 Conflict`. Because the rule lives in the schema, it
holds for every write path (create and modify) without being re-implemented per method.

**Optimistic locking on bookings.** The exclusion constraint stops overlapping *slots*,
but not two requests booking the *same* free slot at once (same row, no new overlap). A
`@Version` column on `TimeSlot` handles that race: the second concurrent booking fails the
version check and gets `409` instead of silently double-booking. Both guards are needed —
they cover different races.

**Availability is aggregated by slot status.** The free/busy view partitions a user's
slots in the requested window by their status. A slot's status already is the free/busy
truth in this model, so this is a simple, honest aggregation (rather than computing gaps
between meetings, which would be a different calendar-fill model).

**Built for hundreds of users / thousands of slots.** Composite index on
`(calendar_id, start_time)` for time-window queries, a second on `(calendar_id, status)`
for free/busy filtering, paginated slot listings, a stateless service tier (scales
horizontally), and `open-in-view: false`.

**UUID primary keys**, assigned by the application: no DB round-trip to generate ids, safe
to expose in URLs, and they don't leak row counts.

**Layering:** `controller` (REST + DTOs) → `service` (business rules, transactions) →
`repository` (Spring Data JPA) → `domain` (entities).

## Data model

```
User 1───1 Calendar 1───* TimeSlot 1───0..1 Meeting 1───* Participant
```

A meeting is a booked slot (1:1); creating one flips the slot to `BUSY`. Participants are
invited by email (with an optional link to a registered user) and carry their own
response status.

## Testing

Unit-style tests run in the default build. The integration test
(`BookingConcurrencyTest`) uses Testcontainers to start a real PostgreSQL and fires two
concurrent bookings at the same slot, asserting exactly one succeeds — proving the
optimistic-lock guard end to end.

Integration tests are tagged `integration` and excluded from the default build, so
`mvn clean install` runs fast and green without requiring Docker:

```bash
mvn clean install              # unit tests only — no Docker needed
mvn test -Dgroups=integration  # runs the Testcontainers integration test
```

The integration test needs a Docker daemon exposing API ≥ 1.40 (works out of the box on
Docker Desktop, Linux, and CI). The same behaviour was also verified manually: booking a
slot twice returns `409`, and creating an overlapping slot returns `409`.

## What I'd add with more time

- Authentication / authorization (per-user access to slots).
- Participants fully linked to `User` records with RSVP responses.
- Multi-calendar support per user (the schema already allows it by relaxing the unique constraint).
- More test coverage (service-level unit tests, controller/web-layer tests).
- Rate limiting and caching of availability queries.
- A duplicate-email conflict mapped to `409` (currently surfaces as a generic error).