# ADR-0003 — A stale low reading buys nothing until it is re-checked

**Status:** accepted

## Context

"You have no energy, so go through" is the gate's own loophole. If the last
reading is hours old, an estimate of empty may simply be wrong — and it is the
one state that disables blocking entirely.

## Decision

Past `staleReadingMinutes` (default 150), a low reading stops granting free
passes. The lock screen asks the user to open Duolingo so the reader can take a
fresh look, and promises that if they really are empty they go straight through.

With Streak Saver active, the check always happens regardless of age.

## Consequences

- The honest failure mode is a small amount of friction — one trip into
  Duolingo — rather than a silent unlock that defeats the app.
- This state needs its own lock-screen copy. It used to share a headline, an
  illustration and a "Do a lesson" button with the ordinary prompt, which read
  as "go do a lesson" when the actual ask was "let me look at your meter". It
  now has a distinct headline, colour and button.
