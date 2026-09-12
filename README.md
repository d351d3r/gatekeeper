# Gatekeeper

Gatekeeper isolates unwanted apps inside an Android Work Profile: a separate
system user with its own data, accounts, permissions, and lifecycle. Cloned apps
look and behave like ordinary phone apps, but they cannot see the owner's
personal environment.

The project is a fork of [Shelter](https://cgit.typeblog.net/Shelter/about/)
(PeterCxy, GPL-3.0). Compared to upstream it adds a modernized interface, stable
cross-profile file access, the Anti Spy subsystem, and diagnostics for profile
reliability.

**Repository:** [d351d3r/gatekeeper](https://github.com/d351d3r/gatekeeper)

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
Changelog: [CHANGELOG.md](CHANGELOG.md).

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
   Artifacts and SHA-256 checksums are attached to the run. If the
   `CI_RELEASE_KEYSTORE*` secrets are set, the release APK is signed with the
   release key; otherwise it uses the debug certificate.

Releases are published from `v*` tags — see
[.github/workflows/release.yml](.github/workflows/release.yml).

## Repository layout

- `app/` — the application (Kotlin + Java, AIDL).
- `libs/SetupWizardLibrary/` — vendored setup-wizard library
  ([upstream](https://gitea.angry.im/PeterCxy/SetupWizardLibrary)), built from
  source as the `:setup-wizard-lib` module.
- `tools/testbench.sh` — emulator-based verification bench (ADB).
- `docs/` — threat model, feature specs, test checklists.
- `assets/` — toolbar/shortcut icon sources.

## Uninstalling

Delete the work profile in system **Settings** first, then uninstall Gatekeeper
normally. Removing only the launcher icon does not remove the work profile or
the cloned apps.

## License

GPL-3.0-or-later — see [LICENSE](LICENSE). Gatekeeper is derived from Shelter;
respect upstream licensing when redistributing.

## Upstream

- [Shelter](https://cgit.typeblog.net/Shelter/about/) by PeterCxy.
- [SetupWizardLibrary](https://gitea.angry.im/PeterCxy/SetupWizardLibrary) —
  vendored under `libs/SetupWizardLibrary`.
