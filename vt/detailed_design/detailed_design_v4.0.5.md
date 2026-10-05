# VT 4.0.5 detailed change design

Blocked diagonal positioning legs now stop and repair their remaining route instead of repeatedly waiting on the same waypoint. Recovery preserves the captured surfer S0, saved final destination and original 300-second journey deadline.

- Diagonal route clearance equals the configured retreat threshold when retreat is enabled. The former extra 2 m is removed. `extraPathClearanceMetres` is accepted for compatibility but ignored by diagonal routing, even if an existing file contains 2. The bundled default is 0. Existing user configuration files are not rewritten.
- Recovery checks clockwise and anticlockwise routes and selects the shortest permitted candidate, using the latest aircraft position and captured S0. Movement remains neutral during the replacement-planning cycle.
- If the aircraft is inside S0's clearance circle during a saved journey, recovery may insert an outward escape. Every point along that leg must increase separation from S0; boundary and excursion limits still apply. Escape is rechecked before control submission. No outward escape rule is added with retreat OFF.
- If no permitted replacement exists, positioning holds and continues aiming. Failed recovery is retried no more than once per second within the existing journey deadline. A destination beyond the boundary remains blocked; its position is never changed by recovery.
- Every remaining leg is checked against captured S0, the original-central boundary and the excursion limit. Live surfer movement affects aiming, ride detection and existing retreat, not the saved route geometry.
- Full-log session headers use the installed app's package version. Diagonal logs suppress projected separation/alignment fields even during retreat/cooldown/timeout and identify current-surfer versus captured-S0 direct distance.
- Journey outcome events identify arrival, timeout and cancellation, with the cancellation cause, saved geometry, elapsed time and last blocking reason. Recovery attempts/status and outward-escape legs are recorded in movement cycles.

Front/Sideways motion geometry, retreat priority, pilot intervention and GPS gates are preserved. Around routes still consist of short straight segments and neutral waypoint transitions; continuous smooth arc flight is not included.

## Route examples

For retreat threshold 25 m, required diagonal route clearance is now 25 m. The recorded remaining D–P1 leg that measured 26.955 m is allowed; it needs no recovery merely because it failed the former 27 m rule.

When a boundary overlaps the clearance circle and closes one passage, both clockwise and anticlockwise candidates are evaluated. If P3 remains on the permitted side, the available opposite-side route can be used. If P3 itself is beachward, no direction can reach it legally; hold and log the destination boundary failure.

## Validation

Run `tools/test-aiming.ps1` for the existing regression suite and VT405 recovery, outward-only escape, overlapping-boundary routing, frozen snapshots, timeout, cancellation and JSON logging checks. Build the sample debug APK with the existing offline Gradle setup. Desktop checks do not establish on-aircraft tracking smoothness or performance.
