# ADR-0001 — The gate policy is one pure function

**Status:** accepted

## Context

The decision "does this app open right now?" depends on the foreground app, the
clock, persisted session state, Duolingo's answer about XP and energy, and a
dozen settings. Spread across services, that logic becomes untestable: every
test needs Android, a network, and a controllable clock.

## Decision

All of it lives in `GateEngine.decide(snapshot, user, foreground, time)`, which
returns a list of effects. No Android imports, no network calls, no reads of the
system clock inside it. Services execute the effects it returns.

Duolingo API traffic runs in a separate refresher coroutine, outside the
decision path, so the tick — and the lock screen — never wait on a request.

## Consequences

- The policy is covered by ordinary JVM unit tests, including the awkward cases:
  stale readings, Streak Saver hours, notification toggles, pass sizing.
- New policy must go **inside** `decide`. Putting a rule in a service is how
  this property gets lost, and nothing will fail loudly when it does.
- `decide` is long. It was restructured into named steps rather than split into
  classes, because the ordering between steps is itself the policy and hiding it
  behind indirection made it harder to read, not easier.
