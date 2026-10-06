# VT 4.0.6

Diagonal route planning now leaves configurable room for aircraft tracking and reported-position variation. Runtime blocking continues to use the retreat threshold, so the former extra 2 m blocking rule is not restored.

## Settings and geometry

- New `routePlanningAllowanceMetres`: default **2 m**, valid finite numeric range **0–50 m**, fractional values accepted. Optional in existing `vt_settings.json`; absent values use 2. Existing user files are preserved, and values freeze at explicit Start.
- With retreat ON: **planning clearance = retreat threshold + planning allowance**, while **execution clearance = retreat threshold**. For threshold 25 and allowance 2, every newly planned ordinary leg—including direct, entry, ring, exit and replacement legs—must clear captured S0 by at least 27 m. During execution, the remaining leg blocks only below 25 m.
- The around polygon retains its existing waypoint-arrival/chord allowance outside the planning circle. This geometric construction allowance is not another blocking threshold.
- Both clockwise and anticlockwise candidates are considered. A route that cannot fit the wider planning clearance, central boundary and excursion limit is held with the failed reason and measured geometry. A saved destination inside the planning circle is not silently moved farther away.
- Outward recovery is the explicit exception: if a saved journey needs repair while the aircraft is inside the planning circle, an outward-only bridge may start there and reach the planning ring before joining the fully validated replacement route. Its entire leg must monotonically increase distance from S0, respecting boundary and excursion limits. No new inward permission is introduced. A leg already executing safely between the two thresholds continues without needless replanning.
- Retreat OFF: no retreat-derived planning or blocking circle is introduced. Existing geometric around routing remains.
- Legacy `extraPathClearanceMetres` remains accepted but ignored for diagonal routing; it is independent of the new planning allowance.

## Recovery logging

When permitted movement resumes, `routeRecoveryStatus` becomes `none` and current failed-segment measurements are cleared. `lastRouteRecoveryResult` retains the last recovery-attempt result; `lastJourneyBlockReason` separately retains the last actual leg block. Both reset for a new journey. Logs include `routePlanningAllowanceMetres`, `requiredPlanningClearanceMetres` and the existing `requiredPathClearanceMetres` execution limit.

## Preserved behaviour

Captured S0, saved final destination, original 300-second journey deadline and existing retreat priority are preserved. Front/Sideways motion, central-boundary edge handling and neutral waypoint transitions are unchanged. The planner generates short straight legs; this release does not implement continuous smooth arc flight. A planning allowance reduces fragility but does not guarantee error-free tracking.

## Validation

The regression suite includes separate 27 m planning/25 m execution checks, all-leg clearance, alternate routing beside an overlapping boundary, outward bridging into the planning ring, fixed destination handling, retreat OFF, configuration defaults/validation and current-versus-historical recovery logs. Run `tools/test-aiming.ps1`. Build with the existing offline Gradle sample project. Aircraft testing remains separate from deterministic desktop checks.
