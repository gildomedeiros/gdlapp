# VT 4.0.8 — supplied flight logs, 8 October 2026

Read-only analysis of all eight JSONL files in C:/Users/gildo/temp/408. All record version 4.0.8 and parse without malformed lines. No source, settings or APK changes made during this analysis.

## Outcome

The four active sessions contain 34 diagonal journeys: 27 arrived, two cancelled for retreat, three cancelled for the configured no-ride reset, one cancelled when the screen closed and one cancelled for aircraft RTH. No route recovery failure, start-beachward rejection or timed-out diagonal journey was recorded. Journey ids persist into hold/return, so counts use explicit journey_outcome events rather than treating every row with a journey id as active movement.

| Log start | Result |
|---|---|
| 09:31:36 | VT not started; no translation commands. |
| 09:31:51 | VT not started; no translation commands. |
| 09:32:57 | −45°, 300 s no-ride timeout. Five arrivals; retreat interrupted one journey; screen closure cancelled one. |
| 09:37:51 | VT not started; no translation commands. |
| 09:39:04 | +45°, 60 s timeout. Four arrivals; retreat interrupted one journey; reset skipped movement when already in stand-off zone. |
| 09:47:07 | Start rejected at 09:47:54.860: noRideTimeoutSeconds outside allowed 60..7200. No translation. One earlier LoRa IOException, followed by packets. Log does not expose the rejected numeric value. |
| 09:48:45 | +45°, 60 s timeout. Nine arrivals; one journey replaced by automatic return; return completed. |
| 09:52:11 | −45°, 60 s timeout. Nine arrivals; two journeys replaced by no-ride reset; one cancelled for RC_ONE_KEY_GO_HOME. One moving return completed, plus two stand-off-zone resets without travel. |

## Boundary recovery

At 09:34:05.110 the reported drone was 0.217 m beachward. Ten submitted Diagonal commands continued toward a permitted waypoint; the journey later yielded to live-surfer retreat at 09:34:23.346, not to a boundary lockout. Subsequent journeys continued and arrived.

At 09:41:14.987 the reported drone was 0.146 m beachward. Nine submitted recovery commands continued; journey 4 reached its destination at 09:41:27.680. Neither episode produced the old start_beachward_of_boundary rejection.

All 19 submitted commands while beachward had a positive sea-side velocity component. No beachward-start soft return occurred in these logs, so the separate return-recovery branch remains covered by desktop tests rather than this field run.

## Automatic soft return

| Return | Start | Target remained fixed | Completion | Remaining distance | Actual boundary distance at completion |
|---|---|---|---|---|---|
| First | 09:50:30.760 | Yes | 09:50:43.094 | 0.973 m | +10.681 m |
| Second | 09:56:20.177 | Yes | 09:56:37.540 | 0.933 m | +10.493 m |

Both use the configured 10 m stand-off and existing 1 m completion tolerance. Current surfer remained outside the 19 m retreat threshold throughout submitted return movement (minimum 35.747 m and 33.147 m respectively).

Second return spent approximately 11 s aligning its saved return heading before translation. This is existing return-yaw behaviour, not a blocked route: heading changed from 104.2° to about −3.9°, followed by movement and completion.

At 09:40:59.481, 09:53:48.192 and 09:55:11.152 a due return completed its reset without travel because the drone was already within 0..10 m sea-side of the boundary (+5.314 m, +5.935 m and +9.902 m).

## Other holds and pauses

463 movement cycles report destination_central_boundary (401 in the 09:32:57 log, 62 in the 09:39:04 log). These reject a requested P3 beachward of the boundary, not a beachward drone origin. This is the accepted destination restriction. No remaining-leg boundary failure is recorded.

Initial close-surfer retreat in the 09:32:57 session was blocked by the boundary: distance began at 0.251 m and the backward direction pointed beachward from the central line. It remained blocked until the current surfer distance increased to 21.726 m; then the existing cooldown completed and positioning began. Another short retreat near the boundary was similarly blocked. The 09:48:45 session began with boundary-blocked retreat until the surfer moved outside the threshold. These are existing retreat protections, not failures of the new positioning recovery.

At 09:35:52.386 the session paused for hover and resumed at 09:35:56.154. The saved journey subsequently arrived. Brief individual zero submissions while movement was planned also occurred, without persistent lockout; the longest consecutive cluster belonged to this explicit hover/recovery pause.

At 09:56:54.188 authority reported RC_ONE_KEY_GO_HOME. VT cancelled journey 12 with returning_home and stopped, as intended. This was aircraft RTH, not automatic VT return.

## Independent movement audit

Recomputed local straight-segment distance from actual reported drone position to next waypoint around captured S0 for every submitted diagonal movement command. Minimum remaining-leg clearance was about 21.688 m, above both the configured 21 m planning clearance and 19 m execution threshold. Existing escape exceptions were treated separately.

No submitted ordinary positioning command violated the logged S0 execution clearance, excursion stop or movement speed cap. No submitted ordinary positioning command occurred with the logged current surfer inside the 19 m retreat threshold. Boundary recovery command directions passed the independent sea-normal calculation. Both returns retained one fixed target.

These checks assess recorded inputs and submitted commands. They do not establish physical obstacle clearance, GPS accuracy or unrecorded flight behaviour.

## Issues found

One explicit configuration error prevented the 09:47:07 session from starting: noRideTimeoutSeconds must be 60..7200. Later sessions successfully loaded 60 s. A preceding LoRa connection IOException recovered sufficiently to receive packets. No additional persistent route or soft-return fault was identified in the active sessions.
