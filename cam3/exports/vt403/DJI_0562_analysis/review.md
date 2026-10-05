# DJI_0562: first five minutes

The metadata contains 300 frame-to-telemetry matches. Visual review covers the whole duration at 5.005-second intervals, with 1.001-second samples around takeoff. This is sampled inspection, not continuous playback or exhaustive labeling of every stroke. Unreviewed frames remain explicitly unclassified. The blue-shirt surfer is assumed to be the GPS target.

Clock mapping uses MP4 creation time, 08:53:08 AEST, as recording start. It needs independent validation against an original timestamped frame before millisecond ordering can be treated as exact.

| Video offset | Mapped time | Visible observation | Direct / projected separation | Signed shoreward speed | GPS age | Actual pitch | Yaw error | Drone measured speed | Retreat |
|---|---|---|---|---|---|---|---|---|---|
| 03:17.197 | 08:56:25.197 | Waiting, visible | 25.80 / 25.71 m | — | 10 ms | −24.9° | −0.33° | 0.30 m/s | Inactive |
| 03:22.202 | 08:56:30.202 | Prone / paddling, visible | 26.95 / 26.88 m | −3.53 km/h | 48 ms | −25° | −1.35° | 0 m/s | Inactive |
| 03:24.204 | 08:56:32.204 | Approaching wave face | 26.96 / 26.89 m | 0 km/h | 99 ms | −25° | −1.38° | 0 m/s | Inactive |
| 03:25.205 | 08:56:33.205 | Takeoff / crouch, poor lower-edge margin | 26.07 / 25.89 m | +3.65 km/h | 13 ms | −24.9° | +1.14° | 0 m/s | Inactive |
| 03:26.206 | 08:56:34.206 | Ride moves toward lower edge | 24.17 / 23.90 m | +7.18 km/h | 41 ms | −25° | +3.20° | 0.70 m/s | Inactive |
| 03:27.207 | 08:56:35.207 | Target not identifiable in inspected frame | 18.39 / 17.97 m | +14.25 km/h | 69 ms | −25° | +6.62° | 0.50 m/s | Inactive |
| 03:30.210 | 08:56:38.210 | Target not identifiable | 2.86 / 2.13 m | +28.06 km/h | 48 ms | −35° | +19.78° | 3.98 m/s | Active, command −4 m/s |
| 03:35.215 | 08:56:43.215 | Post-ride whitewater, identity uncertain | 4.71 / −4.49 m | See CSV | 88 ms | −54.9° | +134.71° | 3.80 m/s | Active |
| 03:45.225 | 08:56:53.225 | Post-ride, identity uncertain | 28.27 / 0.47 m | See CSV | 43 ms | −27.5° | −9.73° | 0.10 m/s | Inactive |

## Evidence and pattern

Before takeoff, waiting/paddling occupies most of the sampled clip. No additional clear standing ride was identified in the coarse samples; brief attempts or wave pushes between samples cannot be excluded. The later samples show paddling/waiting again.

This ride has a coupled failure: shoreward acceleration, rapidly shrinking separation, and little drone translation initially. From 08:56:33.205 to 35.207, direct separation falls 26.07 → 24.17 → 18.39 m. The sampled closing rate grows approximately 0.89 → 1.90 → 5.77 m/s, while actual pitch stays near −25°. GPS age is only 13–69 ms at those points. Stale GPS is therefore not the leading explanation at this onset.

Retreat starts in the log at 08:56:35.729; ride detection is logged at 35.219. Poor lower-edge margin is already visible in the samples mapped to 33.205–34.206. At 38.210 the drone is moving about 4 m/s, but signed shoreward surfer speed is about 28.06 km/h (7.79 m/s): matching the commanded retreat speed alone cannot preserve separation during this part of the ride. Earlier movement could create a buffer; it is not proof the whole ride would be saved.

Pitch also matters: separation collapses while pitch remains −25°, then actual pitch steepens to −35° and subsequently about −55°. This is a framing/response sequence, not solely a distance threshold problem. Height here is above takeoff, not a verified height above water, so a precise camera-footprint limit cannot yet be inferred.

## Counterexamples and candidate rule

Paddling occurs well before this takeoff without a clear ride. Paddling alone does not establish ride intent. After the ride, logged detector speed reaches 10.61 km/h around 08:57:08 while the sequence is paddling/return, showing that total speed alone also cannot label a new takeoff. The CSV separates signed shoreward speed from logged detector speed; the latter may be frozen during the ride timer and must not be interpreted as live speed.

The candidate to evaluate on another clip is increasing shoreward motion combined with decreasing framing reserve: closing rate, actual pitch/height geometry, yaw error, GPS uncertainty and measured drone response. Activate retreat on predicted loss of framing reserve before the distance trigger, then assess false activations during paddling and wave pushback. No numerical threshold is selected or validated from this single clear takeoff. Exact missed-ride ground truth and false-warning counts require denser visual labels across the other clips.

Files: frame_telemetry.csv contains flattened metrics and sampled acceleration; frame_telemetry_metadata.json preserves raw matched records, match time offsets, visual labels and uncertainty. The overview and takeoff_detail images provide an audit trail. VT code and settings were not changed.
