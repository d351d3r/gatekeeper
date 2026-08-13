# Gatekeeper

Gatekeeper is a fork of [Shelter](https://cgit.typeblog.net/Shelter/about/), a Free and Open-Source Android app that uses a Work Profile to run apps in an isolated space. You can clone apps into the work profile, freeze them when not in use, and batch-freeze the auto-freeze list from the toolbar or launcher shortcuts.

**Repository:** [d351d3r/gatekeeper](https://github.com/d351d3r/gatekeeper)

Shelter remains the upstream base. Gatekeeper adds a modernized interface, stable cross-profile file access, Anti Spy VPN controls, and diagnostics for profile reliability.

## Features

- Install or clone apps into an isolated work profile
- Freeze / unfreeze individual apps
- **Auto-freeze** list — apps frozen together on screen lock, batch freeze, Anti Spy VPN events, and shortcuts
- **Batch freeze / unfreeze** from the toolbar, settings, or home-screen shortcuts
- Frozen apps sorted to the top of the work profile list
- Anti Spy: detect third-party VPN, prompt for batch freeze, dummy-VPN displacement on app launch

User guide (Russian): [USER_GUIDE.md](USER_GUIDE.md) · [PDF](USER_GUIDE.pdf)

Known issues and test notes: [PROBLEMS.md](PROBLEMS.md)

## Requirements

- Android 7.0+ (API 24+)
- A device with a working Work Profile implementation (AOSP-like ROMs work best; heavily vendor-modified firmware may break profile features)

Tested on:

- Samsung Galaxy S24 Ultra (SM-S928B/DS)
- Samsung Galaxy Tab S9 FE+ (SM-X616B)

## Clone and build

```sh
git clone --recurse-submodules https://github.com/d351d3r/gatekeeper.git
cd gatekeeper
```

If you already cloned without submodules:

```powershell
git submodule update --init --recursive
```

Build a debug APK (Android Studio JBR or JDK 17+):

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/` as `Gatekeeper-{version}-({code})-debug.apk`.
`version.properties` is auto-incremented on each `assemble*` task.

## Publish to GitHub (maintainers)

The checkout is configured for this repository:

   ```powershell
   git remote -v
   ```

To publish a prepared commit:

   ```powershell
   git push -u origin main
   git push origin --tags
   ```

## Uninstalling

Delete the work profile first in **Settings**, then uninstall Gatekeeper normally. Removing only the launcher icon does not remove the work profile or cloned apps.

## License

GPL-3.0-or-later - see [LICENSE](LICENSE). Gatekeeper is derived from Shelter; respect upstream licensing when redistributing.

## Upstream

- [Shelter](https://cgit.typeblog.net/Shelter/about/) by PeterCxy
- [SetupWizardLibrary](https://gitea.angry.im/PeterCxy/SetupWizardLibrary) (git submodule)
