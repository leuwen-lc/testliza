# Vision (general-objective): Event Ticketing Platform

> Intended as a **`general-objective`** entry document for the Liza MAS pipeline
> (epic planning → user stories → architecture → code planning → code). It states
> the problem, the users, the desired behaviour, the business rules and the
> constraints — deliberately **not** the HTTP surface, request/response shapes,
> status codes, storage schema or control flow, which are for the pipeline to
> design. Derived from `vision.md`, a retro-specification of an existing
> implementation.

## Problem & motivation

Selling tickets to seated events has a hard core: two people must never end up
holding the same seat; a customer must never lose money without receiving either
a ticket or a refund; and the public must see accurate availability and prices
without signing in. Getting those guarantees right under concurrency is where
naive implementations fail. This service owns that core lifecycle end to end, so
organisers can put an event on sale and customers can buy with confidence.

## Target users

- **Organiser (admin)** — creates and manages events, seat inventory and prices;
  opens and cancels events.
- **Operations (operator)** — keeps inventory healthy: withholds and restores
  individual seats, triggers reclamation of stale holds.
- **Customer (authenticated)** — browses, holds a seat, buys it, cancels a
  booking for a refund. Signed in; may act only on their own bookings.
- **Public (anonymous)** — reads published sale status, seat availability and
  prices. No account.

## Goal & MVP scope

An HTTP/JSON service covering the full path from event creation to ticket
purchase and cancellation:

1. **Event & inventory setup** — create a draft event, add seats (each with a
   fixed price tier), open the event for sale, cancel an event.
2. **Pricing** — set an absolute price per (event, tier), adjust it by a
   percentage, read the current authoritative price.
3. **Holds** — a customer takes a time-limited hold on a seat, can check its
   state; stale holds are reclaimed automatically.
4. **Purchase** — a customer buys a held (or free) seat: the price is quoted,
   payment is taken, a ticket and receipt are issued.
5. **Cancellation** — a customer cancels a booking and is refunded in full; the
   seat returns to sale.
6. **Public read models** — anyone can read sale status, per-seat availability,
   aggregate sold counts and customer-facing price quotes.

## Out of scope

- The authentication mechanism itself (assume the platform supplies an
  authenticated identity and its roles).
- Seat-scoped pricing (prices are per tier only), discounts, promo codes,
  multi-seat / cart orders, waitlists, partial-value refunds.
- Seat maps and rendering; venue modelling beyond a free-text venue name and
  section / row / number labels.
- Payment methods, PCI handling and settlement — payment is delegated to an
  external gateway behind a small interface.
- Guaranteed delivery, content or channels for notifications.
- Reporting, analytics and financial reconciliation beyond "refunded in full".
- Currency conversion (an event's prices are in a single currency).

## Domain concepts

- **Event** — a sellable show at a venue. Lifecycle: `draft` → `on sale` →
  (optionally) `cancelled`. Cancelling is terminal and idempotent.
- **Seat** — a numbered place (section / row / number) in an event, with a fixed
  **price tier**. Lifecycle: `available` ↔ `blocked` (operator withholds), and
  `available` ↔ `sold`. A `withdrawn` state is reserved for permanent catalogue
  withdrawal.
- **Price tier** — a closed, configured set of named tiers (premium, standard,
  economy, accessible, restricted-view).
- **Money** — an amount in a single currency from a closed set (USD / EUR / GBP),
  always exact.
- **Price** — the current price for an (event, tier), with a full version
  history; every change produces a new version.
- **Hold (reservation)** — a customer's temporary exclusive claim on a seat.
  Lifecycle: `held` → `confirmed` (became a purchase) | `expired` (lapsed) |
  `cancelled` (released).
- **Booking** — a confirmed purchase of a seat by a customer. Lifecycle:
  `confirmed` → `cancelled`.
- **Payment** — the money movement for a booking: taken in full at purchase,
  refunded in full on cancellation.
- **Ticket** — what the customer receives for a confirmed booking; invalidated on
  cancellation.
- **Availability read model** — a public, eventually-consistent view of each
  seat's availability and per-event sold counts.
- **Customer quote read model** — a public, eventually-consistent view of the
  latest price per (event, tier).

## Business rules & product decisions

### Concurrency & integrity

- **Double-booking is impossible.** For any seat, at most one customer can hold
  or own it at a time. Under N concurrent buyers of one seat, exactly one
  succeeds and the rest are cleanly refused.
- **A failed purchase leaves no trace** — no live reservation, no money taken.
- **Cancellation refunds first.** The refund completes before the ticket or seat
  is touched, so "seat released but money kept" can never happen. Cancellation is
  idempotent: a retried or re-driven cancellation never refunds twice.
- **Cancelling an event does not cascade** to its seats or existing bookings.

### Holds

- A hold lasts **15 minutes**. The owning customer may refresh it by
  re-acquiring, but a single hold's **total lifetime is capped at 60 minutes**
  from first acquisition, so a seat cannot be squatted indefinitely on a timer.
- A customer's own live hold converts directly into their purchase.
- Stale holds — and confirmed reservations orphaned by a crash mid-purchase — are
  reclaimed automatically on a recurring sweep (roughly once a minute) and on
  operator demand.
- A hold's reported state is one of: fresh, stale (near expiry), expired, sold,
  or none.

### Purchase

- A customer may hold at most **5 active (confirmed) bookings** at once; a
  further purchase is refused as ineligible.
- The price charged is the current authoritative price for the **seat's own
  tier** at the moment of purchase. A customer must not be quoted or charged for
  a different tier than the seat they are buying (see open questions).
- Payment is taken **in full at the moment of purchase**; there is no separate
  later capture step.
- The confirmation notification is best-effort and never blocks or fails a
  purchase.

### Pricing

- Prices are **versioned**; a version is allocated at most once per (event,
  tier), so two concurrent price changes cannot collide or silently overwrite
  each other.
- A percentage adjustment scales the current price and is **rounded half-up** to
  a whole minor unit.
- Amounts are accepted with at most two decimal places.
- Pricing operates on an (event, tier) key and does not itself check that the
  event exists.

### Consistency expectations

- The **authoritative price** (used for purchase and the admin quote) is updated
  synchronously with the price change.
- The public **availability** and **customer-quote** read models are
  **eventually consistent**; bounded staleness is acceptable. They are never the
  mechanism that prevents double-booking — the authoritative reservation check
  is.

## Access control

Every operation declares exactly one access level; an operation with none is a
build error, never a silently public route.

- **Public** — sale status, seat sellability, seat availability, sold counts,
  price quote, customer quote.
- **Authenticated customer** — acquire hold, check hold, buy, cancel; only for
  the acting customer's own identity and bookings.
- **Organiser (admin)** — all event and pricing writes.
- **Operator** — the hold sweep.

## Non-functional constraints

- **Runtime / stack**: Java 25 (LTS), Spring Boot 4+, PostgreSQL 16+.
- **Persistence guardrail**: all state transitions are hand-written,
  single-statement guarded SQL (no ORM / JPA); schema changes are
  version-controlled migrations.
- **Money**: integer minor units end to end; no floating-point anywhere in
  pricing or payments.
- **Failure reporting**: every known failure maps to a specific, typed outcome
  the caller can act on (malformed input, rule violation, missing resource,
  permission denied, dependency unavailable) — never a generic server error.
- **External payment gateway**: reached through a narrow interface (authorise,
  void, refund) with a real HTTP implementation and an in-memory stub selectable
  for tests and local runs.
- **Configuration**: database connection, gateway location, sweep cadence and all
  hold-timing values are externally configurable.
- **Testing**: each use case unit-tests the happy path and every typed failure;
  integration tests cover the guarded SQL and the projections against a real
  PostgreSQL; a concurrency test proves one-winner-per-seat.

## Success criteria

- An organiser can take an event from creation through seat setup and pricing to
  "on sale"; a second attempt to open the same event is refused.
- A customer can hold a seat, see it reported fresh, then buy it and receive a
  booking, a ticket, a receipt and the correct amount.
- After a successful purchase, the public availability view shows the seat sold
  and the event's sold count incremented (within its lag).
- A second customer buying the same seat is refused.
- A customer already holding five active bookings is refused a sixth.
- A declined payment yields a payment error and leaves the seat sellable, with no
  reservation and no charge.
- A cancellation returns a full refund and then releases the seat; a repeat
  cancellation is refused with no second refund; a non-owner's cancellation is
  refused.
- An unbought hold is reported stale near expiry, expired after it, and its seat
  becomes sellable again after the sweep.
- Blocking a seat refuses holds and buys on it; releasing it restores them.
- No known failure path returns a generic server error.

## Risks, assumptions & open questions

- **Assumption**: an authenticated identity and role set are supplied by the
  platform; this service enforces access levels but does not implement sign-in.
- **Open question — identity binding**: purchase and cancellation must act on the
  *authenticated* customer's identity, not on a customer value passed in the
  request body. The source retro-specification leaves this ambiguous; the
  intended rule is "identity comes from the authenticated principal". Resolve
  during story writing.
- **Open question — tier / seat consistency**: the intended rule is that a
  purchase is always billed at the seat's own tier. An earlier implementation
  trusted a client-supplied tier; this must be settled during story writing.
- **Risk**: read-model staleness is fine for display but must never drive a
  correctness decision.
- **Risk**: a lost domain event (seat sold / released, price changed) leaves the
  public projections stale until the next event for that entity — acceptable for
  MVP; revisit if it becomes user-visible.
