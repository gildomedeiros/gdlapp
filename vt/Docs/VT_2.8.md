# VT 2.8 — Come to me

## Baseline and scope

Built in an isolated worktree from local VT 2.7 (repository commit 1ce7e9b).
The existing 20 m nearby direction-lock setting and independent 60–30 m yaw
multiplier are retained. No local project source was modified. Nearby remains
available as its own setting; it defaults ON in this build.

This version adds forward/backward navigation alongside existing yaw control.
It never commands lateral or vertical velocity. Start aiming remains explicit.
Gimbal pitch remains manual; aircraft yaw alignment is not measured camera alignment.

## User settings

Open the existing overflow menu, then **VT 2.8 settings** while aiming is stopped.

| Setting | Default | Accepted range |
| --- | --- | --- |
| Come to me | ON | ON/OFF in overflow |
| Filming distance | 70 m | 5–200 m |
| Lineup total width | 20 m | 20–200 m |
| Ride start | 18 km/h | 1–100 km/h |
| Ride end | 8 km/h | Greater than zero and below ride start |
| Ride-end confirmation | 30 s | 1–300 s |
| No-ride return | 15 min | 1–120 min |

Come-to-me settings are stored locally and survive app restart. They cannot be
edited during an active/paused aiming session. Full Log defaults ON each app
process and can be switched OFF. On Android 7–9, storage permission is required.
A storage failure is displayed and does not acquire or maintain flight control.
Existing log directory/naming remains Downloads/CAM3 and cam3_full_* for compatibility;
the session header identifies version 2.8.

Other agreed constants:
- Lineup qualification: 60 s.
- Forward/backward speed: up to 0.5 m/s; acceleration 0.25 m/s².
- Slowdown over the last 5 m; final crawl 0.05 m/s to reach the arrival boundary.
- Filming stop: configured distance + 2 m.
- Central arrival: within 3 m, completion latched.
- Heading tolerance: shared 3° constant, independent decisions for yaw and movement.
- Maximum GPS-measured excursion from central: 200 m.
- Each approach/return attempt: maximum 300 s, including pauses/alignment waits.

## Explicit forward-movement permission

The drone moves forward only when ALL of these are true:
1. Start was explicitly requested and existing authority/input checks have succeeded.
2. Come to me is enabled, central is captured, and no movement timeout is latched.
3. Fresh readings qualified the fixed lineup band for 60 s.
4. No ride is active and no return is in progress.
5. Drone–surfer distance exceeds configured filming distance + 2 m.
6. Shortest aircraft-heading error to surfer is within 3°.
7. Drone is inside the 200 m excursion boundary.
8. The attempt is under 300 s and the final pre-send checks still permit movement.

Yaw zero is NOT used as an alignment signal. Both controllers use the same
numeric tolerance but calculate their decisions independently. New GPS arriving
after planning must be processed before another nonzero translation is submitted.

## State flow

- **WAITING**: hold current position; yaw tracks surfer; qualify lineup.
- **APPROACHING**: forward movement only while aligned; yaw continues tracking.
- **HOLDING**: filming distance or excursion boundary reached; zero translation.
- **RETURNING**: align tail toward saved central, then move backward; return yaw
  replaces surfer yaw and ignores the nearby direction lock.
- **STOPPED**: movement timeout latched; zero translation, surfer yaw continues
  if normal input/control gates permit. New Start or manual reposition clears it.
- **OFF**: movement disabled or session stopped.

Central is captured at successful activation/valid hover. Automatic movement
never changes central. Manual stick intervention clears the old plan; after
existing neutral-stick/steady-hover recovery, the current position becomes central.

The lineup centre is the surfer fix at band creation. Its orientation is fixed
perpendicular to drone-to-surfer bearing at that moment; actual yaw changes do
not rotate it. Only sideways displacement is bounded. Toward/away paddling can
qualify; the band does not prove the surfer is stationary.

Band exit stops an approach, centres a new band and restarts 60 s qualification.
It does NOT initiate a return. An existing return continues to the same central.
After qualification a new approach can begin from the current aircraft position.

Holding does not continuously maintain distance. A closer surfer causes no retreat.
Once central arrival is accepted, later GPS drift outside 3 m does not restart return.

## Ride and inactivity detection

On each distinct fresh fix, estimate net displacement over approximately 5 s
(5–7 s supported with missing packets). LoRa sender time is used for speed;
local received time is used for freshness. Repeated coordinates remain in the
window so the zero/jump packet pattern does not double the speed estimate.

An estimate >= configured ride-start speed marks a ride and cancels approach.
After a known ride, estimates below end speed for the configured duration cause
return. An estimate >= end speed resets the confirmation timer. Missing/stale GPS,
unavailable speed estimates or sender-clock reset break slowdown confirmation.
Missing GPS does not end a known ride.

Without a ride, the inactivity timer starts with activation/capture. A detected
ride resets it and inhibits its trigger while riding. Confirmed ride end restarts
it. At expiry, cancel approach/hold and begin return. Already-central and already-
returning cases do not create duplicate returns.

Return starts with neutral translation. The required nose heading is bearing from
aircraft to central + 180°. Nonzero backward velocity requires alignment. Return
finishes within 3 m or the 300 s timeout, whichever comes first.

The detector is approximate: GPS jumps can still mark a false ride. Five-second
averaging and GPS freshness checks do not prove actual surfing. The first confirmed
DJI_0370 ride peaked below 18 km/h under one estimator and may be missed.

## Control integration

AimingSession remains the owner of authority, cancellation, acquisition, recovery
and release. The planner has no SDK access. A single combined command sender uses
the existing final authority/freshness checks, then independently revalidates
translation. Every existing pause/cleanup neutral explicitly sends zero translation.

The original hover velocity threshold remains for Start/recovery and yaw-only
operation. A recent submitted translation allows up to 0.8 m/s measured horizontal
speed (command maximum 0.5 m/s plus tracking tolerance), for at most 2 s after the
last nonzero translation. Vertical, stick, mode, GPS and freshness gates remain.
This avoids the old 0.5 m/s hover check cancelling the intended approach.

Command mapping: DJI BODY velocity X maps to roll, Y maps to pitch; positive X is
forward. The combined factory sets roll to signed forward speed and retains zero
pitch/lateral and vertical velocity. References:
- https://developer.dji.com/api-reference/android-api/Components/FlightController/DJIFlightController.html
  (RollPitchControlMode.VELOCITY X/roll and Y/pitch mapping)
- https://developer.dji.com/doc/payload-sdk-tutorial/en/function-set/basic-function/flight-control.html
  (BODY X toward nose, Y to aircraft right)
- Local Docs/Android_API/en/Components/IVirtualStickManager/Value_FlightController_Struct_VirtualStickFlightControlParam.html

Aircraft-specific axis/sign response and braking behaviour still require device
verification. Compilation and JVM tests cannot verify physical movement.

## Display and logging

The existing footer has two separate statuses: **Aiming** and **Come to me**.
Movement text includes stage/reason, distances and timers; narrow screens may
truncate it. Overflow > Details exposes the complete movement snapshot.

Full JSONL retains aiming_cycle and yaw_command, adding:
- movement_cycle joined by session/cycleId;
- movement settings, phase, event, reason and return reason;
- central coordinates/generation, band centre/orientation, lateral offset;
- qualification, speed, ride-end, inactivity and attempt timers;
- target/central distances, independent alignment error and yaw purpose;
- requested and actual submitted forward speed (null when no submission);
- movement_transition events and pause/stop reasons.

The new sample-time field travels atomically with each LoRa fix. Existing readers
using the prior constructors and existing log fields remain compatible.

## Verification

Run tools/test-aiming.ps1 with JDK 21. It covers existing authority/race/recovery,
LoRa, logger, nearby-lock and per-cycle regressions, plus movement/ride/band tests.
The older goalkeeper fixtures were corrected from 60 m to the user's existing 20 m
production lock range without changing that production range.

Android build:
From SampleCode-V5/android-sdk-v5-as with JAVA_HOME pointing to JDK 21:
gradlew.bat --offline :sample:assembleDebug

No app was installed and no aircraft was connected or commanded during development.
