# DuoLock

**An open-source Android app blocker that locks distracting apps until you complete your Duolingo lesson.**

DuoLock turns Duolingo into the key that unlocks your social media. Pick the apps you want to control, and DuoLock blocks them until you complete a Duolingo lesson. Finish the lesson, earn a session of screen time, and when the session expires, the gate closes again.

> **Do your Duolingo. Then get your social media.**

![DuoLock concept](https://raw.githubusercontent.com/Staninna/duolock/main/docs/duolock-flow.svg)

## Why DuoLock?

If you are looking for an **Android app that blocks Instagram until you do Duolingo**, a **Duolingo productivity app**, or an **open-source social media blocker for Android**, DuoLock is built for exactly that idea.

It is designed around a simple rule:

```text
┌─────────────────────┐
│ 🔒 Instagram        │
│ 🔒 TikTok           │
│ 🔒 YouTube          │
│ 🔒 Reddit           │
└──────────┬──────────┘
           │
           ▼
    🦉 Do a Duolingo
       lesson
           │
           ▼
┌─────────────────────┐
│ 🔓 Apps unlocked    │
│    for a session    │
└─────────────────────┘
```

## How it works

1. **Pick your apps.** Choose which apps DuoLock guards. For example: Instagram, TikTok, YouTube, Reddit, or any other distracting app.
2. **Hit the gate.** Opening a blocked app shows the DuoLock lock screen instead, with one button: *Do a lesson*.
3. **Do a lesson.** DuoLock verifies your progress automatically. If you connect your Duolingo account, it watches your XP grow. Without an account token, time spent inside Duolingo can be used as a fallback.
4. **Scroll, guilt-free.** A completed lesson earns a session of access. The default session is 30 minutes, and the timer only runs while a blocked app is actually on screen.
5. **The gate closes again.** When your session expires, your distracting apps are locked again.

## Energy-aware, never a dead end

Duolingo lessons cost energy. DuoLock reads the energy meter from Duolingo's own UI using an accessibility service scoped to Duolingo only, and estimates regeneration from there.

If you do not have enough energy for a lesson, DuoLock does not leave you permanently locked out. Instead, the gate provides a free pass sized to the refill time and shows you how long you need to wait.

## Streak Saver

**Streak Saver** is an optional evening lockdown mode.

From a configurable hour until midnight, everything is locked while today's Duolingo XP is still zero. Your whitelist, phone, SMS, and launcher remain available.

It is intentionally strict: do your lesson before the evening disappears into scrolling.

## Features

- **Duolingo-based app blocking** for Android
- Block any set of apps behind a Duolingo lesson
- XP-verified lessons via the Duolingo API
- Time-in-Duolingo fallback that does not require an account token
- Live Duolingo energy tracking
- Automatic energy refill-rate detection
- Free pass when there is not enough energy for a lesson
- Streak Saver lockdown mode with a per-app whitelist
- Persistent notification with a live countdown to the next possible lesson
- Halfway reminder while a session is running
- Evening warning when your Duolingo streak is at risk
- Survives reboots
- No ads
- No analytics

## Privacy

DuoLock is designed to keep your data on your phone.

The only network traffic is to the Duolingo API, and only if you connect your account. Your Duolingo token is stored in local app storage and is not sent anywhere else. The accessibility service is scoped to reading the Duolingo app's UI.

DuoLock is not affiliated with Duolingo.

## Setup

DuoLock needs four Android permissions. Each permission is explained in the Setup tab and can be enabled from there.

| Permission | Why it is needed |
|---|---|
| Usage access | Detect which app is currently in the foreground |
| Display over other apps | Show the DuoLock gate over blocked apps |
| Accessibility, Duolingo only | Read the Duolingo energy meter |
| Ignore battery optimization | Keep the gate monitor alive in the background |

On Xiaomi/HyperOS, also enable **Autostart** for DuoLock or the system may kill the monitor after a while.

Connecting your Duolingo account is optional. You can paste your `jwt_token` cookie value once, and the in-app guide explains how to obtain it from `duolingo.com` on a computer.

## Building

DuoLock is a standard Android Gradle project.

Open it in Android Studio, or build from the command line:

```sh
gradle assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Requirements:

- JDK 17
- Android SDK
- compileSdk 36
- minSdk 26 (Android 8.0)

Run unit tests with:

```sh
gradle test
```

## Architecture

The gate policy is implemented as one pure function:

```text
GateEngine.decide(state) -> effects
```

It has no Android, network, or clock dependencies. Unit tests cover the decision logic directly. Android services execute the resulting effects, while Duolingo API traffic runs outside the decision path so the lock screen never waits for the network.

## Project status

DuoLock is an independent open-source hobby project. It is currently focused on Android and on the specific workflow of **complete Duolingo first, then use distracting apps**.

## Disclaimer

DuoLock is not affiliated with, endorsed by, or sponsored by Duolingo. It communicates with the Duolingo API on behalf of your own account and reads Duolingo's UI on your own device.

## License

MIT. See [LICENSE](LICENSE).
