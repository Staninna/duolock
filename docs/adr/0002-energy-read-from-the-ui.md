# ADR-0002 — Energy is read from Duolingo's UI, not its API

**Status:** accepted

## Context

The gate must not block someone who *cannot* do a lesson: with no energy, the
lock screen would be a wall with no door. Duolingo's public API exposes XP and
streak, but not the energy meter.

## Decision

An accessibility service (`EnergyReaderService`) watches Duolingo's own screens
and reads the energy counter when it appears. It watches only Duolingo.

Every observation funnels through one channel into one storage transaction, and
readings deduplicate against the store itself — so a value written by anything
else, including the debug screen, is never shadowed by the reader's in-memory
copy.

## Consequences

- The app needs an accessibility permission, which is a large ask of the user
  and is explained in its own onboarding step.
- Readings are opportunistic: the app only learns the energy level when the user
  opens Duolingo. Between readings the level is *estimated* from a refill rate,
  which is itself observed from the energy drawer when possible.
- An estimate that says "empty" can be wrong. ADR-0003 covers what the gate does
  about that.
