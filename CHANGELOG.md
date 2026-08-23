Unreleased (internal rebrand)
===

- Full internal rebrand to Gatekeeper: classes (`GatekeeperApplication`, `GatekeeperService`, `GatekeeperDeviceAdminReceiver`), AIDL (`IGatekeeperService`), styles (`Theme.Gatekeeper.*`), toast helper, shortcut IDs (`gatekeeper-*`), SAF root (`/gatekeeper_storage_root/`), notification channels (`GatekeeperService*`), action strings consolidated on `io.gatekeeper.action.*`.
- Removed legacy compatibility: `net.typeblog.shelter.action.*` bridge and legacy cross-profile intent filters, legacy `"shelter-"` shortcut prefix matching, legacy notification channel cleanup.
- **Breaking:** update from any pre-rebrand build requires recreating the work profile (device admin component changed) and re-pinning shortcuts / re-granting SAF folders.

