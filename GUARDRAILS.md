# Project Guardrails — Event Ticketing Platform

Project-specific constraints for the coding agents.
Uses the tier system from the core contract (`CORE.md`): Tier 0 is never
violated (mandatory halt / RESET); Tier 1 is suspended only with an explicit,
justified waiver; Tier 2 are strong defaults.

Scope: these rules bind every agent (architect, code-planner, coder, reviewer)
on every task in this repository, independently of what any individual task
description says. The goal document says *what* to build for a given goal;
this file says *how the work must always be done here*.

---

## Tier 0 (Inviolable)

### G0.1: No secret in the clear

An API key, password, token, or a connection string carrying credentials is
never committed, never logged, and never returned to a client. Secrets are
supplied by configuration / environment only. Detection → RESET.

### G0.2: SQL is always parameterised

No SQL query is assembled by string concatenation or by interpolating request
data. A fragment that must be dynamic (a column name, a sort direction) is
resolved through a hard-coded allowlist, never from input. A change that
introduces string-built SQL is a halt, not a review comment.

---

## Tier 1 (Hard Constraints — explicit waiver required)

### G1.1: Authorisation check on every id-addressed object

No endpoint reads, returns, or mutates an object identified by a client-supplied
id (booking, hold, receipt, event, seat) without an ownership check (identity =
the OIDC token `sub`) and/or a role check, performed in the same transaction as
the access. **Test:** for every endpoint taking an id, a test proves that a
valid id belonging to another principal yields `403` / `404` and zero data.

### G1.2: No server-controlled field from the request body

Identity, price, tier, status, timestamps, and versions are derived server-side,
never bound from the request body. Request DTOs contain only fields the user
genuinely supplies. (This is the class of bug behind "client-supplied customer"
and "client-supplied tier".)

### G1.3: Only typed errors reach the client

Every known failure maps to a dedicated HTTP status plus an actionable message.
A response never carries a stack trace, a raw exception message, or internal
detail. **Test:** no known failure path returns `500` or leaks internals.

### G1.4: Security headers and CSP

Every response carries the baseline security header set (HSTS,
`X-Content-Type-Options: nosniff`, frame denial, minimal explicit CORS). The
customer-facing UI serves a strict Content-Security-Policy with no
`unsafe-inline` / `unsafe-eval`. Removing or relaxing a header needs a named
waiver.

### G1.5: Least-privilege database account

The application connects with an account that has DML on its own tables only —
no runtime DDL, no superuser.

### G1.6: CSRF protection on the cookie session

The browser session is a cookie (BFF pattern — decided; the SPA holds no OIDC
token). Every state-changing request therefore requires CSRF protection: Spring
Security CSRF tokens plus `SameSite` on the session cookie — `Lax` minimum,
`Strict` for sensitive endpoints (buy, cancel, every admin / operator write).

---

## Tier 2 (Strong Defaults)

### G2.1: Layered shape

Controller (HTTP I/O + DTO mapping) → service (business rule + `@Transactional`
boundary) → repository (SQL). No business logic in a controller, no database
access outside a repository, no outbound HTTP outside a dedicated client. One
transaction boundary per use case.

### G2.2: Parse, don't validate at the boundary

Raw request fields are converted once into domain value objects at the HTTP
edge; an invalid field is rejected there, with the field name and the reason.

### G2.3: No direct dependency between business modules

`booking`, `pricing`, `eventmanagement`, `availability`, `quote` do not import
one another; they communicate through domain events or an explicit read
contract.

### G2.4: Mandatory tests per use case

Happy path + every typed failure. For the guarded writes and the projections,
an integration test against a real PostgreSQL. Plus a concurrency test proving
one-winner-per-seat.

### G2.5: Dependency scanning at build time

The build fails if a dependency carries a critical CVE that has not been
explicitly accepted.

### G2.6: Clean code

SOLID, no unjustified duplication, explicit names, short functions. Commit
messages as `type(scope): subject`.

### G2.7: Stack discipline

Stay within the mandated stack (Java 25, Spring Boot 4+, PostgreSQL 18+, Spring
MVC, Spring Data JDBC / `JdbcTemplate`, Flyway, Spring Security with
`oauth2-client` for the session-based BFF). No new framework, no reactive stack,
no ORM, no code generation. A genuinely needed addition is raised as an open
question, not introduced.
