Unreleased (internal rebrand)
===

- **Behavior change:** the work-profile auto-freeze list is now fully manual. Apps installed by stores inside the work profile (RuStore etc.) are no longer added to the auto-freeze list automatically; enable the snowflake toggle per app. The background mechanism (JobScheduler scan + cross-profile report) was reverted: on stock Android 16 manifest `PACKAGE_ADDED` receivers are not delivered at targetSdk 35 and work→parent cross-profile intent resolution returns no candidates, so it could only ever fire when the user opened the app list anyway.
- Full internal rebrand to Gatekeeper: classes (`GatekeeperApplication`, `GatekeeperService`, `GatekeeperDeviceAdminReceiver`), AIDL (`IGatekeeperService`), styles (`Theme.Gatekeeper.*`), toast helper, shortcut IDs (`gatekeeper-*`), SAF root (`/gatekeeper_storage_root/`), notification channels (`GatekeeperService*`), action strings consolidated on `io.gatekeeper.action.*`.
- Removed legacy compatibility: `net.typeblog.shelter.action.*` bridge and legacy cross-profile intent filters, legacy `"shelter-"` shortcut prefix matching, legacy notification channel cleanup.
- **Breaking:** update from any pre-rebrand build requires recreating the work profile (device admin component changed) and re-pinning shortcuts / re-granting SAF folders.

