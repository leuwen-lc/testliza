# Specification Review: Event Ticketing Platform Vision

Reviewed: `specs/vision.md` (289 lines, single file — `specs/plans/vision/` is empty, no other spec files in scope).

## Summary

- Critical: 0
- High: 6
- Medium: 6
- Low: 3

---

## High Issues

### Reservation state written by the buy-ticket claim is unspecified

- **Location:** vision.md:129-131 (req 17d) vs vision.md:178-186 (req 24) vs vision.md:187-190 (req 25)
- **Type:** Ambiguity
- **Description:** Req 24 says acquire-hold and buy-ticket share "a single guarded SQL statement" and shows its shape (`INSERT ... ON CONFLICT ... DO UPDATE SET claimId = <new>, ... WHERE ...`) but elides which `state` value is written. Req 25 separately lists "confirm/release reservation" as its own category of guarded UPDATE, distinct from the "claim" in req 24. It's never stated whether buy-ticket's claim (17d) writes `state='confirmed'` directly in one step, or whether a second, separate "confirm" guarded UPDATE (implied by req 25) must run after the claim to move `held → confirmed`. The saga steps in req 17 (a-g) never show such a second step.
- **Suggestion:** Spell out the exact state value(s) the claim statement writes for each caller (acquire-hold vs buy-ticket), and either fold "confirm reservation" into req 24's statement explicitly or add the missing step to the req 17 saga.

### Buy-ticket's client-supplied `tier` is never validated against the seat's actual tier

- **Location:** vision.md:120-122 (req 17 body/response) vs vision.md:15-16 (Seat.tier) vs vision.md:90-92 (req 12, price keyed by tier)
- **Type:** Gap
- **Description:** Seat already carries an authoritative `tier` at creation (req 4). Buy-ticket nonetheless accepts a client-supplied `{ "tier" }` field and uses it (17c) to read the "authoritative" price via `GET /pricing/quote/{event}/{tier}`, which is keyed on `(event, tier)`, not on the seat. Nothing requires that the request's `tier` match the seat's own `tier`. As written, a client could request a cheap tier for an expensive seat and be charged accordingly.
- **Suggestion:** Either drop the client-supplied `tier` field and derive it from the seat record, or add an explicit validation step (with its own failure status) that rejects a mismatch.

### Client-supplied `customer` field on authenticated endpoints is never checked against the principal

- **Location:** vision.md:99-100, 120-122, 144-145 (req 14/17/18 bodies) vs vision.md:210-211 (Constraints — principal via `X-User`/`X-Roles`)
- **Type:** Gap
- **Description:** Acquire-hold, buy, and cancel are `authenticated` and the Constraints section establishes a resolvable current principal, yet each request body also carries an explicit `customer` field. The spec never states whether that field must equal the authenticated principal (with a rejection if not) or is otherwise reconciled with it. Req 18a's ownership check ("verify the caller owns the booking") doesn't say which of the two — body field or principal — is authoritative.
- **Suggestion:** State explicitly that `customer` is derived from the principal (and the body field, if kept, must match or is ignored), to close the impersonation gap.

### `Payment.status = captured` is unreachable

- **Location:** vision.md:30-31 (Payment.status enum) vs vision.md:230-234 (Constraints — gateway interface) vs vision.md:120-143 (req 17 buy saga)
- **Type:** Gap
- **Description:** `Payment.status` includes `captured`, but the payment gateway interface exposes only `authorize`, `void`, and `refund` — no capture operation. The buy saga (17e) only authorizes; nothing ever transitions a payment from `authorized` to `captured`.
- **Suggestion:** Either add a capture step/operation, or remove `captured` from the enum and clarify that `authorize` is auth-and-capture combined.

### Domain Model omits `version` fields that requirements assume exist

- **Location:** vision.md:15-16 (Seat definition) vs vision.md:73-76 (req 9), vision.md:165-166 (req 21), vision.md:174 (req 23)
- **Type:** Gap
- **Description:** Req 9 says fact consumers "ignore a fact whose `version` is not newer than the seat's recorded version," and reqs 21/23 say the availability and customer-quote projection rows are guarded the same way. The Domain Model section defines `version` explicitly only for Reservation and the Price record — Seat's field list (`id, eventId, section, row, number, tier, state`) has no `version`, and the two projection rows are never modeled as entities at all.
- **Suggestion:** Add `version` to the Seat definition, and define the two projection rows (fields including `version`) in the Domain Model.

### `check-hold` has no defined outcome for a `cancelled` reservation

- **Location:** vision.md:106-112 (req 15) vs vision.md:23-26 (Reservation.state) vs vision.md:274-275 (Success Criteria #2)
- **Type:** Gap
- **Description:** Req 15's precedence order covers `confirmed → SOLD`, `expired → EXPIRED`, "no reservation → NONE," and live `held` rows. It never addresses a reservation row whose persisted state is `cancelled` — a real, reachable state (req 24's guard treats "existing is cancelled/expired" as reclaimable, implying release writes `cancelled` rather than deleting the row). Success Criteria #2 asserts that after a declined payment the seat "has no reservation," but the likely implementation leaves a `cancelled`-state row rather than removing it, so what `check-hold` reports in that case is unspecified.
- **Suggestion:** Add an explicit branch: does `cancelled` read as `NONE`, or a distinct value?

---

## Medium Issues

### Seat state `withdrawn` is never reachable

- **Location:** vision.md:15-16 (Seat.state enum) vs vision.md:63-66 (req 5/6) vs vision.md:67-70 (req 7)
- **Type:** Gap
- **Description:** `withdrawn` is a declared Seat state and req 7's sellability switch explicitly handles it (`sellable:false`), but no requirement ever transitions a seat into or out of `withdrawn` — only `available ↔ blocked` (req 5/6) and `available ↔ sold` (req 9, via facts) are wired up.
- **Suggestion:** Add the missing admin operation (e.g., withdraw/reinstate seat), or remove the state if out of scope.

### "Authoritative" means different freshness guarantees for Seat vs Price

- **Location:** vision.md:73-76 (req 9, Seat's "authoritative state") vs vision.md:80-84, 90-92 (req 10/12, `current_price` "authoritative" quote)
- **Type:** Ambiguity
- **Description:** For pricing, "authoritative" (`current_price`, req 12) is updated synchronously in the same write as set/adjust-price (req 10/11). For seats, "authoritative state" (req 9) is updated only asynchronously by consuming `SeatSold`/`SeatReleased` — the same mechanism used for the admittedly-lagging availability projection (section 4). The real safety net against double-booking is the Reservation table (req 24), not Seat.state, but nothing states that Seat.state (despite being called "authoritative") can lag behind an in-flight claim.
- **Suggestion:** Either rename Seat's field to avoid "authoritative," or add a note that it's eventually consistent and that req 24's Reservation guard — not Seat.state — is the actual correctness mechanism.

### Pricing endpoints never declare a failure for an unknown event

- **Location:** vision.md:80-92 (req 10/11/12) vs vision.md:50-62 (req 2/3/4, which do declare `404` unknown event)
- **Type:** Gap
- **Description:** Set-price, adjust-price, and quote-price all take an `event` scope but list no `404`/failure for a nonexistent event id — only `400`/`422` (malformed input) or `404 no current price for scope` (which is a different condition: the event exists but has no price yet). Event-management endpoints consistently define `404 unknown event`; pricing endpoints don't.
- **Suggestion:** Add the missing failure case, or state explicitly that pricing endpoints are indifferent to event existence.

### Seat write endpoints don't declare 404 for an unknown seat, unlike seat reads

- **Location:** vision.md:63-66 (req 5/6), vision.md:99-105 (req 14), vision.md:120-143 (req 17) vs vision.md:67-70 (req 7, which has `404 unknown seat`)
- **Type:** Inconsistency
- **Description:** Block seat, release seat, acquire-hold, and buy-ticket all reference a seat by id but list only `409`/`400`/`503` — an unknown seat id presumably falls into the generic "not available"/"unavailable" `409` bucket instead of `404`, while seat-sellability (a read) does return `404 unknown seat`.
- **Suggestion:** State whether folding "unknown" into `409` is deliberate (e.g., to avoid leaking existence) or align these endpoints with req 7's convention.

### No mechanism specified for "at most once" price version allocation

- **Location:** vision.md:93-95 (req 13) vs vision.md:178-186 (req 24, which fully specifies the analogous seat-claim mechanism) vs vision.md:187-190 (req 25's guarded-write list, which omits price versioning)
- **Type:** Gap
- **Description:** Req 13 requires that concurrent double-allocation of a price version "must make the loser's write fail visibly," but — unlike the seat claim, which is given an exact SQL shape and is explicitly reinforced in Constraints (vision.md:216-219) — no mechanism (unique constraint, guarded insert, etc.) is specified, and req 25's catalogue of guarded writes doesn't include it either.
- **Suggestion:** Specify the enforcing mechanism (e.g., unique constraint on `(event, tier, version)` with insert-failure handling), matching the rigor applied to the seat claim.

### Rounding rule unspecified for percentage-based price adjustment

- **Location:** vision.md:85-89 (req 11) vs vision.md:220-221 (Constraints — Money is integer minor units, "never a floating-point type")
- **Type:** Gap
- **Description:** `adjust price` scales the current integer minor-unit amount by an integer percent (e.g., 110 = +10%). Percentages that don't divide evenly (e.g., 133% of 4950 = 6583.5) will produce a non-integer result, and no rounding rule (round-half-up, floor, ceiling, banker's) is given.
- **Suggestion:** Specify the rounding rule for the scaling operation.

---

## Low Issues

### Per-seat price scope is declared but never used

- **Location:** vision.md:20-22 (Price record: "per `(event, tier[, seat])` scope")
- **Type:** Gap
- **Description:** The optional `[, seat]` scope component is declared in the domain model but no requirement (set/adjust/quote price, quotes projection) ever exposes or exercises seat-scoped pricing — all pricing operations are keyed on `(event, tier)` only.
- **Suggestion:** Drop the `[, seat]` scope from the domain model, or specify the seat-scoped pricing behavior/endpoints it implies.

### "Active bookings" undefined against Booking.status

- **Location:** vision.md:127 (req 17b: "fewer than 5 active bookings") vs vision.md:27-29 (Booking.status ∈ `confirmed | cancelled`)
- **Type:** Undefined Term
- **Description:** The buy-ticket eligibility gate counts a customer's "active bookings" without defining the term against the two-value `Booking.status` enum (presumably `confirmed`, but never stated).
- **Suggestion:** Define "active" explicitly (e.g., "bookings with `status = confirmed`").

### Decimal precision/rounding unspecified for set-price's amount string

- **Location:** vision.md:80-84 (req 10: `amount` a decimal string, `400` malformed amount)
- **Type:** Gap
- **Description:** No rule states how many decimal places are valid, or what happens to excess precision (e.g., `"49.505"`) — rejected as malformed, truncated, or rounded.
- **Suggestion:** Specify the accepted decimal precision and behavior on excess precision.

---

## Recommendations

The spec is unusually precise where it chooses to be precise — the seat-claim SQL (req 24), the access-control tally (req 28, which I verified sums correctly against every per-endpoint declaration), and the hold-TTL numbers are all internally consistent. The gaps found cluster around three seams: (1) the boundary between the synchronous write path and the async fact-consumption path (Seat.state authority, missing version fields, confirm-vs-hold ambiguity), (2) error-status conventions for "unknown reference" that are rigorous for events but inconsistently applied to seats and pricing, and (3) two places where client-supplied data (`tier`, `customer`) duplicates server-known state without a stated reconciliation rule — both are worth resolving before implementation since they're security/correctness-adjacent, not just polish.
