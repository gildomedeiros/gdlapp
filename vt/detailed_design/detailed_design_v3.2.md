# VT 3.2 - moving-target approaches and prompt movement completion

Implemented in the existing vt-28 worktree on top of VT 3.1. Version name 3.2, versionCode 16.

| Area | VT 3.1 | VT 3.2 | Issue addressed |
| --- | --- | --- | --- |
| Qualification | 20 seconds | **0 seconds** | No settling wait at Start, band exit or qualification reset |
| Confirmed ride end | Return to central | No ride-end return or band recreation | Resume distance-based approach/holding |
| Approach/re-approach | Complete all saved travel | Complete with <=1 m remaining | Release yaw to surfer aiming without the final crawl |
| Return | Complete all saved return travel | Complete with <=1 m remaining | Avoid the same slow finish |
| Excursion | Stop at 250 m from central | Stop outward movement at 249 m | Avoid creeping toward the hard boundary |
| Logs | Existing movement records | Tolerance, signed residual travel, excursion stop and completion reasons | Explain early arrival and ownership handoff |

## Exact behaviour

The planner keeps the saved origin, heading and planned distance. Net displacement projected
along that heading measures progress. Arrival requires planned distance minus progress <=1 m.
Approach enters HOLDING, submits zero forward movement and releases yaw to surfer aiming in
the same cycle. Return enters WAITING and releases navigation ownership. Existing conditions
can subsequently start another approach. The return already plans to stop 3 m short of
central; this tolerance can finish approximately 4 m from central on a straight return.
It is NOT a 1 m radius around central. Lateral drift is still excluded from progress.

Outward approach movement stops at a GPS-derived central-to-aircraft distance of 249 m.
The hard maximum remains 250 m. This does not constrain central-to-surfer distance or
prevent backward return movement. The existing excursion-limit hold remains latched.
The final submission checks use the same arrival/excursion thresholds as the planner.
These are command thresholds, not a guarantee of centimetre-level physical stopping.

Qualification is now 0 seconds. Band settings, centres and exit bookkeeping remain,
but a reset adds no dwell. A fresh (<=3 s) surfer position, valid aircraft telemetry,
control ownership, eligible distance and no active ride are still required to start
an approach. Alignment is required before translation. Manual recovery is unchanged.
Saved navigation through surfer-GPS gaps and orientation logging remain unchanged.

Fast ride detection still interrupts approach and enables ride aiming. Five-second
confirmation still cancels the no-ride countdown. When the ride ends, the planner can
hold or start an eligible approach immediately; ride end does not request return or
recreate the band. An independently observed band exit can still recreate it.
The no-ride timeout remains the return trigger. Its existing lifecycle is retained:
confirmation cancels it; the next enterFilmingHold transition starts it again. Merely
ending a ride does not restart that timer. Existing within-margin holding with hasFilmed
already set does not independently restart it. No new timer policy is introduced here.
Rotation deadbands/reversal blocking, acceleration, saved destinations, re-approach
margin, manual/control checks and five-minute attempt deadline are retained.

## Movement speed (updated within VT 3.2)

Approach/re-approach and backward return now cruise at up to 2 m/s (7.2 km/h).
Acceleration stays 0.25 m/s2: eight seconds from rest to full speed when travel allows.
The arrival slope stays 0.2 m/s per metre remaining, independent of cruise speed.
Slowdown begins at 10 m: 10 m -> 2 m/s; 8 m -> 1.6; 5 m -> 1; 2 m -> 0.4;
just over 1 m -> about 0.2. Complete at <=1 m and command zero. Short travel may
never reach cruise speed. The shared profile also applies near the excursion boundary.
Aircraft translation validation retains its MAX_SPEED + 0.4 margin (now 2.4 m/s);
stationary eligibility (0.5 m/s) and vertical checks are unchanged. No gimbal changes.
Version remains 3.2 / code 16. Commanded speed is not a measurement of actual speed.

## Troubleshooting

movement_cycle records maxMovementSpeedMps=2, movementAccelerationMps2=0.25,
movementSlowdownDistanceM=10 and arrivalSpeedPerMetre=0.2.
It also adds completionToleranceM=1, excursionStopDistanceM=249,
approachRemainingM, returnRemainingM and excursionRemainingM. Residuals are signed;
negative values identify an overshoot, and unavailable plans serialize as null.
reason=approach_arrival_tolerance or return_arrival_tolerance distinguishes completion
short of the endpoint. Existing exact/overshoot completion reasons remain compatible.
qualificationRequiredMs=0, qualificationWaitEnabled=false and rideEndTriggersReturn=false. Full-log version and in-app settings labels identify 3.2.

## Validation

Prior validation, before the zero-wait/ride-end update: full tools/test-aiming.ps1 suite passed, including 137 session, 170 movement, 80 VT 3.0, 45 VT 3.1 and 212 VT 3.2 assertions. Both directions reach 2 m/s in eight seconds with unchanged arrival speeds and one-metre completion. Production log speed-profile fields passed independent parsing. Offline :sample:assembleDebug succeeded (3.2 / code 16). Device testing pending.

## Source changes and call hierarchy

- ComeToMeSettings.java: QUALIFY_MS=0, COMPLETION_TOLERANCE=1, EXCURSION_STOP=249.
- ComeToMeController.java: update_state_machine() finishes approach/return within tolerance;
  permits() independently blocks commands crossing those boundaries; details() uses the
  configured qualification constant. rampedSpeed() retains the existing speed profile.
- MovementCycleLog.java: record() serializes thresholds and signed residuals.
- FullSessionLog.java: session header version 3.2.
- DefaultLayoutActivity.java: settings menu and dialog identify 3.2.
- android-sdk-v5-sample/build.gradle: versionCode 16/versionName 3.2.
- Vt32Test.java: real planner/session scenarios at arrival boundaries, fresh-GPS start,
  late-telemetry command vetoes, excursion latch and same-cycle yaw handoff.
- ComeToMeTest.java / Vt31Test.java: retain earlier scenarios with updated boundary fixtures.
- tools/test-aiming.ps1: executes all retained tests and independently parses new log fields.

Call hierarchy (file-labelled): AimingSession.java tick -> ComeToMeController.java
update_state_machine -> enterFilmingHold/finishReturn or rampedSpeed -> AimingSession.java
selects navigation/surfer yaw -> ComeToMeController.java permits before command submission.
MovementCycleLog.java record captures resulting phase/reason/progress; FullSessionLog.java
serializes the JSONL. Existing safety failures still zero movement/pause/cancel as in 3.1.

## Latest 3.2 policy validation

Verified 2026-09-27: full regression suite passed (137 session, 167 movement, 80 VT 3.0, 40 retained VT 3.1, 212 navigation/speed and 176 zero-wait/ride-policy assertions), including independent JSONL policy flags. Debug APK build passed; version remains 3.2 / code 16. Device validation of the latest policy is pending.
