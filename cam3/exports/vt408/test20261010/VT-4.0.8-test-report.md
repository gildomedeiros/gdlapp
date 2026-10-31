# VT 4.0.8 — 10 October 2026 test archive

Source: `C:/Users/gildo/temp/CAM3/cam3_full_2026-10-10_153736_128_a258698f.zip`.

All eight archived JSONL files were read and parsed successfully. All identify version 4.0.8. Five logs contain no VT activation or translation; three contain active sessions. This is recorded-flight analysis, not a new aircraft simulation or software regression run. No application code or user settings changed.

## Effective configuration

All three active sessions used filmingSeparationMetres 31, minimumDistanceMetres 25, reapproachMarginMetres 5, routePlanningAllowanceMetres 2, maxExcursionMetres 300, returnBoundaryStandOffMetres 10, maximum movement speed 4 m/s, yaw limit 15°/s and yaw acceleration 8°/s². Selected waveLineId was 9b9655d0-b8f9-474d-9887-f53151950cdd. Loaded gimbal bands and rotation curves exactly match the provided C:/VT408_ files.

| Active log | Angle | No-ride timeout | Journeys |
|---|---:|---:|---|
| 15:39:24 | −45° | 300 s | 8 arrived; 1 cancelled on screen closure |
| 15:45:04 | +45° | 300 s | 5 arrived; 1 cancelled for retreat |
| 15:48:48 | +45° | 60 s | 6 arrived; 1 cancelled for automatic return |

Total: 22 journeys, 19 arrivals and three explicit cancellations. Nine, six and seven routes respectively were planned. Route types across the active logs: 18 direct and four around. No journey timed out, remaining-leg route block or route replanning failure was logged.

## Movement audit

Independently recomputed the remaining straight segment's minimum distance to captured S0 for every submitted diagonal approach command. Lowest clearance per active session: 30.263 m, 26.980 m and 27.694 m. All remain above the 25 m execution limit. The 26.980 m value is an actual remaining execution leg; new routes use 27 m planning clearance, and execution intentionally permits the configured 2 m allowance to be consumed.

No submitted ordinary positioning command exceeded the 4 m/s cap or excursion stop, or occurred while the logged current surfer was inside the enabled 25 m retreat threshold. Thirty-nine submitted Come-to-me commands recovered from beachward reported positions. Eleven retreat commands also began beachward and pointed sea-side. All those commands had a positive sea-side component. No beachward soft-return recovery occurred in this archive.

These are checks of logged inputs and submitted commands, not verification of physical obstacle clearance or GPS accuracy.

## Automatic Return to central

At 15:50:34.861 the configured 60 s no-ride timer cancelled journey 6 and initiated return. It aligned its return heading until approximately 15:50:42.435, then moved directly toward one fixed target. Return completed at 15:51:05.970 (movement cycle timestamp 15:51:05.972):

- Reported drone distance to saved target: 0.983 m, within existing 1 m completion tolerance.
- Reported boundary distance: +10.908 m sea-side, consistent with configured 10 m stand-off.
- Current surfer during submitted return movement: minimum 35.485 m, outside 25 m retreat threshold.
- Ordinary positioning restarted at 15:51:06.129; journey 7 arrived at 15:51:47.315.

This is the agreed early soft reset, not travel all the way to the central pin.

## Holds and retreat

The first active session had 548 cycles rejecting a beachward final destination. Windows: 15:40:37.320–15:40:56.400, 15:42:54.124–15:42:56.890, and 15:43:06.034–15:43:42.425. This is the accepted destination rule; a permitted recovery from a beachward drone does not permit a beachward P3. It is not the old start-position lockout.

At 15:42:00.233–15:42:02.474, 22 cycles held with destination_inside_planning_clearance. Current drone–surfer separation fell from 26.148 m to 25.087 m. Alignment at that distance would preserve the current radius, placing the proposed destination inside the 27 m planning circle. VT held rather than moving outward. At 15:42:02.576 separation was 24.106 m and existing retreat started. This is a real brief hold between the 25 m retreat trigger and 27 m ordinary planning requirement; no persistent deadlock occurred.

Nine retreat starts occurred in the first active session; one in the second. Several backward retreats were stopped/slowed by the original boundary. The second active session's retreat at 15:45:24.524 cancelled journey 1; it was boundary-blocked at 15:45:26.715. Surfer separation became sufficient at 15:45:57.117, then positioning resumed after cooldown, including beachward recovery. These are existing retreat-priority and direction rules.

## Observations requiring distinction

1. **Landing SDK release warning:** pilot-stick input paused VT at 15:51:52.943, followed by further pilot/hover inputs. Landing stopped VT at 15:52:09.672. DJI replied to the disable-control request at 15:52:09.700 with success=false, CONTROL_AUTH_LANDING. VT entered RELEASE_UNCONFIRMED and remained there until screen closure/log end at 15:52:15.704. Recorded command submission was suppressed. The log does not confirm successful SDK release before its end. This is a control-release confirmation warning, not an active journey lockout or evidence of continued VT flight commands.
2. **Pre-start telemetry availability:** 14 aircraft-position retry timeouts occurred at 15:39:28.728–15:39:55.445, before VT started at approximately 15:40:07. Telemetry later became available and the session activated. No active-flight stale-position pause was recorded in that session.
3. **No configuration rejection or LoRa connection exception** was recorded in this archive.

No source fix is proposed or applied by this report. The landing disable failure deserves separate review if improving SDK-release status handling is requested.
