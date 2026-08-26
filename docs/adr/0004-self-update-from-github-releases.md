# ADR-0004 — The app updates itself from its own GitHub releases

**Status:** accepted

## Context

DuoLock is not on any store. Getting a new build onto the phone meant a cable
and `adb install`, which is fine at a desk and useless anywhere else.

## Decision

CI publishes a signed APK on every `v*` tag. Settings → Updates asks
`releases/latest` — unauthenticated, because the repo is public — and offers to
download and install anything newer than `BuildConfig.VERSION_NAME`.

Two guards sit in the release workflow rather than in the app:

- the tag must equal `versionName`, or the release fails; otherwise the app
  offers an update it already is
- the APK must not be debug-signed, or the release fails; a missing keystore
  secret otherwise publishes an unsigned download that installs on nothing

## Consequences

- Version bumps become part of cutting a release, not an afterthought.
- The first release of any app can only be installed by hand: the updater only
  takes over from the build that contains it.
- Silent install is requested on Android 12+ and refused by some vendor builds.
  HyperOS destroys the session rather than reporting `PENDING_USER_ACTION`, so a
  failed silent attempt retries once with the system installer dialog.
- No credential is ever compiled in. Were the repo private, self-update would
  need a token inside the APK, which anyone holding the APK could read — which
  is the reason the repo is public rather than a happy accident.
