# VT 4.0.8 — boundary recovery

Version code 31. Changes apply to Diagonal Come-to-me and automatic VT Return to central.

## Diagonal Come-to-me

Remove the immediate beachward-start rejection. Plan candidate routes from the current drone position. Every waypoint, including final P3, must be on the permitted sea-side of the original boundary. A straight first leg from a beachward origin to a permitted waypoint monotonically improves signed boundary distance. All later legs remain on the permitted side. No arbitrary maximum beachward recovery depth is added; excursion limits still apply.

Retain the quadrant rule for straight routes and clockwise/anticlockwise around search. Preserve captured S0, P3, fix timestamp and attempt deadline during replanning. Ordinary planned legs must clear S0 by retreat threshold plus routePlanningAllowanceMetres. Execution checks use the retreat threshold alone. Existing outward escape permits a first leg inside the S0 circle only if distance from S0 increases throughout; its endpoint and continuation must pass boundary and excursion checks. The same recovery entry point is used for initial calculation and active replanning. An invalid destination is never repaired by moving P3.

Check actual-position-to-waypoint geometry repeatedly and again before command submission. Use current heading to validate actual BODY forward/right command direction: from beachward it must have a positive sea-side component; at the boundary it must not point beachward. Reject stale command vectors after heading/position changes. Keep existing telemetry, authority, timeout, retreat and cooldown gates.

Arrival within 1 m must also have a permitted actual boundary position and a passing remaining-leg check. Otherwise allow the short recovery movement rather than finishing early or vetoing it merely because its remaining distance is below 1 m.

## Automatic Return to central

Keep direct travel and the configured stand-off, default 10 m. For an ordinary sea-side start retain the original start-to-original-central intersection with the stand-off line. A start already between boundary and stand-off completes the reset without travel, as before.

For a beachward start, save the nearest sea-side-normal projection from the actual drone position onto the same stand-off line. This avoids division by zero or extreme alongshore extrapolation near the boundary. Freeze this target during travel. A beachward report during an active return does not change the saved target: allow direct travel toward it if boundary, excursion, telemetry, authority and command checks pass. Do not add a captured-S0 clearance circle or intermediate waypoint/around planner to return. Existing live-surfer retreat remains the protection and takes priority. Retreat interruption/cooldown and pending return keep their original deadline policy.

Removing the start rejection allows a due return to take over rather than repeatedly preventing boundary recovery. No separate recovery destination is inserted before a return. Target excursion failure continues to hold with a reason.

## Unchanged

Front and Sideways Come-to-me, aiming, retreat configuration and direction policy, manual intervention, Stop, aircraft RTH, planning allowance, filming angle/distance settings, excursion settings and fixed original boundary anchor. Soft-return recovery applies to all positioning modes. No new configuration keys or boundary reserve.

## Diagnostics

Retain route failure reason, failed segment, minimum S0 clearance, boundary and excursion metrics, both direction failures and recovery history. Add currentBoundaryDistanceM and boundaryRecoveryFlow (diagonal_come_to_me, return_to_central or none) to movement-cycle logs.

## Logged regression

Supplied 06:52:04 flight, journey 2: D1 is 0.2835 m beachward, current surfer 50.49 m away, captured S0 48.40 m away. Remaining D1→P3 clearance is 30 m, P3 is about 0.106 m sea-side. Its one-leg route passes 21 m planning clearance and excursion checks. The original 4.0.7 planner rejected the start before waypoint search. 4.0.8 permits the remaining leg; no escape or extra waypoint is needed. Embed all 27 distinct failed recovery positions from this flight into regression fixtures.

## Validation scope

Run production Java planner/controller/session tests, independent JSON log checks and offline Android assembly. New cases cover signed-boundary rules, actual logged positions, crossing-S0 rejection, excursion conflicts, combined circle/boundary escape, initial journey from beachward, sub-metre arrival, late heading veto, return starts and deviations in Front/Sideways/Diagonal, pending return with retreat priority and Stop. Existing regression suites retain GPS, authority, deadlines, clockwise/anticlockwise routing, angle limits, OFF-retreat and pilot/RTH checks. Geometry replay verifies recalculated commands/routes, not recorded physical flight or an aircraft simulator.

## Release verification

Full desktop regression suite and independent JSON checks passed. New tests include 27 logged recovery geometries, controller/session submission and return/retreat interactions. Offline Android build succeeded. Embedded APK version 4.0.8, code 31 and bundled defaults verified. APK SHA-256: `95FC31F84C08963B920384320BE66EC1224B9EBAD89EBAE880B957E0EC118F35`. Aircraft flight test not performed.
