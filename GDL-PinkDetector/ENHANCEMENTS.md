# Enhancements

Add ideas here; tick them off with the app version when completed.

- [ ] **Handle DJI Fly closing or freezing.** Added 2026-09-13.
  - Current behaviour: when DJI Fly loses foreground, GDL pauses new captures and blocks new dispatches after detecting the change, displays “ARMED • waiting for DJI Fly”, and stays armed. It can resume when DJI returns; it does not restart DJI Fly.
  - Gaps to address: explicitly handle cancellation of an ongoing gesture; recheck foreground between the 2-second hold and 6-second drag; detect a frozen DJI window that still matches the foreground package.
  - Decide later: automatic resume versus manual re-arming, and whether any DJI restart handling is wanted.
  - Verify on device: closing/freezing during a gesture and reopening DJI. Aircraft and ActiveTrack behaviour after a crash remain unverified.
  - Evidence: static code review at `67b5802da25e127da5fcc81d6c5c226c1cf516c5`, recorded in [GDL_CURRENT_STATE.md](GDL_CURRENT_STATE.md#dji-fly-exitfreeze-review-2026-09-13). This is a backlog item, not an implemented fix.
