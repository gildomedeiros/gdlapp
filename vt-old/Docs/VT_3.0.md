# VT 3.0 - responsive surfer aiming without rapid reversals

Version name 3.0, Android versionCode 14. Implemented in the existing vt-28 worktree.
The original local project is not modified or deployed by this work.

## Control ownership

AimingSession.tick validates control and inputs, updates the movement state machine,
then selects exactly one yaw owner. APPROACHING and RETURNING retain their fixed headings,
3-degree initial alignment and existing travel completion rules. They never use the
surfer reverse block. Otherwise SurferYawController uses the shortest signed bearing error.
No long-route turn, Nearby direction votes, Nearby UI toggle or distance speed boost remains.
All yaw uses the existing normal limit 8 deg/s and acceleration 4 deg/s^2.

## Two separate ride decisions

RideDetector observes validated GPS inputs even with Come to me disabled or STOPPED.

- One-second speed at/above the configured start threshold latches fast riding. This enables
  small-angle surfer corrections and immediately cancels an approach, but preserves any
  active No-ride countdown. The threshold remains configurable (default 18 km/h).
- Five-second speed at/above that same threshold confirms the ride for return. Only this
  confirmation cancels the No-ride countdown. It is a displacement window, not an additional
  five seconds of required continuous high speed after fast detection.
- Fresh one-second speed below the configured end threshold continuously for the configured
  interval ends the riding mode. Defaults remain below 8 km/h for 30 seconds.
- A fast-only ride ending returns to normal aiming and lineup qualification without a return.
  A confirmed ride ending requests the existing return to central, if movement is enabled.
- Missing data, invalid speed evidence or rejected jumps restart low-speed confirmation;
  they do not prove that a ride ended. Manual reposition/Stop starts a fresh ride history.
- The No-ride timeout remains independent of fast-only detections and can still expire.

## GPS timing and duplicate coordinates

The LoRa protocol supplies a packet sender timestamp, not an independent GPS fix identifier.
Consequently identical coordinates cannot safely be discarded as duplicates: a stationary
surfer also sends identical coordinates and must eventually satisfy ride-end confirmation.
Both speed windows retain fresh stationary packets. References are the newest samples at
least 1 s or 5 s old. Accepted spans are 1-2 s and 5-7 s respectively; actual spans are logged.
The 40 km/h rejection uses at least a 1 s reference (up to 3 s), never an adjacent repeated
0.5 s packet. This prevents artificially doubling speed for 1 Hz coordinates sent at 2 Hz.
It is a window-based correction, not perfect reconstruction of the receiver's GPS timing.
Raw jump history is retained to detect jumps back. Speed history and low-speed confirmation
are cleared on rejection. The aiming target itself is not filtered by this ride-speed check.
Sender clock resets or reception gaps over 3 s clear evidence. No gap counts as stationary.
A one-second average can still accept a brief position error below 40 km/h; the separate
five-second confirmation reduces its movement consequences without promising zero false rides.

## Aiming and reverse blocking

Normal aiming uses max(abs(error)-3, 0) * 0.5. Riding uses abs(error) * 0.5.
Both retain acceleration/rate caps. Exact alignment commands zero immediately.
The 2 s reverse block applies to all surfer aiming, not only riding:

1. A permitted nonzero correction refreshes that direction's timestamp on the control clock.
2. An opposite request before expiry commands zero and does not refresh the timestamp.
3. After expiry, the opposite direction is permitted if still required.
4. Aligned readings do not extend the block. Zero output does not erase direction memory.
5. Navigation, pause/manual takeover, Stop and ride-end transitions reset the surfer lock.

The block intentionally permits stop/start and same-direction speed variation. A real
reversal can be followed late. No camera-vision processing or target prediction is added.

## Telemetry thresholds

Retain the user's latest 2.9 changes: vertical 0.5 m/s, active translation horizontal 1.4 m/s,
stationary horizontal 0.5 m/s. Central-position capture now also uses vertical 0.5 rather
than the leftover 0.3 check. These limits do not change commanded translation speed (1 m/s).

## Troubleshooting evidence

Full logs identify version 3.0. Existing raw packet, readiness, control, pause, transition,
translation and command records remain. Join aiming_cycle and movement_cycle by session/cycleId.

- Fast/confirmation speed and actual span, state and event, configured thresholds and timers.
- New packet/repeated coordinates, sender timestamp, jump speed/span/limit/rejection count.
- Slow-confirmation reset reason; No-ride timer start/cancel reason and elapsed time.
- Raw shortest heading error, normal/riding/navigation mode, required/permitted direction,
  last permitted timestamp, block remaining, desired/requested/submitted yaw and rate limits.
- Horizontal/vertical velocity and active limits; final pre-send veto snapshot where available.
- Original central/band positions, frozen navigation plans, progress, deadlines and final miss distance.

lastPermittedDirectionMs uses the same monotonic clock as cycleAtMs, not the log writer's clock.
A submitted command is not proof of physical rotation or displacement.

## Validation

Run tools/test-aiming.ps1 (JDK 21). Retained safety/session, telemetry, log serialization,
command-axis and fixed movement tests plus VT 3.0 tests for:
normal/riding yaw, wraparound, reversal/expiry, pause/Stop, command limits, repeated coordinates,
stationary ride end, jumps, gaps/clocks, false fast rides preserving the No-ride timer,
early approach interruption, disabled movement and movement timeout isolation.

September 22 zip replay through the actual RideDetector found all six previously detected
ride candidates and no additional starts in the replayed active input sequence:

| Fast detection | Five-second confirmation | Earlier by |
| --- | --- | --- |
| 06:50:27.058 | 06:50:29.114 | 2.06 s |
| 06:53:31.564 | 06:53:35.046 | 3.48 s |
| 06:57:56.103 | 06:57:58.152 | 2.05 s |
| 07:02:43.102 | 07:02:44.125 | 1.02 s |
| 07:49:52.898 | 07:49:54.883 | 1.99 s |
| 07:52:53.825 | 07:52:54.863 | 1.04 s |

Mean lead approximately 1.94 s. Replay uses recorded GPS, settings and actual pause boundaries;
it cannot predict a different aircraft trajectory, camera framing or new control-induced pauses.
Device validation is still required. RTH release-confirmation technical debt remains deferred.

## Filming geometry and September 22 afternoon field observations

Status: operating guidance and observed VT 3.0 behavior, added after reviewing
3.0.zip and video subtitle timestamps. This section does not change control code.
Running on land was used to simulate ride starts; the marked starts below are not
verified surfing pop-ups. Test ride-start threshold was 5 km/h, not the 18 km/h default.

### Set up the filming geometry before changing detection logic

A, B and C show alternative positions of the SAME drone relative to the surfer.
Every arrow points toward the surfer. These are schematic top views, not scaled
FOV simulations. A and C are side/oblique views; B is the central view.

```text
A - DRONE TO THE LEFT

                   S  surfer
                /
             /  camera aims toward S
          D
-------------------------------------- shoreline

B - DRONE IN THE MIDDLE

                   S  surfer
                   ^
                   |
                   D
-------------------------------------- shoreline

C - DRONE TO THE RIGHT

                   S  surfer
                      \
                         \  camera aims toward S
                            D
-------------------------------------- shoreline
```

For the user's intended setup, A provides the front-facing view of the right-hander.
To obtain the equivalent front-facing view for a left-hander, the surfer paddles to
the other side of the drone before catching the wave. The drone can stay at the same
physical location: the surfer's new location makes the geometry C relative to them.
Right/left-hander denotes the surfer's riding direction, not screen-left/right or a
fixed clockwise/counterclockwise drone command. The actual ride path determines
whether the view is front, rear or side-on; these diagrams alone do not specify it.

VT 3.0 already aims toward the changed surfer position. A lineup-band exit while
holding starts a new qualification period. Once qualified, Come to me may approach
if needed. Therefore the drone can stay put during paddling, but automatic distance
adjustment may subsequently move it; VT does not orbit to choose a front/rear shot.

A ride proceeding mainly along the camera's view can remain framed longer during a
rotation delay than a ride crossing the image rapidly. Oblique viewing changes the
length of riding path covered by the camera, not the lens's FOV angle. A front-facing
pass can become harder to follow as the surfer gets closer. Vertical framing, camera
pitch, zoom and the actual ride path also matter.

Decision: test suitable starting geometry with existing VT 3.0 before complicating
ride detection or introducing a GO button. The earlier illustrative FOV distances
and drone rankings are not validated design requirements: the geometry and angle
reference changed during discussion. No universal A/B/C ranking is asserted here.

### Why rotation started late in the recorded tests

Sources: cam3_full_2026-09-22_170816_901_7aa1380e.jsonl and
cam3_full_2026-09-22_171558_580_bd7c48b0.jsonl in 3.0.zip; DJI_0400/0402 SRTs;
user-marked running-start frames. Log times below are September 22, 2026, UTC+10.
Playback times are rounded; command submission is not proof of physical rotation.

| Measurement | Ride 1: DJI_0400 | Ride 3: DJI_0402 |
| --- | --- | --- |
| User-marked physical start | 17:12:00.177 (03:02.20 playback) | 17:19:34.215 (00:32.89 playback) |
| Fast detection / first running-related yaw command | 17:12:02.926 | 17:19:36.974 |
| Delay from marked start | 2.75 s | 2.76 s |
| Drone-surfer distance at detection | 29.2 m | 19.4 m |
| Maximum aiming error during detected ride | 22.8 degrees | 43.9 degrees |
| Reverse block at detection | No | No |

Ride 1 detail (DJI_0400):

| Playback | Calculated surfer speed, preceding 1 s | Rightward aiming error | Submitted yaw / explanation |
| --- | --- | --- | --- |
| 03:01.93 | 3.53 km/h | 0.92 degrees | Zero: inside normal 3-degree tolerance |
| 03:02.44 | 3.53 km/h | 0.91 degrees | Zero: inside normal tolerance |
| 03:02.96 | 4.00 km/h | 0.13 degrees | Zero: inside normal tolerance |
| 03:03.90 | 3.53 km/h | 2.07 degrees | Zero: inside normal tolerance |
| 03:04.43 | 3.53 km/h | 1.96 degrees | Zero: inside normal tolerance |
| 03:04.95 | 11.33 km/h | 8.14 degrees | Right 0.41 deg/s: riding detected, acceleration limited |

Ride 1 positions DID change before detection. The early steps were approximately
0.98 m east, 1.11 m north, then 0.98 m west. They implied speed below 5 km/h and an
error inside the normal aiming tolerance, so VT correctly requested zero yaw from
those inputs. Low speed itself is not a yaw prohibition: normal aiming can turn
without a ride if the error exceeds 3 degrees. The decisive packet arrived at
17:12:02.869 and the command followed 57 ms later; no packet was missing in the
inspected interval. VT was not waiting for five-second ride confirmation.

Ride 3 instead kept receiving identical coordinates until the first changed position
that triggered detection. In both runs, packets arrived about twice per second,
while moving coordinates generally changed about once per second. Raw coordinates
had five decimal places, corresponding here to approximately 1.11 m latitude and
0.98 m longitude steps. Short-window displacement speeds and bearings consequently
changed in steps. Rounding, GNSS error and upstream position latency can contribute;
these logs do not isolate receiver smoothing, true fix age or physical trajectory.
Do not attribute the entire delay conclusively to GPS inaccuracy, or interpret the
similar 2.75 s delays as a programmed timer.

After detection, yaw still ramps at 4 deg/s^2 and is capped at 8 deg/s. The recorded
bearing changed about 8.5 deg/s over an early 3.02 s interval in ride 1, versus
13.3 deg/s over an early 3.98 s interval in ride 3. The closer ride therefore outran
the available yaw correction and developed a larger error. These are interval
averages of reported bearing, not independent measurements of true motion.

A separate cause appeared in ride 2 (DJI_0401): small preceding right corrections
refreshed the reverse lock. Fast detection at 17:14:24.887 required left rotation,
but the first left command was 17:14:26.623, 1.74 s later. This must not be confused
with the input/tolerance delays in rides 1 and 3.

Repeated coordinate activity offered an experimental early movement hint in ride 1
(three changes about one second apart, recognizable around playback 03:03.89).
It also occurred during slower movement and did not anticipate ride 3. It is not an
implemented or validated early ride detector. GO buttons, direction buttons, IMU
triggers and GNSS-reported speed were discussed only; none are added by this note.

