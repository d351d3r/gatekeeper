## Gatekeeper 0.0.1

First published build. Gatekeeper isolates unwanted apps in an Android work
profile: a separate system user with its own data, accounts, permissions and
lifecycle. Cloned apps look and behave like ordinary ones, but they cannot see
the personal side of the phone.

### Requirements

- Android 7.0 or newer (API 24+), arm64-v8a.
- A firmware with a working Work Profile implementation. AOSP-like builds are
  fine; heavily reworked vendor shells can break profile features.

Developed against AOSP 16 on an emulator; the feature set has been exercised on
Samsung Galaxy S24 Ultra and Galaxy Tab S9 FE+.

### What is in this build

- Cloning apps into an isolated work profile, from the personal profile or from
  an APK on the device.
- Freezing and unfreezing individual apps: the icon disappears, processes are
  killed, the app cannot start in the background.
- An auto-freeze list that can be frozen together on screen lock, from the
  toolbar, from a home screen shortcut, or on an Anti Spy event.
- Anti Spy: detects a third-party VPN and offers to freeze the list; can stub
  the VPN while a protected app starts.
- File Shuttle: files cross the profile boundary through the system file picker,
  one picked file at a time. A file manager that can add a storage location
  keeps access permanently.
- Media Mirror: new screenshots and camera photos from the work profile appear
  in the personal gallery. Off by default.
- Root CA certificates installed into the work profile only, with the SHA-256
  fingerprint shown before installing.
- Always-on VPN pinned inside the profile, with optional lockdown.
- Setup wizard and a diagnostics screen for firmware quirks.

### Before you install

Removing the work profile deletes it with every cloned app, its accounts, its
chats and its files. There is no undo. Gatekeeper itself cannot be uninstalled
while it owns the profile: Android refuses, and the button in system settings is
greyed out. Removing the personal copy is harmless, and a reinstalled copy can
reclaim the live profile after a confirmation with a matching code.

Settings has a screen that states all of this: Uninstalling Gatekeeper.

### Platform limits

These are Android and firmware boundaries, not defects: apps inside the profile
can see that a VPN is up even when it runs only in the personal profile; NFC
payment apps installed only in the profile are not offered as the default on
most firmwares; a screenshot taken inside the profile lands in the profile's
storage. The full list, with what each limit costs and what works around it, is
in the README.

### Installing

Only the arm64-v8a APK is published. It is signed with the project release key;
a debug build will not install over it and the reverse is also true.
