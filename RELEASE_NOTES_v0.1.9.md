## Gatekeeper 0.1.9 (18)

- Added an opt-in bridge for creating calendar events from work apps in a personal-profile calendar.
- The bridge forwards standard calendar `ACTION_INSERT` requests without exposing personal calendar accounts to the work profile.
- Existing events and calendar accounts remain in their original profiles.
- Added emulator coverage for the work-to-personal calendar forwarder.
