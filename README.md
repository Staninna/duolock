# DuoLock

**Your doomscrolling apps stay locked until you finish a Duolingo lesson.**

DuoLock is a small Android app that puts a gate in front of the apps you waste
time in. Open a blocked app and Nox, the gatekeeper, steps in: do one Duolingo
lesson and you earn a session of scroll time. When the clock runs out, the gate
closes again. One lesson, one round — every day, in both directions.

## How it works

1. **Pick your apps.** Choose which apps DuoLock guards (Instagram, TikTok,
   YouTube — whatever eats your evenings).
2. **Hit the gate.** Opening a blocked app shows the lock screen instead, with
   one button: *Do a lesson*.
3. **Do a lesson.** DuoLock verifies it automatically — by watching your XP
   grow via the Duolingo API (optional token), or simply by time spent inside
   Duolingo as a fallback.
4. **Scroll, guilt-free.** A finished lesson buys a configurable session
   (default 30 minutes). The clock only burns while a blocked app is actually
   on screen.

### Energy-aware, never a dead end

Duolingo lessons cost energy. DuoLock reads the energy meter straight from
Duolingo's own UI (via an accessibility service scoped to Duolingo only) and
estimates regeneration. If you don't have enough energy for a lesson, the gate
**never locks you into a dead end** — you get a free pass sized to the refill
time instead.

### Streak Saver

Optional evening mode: from a configurable hour until midnight, *everything*
is locked (except your whitelist, phone, SMS and launcher) as long as today's
XP is still zero. Your streak survives; your evening plans adapt.

## Features

- Block any set of apps behind a Duolingo lesson
- XP-verified lessons via the Duolingo API, with a time-in-app fallback that
  needs no account token
- Live energy tracking with automatic refill-rate detection
- Free pass when energy is too low for a lesson — with the wait time shown
- Streak Saver lockdown mode with per-app whitelist
- Persistent notification with a live "next lesson possible in…" countdown
- Halfway reminder while your session runs
- Evening warning when your streak is at risk
- Survives reboots; no ads, no analytics, no network calls except to Duolingo

## Privacy

Everything stays on your phone. The only network traffic is to Duolingo's API,
and only if you connect your account. Your Duolingo token is stored locally
and never leaves the device. The accessibility service is restricted to the
Duolingo app and reads nothing else.

## Setup

DuoLock needs four permissions, each explained and one tap away in the Setup
tab:

| Permission | Why |
|---|---|
| Usage access | Detect which app is in the foreground |
| Display over other apps | Show the lock screen |
| Accessibility (Duolingo only) | Read the energy meter |
| Ignore battery optimization | Keep the gate alive in the background |

On Xiaomi/HyperOS, also enable **Autostart** for DuoLock, or the system may
kill the monitor.

Connecting your Duolingo account is optional: paste your `jwt_token` cookie
value (a one-time, in-app guide walks you through it). Without it, a few
minutes spent inside Duolingo counts as your lesson.

## Building

Standard Android Gradle project — open in Android Studio, or:

```sh
gradle assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 17 and an Android SDK (compileSdk 36, minSdk 26 / Android 8.0).
Unit tests: `gradle test`.

## Architecture

The gate policy is a single pure function — `GateEngine.decide(state) →
effects` — with no Android, network, or clock dependencies, fully covered by
unit tests. Services around it only execute effects; all Duolingo API traffic
runs off the decision path so the lock screen never waits on the network.

## Disclaimer

DuoLock is an independent hobby project, not affiliated with or endorsed by
Duolingo. It talks to the Duolingo API on your own account's behalf and reads
Duolingo's UI on your own device.

## License

MIT — see [LICENSE](LICENSE).
