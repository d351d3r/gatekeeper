## Gatekeeper 0.1.8 (17)

- Added opt-in cross-profile content intents for opening, editing, picking, and creating documents.
- Added Google Files entry flow that opens the Gatekeeper work-profile storage root in the system document picker.
- Added temporary document grants for compatible external file managers.
- Fixed a File Shuttle startup race that could leave the document root unavailable.
- Added instrumentation coverage for document-provider access and cross-profile intent resolution.
