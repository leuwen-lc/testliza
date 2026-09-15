# User Stories: Set Absolute Price

Status: draft

---

# Part 1 — Story Review

*For the intent owner. Verify the user-visible behavior before coding starts.*

## Promise

**Before this story:** For a given (event, tier) pair, there is no way for an organizer to
establish a price at all. Nothing downstream has anything authoritative to read or charge.

**After this story:** An organizer can fix an absolute price (amount + currency) for any
(event, tier) pair. Each time they do this, a new price version is created and immediately
becomes the authoritative price for that pair. If two organizers (or two concurrent submissions)
try to set the price for the exact same pair at the exact same moment, one of them succeeds and
the other is told clearly that their change was rejected because someone else changed it first —
neither is ever silently discarded, merged, or overwritten without visible notice.

## Behavior Map

| Story | User-visible change | Source intent captured | Main exclusion |
|-------|---------------------|-------------------------|----------------|
| ST-001 — Set absolute price | Organizer fixes a price for an (event, tier) pair and sees it become the current price; a losing concurrent attempt is visibly rejected instead of silently lost | vision-general-objective-fr.md:45-46, 92-93, 155-164 | Reading the current price or its version history (CAP-004, sibling epic); the percentage adjustment operation (ST-002, `adjust-price-percentage.md`) |

## Example Walkthrough

A representative organizer would:
1. Open the admin console (separate deliverable) and pick an event and a tier (e.g. "Standard").
2. Enter an amount and a currency (e.g. 25.00 USD) and confirm.
3. See the price accepted as version 1 — the current price for that pair.
4. Later, decide to reprice and enter a new amount (e.g. 30.00 USD) for the same pair.
5. See version 2 created and become the new current price; version 1 is not shown as current
   anymore (its history is read elsewhere — out of scope here).
6. If, at the exact same moment, another organizer (or a second browser tab) submits a different
   price for the same pair, exactly one of the two submissions is accepted; the other sees a clear
   "someone else just changed this price — reload and try again" outcome, never a silent loss of
   their own attempted change nor a silent loss of the other organizer's.

## Interpretation Decisions

| Source signal | Story interpretation | Confidence | Verify? |
|---|---|---|---|
| "Montant... une somme dans une seule devise... toujours exacte" (vision-general-objective-fr.md:90-91); no explicit floor stated for a directly-set absolute price | A price amount being set must be a strictly positive number of minor units; setting zero or a negative amount is rejected as a rule violation rather than accepted | MEDIUM | Yes |

## Review Questions

- [ ] Should setting an absolute price of 0 or a negative amount really be rejected, as assumed (ASM-001-1), or is there a legitimate use case (e.g. a free tier) that needs a different treatment?
- [ ] Should the currency really be free-form per `set` call with no cross-check against the event's own currency, as the epic assumes (ASM-000-2), or does the intent owner expect a check even though this capability never reads event management?
- [ ] When a "set" loses a concurrency race, should the visible conflict result carry enough information (e.g. the winning version's amount) for the organizer to decide their next move, or is a bare "conflict, please retry" sufficient for this story?

---

# Part 2 — Coder Contract

*For the Coder. Implementation-ready acceptance criteria and context.*

## Goal
An organizer can fix an absolute price for an (event, tier) pair, with every price change
allocating a new version and no concurrent change ever silently lost.

## Parent Epic
`specs/epics/vision-general-objective-fr/20260914-152508-epm-1-ep-2.md` — Capability CAP-003
(Price Setting and Adjustment)

## Context
This is the first of two write operations on the `pricing` module's price aggregate for a given
(event, tier) key (the second is the percentage adjustment in `adjust-price-percentage.md`, ST-002).
Both operations share the same version chain per (event, tier) pair, which is why the concurrency
guarantee below must hold regardless of which of the two operations is racing against which. This
capability does not check that the (event, tier) key refers to a real, existing event — that check
belongs to `eventmanagement` and is out of scope here (vision-general-objective-fr.md:163-164).

## Personas
- **Organisateur (admin)**: operates from an admin console (a separate deliverable, not described
  here); sets and reprices tiers mostly before and during the on-sale window; expects a visible
  refusal on a change collision, never a silent overwrite of their own or someone else's change.

## General Information

Applies to: the entire scope (ST-001).

### References
- spec: vision-general-objective-fr.md:45-46 (MVP item 2 — tarification), :90-91 (Montant),
  :92-93 (Prix — versioned, full history), :155-164 (Tarification — versioning, no event-existence
  check), :177-191 (Contrôle d'accès — organisateur seul écrit la tarification), :511-512
  (montants en unités mineures entières)
- epic: `specs/epics/vision-general-objective-fr/20260914-152508-epm-1-ep-2.md` — Capability
  CAP-003, "Completion Criteria", NFR-000-1, NFR-000-2, NFR-000-4, ASM-000-1, ASM-000-2, OQ-000-2
- guardrails: GUARDRAILS.md G1.1 (role check on the write), G1.2 (version and resulting price are
  always server-derived, never accepted from the caller), G2.1 (layered shape), G2.7 (stack
  discipline)

### Non-Functional Requirements
- NFR-000-1: Amounts are integer minor units end-to-end; no floating point anywhere in the
  computation or storage of a price.
- NFR-000-2: A price version is allocated at most once per (event, tier) pair per logical change;
  a losing concurrent write fails visibly with a typed conflict result — never silently overwritten
  or merged.
- NFR-000-4: This capability's write declares exactly one access level (organisateur); an
  undeclared access level is a build-time error, never a silent public route.

### Out of Scope
- Reading the current authoritative price or its version history (CAP-004, sibling epic).
- Verifying that the (event, tier) key refers to an event that actually exists
  (vision-general-objective-fr.md:163-164).
- The percentage adjustment operation (ST-002, `adjust-price-percentage.md`).
- Per-seat pricing, discounts, promo codes (explicitly out of scope of the vision).
- Consumption of the resulting price by the purchase flow or by the public tariff projection.
- Any admin-console UI, screen, or navigation.
- Recording who (which organizer) made a given change, beyond the version number itself — the
  vision does not ask for an audit trail here.

### Assumptions
- **ASM-000-1**: There is no implicit default price for an (event, tier) pair until an explicit
  "set" has occurred. — *Why*: the vision only describes "set" (absolute) and "adjust" (relative to
  the current price), never an implicit starting value. Confidence: HIGH.
- **ASM-000-2**: A price carries its own currency, supplied by the caller at "set" time, from the
  closed set (USD/EUR/GBP); this capability does not cross-check that currency against the event's
  own configured currency, since it never reads event management. — *Why*: "Montant" is defined as
  a sum in a currency (vision-general-objective-fr.md:90-91), and this capability operates only on
  the (event, tier) key (vision-general-objective-fr.md:163-164). Confidence: MEDIUM.

### Open Questions
- **OQ-000-2**: Who guarantees — if anyone — that the currency carried by a set price matches the
  event's own configured currency, given this capability never verifies the event exists?
  — *Impact if unresolved*: a currency mismatch between a price and its event could go unnoticed
  until purchase.

---

## Story ST-001 - Set absolute price

### References
- spec: vision-general-objective-fr.md:155-164 (fixer un prix absolu ; versionnement ; au plus
  deux décimales), :90-91 (Montant)
- epic: Capability CAP-003, Story Documents table (row "set-price.md")

### User Story
**As an** organisateur (admin) operating the admin console, **I want to** fix an absolute price
(an amount and a currency) for a specific (event, tier) pair, **so that** that pair has a current,
authoritative price that other parts of the system can later rely on, and that I can change again
whenever I need to without ever losing a concurrent change silently.

### Acceptance Criteria
- AC-001-1: Given an (event, tier) pair with no existing price version, when the organizer sets an
  absolute price with a valid amount (at most two decimal places) and a currency from the closed
  set (USD/EUR/GBP), then a new price version (the first one) is created for that pair and becomes
  its current authoritative price.
- AC-001-1b: Given an (event, tier) pair that already has a current authoritative price, when the
  organizer sets a new absolute price for it, then a new version, numbered strictly after the
  previous one, is created and becomes the new current authoritative price; the previously current
  version's own recorded amount is not modified or deleted.
- AC-001-2: Given an amount expressed with more than two decimal places, when the organizer
  attempts to set it, then the request is rejected with a validation error naming the amount field,
  and no new version is created.
- AC-001-2b: Given a currency outside the closed set (USD/EUR/GBP), when the organizer attempts to
  set a price in that currency, then the request is rejected with a validation error naming the
  currency field, and no new version is created.
- AC-001-3: Given an amount of zero or a negative amount, when the organizer attempts to set it,
  then the request is rejected with a typed rule-violation result, and no new version is created.
  (see ASM-001-1)
- AC-001-4: Given two organizers (or two concurrent requests, e.g. two browser tabs) submitting a
  "set absolute price" for the exact same (event, tier) pair at the same time, when both submit,
  then exactly one succeeds and its version becomes the new current authoritative price; the other
  receives a visible, typed conflict result and their submitted price is discarded — never silently
  merged, automatically retried, or partially applied.
- AC-001-5: Given a principal that does not hold the organizer role (an authenticated client, an
  operator, or an unauthenticated caller), when they attempt to set an absolute price, then the
  request is refused with a typed permission-denied result and no price change occurs.

### Depends on:
None. This story is the first write against a (event, tier) pair's price aggregate.

### Out of Scope
- The percentage adjustment operation (ST-002 in `adjust-price-percentage.md`).
- Reading the resulting current price or any version history back to the caller beyond confirming
  the new version was created (CAP-004, sibling epic).
- Verifying the (event, tier) key against an actually-existing event.

### Assumptions
- **ASM-001-1**: A price amount being set must be a strictly positive number of minor units;
  setting an amount of zero or negative is rejected, not accepted, clamped, or silently corrected.
  — *Why*: the vision does not state a floor explicitly for a directly-set absolute price, but a
  price represents a chargeable monetary sum for a ticket tier, and a zero or negative price is not
  a meaningful sum to charge. This mirrors the same open gap the epic flags for percentage
  adjustments (OQ-000-1 in the epic document) so both write paths treat the floor consistently.
  Confidence: MEDIUM — flagged for human confirmation before this story is coded.

### Open Questions
None beyond the doc-level OQ-000-2 above.
