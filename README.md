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
  -d '{"startTime":"2026-11-02T10:00:00Z","endTime":"2026-11-02T10:30:00Z"}'
# -> { "id": "<slotId>", ..., "status": "FREE" }

# 3. Book the slot as a meeting
curl -X POST localhost:8080/api/v1/slots/<slotId>/meeting \
  -H 'Content-Type: application/json' \
  -d '{"title":"Sync","description":"weekly","participants":["bob@example.com"]}'
# -> 201 Created; the slot is now BUSY

# 4. List a user's slots in a time window (paginated, sorted by start time)
curl "localhost:8080/api/v1/users/<userId>/slots?from=2026-11-02T00:00:00Z&to=2026-11-03T00:00:00Z"

# 5. Aggregated free/busy availability over a time frame
curl "localhost:8080/api/v1/users/<userId>/slots/availability?from=2026-11-02T00:00:00Z&to=2026-11-03T00:00:00Z"
# -> { "from": "...", "to": "...", "free": [ ... ], "busy": [ ... ] }
```

```bash
# 6. Cancel a meeting; the slot becomes FREE again
curl -X DELETE localhost:8080/api/v1/slots/<slotId>/meeting
# -> 204 No Content

# 7. Mark a slot busy without booking a meeting (block the time), or free again
curl -X PATCH localhost:8080/api/v1/users/<userId>/slots/<slotId>/status \
  -H 'Content-Type: application/json' \
  -d '{"status":"BUSY"}'
# -> { "id": "<slotId>", ..., "status": "BUSY" }
```

A slot "in a window" is any slot that **overlaps** it (`start < to` and `end > from`), so a
slot that starts before `from` but runs into the window is included; one that only touches
an edge is not.

### Errors

All errors share one body shape: `{"message": "...", "details": ["field: problem", ...]}`.

| Status | When |
|--------|------|
| `400 Bad Request` | Invalid body (missing/blank fields, bad email, `endTime` not after `startTime`), malformed JSON or timestamp, invalid UUID, `from` not before `to` |
| `404 Not Found` | Unknown user, slot or meeting, or a slot that belongs to a different user |
| `409 Conflict` | Overlapping slot, slot already booked or marked busy, editing/deleting a slot or changing its status while it has a meeting, duplicate email |

### Endpoints

| Method | Path | Purpose |
|--------|------|---------|
| `POST`   | `/api/v1/users` | Create a user |
| `POST`   | `/api/v1/users/{userId}/slots` | Create a free slot |
| `GET`    | `/api/v1/users/{userId}/slots?from=&to=` | List slots in a window (paginated) |
| `GET`    | `/api/v1/users/{userId}/slots/availability?from=&to=` | Aggregated free/busy view |
| `PATCH`  | `/api/v1/users/{userId}/slots/{slotId}` | Modify a slot's times |
| `PATCH`  | `/api/v1/users/{userId}/slots/{slotId}/status` | Mark a slot `BUSY` or `FREE` |
| `DELETE` | `/api/v1/users/{userId}/slots/{slotId}` | Delete a slot |
| `POST`   | `/api/v1/slots/{slotId}/meeting` | Book a slot as a meeting |
| `DELETE` | `/api/v1/slots/{slotId}/meeting` | Cancel the meeting and free the slot |

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

**Row lock on bookings.** The exclusion constraint stops overlapping *slots*, but not two
requests booking the *same* free slot at once (same row, no new overlap). Booking loads the
slot with `SELECT ... FOR UPDATE`, so concurrent bookings of one slot are serialised: the
second transaction waits, then sees the slot as `BUSY` and gets `409 Slot is already booked`.
A pessimistic lock fits here because contention is on a single hot row and the critical
section is tiny. The `UNIQUE(meeting.slot_id)` constraint stays as a last line of defence,
and any constraint violation that still reaches the web layer is mapped to `409`, never
`500`. The `@Version` column on `TimeSlot` remains for lost-update protection on edits.

**Booked slots are protected.** A slot with a meeting can't be moved or deleted (`409`);
otherwise the meeting would be silently rescheduled or cascade-deleted. Cancel the meeting
first. Slot edits and deletes are scoped to the owner's calendar, and a slot owned by
someone else returns `404`, so slot ids can't be probed.

**Busy has two sources.** A slot is `BUSY` either because a meeting is booked on it, or
because its owner marked it busy to block the time. A meeting always wins: a slot with a
meeting can't be set back to `FREE` (cancel the meeting instead), while a slot that is only
marked busy has no meeting to protect and stays editable. Booking a slot marked busy returns
`409 Slot is marked busy`. Status changes take the same row lock as booking, so the two can't
interleave.

**Availability is aggregated by slot status.** The free/busy view partitions every slot that
overlaps the requested window by its status. A slot's status already is the free/busy
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

A meeting is a booked slot (1:1); creating one flips the slot to `BUSY`. A slot can also be
marked `BUSY` by hand, without a meeting. Participants are
invited by email (with an optional link to a registered user) and carry their own
response status.

## Testing

- `RequestValidationTest` (`@WebMvcTest`, no database) checks that invalid input is
  rejected with `400` before reaching a service. It runs in the default build.
- `ApiIntegrationTest` (MockMvc + Testcontainers PostgreSQL) drives the HTTP API end to end:
  overlap and back-to-back slots, ownership, double booking, booked-slot protection,
  cancellation, marking slots busy/free, window edges, duplicate emails and error codes.
- `BookingConcurrencyTest` fires 8 parallel bookings at one slot and asserts exactly one
  succeeds and every other request fails with `SlotAlreadyBookedException` (`409`).

Integration tests are tagged `integration` and excluded from the default build, so
`mvn clean install` runs fast and green without requiring Docker:

```bash
mvn clean install              # unit tests only — no Docker needed
mvn verify -Pintegration       # runs the Testcontainers tests only (needs Docker)
```

The integration tests need a running Docker daemon (Docker Desktop, Colima, Linux, or CI).
Testcontainers 1.21.4+ is required for current Docker Engine releases. With Colima, point
Testcontainers at its socket first:

```bash
export DOCKER_HOST="unix://$HOME/.colima/default/docker.sock"
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
```

## What I'd add with more time

- Authentication / authorization (per-user access to slots).
- Participants fully linked to `User` records with RSVP responses.
- Multi-calendar support per user (the schema already allows it by relaxing the unique constraint).
- Service-level unit tests alongside the API-level ones.
- Rate limiting and caching of availability queries.
- Configurable slot durations (e.g. generate 30-minute slots across a day) and editing
  meeting details after booking.