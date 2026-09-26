# VT 3.1 - offshore qualification and retained-target aiming

Version name 3.1, versionCode 15. Implemented in the existing vt-28 worktree on top of
its uncommitted VT 3.0 baseline. Original local project and vt - Copy are untouched.

## Changes and issues addressed

| Area | VT 3.0 | VT 3.1 | Issue addressed |
| --- | --- | --- | --- |
| Surfer aiming during GPS outage | Shared stale check pauses yaw | Active surfer yaw finishes toward its last validated target, with fresh aircraft telemetry; no prediction | Offshore packet gaps interrupt useful corrections |
| Saved approach/return during GPS outage | Pauses navigation yaw and translation | Finish saved travel with fresh aircraft telemetry | Packet gaps interrupt a known plan |
| Qualification | 60 s | 20 s timer; continues through surfer-GPS gaps | Paddling rarely qualifies before another reset |
| Default lineup width | 20 m total | 50 m total (+/-25 m), configurable | Frequent sideways exits |
| GPS qualification loss | Clear progress | Keep timer running; wait for fresh in-band fix before approach; outside-band fix resets | Outages erase accumulated observation |
| GPS-only recovery | 2 s dwell | First valid fix, with all remaining checks satisfied | Extra delay consumes useful reception windows |
| Ride qualification loss | Clear progress | Freeze credit while riding; band exits retain their reset behavior | Detection unnecessarily erases credit |
| Re-approach | No additional margin | Configurable 0-200 m margin, default 15 m, only after first filming hold | Small range changes interrupt framing |
| Diagnostics | Existing records | Qualification status/events, required duration, GPS movement pause, margin/threshold/hasFilmed and retained-target yaw | Explain each decision after a field test |

Saved settings are preserved. A previously saved 20 m width stays 20 m; change it to
50 m in VT 3.1 settings for the proposed field test. A new installation/absent width
uses 50 m. Margin defaults to 15 m and is saved with the other session settings.
Come to me toggle preserves the configured margin. Settings edits remain stopped-only.

## Rotation ownership and GPS boundaries

A fresh valid target is required to Start. AimingSession remembers the last target
validated in the current session. While active, including saved navigation, it may relax only
that target's age check. Future/invalid timestamps, invalid positions, aircraft telemetry,
manual events, takeover and command eligibility checks are not bypassed. The last target
coordinate is used with live aircraft position/heading; no extrapolation is added.
Normal 3-degree yaw tolerance, riding full-error yaw, 8 deg/s rate, 4 deg/s^2 acceleration
and the 2-second reversal block remain. Thus aligned means the existing yaw tolerance,
not pixel centering or a guarantee of zero geometric error.

APPROACHING/RETURNING own yaw. Both rotation and translation continue their saved plans
through surfer-GPS outages, using fresh aircraft telemetry. Stale fixes do not update
ride detection or create new approaches. Other and mixed pause causes retain
2 seconds of healthy recovery and control revalidation as before. A GPS update cannot
bypass a latched manual or telemetry pause. Movement timeout includes paused time.

## Qualification, rides and re-approach

The qualification timer advances each control tick, capped at 20 seconds, including
surfer-GPS gaps. Completion during an outage waits for a fresh in-band position before
saving a new approach. Fresh out-of-band positions reset the timer. Rides freeze credit.
Manual reposition and other pauses retain their old qualification reset behavior.
Band orientation stays fixed until exit; exits recreate the band and clear credit,
including during rides. A ride event alone freezes rather than clears progress.
Confirmed ride end still invokes the existing return, including its new-band creation;
that new band starts fresh qualification. A fast-only ride end can resume saved credit.

First approach keeps the existing filming-distance tolerance of 2 m. On the first
filming hold, hasFilmed latches. Subsequent eligibility requires distance strictly
greater than filmingDistance + reapproachMargin and completed qualification. A holding
tripod can re-approach when that threshold is crossed, without needing a new band exit.
The movement plan still subtracts filmingDistance, never the margin. For 70 m + 15 m,
re-approach starts beyond 85 m and plans to end 70 m from the captured target.
Manual reposition/new explicit Start clears the latch; automatic return does not.
No-ride deadlines are not restarted by margin holds or re-approaches.

## Retained behavior

Fixed approach/return heading, projected travel completion, initial 3-degree alignment,
1 m/s movement, acceleration, five-minute attempt deadline, control acquisition/release,
fast/confirmed ride separation, 40 km/h jump rejection and ride-end evidence remain.
No Nearby logic is restored. Existing manual and telemetry gates remain.

## Files and call hierarchy

- ComeToMeSettings: 20 s constant, default width, validated immutable margin.
- ComeToMeController: cumulative qualification, pauseForGps, ride freeze, hasFilmed and
  approachStartThreshold; update_state_machine retains fixed navigation.
- AimingSession: Inputs.validate overload and controlProblem allow retained yaw and existing saved navigation;
  tick skips surfer evidence processing on stale fixes; pauseAiming distinguishes mixed causes.
- YawAimingController: stopped-only setting persistence, retained-target screen status.
- DefaultLayoutActivity: VT 3.1 settings and margin input; toggle preserves all settings.
- AimingCycleLog / MovementCycleLog / FullSessionLog: version and decision evidence.

YawAimingController.tick -> AimingSession.tick -> control/input validation ->
ComeToMeController.update_state_machine (fresh) or pauseForGps (retained yaw) ->
choose exactly one yaw owner -> final input/authority checks -> movement.permits ->
YawAimingController.sendMotion -> DJI SDK combined command.

## Validation and field follow-up

Unmodified VT 3.0 baseline suite passed before edits. Updated regression suite passes
137 session/math assertions, 130 retained movement assertions, 80 VT 3.0 assertions,
45 VT 3.1 assertions, plus packet parsing, command bytecode, async logs and independent
JSON parsing. Existing tests were updated for immediate GPS recovery, the 250 m excursion boundary,
and the wall-clock qualification timer; the other regression expectations remain. New tests cover qualification timing through gaps, band
exit on recovery, ride freezing, first versus subsequent approach, custom margin and
no-ride timer continuity, retained-target yaw/zero-at-alignment, manual/telemetry/cancel
vetoes, saved navigation through stale GPS and mixed-cause recovery.
Android offline :sample:assembleDebug build passed. APK metadata verified: versionName 3.1, versionCode 15. No aircraft test or deployment.

Field check: 50 m lineup selected, GPS gap while waiting versus moving, recovery in/out
of band, ride interruption, 70/85 m margin boundary, manual takeover and actual framing.
GPS position/compass/camera framing discrepancy remains unresolved by this release.
RTH release-confirmation enhancement remains deferred; no silent change to that policy.


### VT 3.1 orientation logging addition

Full Log now includes `orientation_cycle`, joined by session/cycleId to aiming and movement.
It records raw SDK aircraft and main-gimbal pitch/roll/yaw (degrees), plus the explicit
gimbal yaw relative to aircraft heading. Raw gimbal yaw is not relabelled as camera
compass heading. Each source has availability, freshness, read error, request-start
elapsedRealtime timestamp and age; missing/non-finite angles are null, never zero.
Optional reads use the existing 500 ms polling cadence; snapshots are written each
control cycle. Repeated snapshots are not new sensor samples. Unsupported keys do not
gate aiming, movement, startup or recovery. No control behavior changed.


VT 3.1: Approach excursion limit increased from 200 m to 250 m from the saved central point.


### GPS outage completion update

- Qualification continues through missing packets; fresh GPS (age <=3 s) is required
  to start a new approach, and a returned outside-band position resets qualification.
- Existing approach AND return retain rotation and translation through old surfer GPS.
  Fresh aircraft telemetry, manual/control vetoes, deadlines and limits still apply.
- Logs expose `waitingForFreshApproachFix`, `savedNavigationWithOldGps`, timer status,
  and `maxExcursionM` (250 m). Old target coordinates are not new ride evidence.
