# DuoLock

**Your doomscrolling apps stay locked until you finish a Duolingo lesson.**

I built DuoLock because my evenings kept disappearing into the same three
apps, and willpower alone wasn't cutting it. It's a small Android app that
puts a gate in front of the apps you waste time in. Open a blocked app and
Nox, the gatekeeper, steps in. Do one Duolingo lesson and you earn a session
of scroll time. When the clock runs out, the gate closes again.

## How it works

1. **Pick your apps.** Choose which apps DuoLock guards. For me that's
   Instagram, TikTok and YouTube.
2. **Hit the gate.** Opening a blocked app shows the lock screen instead,
   with one button: *Do a lesson*.
3. **Do a lesson.** DuoLock verifies it automatically. If you connect your
   Duolingo account it watches your XP grow. Without an account token, a few
   minutes spent inside Duolingo counts instead.
4. **Scroll, guilt-free.** A finished lesson buys a session (default 30
   minutes). The clock only burns while a blocked app is actually on screen.

### Energy-aware, never a dead end

Duolingo lessons cost energy. DuoLock reads the energy meter straight from
Duolingo's own UI, using an accessibility service that is scoped to Duolingo
only, and estimates regeneration from there. If you don't have enough energy
for a lesson, the gate refuses to trap you. You get a free pass sized to the
refill time, and the lock screen tells you how long that is.

### Streak Saver

An optional evening mode. From a configurable hour until midnight, everything
is locked (except your whitelist, phone, SMS and launcher) as long as today's
XP is still zero. Slightly brutal, works great.

## Features

- Block any set of apps behind a Duolingo lesson
- XP-verified lessons via the Duolingo API, with a time-in-app fallback that
  needs no account token
- Live energy tracking with automatic refill-rate detection
- Free pass when energy is too low for a lesson, with the wait time shown
- Streak Saver lockdown mode with a per-app whitelist
- Persistent notification with a live "next lesson possible in..." countdown
- Halfway reminder while your session runs
- Evening warning when your streak is at risk
- Survives reboots; no ads, no analytics

## Privacy

Everything stays on your phone. The only network traffic is to Duolingo's
API, and only if you connect your account. Your Duolingo token lives in local
app storage and never leaves the device. The accessibility service reads the
Duolingo app and nothing else.

## Setup

DuoLock needs four permissions. Each one is explained and one tap away in the
Setup tab:

| Permission | Why |
|---|---|
| Usage access | Detect which app is in the foreground |
| Display over other apps | Show the lock screen |
| Accessibility (Duolingo only) | Read the energy meter |
| Ignore battery optimization | Keep the gate alive in the background |

On Xiaomi/HyperOS, also enable **Autostart** for DuoLock, or the system kills
the monitor after a while. Ask me how I know.

Connecting your Duolingo account is optional. You paste your `jwt_token`
cookie value once; an in-app guide walks you through getting it from
duolingo.com on a computer.

## Building

Standard Android Gradle project. Open it in Android Studio, or:

```sh
gradle assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 17 and an Android SDK (compileSdk 36, minSdk 26 / Android 8.0).
Unit tests: `gradle test`.

## Architecture

The gate policy is one pure function, `GateEngine.decide(state) -> effects`,
with no Android, network, or clock dependencies. Unit tests cover it
directly. The services around it only execute effects, and all Duolingo API
traffic runs off the decision path, so the lock screen never waits on the
network.

## Disclaimer

DuoLock is an independent hobby project, not affiliated with or endorsed by
Duolingo. It talks to the Duolingo API on your own account's behalf and reads
Duolingo's UI on your own device.

## License

MIT, see [LICENSE](LICENSE).
