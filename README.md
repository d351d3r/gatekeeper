# Gatekeeper

Gatekeeper isolates unwanted apps inside an Android Work Profile: a separate
system user with its own data, accounts, permissions, and lifecycle. Cloned apps
look and behave like ordinary phone apps, but they cannot see the owner's
personal environment.

Product direction and platform boundaries: [PRODUCT.md](PRODUCT.md).
Русская версия: [README_RU.md](README_RU.md).

## Features

- **Clone apps** into the isolated work profile (installed from the personal
  profile or from an APK on the device).
- **Freeze and unfreeze** individual apps: the shortcut disappears, processes
  are killed, and the app can no longer run in the background.
- **Auto-freeze list** — apps frozen together on screen lock, from the toolbar,
  on Anti Spy events, or via a home-screen shortcut.
- **Batch freeze / unfreeze** in one action from the toolbar, settings, or
  shortcuts.
- Frozen apps are sorted to the top of the work-profile list.
- **Anti Spy** — detects third-party VPNs, prompts to batch-freeze the list,
  and displaces an active VPN with a dummy tunnel when a protected app launches.
- **File Shuttle** — moves files between the personal and work profiles via
  Storage Access Framework: documents open in the right profile without copying
  data around.
- **Media auto-copy** — new screenshots and camera photos/videos from the work
  profile appear in the personal gallery whenever File Shuttle connects
  (disabled by default).
- **Work-profile root CA certificates** — install a root CA into the work
  profile only, with the SHA-256 fingerprint shown before install, plus a
  one-tap removable list.
- First-run setup wizard and firmware-problem diagnostics.

User guide (Russian): [USER_GUIDE.md](USER_GUIDE.md).
Known issues and test notes: [PROBLEMS.md](PROBLEMS.md).

## Limitations (platform walls)

These are Android/firmware boundaries, not Gatekeeper bugs — no app of this
class can fix them:

- **VPN visibility.** Apps inside the work profile can see that a VPN is
  active (the tun0 interface), even when the VPN runs only in the personal
  profile. Practical workarounds: run the VPN *inside* the work profile, or
  rely on Anti Spy auto-freeze when the VPN comes up.
- **NFC payments.** Payment apps installed only in the work profile are not
  offered as the default payment app on most firmware (works on some devices,
  e.g. recent Honor). The in-app "payment service stub" only works around one
  specific Android bug.
- **Freezing an app that is open right now.** Hiding an app stops it, even
  when it is on screen: Android force-stops the package. Gatekeeper does not do
  that to apps you are using — the "Skip foreground apps" setting keeps music
  and calls alive and leaves those apps running. Turn it off and they are frozen
  too. This is a product choice, not a platform limit.
- **Screenshots stay in the profile.** A screenshot taken inside the work
  profile lands in the work profile's storage; Media Mirror auto-copies
  screenshots and camera photos to the personal profile.
- **Fake GPS cannot be scoped to the profile.** The permission can:
  `android:mock_location` is granted per user, so a fake-GPS app inside the
  profile can be authorized there alone. The override cannot: a mock provider
  replaces the real one for the whole device, so the personal side sees the fake
  location too.
- **Vendor ADB restrictions.** Some firmware (HyperOS, ColorOS) blocks
  `pm ... --user <profile>` for shells, so profile cleanup via ADB is not
  portable.
- **Private Space** (Android 15+) solves a different problem than a work
  profile: hiding apps behind another lock, not sandboxing untrusted apps.
  See USER_GUIDE §13 for a detailed comparison.

## Requirements

- Android 7.0+ (API 24+).
- A device with a working Work Profile implementation (AOSP-like ROMs work best;
  heavily vendor-modified firmware may break profile features).

## Building

Requires JDK 17 and the Android SDK (compileSdk 36, build-tools 36.0.0).

```sh
git clone https://github.com/d351d3r/gatekeeper.git
cd gatekeeper
./gradlew :app:assembleDebug      # debug APK
./gradlew :app:assembleRelease    # release APK (minified)
```

The finished APK is copied to the repository root as
`Gatekeeper-{version}-({versionCode})-{debug|release}.apk`. The version number
comes from [version.properties](version.properties) and can be overridden:
`./gradlew :app:assembleDebug -PversionCode=400`.

Checks before building:

```sh
./gradlew :app:lintDebug :app:detekt :app:testDebugUnitTest
```

## Repository layout

- `app/` — the application (Kotlin, AIDL).
- `tools/` — utility scripts: `testbench.sh` (emulator verification bench over
  ADB), `repackage-google-play.sh` (Google Play variant without
  MANAGE_EXTERNAL_STORAGE).
- `docs/` — threat model, feature specs.
- `assets/` — toolbar/shortcut icon sources.

## Uninstalling

Delete the work profile in system **Settings** first, then uninstall Gatekeeper
normally. Removing only the launcher icon does not remove the work profile or
the cloned apps.
