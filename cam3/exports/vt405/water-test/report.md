# VT 4.0.5 device-log audit — 6 October 2026

Archive: `C:/Users/gildo/temp/405 test/cam3_full_2026-10-06_183437_420_649a64eb.zip`.

The archive contains eight log files, seven configured test sessions, and 68,340 valid JSON records. The independent audit produced zero failed assertions. This confirms the recorded planning/submission checks below, not every physical flight outcome.

## Results

- All eight full-log headers report 4.0.5.
- All 10,896 diagonal movement rows have zero effective extra clearance and the configured 25 m route-clearance threshold. The saved settings still contain `extraPathClearanceMetres: 2`; the runtime correctly ignores it.
- All 10,896 diagonal rows suppress projected separation/alignment fields. Captured-S0 distances agree with independent calculations within 2 cm.
- Sixteen journeys were planned; eight arrived. The other eight have exactly one cancellation outcome each: four pilot intervention, two user stop, one screen closed and one retreat. No journey timed out in this archive.
- Two successful route replacements occurred in one journey, at 18:23:14.928 and 18:23:16.011 Brisbane time. Both used anticlockwise around routes, with 43 and 46 waypoints respectively. S0 and the saved final destination remained fixed. Elapsed time increased from 6.050 to 7.136 seconds, without resetting the attempt deadline. Positioning commands were zero in the replacement-planning cycles, then movement resumed. This journey was cancelled by user stop at 18:24:23.408; full arrival after recovery was not demonstrated.
- All 240 initial/replacement planned legs passed independently computed clearance, boundary and excursion limits. All 3,165 recorded nonzero approach cycles passed the observed-position clearance and boundary checks within 2 cm numerical tolerance. Actual final submission aircraft coordinates are not logged separately, so these checks use the joined `aiming_cycle` snapshot.
- Positioning command speed limits, 2,321 blocked/replanned neutral checks and 657 active retreat command-envelope checks passed.

## Remaining issues / limitations

### Central-boundary start sensitivity

The original central boundary passes through the initial drone capture, putting the starting position exactly on the limit. A reported position slightly beachward prevents both continuing the saved leg and planning a replacement.

At 18:34:44.189, the +45 degree journey was planned at boundary distance 0.000 m. At 18:34:44.295, its reported position was 0.012902 m (1.29 cm) beachward, so recovery failed with `start_beachward_of_boundary`. Thirty-two recovery attempts followed. Positioning first resumed at 18:35:17.474, with the reported position 0.001175 m on the permitted side. The first destination was reached at 18:35:57.055, and a subsequent alignment journey arrived at 18:36:06.259.

Thus the boundary checks worked as specified, but a tiny reported displacement caused roughly 33 seconds of waiting. Logs do not distinguish GPS variation from actual aircraft motion. A next design change could allow a boundary recovery leg that only moves toward the permitted side, while prohibiting farther beachward motion; this requires changing the current whole-segment boundary rule. No such change was made during this audit.

### Tight entry clearance still causes repeat repairs

The two repaired routes had only 0.119 m spare minimum clearance above 25 m. The first replacement's next leg subsequently measured 24.642 m and was blocked again, leading to the second replacement. Recovery now prevents the permanent same-waypoint stall, but normal position changes can still cause repeated repairs on tight connecting legs.

### Recovery status can retain an old failure

At 18:35:17.474, motion resumed with `routeStatus: traveling` while `routeRecoveryStatus` still read `start_beachward_of_boundary`. This field retains the last recovery attempt result; it is not reliable as a statement of the current movement condition. Clearing it on successful continuation or renaming it as the last-attempt result would make logs clearer.

### Not demonstrated here

No outward-escape route was recorded. The recovery journey was stopped before arrival. No 300-second timeout, excursion-limit approach, continuous smooth arc flight, or complete overlapping-boundary alternate-route scenario was demonstrated by this archive. Desktop regression tests cover several of these cases, but are separate evidence.

Machine-readable assertions and supporting timelines: `results.json`, `routes.csv`, `blocks.csv`, `details.txt` in this folder. No app source or APK was changed during this audit.
