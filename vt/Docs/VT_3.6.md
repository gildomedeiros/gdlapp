# VT 3.6 â€” quicker approach starts and updated retreat defaults

Version 3.6, Android versionCode 20. Implemented in the vt-28 working tree only.
Separate local VT copy remains unchanged. Prior VT 3.5 APK preserved at `build/releases/VT-3.5.apk`.

## Changes

| Item | VT 3.6 behaviour |
|---|---|
| Approach margin | One configurable margin, default 5 m, used for first and all subsequent approaches. No separate 2 m rule. |
| Approach and Return to central speed | Immediately command the distance-limited speed; remove the 0.25 m/sÂ² software acceleration ramp. |
| Approach/return maximum | Configurable 0.1–5 m/s, default 4 m/s (14.4 km/h); separate from retreat speed. |
| Arrival slowdown | Unchanged: 0.2 Ã— metres remaining to saved destination, capped at the configured maximum. With default 4 m/s, slowdown starts at 20 m remaining; at 15/10/5/2 m, command 3/2/1/0.4 m/s. |
| Arrival completion | Unchanged: zero translation with 1 m or less remaining. |
| Retreat distance default | 23 m, still configurable. Strictly below triggers; equality does not. |
| Retreat duration default | 3 seconds, still configurable. |
| Retreat speed maximum/default | 5 m/s = 18 km/h; configurable 0.1â€“5 m/s, independent of approach/return profile. |
| Come to me cooldown | Unchanged: default 5 seconds after last retreat, configurable. |

The aircraft still physically accelerates and brakes. The removed ramp was a software
command limiter, not a guarantee of instantaneous physical velocity. Saved destinations,
heading alignment, ride gates, one-metre completion and excursion protection remain.

The approach/return maximum, four retreat settings and common approach margin are editable while stopped. The new maximum is saved as maxMovementSpeed and defaults to 4 m/s when absent; toggling Come to me preserves it.
Existing saved user values are preserved. New defaults apply to missing preferences or
the existing invalid-configuration fallback; installing 3.6 does not overwrite saved
23/25 m, 3/10 second or 3/5 m/s choices. Review settings on upgrade to use the new defaults.
For filming distance 30 m and margin 5 m, every approach starts only above 35 m, still
targets filming distance 30 m, and default retreat triggers below actual separation 23 m.

## Higher retreat speed and telemetry

The command factory permits up to the hard 5 m/s ceiling. The saved-navigation
submission gate rejects approach/return commands above the configured maximum in either direction.
Only an actually submitted retreat command grants a larger aircraft horizontal-velocity
allowance: max(configured movement maximum, submitted retreat speed) + the existing 0.4 m/s tolerance. The allowance
lasts for the existing two-second recent-translation interval to accommodate braking.
Normal translation uses the configured maximum + 0.4 m/s (default 4.4 m/s); start/pause/recovery remains 0.5 m/s. Vertical, attitude,
freshness, pilot stick, flight mode, ownership and cancellation gates are unchanged.

## Retreat behaviour retained from VT 3.5

Below the configured minimum, retreat replaces the saved approach; a later approach must
plan anew. Fixed body-backward velocity runs for the full configured period while normal
surfer aiming and automatic JSON gimbal pitch continue. At expiry, repeat immediately if
still too close; otherwise stop and apply Come to me cooldown. No gap between repeated periods.
Retreat can trigger during cooldown and during rides. Retained session GPS, including NOFIX,
remains usable for retreat with fresh drone telemetry. Existing saved returns retain ownership.
Protection pauses cancel timers; recovery follows existing requirements and starts a new
full timer when eligible. No change to recording prerequisite, touch lock, gimbal config,
ride detection/timer, saved-target approach, RTH/control release or other protective behaviour.

## Logging and version display

Settings menu and dialog display VT 3.6; APK metadata is 3.6/code 20; full-log header is 3.6.
All retreat transition and cycle events remain. Retreat records include speedMps, speedKmh
and maximumSpeedMps. Movement records now identify distance_limited_immediate,
movementAccelerationRampEnabled=false, movementAccelerationMps2=null and
firstApproachUsesSameMargin=true. They record maxMovementSpeedMps, maxMovementSpeedKmh and the derived movementSlowdownDistanceM. Existing settings, command, position, orientation and
gimbal evidence remain available; distinguish requested motion from actual motion.

## Validation

Run `tools/test-aiming.ps1` and offline `:sample:assembleDebug` from
`SampleCode-V5/android-sdk-v5-as`. Existing tests are retained with expectations updated
only for the intentionally changed speed/margin/version/defaults. VT 3.5 retreat scenarios
also exercise explicitly configured old values. VT 3.6 adds first/later/manual/reset margin
checks, immediate speed and retained braking in both directions, 5 m/s retreat cancellation
and repetition, scoped telemetry allowance, independent JSON parsing and release metadata.
Flight testing remains required for acceleration, framing, braking and overshoot.
