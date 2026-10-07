# VT 4.0.7

VT's automatic no-ride return now stops at a saved restart point before reaching central. Retreat can interrupt a return. Shoreline configuration is renamed to Wave line, with fresh files and no migration.

## Soft return

`returnBoundaryStandOffMetres` defaults to **10 m**. Valid values are finite numbers from 1 to 100, below `maxExcursionMetres - 1`. The no-ride timer, return speed limit, slowdown slope and five-minute attempt deadline remain in effect.

The boundary remains the line through the original first central capture, parallel to the configured Wave line. Its positive/sea side comes from the selected Wave line orientation. The Wave line can be over water; it supplies direction, not the physical beach location. Neither the boundary nor the original anchor is moved during return.

At return start, VT intersects the straight route from the current drone position toward the original central capture with a line 10 m sea-side of the boundary. That intersection is the fixed return target. Heading alignment is followed by forward/right BODY velocity toward this saved target; telemetry deviations cause correction toward the same target. Completion uses direct horizontal distance to the target, within the existing 1 m arrival tolerance, and requires a reported position on the permitted side. Thus the 10 m value is a planned stand-off, not a guarantee of exactly 10 m physical stopping distance.

| Starting position relative to original boundary | Behavior |
|---|---|
| 40 m sea-side, 20 m sideways from original central | Return target: 10 m sea-side, 5 m sideways; neutral transition, align, then move to saved target |
| Between boundary and configured 10 m sea-side line, including endpoints | Skip translation, consume reset, continue aiming; ordinary positioning is reconsidered on the next tick |
| Beachward of boundary | Hold, aim and log `return_start_beachward_of_boundary`; do not accept as restart zone |
| Reported position becomes beachward during return | Hold and log `return_beachward_of_boundary`; do not declare arrival |
| Return target violates excursion limit | Hold with pending reset and log `return_target_excursion_limit` |

The boundary is not made into a new blanket prohibition on every aircraft operation. Existing positioning and retreat boundary rules retain their meaning. Aircraft RTH behavior is unchanged. This change governs VT automatic no-ride return only. Legacy controllers without a Wave line preset retain their original return algorithm.

## Retreat priority

The current surfer's direct horizontal separation controls retreat, independently of captured S0 used by an angle journey.

| Situation | VT 4.0.7 behavior |
|---|---|
| Surfer inside retreat threshold before return starts | Retreat takes priority; return waits |
| Current surfer enters retreat threshold while returning | Neutralize return, preserve pending reset and original return deadline; existing retreat owns translation |
| Retreat or cooldown active | Keep reset pending; aiming continues |
| Retreat/cooldown ends with usable GPS and no current confirmed ride | Reconsider reset from current aircraft position; compute a new fixed restart target if needed |
| Already in restart zone after retreat | Consume reset with no return movement |
| Pending return reaches original five-minute deadline | Stop navigation with `movement_timeout`; do not renew deadline |
| Pilot intervention, Stop or control loss | Existing cancellation rules clear saved/pending return |

Retreat speed, duration, cooldown, stale-GPS allowance and its existing boundary checks are unchanged. An inactive timed-retreat configuration does not create a new return-clearance circle. Automatic return retains the existing live retreat policy rather than adding S0 routing to return.

## Fresh Wave line configuration

Use `waveLineId` in `vt_settings.json`. Use **`vt_wave_lines.json`** with root array **`waveLines`**. Java classes, UI labels and current log fields use Wave line names, including `effectiveWaveLinesJson` and `waveLineId`. Geometry, A/B capture, selected sea side, Front/Sideways/Diagonal angle meanings and validation retain their behavior.

Old `shorelineId` / `shorelines` keys are rejected, and `vt_shorelines.json` is not loaded or migrated. Start with a fresh configuration folder, or explicitly replace the settings and Wave line library with the new schema. Capture/select a Wave line before Start. Existing old user files are not deleted. The bundled Wave line library is empty and the selected ID is blank.

## Retained angle behavior

Default angle +45°, range −90°..+90°, angle tolerance 5°. Default route planning allowance remains 2 m; execution checks use the retreat threshold without adding that allowance. Frozen S0/P3, alternate-direction route recovery, outward escape, excursion default 300 m and all existing distance/angle triggers remain unchanged. No additional motion smoothing is introduced.

## Diagnostics and validation

Movement logs add `returnBoundaryStandOffMetres`, `returnTargetLatitude`, `returnTargetLongitude`, `returnTargetRemainingM`, `returnBoundaryDistanceM` and `returnPending`. Transition reasons identify skipped return, beachward holds, excursion holds and retreat interruption. Full regression suite covers existing modes and routing plus focused soft-return, retreat/deadline and fresh-schema cases. Desktop checks and APK build do not replace an aircraft flight test.


## Release verification

- Full desktop regression suite passed, including 99 VT 4.0.7 checks and independent JSONL validation.
- Offline Android debug APK build passed; package version 4.0.7, code 30.
- Packaged assets verified: fresh Wave line schema, return stand-off 10 m, planning allowance 2 m, excursion 300 m.
- APK SHA-256: `BBEDB379C4B084B82DCD1E1839137C32FCF3F6E4FA57E41740FA9329B1BD84BA`.
- Aircraft flight test remains pending.
