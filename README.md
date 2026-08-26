# DuoLock

**Your doomscrolling apps stay locked until you finish a Duolingo lesson.**

I built DuoLock because my evenings kept disappearing into the same three apps, and willpower alone wasn't cutting it. It puts a gate in front of the apps you waste time in. Open a blocked app and Nox, the gatekeeper owl, steps in with his firefly Lumen. Do one Duolingo lesson and you earn a session of scroll time. When the clock runs out, the gate closes again.

```text
┌─────────────────────┐
│ 🔒 Instagram        │
│ 🔒 TikTok           │
│ 🔒 YouTube          │
└──────────┬──────────┘
           │
           ▼
    🦉 One Duolingo
       lesson
           │
           ▼
┌─────────────────────┐
│ 🔓 30 min of scroll │
│    time, then locked│
│    again            │
└─────────────────────┘
```

## The loop

1. **Pick your apps.** Choose which apps DuoLock guards in the Apps tab.
2. **Hit the gate.** Opening a blocked app shows the lock screen instead: Nox, Lumen glowing with your live energy level, and one button: *Do a lesson*.
3. **Do a lesson.** DuoLock verifies it on its own. With your Duolingo account connected it watches your XP grow; without a token, a configurable number of minutes inside Duolingo (default 5) counts as a lesson instead.
4. **Scroll.** A finished lesson buys a session (default 30 minutes). The clock only burns while a blocked app is actually on screen, so checking one message doesn't eat your whole session.
5. **Locked again.** When the session runs out, the gate closes. Halfway through, DuoLock reminds you that another lesson resets the clock, but only when you actually have the energy for one.

## Energy

Duolingo lessons cost energy, and an app that locks you out of everything while you can't even do a lesson would be a dead end. DuoLock refuses to be that.

### How DuoLock knows your energy

An accessibility service, scoped to the Duolingo package only, reads the meter from Duolingo's own UI on three screens:

- **The home screen** top bar, where energy is the rightmost counter.
- **The energy drawer** (the fullscreen "X / 25" view). When the drawer shows its time-until-full timer, DuoLock also derives your personal refill rate from it. A full 25/25 meter is read from the progress text alone.
- **Inside a lesson**, live. The in-lesson counter drains as you answer and sometimes Duolingo hands energy back; DuoLock records every change within seconds, so the value after a lesson is exact, not guessed.

Between readings, DuoLock estimates regeneration from the last value and the refill rate: auto-detected from the drawer when possible, overridable in Settings, and 58 minutes per unit as the default.

### Not enough energy: the free pass

Below the lesson threshold (default 10 units, configurable) the gate will not trap you. Instead you get a free pass sized to the refill wait, capped at the session length so the meter is re-checked every round. The lock screen and notification tell you how long until the next lesson.

Three rules keep the free pass honest:

- **A recovered meter kills the pass.** The moment a reading shows enough energy for a lesson, the pass is revoked and the gate arms again. Only passes earned by an actual lesson survive.
- **Old readings must be re-verified.** A low reading older than the re-check window (default 150 minutes) stops buying passes. The lock screen asks you to open Duolingo once; the reader stores the real meter within seconds, and you either bounce straight back to your app with an honest pass or the gate arms because you had energy all along.
- **Streak Saver has no grace at all.** While it is armed, every free pass needs a reading fresh from the current Duolingo visit.

### When the reader dies

Android never restarts a crashed accessibility service; the system keeps claiming it is enabled while every reading silently goes stale. DuoLock checks for that gap every 30 seconds and sends one notification ("Nox fell asleep") telling you to toggle the reader off and on in the Setup tab.

## Streak Saver

An optional evening mode for people whose streak matters more than their evening. From a configurable hour (default 21:00) until midnight, *everything* is locked while today's XP is still zero: not just your blocked list. Your whitelist, phone, SMS, launcher, and Duolingo itself stay usable. Do the lesson and it lifts.

It needs a connected account (the XP check is the trigger) and an active streak. Separately, DuoLock warns you once per evening when your streak is at risk, from a configurable hour.

## Settings reference

| Setting | Default | Range |
|---|---|---|
| Session length after a lesson | 30 min | 1–240 |
| Minutes in Duolingo that count as a lesson (fallback) | 5 min | 1–60 |
| Energy refill rate | auto-detected, else 58 min/unit | 1–720, blank = auto |
| Energy needed to finish a lesson | 10 units | 1–25 |
| Notify when entering a blocked app with time left | on | |
| Halfway reminder | on | |
| Re-check low energy after | 150 min | 15–1440 |
| Streak warning from hour | 21 | 0–23 |
| Streak Saver | off | |
| Streak Saver start hour | 21 | 0–23 |
| Streak Saver whitelist | empty | any apps |

Number fields save through the Save button; switches apply immediately.

## Setup

DuoLock walks you through this at first launch, and the Setup tab shows live status for each permission afterwards.

| Permission | Why |
|---|---|
| Usage access | See which app is in the foreground |
| Display over other apps | Show the lock screen over blocked apps |
| Accessibility (Duolingo only) | Read the energy meter |
| Ignore battery optimization | Keep the monitor alive in the background |

On Xiaomi/HyperOS, also enable **Autostart** for DuoLock in the system app settings, or MIUI kills the monitor after a while. DuoLock restarts itself after a reboot.

### Connecting your Duolingo account

Optional but recommended: it enables XP-verified lessons, Streak Saver, and the streak warning. Log in at duolingo.com on a computer, copy the `jwt_token` cookie value (starts with `eyJ`), and paste it in Settings. The in-app guide under the token field has the step-by-step. Without a token, the time-in-Duolingo fallback still works.

## Privacy

Everything stays on your phone. The only network traffic is to the Duolingo API, and only if you connect your account; the token lives in local app storage and goes nowhere else. The accessibility service can only see the Duolingo app. No ads, no analytics.

## Building

A standard Android Gradle project. Open it in Android Studio, or:

```sh
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
./gradlew test
```

Requirements: JDK 17, Android SDK with compileSdk 36. Runs on Android 8.0 (minSdk 26) and up.

Debug builds carry an extra Debug tab (hideable in Settings) with developer tools: fake energy readings, instant test passes, session reset, and reading age simulation. Release builds compile all of it away.

### Cutting a release

Bump `versionCode` and `versionName` in `app/build.gradle.kts`, commit, then push a matching `v*` tag. CI runs the tests, builds the APK signed with the release keystore, and attaches it to a generated GitHub release.

`versionName` must match the tag: Settings > Updates compares the newest release tag against the running build, so a stale `versionName` makes the app offer an update it already is.

## Architecture

The whole gate policy is one pure function: `GateEngine.decide(snapshot, user, foreground, time) -> effects`. No Android, network, or clock dependencies inside, which is why the unit tests can cover the decision logic directly. Services execute the returned effects; Duolingo API traffic runs outside the decision path, so the lock screen never waits on the network.

The energy reader is a separate accessibility service that funnels every observation through one channel into one storage transaction. Readings deduplicate against the store itself, so a value written by anything else (including the debug screen) is never shadowed by the reader's memory.

## Disclaimer

DuoLock is an independent hobby project, not affiliated with, endorsed by, or sponsored by Duolingo. It talks to the Duolingo API on behalf of your own account and reads Duolingo's UI on your own device.

## License

MIT. See [LICENSE](LICENSE).
