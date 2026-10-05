# VT 4.0.4 — configurable-angle positioning detailed design

Implementation authorized on 5 October 2026. Consolidated design; final defaults and validation are recorded below.

## Scope and evidence

Add an angle-based positioning mode, using existing Front as the control-behavior reference. Preserve existing Front and Sideways unless a replacement is explicitly agreed. This document consolidates the current chat, its annotations, the side chat “Retreat Behavior Clarification”, the VT 4.0.3 handoff and source review. Side-chat assistant explanations are supporting context, not additional user approvals.

Authoritative source reviewed: C:/Users/gildo/.codex/worktrees/vt/gdlapp/vt. Source declares 4.0.3/code 26; build/releases/VT-4.0.3.apk exists. Existing uncommitted work is preserved. This does not prove every current source byte matches the released APK.

Reviewed release notes 4.0–4.0.3 and production geometry, configuration, movement/session, retreat, ride, logging and Vt40Test code. Tests were read, not rerun for this design. No flight verification performed. The 4.0 design's backup/rollback text is superseded by 4.0.2 direct writes with read-back verification; retreat-only boundary behavior was added in 4.0.3.
## Detailed review of modes 1 and 2

Let S be the configured seaward unit vector in local north/east coordinates. W = -S is shoreward. L = (S_east, -S_north) is left while looking seaward; R = -L. These left/right names do not depend on A/B ordering when the configured sea side is reversed consistently. Let d = drone position minus surfer position.

Each preset uses an orthonormal basis: filming axis U and perpendicular alignment axis V = (-U_east, U_north). Signed separation p = d dot U; signed alignment error q = d dot V. The sign of q depends on the selected basis; its magnitude drives alignment. Direct horizontal separation is a separate GPS distance, approximately sqrt(p² + q²) in the local model.

| Property | Mode 1: Front | Mode 2: Sideways |
|---|---|---|
| Filming axis U | W | L or R |
| Positive p | Drone shoreward of surfer | Drone on configured alongshore side |
| Alignment | Alongshore error | Shore-normal error |
| Filming endpoint | Surfer fix + F W | Surfer fix + F L/R |
| Alignment-only endpoint | Surfer fix + p W | Surfer fix + p L/R |
| Existing side guard | p must be positive when planning | p must be positive when planning |

Here F is filmingSeparationMetres. Front does not require a particular left/right side. Sideways does not require the aircraft to be shoreward of the surfer. Sideways alignment puts the endpoint at the same shore-normal coordinate as the planning surfer fix; this is the reason it does not create a diagonal view.

### Shared planning and movement sequence

1. Explicit Start validates all five JSON files before publishing immutable session settings and obtaining control. A selected valid shoreline is required when movement is enabled. Editing files during a session applies at the next Start.
2. First steady-hover capture sets the drone central point. This is distinct from the surfer/band origin. The first central capture also anchors the immutable retreat boundary for that explicit session.
3. Fresh valid target fixes update ride evidence and live separation measurements. QUALIFY_MS is currently zero. Preset planning has no active qualification wait; band counters are still maintained but do not impose a shoreline-positioning gate.
4. In WAITING/HOLDING, p <= 0 holds with wrong_filming_side_hold. If p > F + reapproachMarginMetres (with the existing 0.000001 m rounding tolerance), plan an endpoint at F and correct alignment simultaneously. Otherwise, if abs(q) > alignmentToleranceMetres, plan alignment only, preserving measured p. If neither condition holds, hold.
5. The endpoint and its planning-fix timestamp are captured once. The initial straight segment must remain outside the retreat radius around the planning surfer fix. The start and endpoint must satisfy the 249 m excursion constraints around the current return central. A rejected path does not create a detour.
6. Planning produces a neutral transition. During subsequent travel, live aircraft position determines the vector to the fixed endpoint; live heading converts that vector to BODY forward/right velocity. Yaw continues aiming at surfer GPS, rather than facing the travel direction. No yaw-alignment prerequisite is imposed on preset travel.
7. Combined translation speed is bounded by maxMovementSpeedMetresPerSecond. Arrival speed uses 0.2 times remaining metres, with the existing 0.05 m/s floor before completion. There is no software acceleration ramp. Arrival within 1 m neutralizes both axes. A 300 s attempt timeout latches STOPPED.
8. A moved surfer does not shift the saved endpoint. A new journey may be planned after arrival. A positive separation below F does not itself command outward movement to restore F. Alignment-only can reduce direct separation while preserving p, so endpoint and segment clearance matter.
9. A fresh ride detection clears approach, suppresses ordinary approach while the fixed timer runs, and cancels the no-ride countdown. Ride expiry only releases the ride gate; it is not evidence of physical wave end and does not itself trigger return.
10. Retreat can replace approach, neutralizes lateral translation and owns timed BODY-backward movement while yaw still aims at the surfer. Cooldown blocks approach; a fresh fix subsequently permits a new plan. A running return retains navigation ownership and is not interrupted by ride or retreat.
11. Saved navigation may continue with old but available surfer GPS under existing validation. No new endpoint is planned from stale data. Explicit NOFIX pauses outside the bounded retreat/retained-target allowance and preserves the endpoint for recovery. Manual reposition clears navigation and may recapture return central; Stop/control loss cancels navigation.
12. Final submission checks control authority, timing and latest inputs. A newly received target fix must have passed the state update. Recomputed BODY components must match the requested vector within the existing 0.01 m/s tolerance; changed aircraft position/heading can neutralize translation. The Android adapter repeats relevant checks before submission.

### Retreat boundary and known limits

Retreat triggers on direct horizontal distance strictly below minimumDistanceMetres, not p, q or 3D distance. Its period repeats if still close at expiry, subject to stale-GPS allowance. Crossing the distance threshold during a period does not itself end that period early. A new close encounter can end cooldown and start retreat again.

The 4.0.3 boundary is parallel to the shoreline through the FIRST drone central capture. Shoreward backward commands are limited using a 1 s horizon and 0.5 m reserve; parallel/seaward retreat remains allowed. Manual reposition does not shift this boundary. The final retreat check recomputes permitted speed from latest aircraft position/heading. Boundary blockage does not stop the period clock; separation may remain below the retreat threshold and blocked periods can repeat.

This boundary applies only to retreat. It is not a shoreward fence for Come-to-me, alignment or return. The selected A/B line supplies orientation, not a verified coast polygon or guarantee that the aircraft remains over water. shorelineOrientationVerified remains false.

Path clearance is checked at planning against the planning surfer fix. Live direct-distance retreat and command vetoes provide separate protections, but the source does not continuously revalidate the entire remaining path against a moving surfer. The filming-side guard is also a planning guard; it does not continuously cancel an existing journey on a side crossing. These limitations must not be concealed in the diagonal design.

### Existing test evidence and gaps

Vt40Test covers Front projections, left/right Sideways projections and endpoints, consistent A/B reversal, shifted Front layouts, fixed destinations under surfer movement, alignment-only preservation, wrong Front side, segment/endpoint clearance, excursion rejection, combined BODY speed and heading veto, ride interruption, saved navigation with stale GPS, NOFIX pause, retreat/cooldown arbitration, immutable config and both modes' retreat boundary.

The production-session two-axis test uses Front. Sideways has geometry/endpoint tests but lacks the same full two-sided session coverage in this class. A/B reversal tests directly compare seaNorth in a simple east/west fixture; diagonal validation should exercise both components over arbitrary shoreline bearings. Current tests do not establish physical SDK lateral direction, braking, GPS accuracy or framing quality.

## New-mode behavior

### Distance and angle

filmingSeparationMetres means desired DIRECT HORIZONTAL drone–surfer distance in the new mode. Existing Front/Sideways retain their projected meanings. Angle specifies where the destination lies relative to the selected shoreline; yaw/gimbal still aim at the surfer.

Implemented angle convention:

| positioningAngleDegrees | Destination relative to surfer |
|---|---|
| -90 | Left looking seaward |
| -45 | Shoreward-left |
| 0 | Shoreward: Front direction |
| +45 | Shoreward-right |
| +90 | Right looking seaward |

Accept fractional finite values in [-90,+90]. Default +45 selects right. Angle sign replaces an independent side setting for this new mode; old sidewaysSide continues to apply to old Sideways. Mode JSON value is diagonal, even when its configured angle is 0 or +/-90. Same endpoint direction does not imply identical old/new routing behavior.

For fresh planning surfer fix C, shoreward unit vector W and right-looking-seaward unit vector R:

U = cos(theta) W + sin(theta) R
Destination E = C + chosen radius * U

At radius 28 m and angle -45, E is about 19.80 m shoreward and 19.80 m left. Radius is direct horizontal distance, not diagonal projected separation.

### Approach and alignment, using Front as reference

Let D be current direct horizontal distance, F filmingSeparationMetres and M reapproachMarginMetres.

| Condition when no route is running | Action |
|---|---|
| D > F + M | Plan endpoint at radius F and configured angle |
| D <= F + M, but angle alignment error exceeds tolerance | Plan endpoint at current radius D and configured angle |
| Neither condition | Hold translation; keep aiming |
| Existing ride, retreat/cooldown, return or control gate blocks positioning | Apply existing gate/ownership rules |

Retain the existing tiny rounding allowance at the approach threshold. No ordinary outward movement solely to restore F. Existing retreat supplies the close-distance outward response. Alignment corrects the angle below the filming setting, just as Front corrects its alongshore offset below its projected filming setting.

New-mode alignment uses positioningAngleToleranceDegrees, as requested. Measure the signed current drone bearing relative to shoreward at the fresh planning surfer fix, then compare the magnitude of the shortest angular difference to positioningAngleDegrees. Align only when that difference exceeds positioningAngleToleranceDegrees. Preserve alignmentToleranceMetres unchanged for Front and Sideways. User confirmed positioningAngleToleranceDegrees default 5 degrees; supported range still to define. At coincident drone/surfer positions the angle is undefined; do not fabricate an angle or alignment route, and retain existing retreat/control handling.

Example:

| Item | Existing Front | New mode |
|---|---|---|
| Initial position | 20 m shoreward, 15 m right | Same |
| Initial direct distance/angle | 25 m / +36.87 degrees | Same |
| Configured filming value | 28 m shoreward projection | 28 m direct distance |
| Desired angle | 0 degrees | +45 degrees |
| Alignment endpoint | 20 m shoreward, 0 m right | 17.68 m shoreward, 17.68 m right |
| Endpoint direct distance | 20 m | 25 m |

New-mode alignment preserves D at the endpoint; the route need not have constant radius everywhere. A straight segment can cut closer to the surfer; an around route can temporarily move farther away. Neither is deliberate restoration to F. Forbidding every outward component would prevent some detours and has not been requested.

### Frozen snapshot, not continuous following

Capture C, E and all route waypoints once from fresh GPS. Later surfer movement does not shift them or trigger target-driven replanning. Complete the saved route, then reassess with a fresh fix under ordinary approach/alignment rules. Live surfer fixes continue to drive yaw/gimbal, ride detection and direct-distance retreat.

Path clearance is against captured C, not a promise of future clearance from a moving surfer. Fresh ride detection cancels positioning; retreat can replace it. A running return retains its own navigation ownership. Ride expiry does not itself mean physical wave end or trigger return.

Preserve existing GPS handling: saved navigation can continue with stale but available target GPS and valid live aircraft telemetry; no new route from stale data. Explicit NOFIX pauses outside existing retreat/cooldown allowance and preserves route for recovery. Do not add stale-GPS cancellation or moving-surfer route replanning.

## Direct path or around route

No starting quadrant blocks all positioning. Quadrant only selects route type.

For intermediate angles, intended quadrant means shoreward and on the selected alongshore side of C. Quadrant edges count as inside, subject to clearance checks. At 0 use the shoreward half-plane; at +/-90 use the selected alongshore half-plane.

| Starting area and checks | Choice |
|---|---|
| Intended quadrant; complete straight segment passes checks | Direct leg |
| Intended quadrant; straight segment fails surfer-distance check | Try around route |
| Other quadrant | Around route even if direct shortcut would pass |
| Destination violates boundary/excursion | Hold, log exact destination reason |
| No complete permitted route | Hold, log all candidate rejection reasons |

This implements the preference that crossing quadrants always goes around. Current direct distance greater than retreat threshold alone does not authorize a straight segment.

### Around route is a sequence of short straight moves

Start -> P1 -> P2 -> ... -> E.

These are GPS waypoints. Plan clockwise and anticlockwise candidates, validate the whole route, choose the shortest valid candidate with deterministic tie-break, and freeze it. Do not repeatedly switch sides while traveling. Around candidates must not degenerate into the forbidden direct shortcut.

With active surfer-distance clearance, use tangent entry/exit and exterior polygon legs around the clearance circle. Connecting points ON a circle with straight chords cuts inside the circle; use outer points/geometry and independently check every segment. Segment subdivision depends on clearance and waypoint tolerance, not an invented fixed safe step length.

For each leg, command toward its saved next waypoint using live aircraft position and heading. Slow near it, neutralize on transition and continue to the next leg. Before starting the next leg, validate the segment from actual completion position to its saved waypoint against C, boundary and excursion. The existing 1 m arrival tolerance must not allow corner cutting. If invalid, hold with a detailed next-leg reason; do not silently invent a new surfer snapshot.

Retain one 300 s attempt deadline for the complete route; intermediate waypoints do not reset it. Maintain combined velocity cap, existing distance-based braking, live authority/input checks and final BODY-vector validation. Physical braking/turn accuracy remain unverified.

## Exact restrictions

### Surfer-distance path check

Specific term: MINIMUM DIRECT HORIZONTAL DISTANCE FROM THE WHOLE PLANNED SEGMENT TO THE CAPTURED SURFER POSITION. Not projected diagonal distance, altitude/slant range or endpoint-only distance.

With retreat ON, proposed path radius = minimumDistanceMetres + configurable extra direct-horizontal path clearance.

Example: threshold 18 m, extra 2 m -> no planned segment comes within 20 m of captured C. Threshold 18 m, extra 0 m -> 18 m. extraPathClearanceMetres is configurable, default 2 m, range 0..50 m. Existing nominal F >= retreat threshold + 5 m validation remains. Also check actual endpoint radius against the resulting route radius: a large extra allowance can make an endpoint unavailable; never silently clamp F.

A start inside the extra-clearance circle cannot use standard tangents. Direct distance below retreat threshold gives retreat priority. Outside retreat threshold but inside the extra reserve: hold and log; no additional escape maneuver is implemented.

### Retreat OFF, consistent with Front

Retreat OFF disables timed backward retreat and the retreat-radius positioning-path exclusion, as existing Front does. The new mode's extra surfer-distance reserve is inactive with that exclusion. Central-boundary and excursion checks remain active for new-mode positioning.

With retreat OFF, mandatory around routing is constructed around the smaller starting/destination direct radius. This geometric construction prevents an around route becoming a straight shortcut through C, without reinstating the disabled retreat-distance exclusion.

### Boundary line: based on where the drone started

Original boundary anchor = aircraft GPS position at its FIRST steady-hover central capture after explicit Start. Not surfer position, not shoreline A or B. Draw a line through that aircraft point parallel to selected shoreline.

                         SEA
                 Permitted positioning
Boundary ---------------*----------------
                   First captured
                   drone position
                 Beachward: blocked
                        BEACH

Manual reposition can change the separate return central but cannot move this boundary. New explicit Start establishes a new anchor.

Every new-mode destination and route leg must remain on or on the sea side of this boundary line. No additional positioning boundary reserve is requested. Either side around the surfer is acceptable if the whole route stays in this area. Reject a beachward alternative crossing the line; try the other. Do not move the destination to another angle/distance to make it fit.

Existing retreat retains its current internal 0.5 m reserve and 1 s projection horizon unchanged. These are not new positioning settings. Front positioning does not have a boundary reserve; do not add one to the new mode. Check new positioning against the boundary line itself. No configurable positioning look-ahead horizon is introduced.

Initial capture lies exactly on the line: allow movement along the line or seaward. Do not reject a start merely because it lies on the boundary. A start already beachward of the line holds ordinary positioning and logs the reason; no new automatic recovery maneuver is implemented.

Recheck signed boundary distance and command shoreward component during travel and final submission using latest aircraft position/heading. Neutralize if a limited vector no longer safely follows its leg. This boundary is live even though C/E are frozen. Applies to new-mode approach/alignment/around routes and existing retreat; old-mode approaches and return remain unchanged unless explicitly expanded.

### Excursion

User-requested change: add maxExcursionMetres to vt_settings.json, default 300 m. This replaces the existing hardcoded 250 m session excursion maximum. Retain the existing 1 m stop allowance: effective stop/planning radius = maxExcursionMetres - 1 m, so the default is 299 m. Validate every waypoint and complete segment against that radius. Measure from current return central, distinct from immutable boundary anchor after manual reposition. Apply the resolved session value consistently across Front, Sideways, new-mode planning/travel, retreat excursion eligibility and final submission checks; preserve return-to-central behavior. Freeze at explicit Start. Existing JSON missing this new field resolves to the user-requested 300 m default without rewriting the file. Supported range still requires definition; reject invalid/nonfinite values rather than silently clamp. Log maximum, derived stop radius and actual excursion distance. Historical source-review sections retain 250/249 as the 4.0.3 baseline, not the new design.

## Worked Come-to-me and alignment scenarios

These examples use positioningAngleDegrees=+45 (shoreward-right), filmingSeparationMetres=28 m and reapproachMarginMetres=5 m. The approach trigger is direct distance strictly greater than 33 m. positioningAngleToleranceDegrees=5 is the agreed default. Where used, retreat threshold=18 m and configurable extra path clearance=2 m give minimum planned direct horizontal path distance 20 m from the frozen surfer snapshot. Assume retreat is ON.

The boundary position must also be specified: these first examples place its line 40 m shoreward of the captured surfer position. This allows the demonstrated routes; it does not relocate a real boundary. In production the line remains through the first captured drone central, normally over water. Diagrams are schematic, not scale drawings. All distances below are horizontal metres.

### A. Same distance, either side of surfer

```text
                              SEA ↑

                         C ● SURFER SNAPSHOT
                          / \
                         /   \
             L36 ●      /     \      ● R36
             -45 deg                  +45 deg
             D=36                     D=36
                                ● E28
                                +45 deg, D=28

Boundary ------------------------------------------------
          40 m shoreward of C; routes must stay above it
                              BEACH ↓
```

| Start | D | Angle | Distance decision | Route and saved endpoint |
|---|---:|---:|---|---|
| Right, on configured direction | 36 | +45 | 36 > 33: reduce to F | Direct to +45, 28 m, if all checks pass |
| Left, opposite quadrant | 36 | -45 | 36 > 33: reduce to F | Around to +45, 28 m |
| Right, on configured direction | 30 | +45 | 30 <= 33; no angle correction needed | Hold translation; continue aiming |
| Left, opposite quadrant | 30 | -45 | 30 <= 33: preserve D, correct angle | Around to +45, 30 m |

The margin controls distance reduction on either side. Starting area selects straight or around; it does not prevent all planning. Once planned, endpoints are relative to C, not later surfer positions.

### B. Around approach from the left at 36 m

```text
                              SEA ↑

                    P2 ●---------------● P3
                      /      C ●        \
                     /    SURFER         \
                P1 ●                      ● P4
                   |                       \
             START ●                        ● E28
             -45 deg,36 m                   +45 deg,28 m

Boundary ------------------------------------------------
                              BEACH ↓
```

Illustrated candidate: Start -> P1 -> P2 -> P3 -> P4 -> E28. Every arrow is implemented as a straight waypoint leg. Actual waypoint count and coordinates are calculated, not copied from this diagram. This diagram illustrates the seaward candidate; evaluate the other direction too and use the shortest complete permitted around route. If its beachward alternative crosses the boundary, reject that candidate. If neither works, hold and log why.

The aircraft may temporarily move farther from C along the detour. That is route geometry, not outward restoration of filming distance. All segments stay at least the example 20 m from C, not merely their endpoints.

### C. Around alignment from the left at 30 m

```text
                              SEA ↑
                    P2 ●---------------● P3
                      /      C ●        \
                P1 ●                    ● P4
                   |                     \
             START ●                      ● E30
             -45 deg,30 m                 +45 deg,30 m

Boundary ------------------------------------------------
                              BEACH ↓
```

Same around-routing principle as B, but endpoint radius is 30 m because D=30 is within the 33 m distance trigger. Do not reduce to 28 solely because angle correction is needed. Do not hold simply because D is below the approach trigger: incorrect angle still triggers alignment.

### D. Small angle correction inside the intended quadrant

Drone starts 20 m shoreward and 15 m right of C: D=25 m, angle=+36.87. Configured angle=+45; error=8.13 degrees, greater than illustrative tolerance 5.

```text
                              SEA ↑
                         C ●
                           \
                            \ 25 m
                             ● START: +36.87
                              \ short direct alignment
                               ● E25: +45

Boundary ------------------------------------------------
                              BEACH ↓
```

Endpoint is 17.68 m shoreward and 17.68 m right, still 25 m from C. Direct segment's minimum distance is about 24.94 m, so it passes the example 20 m path check; boundary/excursion must also pass. If angle error were <=5 degrees, hold. Angle correction does not deliberately restore 28 m.

### E. Correct quadrant does not guarantee a clear direct segment

Use two different positions in the shoreward-right quadrant:

- Start: radius 21 m, angle +89 degrees (about 0.37 m shoreward,21.00 m right).
- Configured direction: +1 degree.
- D=21 <=33: alignment endpoint retains 21 m radius (about 21.00 m shoreward,0.37 m right).

```text
                              SEA ↑
                         C ●----------● START
                           |        /
                           |      ×    direct shortcut
                           |    /      enters clearance circle
                           |  /
                         E ●

Boundary ------------------------------------------------
                              BEACH ↓
```

Both endpoints are about 21 m from C, outside example path radius 20 m. But the direct segment comes within about 15.11 m of C (21*cos(44 degrees)). Try an around route instead. This is why checking current D or quadrant alone is insufficient. This scenario uses target +1 degree, unlike A–D's +45, to demonstrate the general configurable-angle case.

### F. Destination blocked while surfer is still at beach

Boundary lies through the first drone central,14 m seaward of surfer starting at shoreline. Configuration -45 degrees,30 m gives requested endpoint21.21 m shoreward and21.21 m left of C.

```text
                              SEA ↑
Boundary --------------------● CENTRAL-------------------
                             | 14 m
Shoreline -------------------● C: SURFER------------------
                            /
                           / 30 m
                      E × /  requested -45 destination
                      on land; blocked
                              BEACH ↓
```

E is35.21 m beachward of boundary. Hold at current drone position, continue aiming and log destination_central_boundary with anchor, E and overrun. Do not fly to boundary or change angle/radius. Once surfer moves farther out, a NEW permitted planning snapshot may yield an endpoint on the sea side. No old running route is continuously shifted.

### G. One around direction blocked by boundary

If desired endpoint is allowed but a beachward around candidate has a leg crossing the central boundary, reject that candidate and consider the seaward candidate. If complete seaward candidate clears C and excursion, execute it. If both candidates are blocked, hold and log each failed leg and measured boundary/clearance/excursion values. Going around is not permission to cross the beachward line.

### H. Retreat interrupts positioning

If live direct drone–surfer distance becomes <18 m in these examples, existing eligible retreat can replace the positioning route. Its trigger remains18 m, not20 m; the additional2 m is planning-path clearance only. Existing timed backward periods, zero lateral command, cooldown, stale-GPS allowance and retreat boundary remain unchanged. A running return keeps its existing priority.

### I. Snapshot movement and arrival

Suppose B plans E28 from C and then surfer moves10 m. E28 and all waypoints stay where planned. Yaw/gimbal use subsequent valid surfer fixes; ride/retreat can interrupt under existing rules. At the final saved endpoint within1 m, neutralize translation. A fresh subsequent planning cycle can approach or align relative to the new surfer fix. No speed assessment while the fixed ride timer is active, and no ordinary positioning during a detected ride.

### J. Excursion and retreat-OFF cases

Reject a complete route if its endpoint or any leg exceeds theconfigured planned excursion area (default 299 m) around current return central; log the exact component. Boundary anchor remains separate after manual reposition.

Retreat OFF removes the retreat-circle path check, consistent with Front, but not the new positioning boundary/excursion checks. Mandatory around routing uses the smaller starting/destination direct radius as its geometric circle; it does not reinstate the disabled retreat-distance exclusion.

### Scenario acceptance tests

Turn A's four rows into separate direct/around/hold tests. Add B/C waypoint clearance and saved-radius tests, D degree-tolerance boundary checks, E unsafe chord detection, F blocked destination with14 m seaward boundary, G per-candidate rejection, H live retreat replacement, I fixed route despite surfer movement and J excursion/OFF policy tests. Verify corresponding JSONL route type, endpoint radius, angle error and block reason independently.
## Logging and display

Keep existing Full Log default/request policy and JSON-only tuning. Explain direct distance for new mode and projected distances for old modes.

Log desired angle/radius, D, angle error in degrees and configured angular tolerance, actual endpoint radius, planning C/time, immutable boundary anchor, return central, route type/direction, waypoints, leg index, segment minimum distance, effective clearance/reserves, signed boundary distance, excursion values, requested/submitted forward/right, interruption and completion reasons. Old projectedSeparationM is not applicable to new mode; do not put direct distance under that key.

Block reasons must distinguish destination_central_boundary, route_central_boundary, destination_excursion_limit, route_excursion_limit, route_surfer_clearance, start_inside_routing_clearance, start_beachward_of_boundary, next_leg_clearance_failed and no_valid_around_route. Record candidate directions and each rejected segment/reason/value. A generic 'blocked' message is insufficient. Log default-versus-explicit config values. Logging failure must not influence control.

## Configuration and implementation changes

1. Add new mode, optional angle default, positioningAngleToleranceDegrees, configurable extra path clearance and maxExcursionMetres (default 300), retaining strict JSON validation and old file compatibility. Freeze resolved settings at Start; no automatic overwrite of existing JSON. Preserve extra keys during shoreline selection writes.
2. Extend immutable preset/session configuration and pure geometry planner for direct and around routes; preserve old constructors/behavior needed by regressions.
3. Add direct-distance reapproach and preserved-D angle alignment, multi-leg snapshot state and one attempt deadline.
4. Add new-mode boundary checks during planning/execution/final submission; preserve existing ride/retreat/return ownership, GPS and snapshot behavior.
5. Update summaries/help to avoid old front-versus-other labels incorrectly calling new distance alongshore projection. Add waypoint/block evidence to existing logs.
6. After approval, implement final 4.0.4 release/design notes and version code/name bump, run regressions and offline build. No installation, deployment or flight action implied.

## Required validation

- Old Front/Sideways geometry, alignment-only, fixed destinations, side guards, stale-GPS/NOFIX and retreat-boundary regressions remain unchanged.
- New signed angles/endpoints/fractions, arbitrary shoreline bearings, A/B reversal and direct-radius endpoints.
- Approach threshold, preserved-radius angle alignment below F, no outward restoration, tolerance boundaries and no translation while ride gates apply.
- Both around directions, all starting quadrants, axes, safe endpoints with unsafe connecting segment, boundary/excursion rejection and no valid route.
- Complete segment clearance, inflated radius, tangent/polygon numerical tolerance and actual waypoint completion shortcuts.
- Initial-boundary departure, beachward-start block and final changed-heading/position command veto.\n- Configurable excursion: missing field defaults to 300 m; explicit values freeze at Start; default stop/planning radius 299 m; all modes and retreat/final checks use the resolved value; invalid values fail; independent logs distinguish configured maximum from derived stop radius.
- Fixed route under surfer movement; live yaw/ride/retreat; saved navigation with stale available GPS; explicit NOFIX pause; manual reposition/Stop/control loss; one timeout across legs.
- Retreat ON/OFF, extra-clearance config and finalized OFF around-radius policy.
- Strict JSON compatibility/frozen session values/selection preservation; independent JSONL validation of route, failed segment and submitted vectors.

Software tests do not establish GPS accuracy, physical braking, SDK lateral direction, tree avoidance or ride-framing coverage. No speculative operating threshold is presented as proven.

## Final configuration decisions

- positioningAngleDegrees: default +45, range -90..90, finite fractional numbers; old modes retained.
- positioningAngleToleranceDegrees: default 5, range 0.1..45 degrees; old alignmentToleranceMetres unchanged.
- extraPathClearanceMetres: default 2, range 0..50 m, active only with retreat ON.
- maxExcursionMetres: default 300, range 10..1000 m; stop/planning maximum minus 1 m.
- Around geometry with retreat OFF uses the smaller starting/endpoint direct radius, with no retreat-distance exclusion check.
- No new positioning boundary reserve/horizon fields.
- New fields optional in existing version-1 JSON; invalid present values block Start; effective values freeze per session.
Front early-retreat/autocalibration, speculative 30/25 m tuning, ride-detection redesign and RELEASE_UNCONFIRMED remain outside 4.0.4 scope.

User confirmed positioningAngleToleranceDegrees = 5 degrees on 5 October 2026. Angle error <= 5 degrees does not trigger alignment-only movement; larger error does. Distance-reducing approach remains independently eligible.


Verified implementation: version 4.0.4/code 27, all existing regressions and 86 new checks passed, independent route JSONL passed, offline Android build succeeded. Manifest inspected; APK saved at C:/Users/gildo/.codex/worktrees/vt/gdlapp/vt/build/releases/VT-4.0.4.apk.
