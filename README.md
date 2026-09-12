# Gatekeeper

Gatekeeper isolates unwanted apps inside an Android Work Profile: a separate
system user with its own data, accounts, permissions, and lifecycle. Cloned apps
look and behave like ordinary phone apps, but they cannot see the owner's
personal environment.

**Repository:** [d351d3r/gatekeeper](https://github.com/d351d3r/gatekeeper)

Product direction and platform boundaries: [PRODUCT.md](PRODUCT.md).
Русская версия: [README_RU.md](README_RU.md).

## What's new in 1.5.3

- **Work-profile media auto-copy** — new screenshots and camera photos/videos from
  the work profile appear in the personal gallery whenever File Shuttle connects
  (disabled by default). Catch-up-by-watermark model: no persistent service, files
  that appeared while the connection was down are picked up on the next connect,
  duplicates are skipped. Work → personal only. Details:
  [docs/feature_media_mirror.md](docs/feature_media_mirror.md).
- **Work-profile root CA certificates** — install a root CA into the work profile
  only: the SHA-256 fingerprint is shown before install, installed certificates are
  listed and removable in one tap. Browsers honor the user CA store, most apps
  (targetSdk 24+) do not. Details:
  [docs/feature_ca_certs.md](docs/feature_ca_certs.md).
- **The auto-freeze list is now fully manual** — apps installed by stores inside
  the work profile are no longer added to the list automatically; toggle the
  snowflake per app. The background auto-add mechanism was reverted: on stock
  Android 16 `PACKAGE_ADDED` receivers are not delivered, and work-to-parent
  cross-profile sends resolve no recipients — the mechanism could only ever fire
  when the user opened the app list anyway.
- **Updating from earlier builds requires recreating the work profile** (the device
  admin component changed) and re-pinning shortcuts / re-granting SAF folders.

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
- **Freezing vs. running processes.** Hiding an app does not kill its already
  running foreground process (e.g. music keeps playing); Gatekeeper only kills
  background processes best-effort.
- **Screenshots stay in the profile.** A screenshot taken inside the work
  profile lands in the work profile's storage; Media Mirror auto-copies
  screenshots and camera photos to the personal profile.
- **Fake GPS cannot be per-profile.** Mock location is system-wide.
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

Tested on Samsung Galaxy S24 Ultra (SM-S928B/DS) and Galaxy Tab S9 FE+
(SM-X616B), and exercised on an AOSP 16 emulator during development.

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

Run the same checks as CI before building:

```sh
./gradlew :app:lintDebug :app:detekt :app:testDebugUnitTest
```

## CI

Every push to `main` runs the pipeline in
[.github/workflows/android.yml](.github/workflows/android.yml):

1. **Gate** — Android lint → detekt static analysis (baseline-locked) → unit
   tests.
2. **Build** — only after a green gate: a bot commits a `VERSION_CODE` bump and
   assembles **arm64-v8a debug and release APKs** with the new version number.
   Artifacts and SHA-256 checksums are attached to the run.

## Signing

APKs built by CI from `main` share one **private project keystore**: the key
lives in GitHub Secrets (`CI_RELEASE_KEYSTORE*`) and CI signs both debug and
release with it, so either flavor always installs as an update over the other.
Nobody outside the repo can sign with this key; fork and PR builds fall back to
the public `app/gatekeeper.keystore` and produce a different signature (they
will **not** install over CI builds).

Keep your own private copy of the keystore: if both the local copy and the
GitHub secret are lost, the signature is unrecoverable and every device must
reinstall the app (export settings first — removing the app removes the work
profile).

Releases are published from `v*` tags — see
[.github/workflows/release.yml](.github/workflows/release.yml).

## Repository layout

- `app/` — the application (Kotlin, AIDL).
- `libs/SetupWizardLibrary/` — the setup-wizard library, built from source as
  the `:setup-wizard-lib` module.
- `tools/` — utility scripts: `testbench.sh` (emulator verification bench over
  ADB), `repackage-google-play.sh` (Google Play variant without
  MANAGE_EXTERNAL_STORAGE).
- `docs/` — threat model, feature specs.
- `assets/` — toolbar/shortcut icon sources.

## Uninstalling

Delete the work profile in system **Settings** first, then uninstall Gatekeeper
normally. Removing only the launcher icon does not remove the work profile or
the cloned apps.

## License

GPL-3.0-or-later — see [LICENSE](LICENSE).
