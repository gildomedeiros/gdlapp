# VT 4.0.9 — surfer aiming offset

Version code 32. Adds an optional aiming-only framing offset to `vt_settings.json`.

## Configuration

`yawTargetOffsetDegrees` is a finite non-negative magnitude in degrees (0–180), default 0. Existing JSON without this key retains centred aiming. Configuration is loaded and validated at Start with the existing session settings. Existing external configuration files are not rewritten.

For a magnitude of 5:

| positioningAngleDegrees | appliedYawOffset | Aim relative to surfer bearing | Intended surfer framing |
|---|---|---|---|
| Negative (e.g. -45) | +5 | 5 degrees right | Left of centre |
| Positive (e.g. +45) | -5 | 5 degrees left | Right of centre |
| Zero | 0 | At surfer | Centred |

Example addition to the existing settings object: `"yawTargetOffsetDegrees": 5`.

The target is the GPS surfer bearing plus the signed offset, normalized to 0–360 degrees. The existing rotation-speed curve receives the error to that target. Normal mode retains its 3-degree stop tolerance (a +5-degree offset can settle 2–8 degrees right of the surfer bearing). Riding retains its own curve and stopping behaviour. This is a heading bias, not a guaranteed screen coordinate.

## Scope

Only surfer-tracking yaw uses the offset, including surfer tracking during preset positioning and retreat. Navigation alignment keeps its saved heading. Speed curves, acceleration, reversal blocking, translation, retreat, gimbal bands, the existing 2-second gimbal command cooldown, authority/input gates and Stop handling are preserved. The default bundled offset is zero.

## Logs

Every `aiming_cycle` includes `appliedYawOffset` (signed degrees) and `targetAimingHeadingDeg` (normalized adjusted surfer target). Existing `subjectBearingDeg` and `relativeBearingDeg` retain their raw surfer meanings. `remainingAngleDeg` is the error used by the controller after applying the offset.

Cycles without a surfer aiming calculation, including navigation, paused cycles and coincident positions, log offset 0 and target null. Calculated values may remain on a cycle subsequently vetoed before submission; use the existing `submitted` flag to distinguish calculation from command delivery.

## Verification

Regression coverage includes legacy/zero configuration, both signs, zero positioning angle, invalid configuration, complete session loading, wraparound, normal tolerance, riding/normal JSON curves, acceleration, reversal blocking, fresh per-cycle logs, pause/Stop, navigation alignment and retreat. See the build verification record for executed checks and APK identity.
