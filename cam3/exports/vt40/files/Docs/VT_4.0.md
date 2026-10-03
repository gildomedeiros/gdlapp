# VT 4.0 — fixed shoreline positioning

Android versionName 4.0, versionCode 23. Implemented in the existing vt-28 working tree. Existing VT 3.x edits are preserved. No phone preferences are migrated into movement settings.

## Configuration and upgrade

Choose the existing Configuration folder (normally Download/VT). The first VT 4.0 setup adds `vt_settings.json` and an empty `vt_shorelines.json` only when absent. Existing files are preserved. After setup, missing or malformed files block Start with the filename and error; files are not silently replaced on every Start. Explicit folder setup can create missing files again.

Five files are validated before acquiring control. Their effective contents are frozen for the session and logged:

| File | Authority |
|---|---|
| vt_settings.json | Come-to-me enabled, mode, selected shoreline ID, sideways side, projected filming separation, reapproach margin, movement cap, lineup width, ride start/duration, no-ride timeout, yaw rate/acceleration limits, alignment tolerance |
| vt_shorelines.json | Named straight A/B shoreline profiles, explicit seaward side, capture method/date/quality |
| vt_retreat_settings.json | Existing direct horizontal distance trigger, fixed backward speed/duration, cooldown, stale-GPS allowance |
| vt_rotation_speeds.json | Existing normal/riding yaw curves |
| vt_gimbal_bands.json | Existing gimbal pitch bands |

Movement tuning forms and the Come-to-me toggle are removed. The configuration menu explains JSON editing. Shoreline capture, selection, rename and delete remain operational UI actions, and selection writes only `shorelineId` in the same JSON source. Editing JSON while running affects the next explicit Start.

New default projected filming separation is **28 m**, matching the existing retreat default **23 m + 5 m**. No shoreline is selected by default: capture or manually enter one, review the sea side, and select it before movement can start. Both modes require `filmingSeparationMetres >= minimumDistanceMetres + 5` when movement and retreat are enabled. Invalid combinations block Start; neither value is silently changed. This is a nominal settings buffer, not a GPS accuracy guarantee.

Settings ranges: filming 10–200 m; reapproach margin 0–200 m; maximum combined translation 0.1–5 m/s; lineup width 20–200 m; ride trigger 1–100 km/h; fixed ride 1–600 s; no-ride timeout 60–7200 s; yaw cap 1–30°/s; yaw acceleration 0.5–30°/s²; alignment tolerance 1–20 m. Preserve existing ride policy: speed jumps above 40 km/h are rejected, so a ride trigger above 40 km/h cannot be satisfied by accepted evidence. JSON rejects unknown/missing fields, wrong types, duplicate keys and invalid coordinates. Files retain the existing 64 KiB strict limit.

## Geometry and fixed journey

Two shoreline GPS points define A→B. `seaSide` explicitly chooses `leftOfAToB` or `rightOfAToB`. The shoreline is treated as a straight line; seaward is its chosen perpendicular. Local north/east metre coordinates are computed using Earth radius 6,371,000 m.

| Mode | Positive projected separation | Come-to-me alignment error | Destination |
|---|---|---|---|
| front | Drone shoreward of surfer along the seaward perpendicular | Alongshore component of drone–surfer displacement | Same alongshore coordinate as the planning surfer fix; chosen shoreward separation |
| sideways | Drone left/right of surfer alongshore; `sidewaysSide` is left/right **looking seaward** | Difference in drone/surfer shore-normal coordinates | Same shore-normal coordinate as the planning surfer fix; chosen alongshore separation |

For each journey, a fresh valid surfer fix computes the destination **once**. Later surfer movement does not move the destination. While traveling, live aircraft position and heading determine a forward/right BODY velocity vector toward that fixed destination; yaw and gimbal keep aiming at the surfer. Sideways displacement does not require yaw to face the movement direction.

- Projected separation above filming + margin: start a fixed destination approach at the filming separation, correcting the other axis too.
- Projected separation at/below that trigger, with other-axis error above tolerance: start alignment-only movement, preserving the projected separation measured at planning.
- Within both limits: hold. For example Front mode, projected shoreward 20 m, alongshore error 0 m, direct horizontal distance 20 m, filming 23 m, margin 5 m, retreat 18 m: hold. No outward correction is commanded merely to restore 23 m.
- Wrong selected side (projected separation <=0): hold instead of crossing the surfer. The existing direct-distance retreat may still trigger.
- Initial straight segment and endpoint must stay outside the configured retreat circle and within the 249 m excursion boundary. Otherwise hold and log `alignment_path_or_destination_blocked`. A 15 m projected alignment endpoint is therefore rejected when retreat is 18 m even if the initial direct distance is larger.

Speed remains `min(maxMovementSpeed, 0.2 × remaining metres)`, with the existing small minimum and neutral arrival at <=1 m. The maximum applies to the **combined vector**, not separately to each axis. A final aircraft snapshot must match the calculated BODY vector; changed heading/position vetoes stale translation. No software acceleration ramp is added.

Ride detection cancels approach. Explicit Stop/control loss cancels all destinations and axes. Existing saved navigation may continue while the last surfer fix remains available but stale; no new destination is planned from stale data. Explicit NOFIX preserves the existing protection pause outside retreat/cooldown: motion stops and the fixed destination remains saved for recovery. Only the existing retreat/cooldown allowance can substitute a retained fix for an explicitly unavailable target. No extrapolation is used. Existing manual reposition, flight protection, late authority veto and release confirmation behavior remain.

## Retreat and return

Retreat remains independent of mode: actual direct **horizontal** drone–surfer distance, not either projected component or 3D slant distance. Retreat clears an approach destination, commands timed body-backward velocity with right velocity zero, and blocks approach during cooldown. After cooldown, a fresh valid fix can plan a new fixed journey. The existing bounded stale-GPS retreat allowance is unchanged.

Existing central-point capture, 250 m maximum excursion with a 249 m stop, no-ride return, saved return bearing, backward translation and 3 m short return destination with 1 m completion tolerance remain. A return already running retains navigation yaw ownership and cannot be interrupted by retreat or ride detection. There is no continuous filming-distance maintenance or dynamic route around a moving surfer.

## Shoreline setup

1. Stop automatic control and select LoRa GPS. No flight is needed.
2. Open Shorelines → Capture new shoreline. Name it. Hold the LoRa unit stationary at shoreline point A and capture A.
3. Walk roughly 50–100 m along the shoreline. Hold stationary at B and capture B.
4. Review the **straight A→B line** and seaward arrow. Choose which side is sea, looking from A toward B. Google Maps buttons open each point externally for geographic checking; those links need no Maps SDK/API key or embedded paid map. They do not draw the custom line. The VT preview is schematic and oriented A→B, not north-up.
5. Save & select. On the next visit choose the named saved shoreline; recapture after materially changed beach conditions or when a curved shoreline needs a new local segment.

Capture needs 10 distinct fresh received LoRa fixes spanning at least 4 seconds. Each must be <=3 seconds old and received after capture began. Duplicate reception timestamps do not count. Maximum radial scatter about the averaged point is 5 m. Stale data/NOFIX clears the batch and visibly waits. Timeout 120 s. Source/screen/control changes cancel capture. The LoRa protocol has no independent GPS-fix ID: this counts accepted distinct packets, and does not claim stationary repeated coordinates are independent sensor measurements.

A/B spacing is validated at 30–10000 m, coordinates at latitude -85..85 / longitude -180..180, library size <=100 profiles. Manual A/B entry is also available. Profiles store ID/name, points, sea side, method, date, fix counts and scatter. Selected profiles cannot be deleted until another is selected. Rename and selection apply while stopped.

Writes retain a `.backup`, verify the written contents, and attempt rollback on failure. Storage Access Framework providers do not offer a general atomic multi-file transaction: if the profile saves but selection fails, the UI reports it and the profile can be selected afterward. Missing/corrupt files fail visibly.

## Validation and device limits

`tools/test-aiming.ps1` runs existing control, GPS, ride, return, retreat, migration and logging regressions plus VT 4.0 geometry, both side choices, shifted Front layouts, fixed endpoints under surfer movement, vector cap/final-heading veto, wrong side, route exclusion, alignment-only projection preservation, capture freshness/scatter and strict JSON configuration. The actual combined SDK factory is compiled and checked for BODY velocity modes, forward roll, lateral pitch and neutral vertical velocity. Movement JSONL is parsed independently to verify preset/destination/axis/yaw evidence.

Offline Android build: from `SampleCode-V5/android-sdk-v5-as`, run `gradlew.bat --offline :sample:assembleDebug` with JDK 21. Debug APK output is `SampleCode-V5/android-sdk-v5-sample/build/outputs/apk/debug/sample-debug.apk`.

JVM tests and a successful APK build do not verify physical SDK axis direction, lateral braking or flight behavior. Confirm BODY X/Y direction and surfer aiming on the target aircraft before using the new two-axis positioning in flight.
