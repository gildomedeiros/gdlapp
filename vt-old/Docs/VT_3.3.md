# VT 3.3 — fixed ride timer, touch lock, recording gate and distance pitch

Status: implemented in the existing vt-28 worktree. Version 3.3 / code 17. Device validation pending.

## Changes

| Area | VT 3.3 |
|---|---|
| Ride start | Existing short-window speed threshold and 40 km/h jump filter. Initial detection immediately cancels the no-ride timer; no second confirmation. |
| Ride duration | Configurable 1–600 seconds; default 90. Monotonic expiry continues through GPS gaps and ordinary pauses. No speed-based exit or extension. Manual reposition/Stop retain their existing reset behavior. |
| Expiry | Normal aiming resumes; fresh GPS and ordinary distance/control gates still apply before approach. No return from ride expiry. Clear speed history; only packets received after expiry may build a new detection. |
| Settings | Replace Ride end speed / Ride end confirmation with Ride duration. Existing end-setting preferences are ignored; the new duration defaults to 90 rather than migrating the old end dwell. |
| Touch lock | Automatically opens on Start request; also accessible through overflow > Lock screen controls. Transparent, full-window modal blocks underlying widgets and existing dialogs. Video/VT continue in foreground. Hold the top unlock button for 3 seconds; leaving the button, cancellation or a second finger cancels the hold. |
| Restart | Lock state is never persisted. Restarting the app restores touch, but does not restart VT. OS locking/backgrounding still stops VT. System power/navigation and physical RC controls are not blocked. |
| Gimbal touch | Disables touch pan/tilt and stops an ongoing touch rotation when locking; restores the previous touch setting on deliberate unlock. |
| Recording prerequisite | Fresh confirmed main-camera recording state is required by both Start readiness and the actual session start. Off: Start video recording first. Unknown/stale: Waiting for camera recording status. Recording loss after Start does not stop VT or produce a delayed warning. |
| Automatic pitch | Save fresh main-gimbal pitch once per session when control is active. Below 30 m, set the configured absolute close-range pitch. Above 35 m, restore the saved pitch. Between boundaries retain current mode. Fresh surfer GPS is required to switch; stale data holds the selected pitch. |
| Tilt setting | Close-range pitch -90 to 0 degrees, default -35 degrees. Independent of original pitch, including an original of 0. A target of 0 means horizon, not disabled. Read supported pitch limits from DJI; unknown/stale angle or range suppresses commands. |
| Parallel operation | Absolute pitch-only rotation, two-second duration, yaw/roll ignored. Transition-triggered, not a continuous pitch lock. It does not seize drone yaw or translation. |
| Logs | Full log version 3.3. Ride duration/start/expiry/remaining. gimbal_pitch_command logs origin/target/actual pitch, configured closeRangePitchDeg, distance, GPS age, trigger and attempt. gimbal_pitch_result logs acceptance/error. gimbal_pitch_cycle logs measured progress and targetReached. touch_lock logs lock/unlock. |

## Retained behavior

Zero qualification delay; existing band bookkeeping; saved approach and return navigation through stale surfer GPS; fresh GPS before new approach; configurable re-approach margin; 3 m/s cruise (10.8 km/h; slowdown begins at 15 m), 0.25 m/s² acceleration, preserved arrival gradient, 1 m arrival tolerance, 249 m inward stop/250 m hard excursion boundary, five-minute navigation deadline; manual/control-loss/RTH/landing stop and recovery rules; no automatic VT restart after RTH.

The no-ride timer still starts on entry to filming hold and is cancelled by detected ride. Expiry itself does not invent a new no-ride timer; the retained hold/approach lifecycle determines its next start.

## Scope and limits

No foreground/background service and no recording-stopped warning. Physical RC inputs remain independent. Touch lock is water-touch protection, not an OS lock: a sustained false touch on the unlock button could still unlock it. Automatic gimbal pitch only sends on distance-mode changes; manual RC wheel adjustment is not continuously overridden. An accepted DJI command is not proof of achieved physical angle; targetReached comes from fresh measured pitch. Absolute commands are bounded to two seconds; no new command is issued while paused/stopped or without control. Missing SDK callbacks are timed out without blind repeated submission; explicit failures have at most three attempts spaced at least three seconds apart. Main camera/gimbal LEFT_OR_MAIN is used.

## Device acceptance checks

1. Recording OFF/unknown blocks Start with correct explanation; recording ON plus existing ready conditions permits Start. Stop recording mid-run does not stop VT.
2. Lock blocks camera gestures, recording, settings, RTH/landing buttons and dialogs. Video continues; hold-to-unlock works; restart opens unlocked. RC RTH still stops VT, without auto-resume.
3. Ride expires at configured duration with no packets, at high speed, and after zero-speed packets. Confirm no unobserved extension, immediate old-evidence rearm or ride-end return.
4. At original 0 or -24 degrees and close-range setting -35 degrees, cross below 30 m: smoothly reach -35; jitter inside 30–35 holds; above 35 restores the saved original. Verify targetReached and command/error logs. Validate SDK range availability on the actual aircraft.
5. Compare retained saved-navigation behavior to 3.2. Bench-check camera/lock first, then controlled flight validation.

VT 3.3 also gates SlidingDialog.show via TouchControlLock so automatic landing/RTH slider prompts cannot open above the lock. Owner-scoped cleanup prevents an old Activity from clearing another screen’s lock.

## Local validation

- Full tools/test-aiming.ps1 suite passed, including 216 VT 3.3 assertions and retained navigation/safety suites.
- Offline Android :sample:assembleDebug passed. APK metadata confirms versionName 3.3 and versionCode 17.
- git diff --check passed. Aircraft/RC/touch behavior still requires device validation.


## Absolute pitch update (still VT 3.3)

The old percentage preference is never interpreted as degrees. Existing installations without closeRangePitchDeg use -35 degrees; select the desired angle in settings. UI accepts signed decimal degrees. Below 30 m the absolute target may tilt up or down from the original; above 35 m the saved original is restored. Logs use closeRangePitchDeg alongside originalPitchDeg, targetPitchDeg and actualPitchDeg. Movement remains 3 m/s.
