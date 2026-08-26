# DuoLock

An Android app-blocker: chosen apps stay locked until a Duolingo lesson is done.
Single module, Compose, no DI framework.

The package is `dev.stan.duolock` and the app is called DuoGate in places.
**Keep the package name** — it is not a mistake to be tidied up.

## House rules

This repo follows the shared Android app pattern. **Load the
`android-app-repo` skill** before touching signing, CI, releases, or the
in-app updater — it holds the invariants, the templates, and the gotchas
that already cost a day of debugging.

The parts that bite hardest if you don't know them:

- **`versionName` must equal the release tag minus the `v`.** The release
  workflow fails the build if they disagree, because the in-app updater
  compares them. Bump `versionCode` and `versionName` together, in the commit
  before the tag.
- **Release signing comes from environment variables.** The keystore lives at
  `~/.secrets/duolock/release.p12` and never in the repo. A local release build
  without those variables is unsigned on purpose: only CI ships installable
  builds.
- **Debug builds install as `dev.stan.duolock.dev`**, labelled "DuoGate dev",
  with their own storage — so a dev build never inherits the real gate's
  blocked-app list or Duolingo token, and a fresh one blocks nothing.
- **Never compile a credential into the app.** The Duolingo JWT is pasted in by
  the user and stays on the device.

## The gate is one pure function

`GateEngine.decide(snapshot, user, foreground, time) -> effects`

No Android, no network, no clock inside it. Services execute the effects it
returns; Duolingo API traffic happens outside the decision path so the lock
screen never waits on a request. **This is why the policy is testable — keep
new policy inside `decide`, not in the services.**

## Layout

```
blocking/   the gate: engine, monitor service, lock screen, energy reader
duolingo/   API client, energy estimation, lesson verification
data/       settings and session state (DataStore)
ui/         screens
updates/    in-app updater
```

## Things that will surprise you

- **The energy reader is an accessibility service** reading Duolingo's UI. It
  watches only Duolingo. Every observation funnels through one channel into one
  storage transaction, and readings deduplicate against the store itself.
- **A stale low-energy reading is not trusted.** Past `staleReadingMinutes` the
  gate asks for a fresh look rather than handing out free passes.
- **Debug builds carry a Debug tab** with fake energy readings and instant
  passes. Release builds compile all of it away.

## Testing

```sh
./gradlew testDebugUnitTest   # the gate policy is covered here
./gradlew lintDebug
```
