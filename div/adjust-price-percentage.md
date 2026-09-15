---
liza_version: "dev-unknown"
---

# User Stories: Adjust Price by Percentage

Status: draft

---

# Part 1 — Story Review

*For the intent owner. Verify the user-visible behavior before coding starts.*

## Promise

**Before this story:** An organizer who has already fixed an absolute price for an (event, tier)
pair (`set-price.md`, ST-001) has no way to change it relatively — every reprice requires
recomputing and re-entering a brand-new absolute amount by hand.

**After this story:** An organizer can adjust the current price of an (event, tier) pair by an
integer percentage (up or down) and see the new amount, rounded half-up to the nearest whole minor
unit, become the new current price. As with a direct "set", each adjustment allocates a new
version, and if two changes race for the same pair — two adjustments, or an adjustment racing a
direct "set" — one wins visibly and the other is told clearly that it lost, never silently.

## Behavior Map

| Story | User-visible change | Source intent captured | Main exclusion |
|-------|---------------------|-------------------------|----------------|
| ST-002 — Adjust price by percentage | Organizer scales the current price of an (event, tier) pair by an integer percentage and sees the rounded result become the current price; a losing concurrent change (another adjustment, or a competing direct "set") is visibly rejected | vision-general-objective-fr.md:45-46, 160-161 | Reading the current price or its version history (CAP-004, sibling epic); fixing an initial absolute price (ST-001, `set-price.md`) |

## Example Walkthrough

A representative organizer would:
1. Already have a current price of 30.00 USD for (event X, tier "Standard") — set previously via
   `set-price.md`.
2. Decide to raise it by 10% ahead of the next sale phase and enter "+10".
3. See the system compute 30.00 × 1.10 = 33.00 USD, allocate a new version, and show it as the new
   current price.
4. Later, decide to cut the price by 50% and enter "-50" on a pair whose current price is an odd
   amount (e.g. 10.05 USD = 1005 minor units); see the system compute 1005 × 0.50 = 502.5 minor
   units, round it half-up to 503 (5.03 USD), and show that as the new current price.
5. If, at the same moment, a second organizer submits either another percentage adjustment or a
   brand-new absolute price for the exact same pair, exactly one of the two changes is accepted;
   the loser sees a clear "someone else just changed this price — reload and try again" outcome.

## Interpretation Decisions

| Source signal | Story interpretation | Confidence | Verify? |
|---|---|---|---|
| "Un ajustement en pourcentage" (vision-general-objective-fr.md:160-161), with no stated direction restriction | A percentage adjustment may be positive (increase) or negative (decrease); "ajuster" is not read as increase-only. A 0% adjustment is a valid, if unusual, explicit no-change request that still allocates a new version | MEDIUM | Yes |
| No stated floor for the resulting amount after a percentage adjustment (epic OQ-000-1) | The resulting amount after rounding must be a strictly positive number of minor units; an adjustment that would drive it to zero or negative is rejected, matching the same floor rule used for a direct "set" (ASM-001-1 in `set-price.md`), rather than being clamped to some minimum | MEDIUM | Yes |

## Review Questions

- [ ] Should a percentage adjustment really be allowed to go negative (a price decrease), or was "ajuster" meant as increase-only, as the epic's own Review Question 3 leaves open?
- [ ] Should an adjustment that would drive the price to zero or below really be rejected outright (ASM-002-1), or is there a different business floor (e.g. a fixed minimum price) the intent owner has in mind?
- [ ] Should a 0% adjustment actually create a new version with an unchanged amount, or should it be treated as a no-op that creates nothing?
- [ ] Is a bare visible "conflict, please retry" enough for a losing concurrent adjustment, or should the response carry the winning amount so the organizer can decide their next move without an extra read?

---

# Part 2 — Coder Contract

*For the Coder. Implementation-ready acceptance criteria and context.*

## Goal
An organizer can scale the current price of an (event, tier) pair by an integer percentage,
rounded half-up to the nearest minor unit, with every adjustment allocating a new version and no
concurrent change — from another adjustment or from a direct "set" — ever silently lost.

## Parent Epic
`specs/epics/vision-general-objective-fr/20260914-152508-epm-1-ep-2.md` — Capability CAP-003
(Price Setting and Adjustment)

## Context
This is the second of two write operations on the `pricing` module's price aggregate for a given
(event, tier) key (the first is the direct absolute set in `set-price.md`, ST-001). An adjustment
reads the current price and writes the new one as a single internal, guarded operation against the
same aggregate and version chain that "set" writes to — it never goes through the public read
interface that the sibling capability CAP-004 exposes to its own consumers. Because both operations
share one version chain per (event, tier) pair, the concurrency guarantee below must hold whether
the race is between two adjustments or between an adjustment and a direct "set". This capability
does not check that the (event, tier) key refers to a real, existing event
(vision-general-objective-fr.md:163-164).

## Personas
- **Organisateur (admin)**: operates from an admin console (a separate deliverable, not described
  here); adjusts prices by percentage mostly to react quickly to demand (e.g. "raise everything
  10% for the last phase") without recomputing absolute amounts by hand; expects a visible refusal
  on a change collision, never a silent overwrite.

## General Information

Applies to: the entire scope (ST-002).

### References
- spec: vision-general-objective-fr.md:45-46 (MVP item 2 — tarification), :90-91 (Montant),
  :92-93 (Prix — versioned, full history), :155-164 (Tarification — versioning, half-up rounding,
  no event-existence check), :177-191 (Contrôle d'accès — organisateur seul écrit la
  tarification), :511-512 (montants en unités mineures entières)
- epic: `specs/epics/vision-general-objective-fr/20260914-152508-epm-1-ep-2.md` — Capability
  CAP-003, "Completion Criteria", NFR-000-1, NFR-000-2, NFR-000-3, NFR-000-4, ASM-000-1, ASM-000-2,
  OQ-000-1, OQ-000-2
- guardrails: GUARDRAILS.md G1.1 (role check on the write), G1.2 (version and resulting price are
  always server-derived, never accepted from the caller), G2.1 (layered shape), G2.7 (stack
  discipline)

### Non-Functional Requirements
- NFR-000-1: Amounts are integer minor units end-to-end; no floating point anywhere in the
  computation or storage of a price.
- NFR-000-2: A price version is allocated at most once per (event, tier) pair per logical change;
  a losing concurrent write fails visibly with a typed conflict result — never silently overwritten
  or merged.
- NFR-000-3: Rounding of a percentage adjustment is half-up to the nearest integer minor unit.
- NFR-000-4: This capability's write declares exactly one access level (organisateur); an
  undeclared access level is a build-time error, never a silent public route.

### Out of Scope
- Reading the current authoritative price or its version history (CAP-004, sibling epic).
- Verifying that the (event, tier) key refers to an event that actually exists
  (vision-general-objective-fr.md:163-164).
- Fixing an initial absolute price (ST-001, `set-price.md`) — this story only scales an amount that
  already exists.
- Per-seat pricing, discounts, promo codes (explicitly out of scope of the vision).
- Consumption of the resulting price by the purchase flow or by the public tariff projection.
- Any admin-console UI, screen, or navigation.

### Assumptions
- **ASM-000-1**: There is no implicit default price for an (event, tier) pair until an explicit
  "set" has occurred. — *Why*: the vision only describes "set" (absolute) and "adjust" (relative to
  the current price), never an implicit starting value. Confidence: HIGH.
- **ASM-000-2**: A price carries its own currency; a percentage adjustment implicitly inherits the
  currency of the current price it scales, with no new currency input from the caller and no
  cross-check against the event's own configured currency. — *Why*: "Montant" is defined as a sum
  in a currency (vision-general-objective-fr.md:90-91), and this capability operates only on the
  (event, tier) key (vision-general-objective-fr.md:163-164). Confidence: MEDIUM.

### Open Questions
- **OQ-000-2**: Who guarantees — if anyone — that the currency carried by a price matches the
  event's own configured currency, given this capability never verifies the event exists?
  — *Impact if unresolved*: a currency mismatch between a price and its event could go unnoticed
  until purchase.

---

## Story ST-002 - Adjust price by percentage

### References
- spec: vision-general-objective-fr.md:160-161 (ajustement en pourcentage, arrondi half-up),
  :155-159 (versionnement, collision de changements simultanés)
- epic: Capability CAP-003, Story Documents table (row "adjust-price-percentage.md")

### User Story
**As an** organisateur (admin) operating the admin console, **I want to** adjust the current price
of an (event, tier) pair by an integer percentage, **so that** I can react quickly to demand by
scaling an existing price up or down without recomputing and re-entering a new absolute amount by
hand, while still being told visibly if someone else changed the same price at the same moment.

### Acceptance Criteria
- AC-002-1: Given a current authoritative price of amount A for an (event, tier) pair, when the
  organizer submits a positive integer percentage adjustment of P%, then the system computes
  A × (1 + P/100), rounds the result half-up to the nearest integer minor unit, allocates a new
  version with that rounded amount in the same currency as the current price, and that version
  becomes the new current authoritative price. Example: current price 333 minor units, P = +50 →
  raw 499.5 → rounded 500.
- AC-002-1b: Given the same setup, when the organizer submits a negative integer percentage
  (a decrease), then the system computes the scaled amount the same way and rounds it half-up.
  Example: current price 1005 minor units, P = -50 → raw 502.5 → rounded 503. (see ASM-002-2)
- AC-002-2: Given a non-integer percentage value (e.g. 12.5), when the organizer submits it, then
  the request is rejected with a validation error naming the percentage field, and no new version
  is created.
- AC-002-3: Given an (event, tier) pair for which no price version exists yet, when the organizer
  attempts a percentage adjustment, then the request is rejected with a typed "no current price to
  adjust" result, and no version is created. (see Depends on)
- AC-002-4: Given a percentage adjustment that would drive the resulting amount to zero or a
  negative number, when the organizer submits it, then the request is rejected with a typed
  rule-violation result, the current authoritative price is left unchanged, and no new version is
  created. (see ASM-002-1)
- AC-002-4b: Given a percentage adjustment of exactly 0%, when the organizer submits it, then a new
  version is created with the same amount as the current price, and that version becomes the new
  current authoritative price. (see ASM-002-2)
- AC-002-5: Given two organizers (or two concurrent requests) submitting a percentage adjustment
  for the exact same (event, tier) pair at the same time, when both submit, then exactly one
  succeeds and its resulting version becomes the new current authoritative price; the other
  receives a visible, typed conflict result — never silently overwritten, merged, or
  automatically retried.
- AC-002-5b: Given one organizer submits an absolute price via `set-price.md` (ST-001) for an
  (event, tier) pair at the same moment another organizer submits a percentage adjustment for that
  same pair, when both submit concurrently, then exactly one of the two operations succeeds and
  becomes the new current authoritative price; the other receives a visible, typed conflict result.
- AC-002-6: Given a principal that does not hold the organizer role, when they attempt a percentage
  adjustment, then the request is refused with a typed permission-denied result and no price
  change occurs.

### Depends on:
Implementation ordering:
- Story ST-001 - Set absolute price (`set-price.md`): a percentage adjustment scales an existing
  current price, so this story requires that ST-001 has already produced at least one version for
  the (event, tier) pair being adjusted. Adjusting a pair with no prior "set" is the failure case
  in AC-002-3, not the happy path.

### Out of Scope
- Fixing an initial absolute price (ST-001 in `set-price.md`).
- Reading the resulting current price or any version history back to the caller beyond confirming
  the new version was created (CAP-004, sibling epic).
- Verifying the (event, tier) key against an actually-existing event.

### Assumptions
- **ASM-002-1**: The resulting amount after a percentage adjustment (post-rounding) must be a
  strictly positive number of minor units; an adjustment that would drive it to zero or negative is
  rejected, not accepted, clamped, or silently corrected. — *Why*: the epic explicitly flags this
  gap (OQ-000-1) for percentage adjustments specifically, since an extreme or negative percentage
  can mathematically reach zero or below; a price represents a chargeable monetary sum, and a zero
  or negative result is not one. This matches the same floor rule used for a direct "set"
  (ASM-001-1 in `set-price.md`), so both write paths treat the floor consistently. Confidence:
  MEDIUM — flagged for human confirmation before this story is coded.
- **ASM-002-2**: A percentage adjustment may be positive (increase) or negative (decrease), and an
  adjustment of exactly 0% is a valid, explicit request that still allocates a new version with the
  unchanged amount, rather than being rejected or silently ignored. — *Why*: the vision states "un
  ajustement en pourcentage" without a directional restriction, and the epic's own Review Question
  ("can it be negative, or is 'ajuster' increase-only?") is left open rather than answered;
  treating 0% as a genuine explicit action (not a no-op to be filtered out) keeps the version
  invariant ("every change produces a new version") consistent instead of carving out a silent
  exception for one input value. Confidence: MEDIUM — flagged for human confirmation before this
  story is coded.

### Open Questions
None beyond the doc-level OQ-000-2 above.
