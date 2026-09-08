# Vision: Event Ticketing Platform (Java / Spring Boot)

## Goal

Build an HTTP service that runs the full lifecycle of selling tickets to seated
events: an organiser creates an event and its seats, sets prices, and puts the
event on sale; a customer holds a seat, buys it (taking payment and issuing a
ticket), or cancels a booking for a refund; and anyone can read published
availability and prices.

## Domain model

- **Event** — `id`, `venue`, `onSaleAt` (ISO-8601 instant), `status` ∈
  `draft | on_sale | cancelled`. Starts `draft`.
- **Seat** — `id`, `eventId`, `section`, `row`, `number` (positive int), `tier`,
  `state` ∈ `available | blocked | sold | withdrawn`. Starts `available`.
- **PriceTier** — closed set `PREMIUM | STANDARD | ECONOMY | ACCESSIBLE | RESTRICTED_VIEW`.
- **Money** — non-negative integer amount in **minor units** + `currency` ∈
  `USD | EUR | GBP`. Never a floating-point type.
- **Price record** — append-only history per `(event, tier[, seat])` scope, each
  row carrying a monotonically increasing `version`; a separate `current_price`
  projection holds the latest.
- **Reservation** — exactly **one row per seat** (`seatId` is the primary key),
  `state` ∈ `held | confirmed | cancelled | expired`, `customerId`, `expiresAt`,
  `heldSince`, a non-key `claimId` (UUID) regenerated on every successful claim,
  and a per-seat `version`.
- **Booking** — `id`, `reservationId`, `seatId`, `eventId`, `customerId`,
  `status` ∈ `confirmed | cancelled`, `ticketId`, `reservationClaimId` (the
  point-in-time claim it was sold under, no FK).
- **Payment** — `bookingId`, `status` ∈ `authorized | captured | voided | refunded`,
  `receiptId`, `Money`.
- **Ticket** — `id`, `bookingId`, `seatId`, `status` ∈ `issued | invalidated`.
- **Domain facts** (cross-subsystem):
  `SeatSold(seatId, eventId, bookingId, version)`,
  `SeatReleased(seatId, eventId, version)`,
  `PriceChanged(eventId, tier, amountMinor, currency, version)`.

## Requirements

All endpoints are JSON over HTTP under `/api/v1`. Every endpoint has a declared
access level: `public`, `authenticated` (any signed-in customer), `role:admin`
(organiser), or `role:operator` (operations). A known domain failure must map to
a specific HTTP status — never a generic 500.

### 1. Event management

1. **Create event** — `POST /events/create`, `role:admin`.
   Body `{ "venue", "onSaleAt" }` → `{ "event": "<uuid>" }`. Creates a `draft`
   event. `400` blank venue or unparseable `onSaleAt`; `503` store unavailable.
2. **Open event** — `POST /events/open/{event}`, `role:admin`.
   Guarded transition `draft → on_sale`. `404` unknown event; `409` already on
   sale, cancelled, or a raced concurrent transition; `503`.
3. **Cancel event** — `POST /events/cancel/{event}`, `role:admin`.
   Guarded transition to `cancelled`. **Idempotent**: cancelling an already
   cancelled event returns success. **No cascade** — seats and bookings are left
   untouched, no fact published. `404` unknown event; `409` raced transition;
   `503`.
4. **Add seat** — `POST /seats/add`, `role:admin`.
   Body `{ "event", "section", "row", "number", "tier" }` → `{ "seat": "<uuid>" }`.
   Seat is created `available`. Refused if the event is `cancelled`. `400` blank
   section/row; `422` non-positive number or unknown tier; `404` unknown event;
   `409` event cancelled; `503`.
5. **Block seat** — `POST /seats/block/{seat}`, `role:admin`.
   Guarded `available → blocked`. `409` seat not `available`; `503`.
6. **Release seat** — `POST /seats/release/{seat}`, `role:admin`.
   Guarded `blocked → available`. `409` seat not `blocked`; `503`.
7. **Seat sellability** — `GET /seats/sellability/{seat}`, `public`.
   → `{ "seat", "state", "sellable" }`. `sellable` is `true` for `available` and
   `sold`, `false` for `blocked` and `withdrawn` (decided by an exhaustive switch
   over the seat states). `404` unknown seat; `400`; `503`.
8. **Sale status** — `GET /events/status/{event}`, `public`.
   → `{ "event", "onSale", "onSaleAt" }`. `404` unknown event; `400`; `503`.
9. **Fact consumers (no route)** — on `SeatSold` set the seat's authoritative
   state to `sold`; on `SeatReleased` set it back to `available`. Both are
   idempotent and ignore a fact whose `version` is not newer than the seat's
   recorded version.

### 2. Pricing

10. **Set price** — `POST /pricing/set`, `role:admin`.
    Body `{ "event", "tier", "amount", "currency" }` (`amount` a decimal string)
    → `{ "version": <n> }`. Appends a new price version for the scope and updates
    the current-price projection. `400` malformed amount; `422` unknown tier or
    unknown currency; `503`. Publishes `PriceChanged`.
11. **Adjust price** — `POST /pricing/adjust`, `role:admin`.
    Body `{ "event", "tier", "percent" }` (`percent` a positive integer, `110`
    means +10%) → `{ "version": <n> }`. Scales the current price and appends a
    new version. `404` no current price for the scope; `422` non-positive
    percent; `503`. Publishes `PriceChanged`.
12. **Quote price (authoritative)** — `GET /pricing/quote/{event}/{tier}`, `public`.
    → `{ "event", "tier", "amountMinor", "currency", "version" }` from the
    current-price projection. `404` no price; `422` unknown tier; `503`.
13. A version is allocated **at most once** per `(event, tier)`: a concurrent
    double-allocation must make the loser's write fail visibly, not silently
    append a duplicate version.

### 3. Booking

14. **Acquire hold** — `POST /booking/holds`, `authenticated`.
    Body `{ "customer", "event", "seat" }` → `{ "reservation": "<claimId>", "state": "held" }`.
    A decaying **15-minute** hold created by the single guarded seat claim
    (see requirement 24). The seat's owner may re-acquire their own still-live
    hold, which refreshes the TTL — but only within a **60-minute total lifetime**
    measured from `heldSince`, so a client re-acquiring on a timer cannot squat a
    seat forever. `409` seat unavailable or not sellable; `400`; `503`.
15. **Check hold** — `GET /booking/holds/{seat}`, `authenticated`.
    → `{ "seat", "state" }` where state ∈
    `FRESH | STALE | EXPIRED | SOLD | NONE`. Persisted state decides first: a
    `confirmed` reservation reads `SOLD`, an `expired` one reads `EXPIRED`, no
    reservation reads `NONE`. For a live `held` row, `EXPIRED` if past
    `expiresAt`, else `STALE` if within 5 minutes of expiry, else `FRESH`.
    `400`; `503`.
16. **Sweep holds** — `POST /booking/holds/sweep`, `role:operator`, **and** run
    automatically on a schedule (fixed delay, 60 s).
    → `{ "released": <n> }`. Reclaims (a) `held` reservations past their TTL and
    (b) **orphaned** `confirmed` reservations older than 1 hour with no confirmed
    booking (left by a crash between confirming a reservation and writing its
    booking row, or by a cancellation whose final write failed). Publishes one
    `SeatReleased` per freed seat. `503`.
17. **Buy ticket** — `POST /booking/buy`, `authenticated`.
    Body `{ "customer", "event", "seat", "tier" }` →
    `{ "booking", "ticket", "seat", "receipt", "amountMinor", "currency" }`.
    Ordered saga with compensation:
    a. validate the request;
    b. **gate** — in parallel: the event is on sale (sale-status read), the seat
       is sellable (sellability read), and the customer has **fewer than 5**
       active bookings;
    c. read the **authoritative** price (pricing quote);
    d. **claim the seat** with the single guarded statement (requirement 24); a
       customer's own live hold is admitted by the guard — this is how a hold
       becomes a purchase;
    e. **authorize payment** through the external gateway; wrap it so that any
       later failure both **voids the authorization** and **releases the
       reservation** (post-state of any failed buy: no reservation, no captured
       money);
    f. persist ticket, then payment, then the **bookings row last** (it is the
       commit marker);
    g. best-effort, non-failing: send a confirmation notification, then publish
       `SeatSold`.
    Failures: `400` bad field; `402` payment declined; `409` seat no longer
    available / event not selling / seat not sellable; `422` customer ineligible
    (too many active bookings); `503` booking store / payment provider / no price
    available.
18. **Cancel ticket** — `POST /booking/cancel`, `authenticated`.
    Body `{ "booking", "customer" }` → `{ "booking", "receipt" }`.
    **Forward recovery by re-drive**, not compensation:
    a. verify the caller owns the booking (`403` otherwise);
    b. **refund first** — nothing the customer holds is touched until the money is
       back, so "seat released but payment kept" is unreachable;
    c. invalidate the ticket (idempotent), free the seat (guarded — freeing an
       already-free seat is a no-op), publish `SeatReleased`;
    d. **close the booking row last** — it is the commit marker and the row the
       ownership/idempotency check reads.
    A re-drive must not refund twice: the completed refund is recorded against
    the booking's payment and read back as the idempotency key. `400`; `403` not
    owner; `404` unknown booking; `409` already cancelled; `503`.

### 4. Availability read model (independent projection)

19. **Seat status** — `GET /availability/seats/{seat}`, `public`.
    → `{ "seat", "state" }` from a projection maintained by consuming `SeatSold` /
    `SeatReleased`. Projected state is the subset `available | sold`. `400`; `503`.
20. **Sold count** — `GET /availability/sold/{event}`, `public`.
    → `{ "event", "sold": <n> }` from the same projection. `400`; `503`.
21. Consumers are idempotent and drop any fact not newer than the row's recorded
    `version`. The projection is allowed to lag (bounded staleness).

### 5. Customer quote read model (independent projection)

22. **Quote for customer** — `GET /quotes/{event}/{tier}`, `public`.
    → `{ "event", "tier", "amountMinor", "currency", "version" }` from a
    projection maintained by consuming `PriceChanged`. `404` no price for scope;
    `422` unknown tier; `503`.
23. The consumer is idempotent and version-guarded, same as requirement 21.

### 6. Cross-cutting invariants

24. **Double-booking is impossible by construction.** The seat claim used by
    *acquire-hold* and *buy-ticket* is a **single** guarded SQL statement —
    `INSERT INTO reservations (...) VALUES (...) ON CONFLICT (seatId) DO UPDATE
    SET claimId = <new>, ... WHERE <existing is cancelled/expired, OR an expired
    held row, OR the same customer's own hold still inside the 60-minute
    lifetime> RETURNING claimId, version`. A confirmed booking or another
    customer's fresh hold yields **zero rows** → typed "seat unavailable" (`409`).
    No `SELECT`-then-`UPDATE`, no application-level lock, no optimistic-retry
    loop.
25. Every write that transitions state (`open/cancel event`, `block/release
    seat`, `confirm/release reservation`) is a **guarded** conditional `UPDATE`;
    "0 rows affected" is the authoritative refusal, and any follow-up read used
    to explain the refusal is best-effort.
26. Compensation and confirm/release in the buy saga match on the **`claimId`**
    returned by the claim, so a concurrent reclaim that rotates `claimId` makes
    every later guarded step for the stale claim fail closed.
27. **Parse, don't validate** at the HTTP boundary: raw request fields are parsed
    into domain value objects once; an invalid field is rejected there with a
    typed failure carrying the field name and reason.
28. Access-control summary that the implementation must satisfy: **8** endpoints
    `role:admin` (all event-management and pricing writes), **4** `authenticated`
    (acquire-hold, check-hold, buy, cancel), **1** `role:operator` (sweep), **6**
    `public` (sale-status, seat-sellability, seat-status, sold-count, price
    quote, customer quote). An endpoint with no declared access level is a
    build-time error, not a silently public route.

## Constraints

- **Language / runtime**: Java 25 (LTS).
- **Framework**: Spring Boot 4+ — Spring Web (MVC REST controllers, Jackson
  JSON), Spring Security for the four access levels (roles `ADMIN`, `OPERATOR`;
  `authenticated`; `public`). The authentication mechanism itself is out of
  scope: assume a resolvable current principal with roles (e.g. a test filter
  that reads `X-User` / `X-Roles` headers).
- **Build**: Maven (`pom.xml`), single module.
- **Persistence**: PostgreSQL 16+ via Spring Data JDBC or `JdbcTemplate` (not
  JPA/Hibernate — the guarded statements are hand-written SQL). Schema is managed
  by **Flyway** migrations under `src/main/resources/db/migration`.
- **The seat claim (requirement 24) must be one round-trip**: a single
  `INSERT ... ON CONFLICT (seat_id) DO UPDATE ... WHERE ... RETURNING`. Reject
  any implementation that uses `SELECT ... FOR UPDATE`, a distributed lock, or a
  retry loop.
- **Money** is stored and computed as `long` minor units; currency is an enum.
  No `double`/`float` anywhere in pricing or payments.
- **Domain facts** (`SeatSold`, `SeatReleased`, `PriceChanged`) are delivered
  **asynchronously** and decoupled from the producing transaction. Acceptable
  implementations: (a) `ApplicationEventPublisher` +
  `@TransactionalEventListener(phase = AFTER_COMMIT)` with `@Async` consumers, or
  (b) a transactional **outbox** table drained by a `@Scheduled` poller.
  Consumers must be idempotent and must ignore non-newer `version`s.
- **Scheduled hold sweep** via Spring `@Scheduled(fixedDelay = 60s)`, calling the
  same code path as the operator endpoint.
- **Payment gateway** is an external HTTP dependency behind an interface with
  three operations — `authorize(amount, currency, customer) → receiptId`,
  `void(receiptId)`, `refund(bookingRef) → receiptId`. Provide an HTTP
  implementation (`RestClient`) and a local **stub** (in-memory, always approves
  except for a designated "decline" customer) selected by Spring profile.
- **Notifications** (booking confirmation) are best-effort, single-attempt, and
  must never fail the request. A no-op/logging implementation is acceptable for
  the default profile.
- **Error mapping**: a central `@RestControllerAdvice` (or `ProblemDetail`-based
  handler) maps each typed domain failure to its documented status code. A test
  must prove that no *known* failure path yields `500`.
- **Tests**: JUnit 5. Every use case has unit tests for validation failure, the
  happy path, and each typed failure, against in-memory fakes (no DB).
  Integration tests for the guarded SQL and the projections use **Testcontainers**
  PostgreSQL. Concurrency test: N parallel buys of the same seat yield exactly
  one `confirmed` booking and N-1 `409`s.
- **Config**: PostgreSQL connection, gateway base URL, sweep cadence, and hold
  TTLs (`15m` / `60m` / `5m` stale window / `1h` orphan age) are all
  externalised in `application.yml`.

## Success Criteria

Given the service running against a fresh database (`admin`, `operator`, and a
customer principal available):

1. **Organiser flow**
   - `POST /api/v1/events/create {venue, onSaleAt}` → `201`-style body with an
     `event` id; the event is `draft`.
   - `POST /api/v1/seats/add {event, section:"A", row:"12", number:7, tier:"STANDARD"}`
     → a `seat` id; `GET /api/v1/seats/sellability/{seat}` →
     `state:"available", sellable:true`.
   - `POST /api/v1/pricing/set {event, tier:"STANDARD", amount:"49.50", currency:"USD"}`
     → `version:1`; `GET /api/v1/pricing/quote/{event}/STANDARD` →
     `amountMinor:4950, currency:"USD", version:1`.
   - `POST /api/v1/events/open/{event}` → success; a second call → `409`.
2. **Purchase flow**
   - `POST /api/v1/booking/holds {customer, event, seat}` → `state:"held"`;
     `GET /api/v1/booking/holds/{seat}` → `FRESH`.
   - `POST /api/v1/booking/buy {customer, event, seat, tier:"STANDARD"}` (same
     customer) → a `booking`, a `ticket`, a `receipt`, `amountMinor:4950`.
   - Within the projection's lag, `GET /api/v1/availability/seats/{seat}` →
     `sold` and `GET /api/v1/availability/sold/{event}` → `1`.
   - A second buy of the same seat by another customer → `409`.
   - A buy by a customer with 5 active bookings → `422`.
   - A buy with the "decline" customer against the stub gateway → `402`, and
     afterwards the seat is still sellable and has no reservation.
3. **Cancellation flow**
   - `POST /api/v1/booking/cancel {booking, customer}` → a refund `receipt`;
     calling it again → `409` (already cancelled) with no second refund recorded.
   - The seat returns to `available` in the availability projection and
     `sold-count` drops back to `0`.
   - `POST /api/v1/booking/cancel` by a non-owner → `403`.
4. **Hold expiry** — a hold left unbought is reported `STALE` within 5 minutes of
   expiry, `EXPIRED` after it, and after a sweep the seat is sellable again and
   `check-hold` reads `NONE`.
5. **Guards** — blocking a seat (`POST /api/v1/seats/block/{seat}`) then
   attempting a hold or buy on it → `409`; releasing it restores purchases.
6. **Build gate** — `mvn verify` passes: all unit tests, the Testcontainers
   integration tests, and the concurrency test (one winner, rest `409`). No
   known failure path returns HTTP `500`.
