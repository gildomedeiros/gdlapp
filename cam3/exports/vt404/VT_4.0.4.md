# VT 4.0.4 — configurable-angle snapshot positioning

Android versionName **4.0.4**, versionCode **27**. Source: `C:/Users/gildo/.codex/worktrees/vt/gdlapp/vt`.

## Select and configure

In the selected configuration folder, edit `vt_settings.json` while stopped. Set `mode` to `"diagonal"` to use the new mode. Newly seeded files still default to `"front"`; existing user files are not replaced.

| Field | Default if absent | Validation | Meaning |
|---|---:|---|---|
| `positioningAngleDegrees` | 45 | −90..90, finite number | −45 shoreward-left, 0 shoreward, +45 shoreward-right; ±90 alongshore. Left/right looking seaward. |
| `positioningAngleToleranceDegrees` | 5 | 0.1..45, finite number | Align when absolute angle error exceeds this value. |
| `extraPathClearanceMetres` | 2 | 0..50, finite number | Extra direct horizontal path clearance beyond enabled retreat threshold. |
| `maxExcursionMetres` | 300 | 10..1000, finite number | Maximum distance from return central; stop and route-planning radius is maximum minus 1 m. Applies across modes. |

Other existing settings are retained. In diagonal mode only, `filmingSeparationMetres` means direct horizontal filming distance; `alignmentToleranceMetres` and `sidewaysSide` remain for old modes. For diagonal mode, angle sign selects side. All five JSON files validate before Start; effective values are frozen for the session. Invalid fields block Start, never clamp silently. Optional new fields allow old version-1 files to load without rewriting them.

## Approach, align and hold

- Direct distance greater than filming distance plus margin: plan at filming distance on the configured angle.
- Within that distance band but outside angle tolerance: align on the configured angle while preserving the current direct radius at planning.
- Within both: hold and aim. Do not move outward merely to restore filming distance.

For filming 28 m, margin 5 m and angle +45: right-side36 m approaches directly to28 m; left-side36 m goes around to28 m; right-side30 m already at45 holds; left-side30 m goes around to45 while preserving30 m. Every route must pass the independent boundary, excursion and enabled surfer-distance checks.

Capture one fresh surfer GPS fix, destination and complete route. Later surfer movement does not move those saved points or automatically replan. Yaw/gimbal keep aiming using subsequent fixes. After arrival, a fresh cycle may plan another approach/alignment. Existing ride detection cancels ordinary positioning; eligible retreat can replace it. A running return retains ownership. The fixed ride timer is not physical wave-end detection.

## Routes and boundaries

Inside intended quadrant, use a straight segment only if the complete segment passes checks. From other quadrants, use an around route. At angle0 or±90, use the corresponding half-plane for route classification. Around paths evaluate clockwise/anticlockwise exterior polygon alternatives, selecting the shortest valid candidate with stable tie-break. They are saved short straight waypoint legs, not a continuously changing orbit. Whole legs clear the circle; chords cannot cut through the excluded area.

With retreat ON, path circle radius is retreat minimum distance plus extra clearance around the captured surfer fix. Internal exterior polygon construction additionally accommodates the existing1 m waypoint arrival tolerance. Before continuing from an actually reached waypoint, check the next entire segment from latest aircraft position. If it fails, neutralize and log the reason. A start inside the configured path circle holds ordinary positioning; existing retreat remains independently eligible below its actual threshold.

With retreat OFF, remove the retreat-distance path exclusion, matching Front. Mandatory around geometry uses the smaller start/destination direct radius rather than reinstating a retreat threshold. Direct-path classification and central-boundary/excursion checks remain. The geometric circle builds the around route; it is not an enabled retreat threshold.

The new mode's destination and every leg must remain on or sea-side of the shoreline-parallel line through FIRST aircraft central capture after explicit Start. This is normally over water, not the actual shoreline. Manual reposition can recapture return central but does not move this boundary. Boundary is live-checked during travel and final submission, with no new configurable positioning reserve/horizon. Existing retreat's internal0.5 m reserve/1 s horizon remain unchanged. Old Front/Sideways approach and existing return do not gain this new positioning boundary restriction.

Excursion is configurable across movement and retreat checks, default300 m with299 m stop/planning radius. A whole route must fit before starting. Movement follows combined BODY forward/right velocity with existing speed cap/braking and one300 s attempt deadline across all waypoints. Waypoint transitions are neutral; final arrival is within1 m. Latest authority, aircraft position/heading and target processing checks veto obsolete commands.

Saved navigation may continue with stale but available GPS; no new route starts from stale GPS. Explicit NOFIX retains existing pause/recovery behavior outside retreat's retained-target allowance. Stop/control loss/manual reposition cancel route as appropriate.

## Logs

Movement cycles include direct-distance meaning, desired angle/tolerance/error, route type/direction/status, native waypoint arrays, index/next waypoint, frozen surfer coordinates/time, endpoint radius, clearance, configured excursion and derived stop, actual requested/submitted axes. New-mode projected separation is null, not a mislabeled direct distance.

Blocks identify destination/route central boundary, excursion, surfer clearance, start beachward/inside clearance, and no valid around route. Rejected clockwise/anticlockwise candidates include reason and representative failed-segment measurements. Continuous cycle logs retain the block evidence; logging errors never control flight.

## Validation and limits

Run `tools/test-aiming.ps1` for existing regressions plus Vt404Test scenario, geometry, configuration, session, stale-GPS/NOFIX, waypoint and JSONL checks. Build offline using JDK21 from `SampleCode-V5/android-sdk-v5-as`, `gradlew.bat --offline :sample:assembleDebug`.

No installation or flight action is included. Host tests/build cannot establish physical braking, GPS accuracy, SDK lateral axis direction or tree avoidance. Route circle uses a frozen surfer position; live retreat is separate and is not continuous path validation against a moving surfer. Boundary is a command constraint, not obstacle detection.

Front early-retreat/autocalibration, ride-detection redesign and RELEASE_UNCONFIRMED are unchanged.
