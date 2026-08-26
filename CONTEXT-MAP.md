# Context map — DuoLock

Three contexts, each owning its own vocabulary. The gate is the core; the other
two exist to feed it or to keep it current.

| Context | Lives in | Owns |
|---|---|---|
| **Gate** | `app/src/main/java/dev/stan/duolock/blocking/` | Whether an app opens right now, and what the user sees when it doesn't |
| **Duolingo account** | `app/src/main/java/dev/stan/duolock/duolingo/` | What Duolingo says about the user: XP, streak, energy |
| **Self-update** | `app/src/main/java/dev/stan/duolock/updates/` | Getting a newer build of DuoLock onto the phone |

Supporting packages carry no domain vocabulary of their own: `data/` persists
whatever the Gate decides, `ui/` renders it, `permissions/` and `boot/` are
platform plumbing.

System-wide decisions: [`docs/adr/`](docs/adr/).

## The one-sentence version

The Gate asks the Duolingo context "has a lesson happened, and is one even
possible right now?", and turns the answer into allowance — minutes of scroll
time — which it spends down while a blocked app is on screen.

## Words that mean something specific here

- **Gate** — the decision, not the screen. `GateEngine.decide` *is* the gate.
- **Allowance** — earned scroll time, in milliseconds, spent only while a
  blocked app is foreground.
- **Pass** — a free unlock granted when a lesson is impossible (no energy), as
  opposed to allowance earned by doing one.
- **Energy** — Duolingo's own meter. Below `minEnergyForLesson` a lesson cannot
  realistically be finished, so the gate must not block.
- **Stale reading** — an energy observation old enough that it no longer
  justifies handing out passes. See ADR-0003.
- **Streak Saver** — the evening lockdown: everything blocked until today's
  lesson is done.
- **Nox** and **Lumen** — the owl and the firefly on the lock screen. Lumen's
  brightness *is* the live energy level; it is not decoration.
