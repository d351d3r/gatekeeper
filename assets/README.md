# Optional assets (not required to build)

Place source PNGs here to regenerate icons:

| File | Script |
|------|--------|
| `gatekeeper_icon_freeze_source.png` | `.\tools\generate_freeze_icons.ps1` (also needs unfreeze) |
| `gatekeeper_icon_unfreeze_source.png` | same |

`generate_freeze_icons.ps1` writes:
- `ic_toolbar_*` — transparent background (toolbar, snowflake badge in app list)
- `ic_shortcut_*` — opaque `#223D2C` PNG for API 25 and older launchers
