# cam3 pending enhancements

Updated: 2026-09-16. This file is the pending-work list; per-version detailed designs remain separate. Items below are not implemented unless explicitly marked complete.

## E01 â€” Recover after landing/RTH without restarting the app

Status: PENDING. Priority: fail-safe recovery. Target version: not yet assigned.

Observed in v2.2: after landing, disableVirtualStick returned CONTROL_AUTH_LANDING. After another RTH event, cam3 also remained RELEASE_UNCONFIRMED. No subsequent disabled-state callback settled those attempts.

Current cause:

- YawAimingController.callback().onFailure logs the SDK error but passes only false to AimingSession.
- AimingSession.settleRelease() then retains RELEASE_UNCONFIRMED and checks the saved Authority on each tick.
- releaseAttempted prevents another disable request. There is no fresh SDK query in this recovery path.
- AUTO_LANDING, APAS after landing, or isFlying=false does not by itself settle release.
- The process-scoped controller survives closing/reopening the flight screen, so that alone does not reset the unresolved session.

Current workaround: if the app remains RELEASE_UNCONFIRMED with Start disabled, fully terminate and relaunch cam3 after the aircraft is safely landed. The later process startup in the user's log successfully started another aiming session. Restart resets cam3's local session; it is not proof that a previous SDK control grant was released. All normal start checks still apply.

This is conditional, not a rule that every RTH or flight-mode change needs a restart. A later usable disabled-state observation can settle release without restarting. In the affected recorded runs, that observation did not arrive.

Required enhancement:

- Preserve structured disable error codes in the session, including CONTROL_AUTH_LANDING.
- Design bounded recovery using verified SDK capabilities and current aircraft/control evidence; do not assume an undocumented fresh-query API exists.
- Distinguish aiming commands stopped, aircraft operating under landing/RTH, and Virtual Stick release confirmed in UI/logs.
- Never auto-resume aiming, compete with landing/RTH, or blindly clear an unresolved grant merely because the aircraft is on the ground.
- Permit another explicit Start only after the defined recovery conditions are satisfied.

Acceptance: reproduce landing/RTH with missing state callbacks, recover without process restart when verified recovery conditions hold, and test late grants, failures and pilot takeover without any unintended yaw command.

## E02 â€” Shared distance rule and consistent reporting

Status: IMPLEMENTED locally in v2.3; device validation pending.

The user manually changed v2.2 to 10 m, then explicitly requested 5 m for v2.3. That latest instruction is integrated: YawAimingMath.MIN_AIMING_DISTANCE_METERS drives eligibility, Details, distance messages and logs. The former reporting discrepancy is removed.

Boundary/accuracy tests and release metadata were updated for v2.3. Below-distance conditions pause and recover after 2 s valid inputs. In v2.4, the same 5 m minimum remains, while the reported-accuracy gate is explicitly bypassed for both LoRa and phone target fixes; coordinate validity and freshness still apply.

See [v2.3 detailed design](detailed_design/detailed_design_v2.3.md) for source changes, validation and remaining device checks.

## E03 â€” Identify exactly which callback stopped aiming

Status: IMPLEMENTED locally in v2.3; device validation pending.

Named telemetry events distinguish flight mode, connection and pilot sticks. Listener logs include key and old/new values; state/control callbacks retain timestamps and session context. Pause/cancel latches run before logging. Coordinate privacy and bounded asynchronous logging remain.

Acceptance: a log can identify whether flight-mode AUTO_LANDING, pilot sticks, connection loss or an authority callback initiated a stop, without inferring it from later snapshots.

## E04 â€” Clarify last submitted command in logs

Status: IMPLEMENTED locally in v2.3; device validation pending.

Readiness logs now use lastSubmittedYawRate, lastCommandAgeMs and sending. Actual submissions update the timestamp; no zero is fabricated when none was sent. The first_command event still describes its single actual submission.

Acceptance: a stopped-state summary cannot be mistaken for evidence of continuing yaw commands.

## E05 â€” Clarify why the ownership field does not change

Status: PENDING investigation. Target version: not yet assigned.

Observed in the user's v2.2 logs: owner remains UNKNOWN through successful Virtual Stick enable, advanced-mode confirmation and successful disable. Separate MSDK_REQUEST reason callbacks are received. The expected RC/MSDK ownership transitions were not observed.

Investigation:

- Log the raw result of getCurrentFlightControlAuthorityOwner() before cam3 maps it. Distinguish null, SDK UNKNOWN and no state callback received yet.
- Correlate raw owner with enabled/advanced flags, authority-change reasons, callback sequence and SDK/aircraft firmware versions.
- Check DJI documentation and model/firmware support to determine whether this is reporting behavior, an SDK issue or an adapter issue. Do not label it a DJI bug without evidence.
- Document which control observations are usable on this device. Do not fabricate RC/MSDK values from enable success or change-reason callbacks.

Acceptance: explain the observed unchanged field with supporting evidence, or clearly record the remaining limitation and its effect on cam3's control checks.

## E06 â€” Reject implausible phone GPS jumps

Status: PENDING. Target version: not yet assigned.

Compare each new phone fix with the last accepted fix using distance, elapsed time, reported GPS uncertainty and a configurable maximum plausible surfer speed. Reject and log implausible jumps rather than immediately steering toward them. Thresholds and configuration scope must be defined in the implementing version's design.

Retain the last accepted position without refreshing its original timestamp. Define recovery so a bad initial fix or genuine GPS correction cannot prevent later good fixes. Since v2.3, stale fixes pause a live session with conditional recovery; this future filter must respect that policy and never restart a permanently stopped session. The user explicitly excluded this filter from v2.3.

Acceptance: test isolated spikes, repeated rejected fixes, ordinary surfer movement, uncertainty changes, invalid time intervals, stale-data stopping and recovery to consistent valid fixes. Include clear rejection/recovery diagnostics. This is a plausibility filter, not proof that accepted coordinates are correct; consistent position errors can still pass.

## E07 â€” Predict surfer movement during a nearby pass

Status: PENDING design. Target version: not yet assigned. No application behavior changed.

Estimate movement from the last few good phone GPS readings.

1. When too close, briefly aim toward the predicted position ahead of the surfer, rather than the noisy nearby GPS point.
2. Return to normal GPS aiming once separation improves.

Purpose: reduce lost tracking during a nearby sideways pass. The user accepts missing a surfer passing directly underneath the drone, especially because cam3 does not automatically tilt the gimbal; following that case is not required.

The implementing version must define what qualifies as good readings, prediction duration/confidence, entry and exit distances, and fallback to PAUSED when prediction is unreliable. Preserve yaw limits and permanent-stop/control-loss protections. Log prediction entry, fallback and return to normal GPS aiming, and display the current stage. This proposal does not change v2.3's 5 m gate or 2-second recovery timer now.

Acceptance: test nearby passes, noisy fixes, changes in direction/speed, prediction expiry and transition back to GPS aiming. Confirm STOP, RTH, landing and control loss cancel prediction. Record the detailed design in the implementing version's own file before implementation.

## E08 â€” Surfer signal to leave space ahead in the camera frame

Status: PENDING design. Target version: not yet assigned. No application behavior changed.

Allow the surfer to send cam3 a framing intention, such as "riding right; leave room ahead", potentially through a T1000-E button and the shore receiver. Cam3 would use the surfer's position and movement direction to place them off-centre with more visible space ahead of their ride.

- Define the direction reference explicitly. "My left" is ambiguous; right across the camera image differs from the surfer's own right or a compass direction.
- Example: riding right across the image means placing the surfer toward the left of the frame. Moving the subject left in the image generally requires panning the camera right.
- Investigate gimbal pan for composition. Horizontal dragging in the local FPV widget sends GimbalKey.KeyRotateBySpeed with yaw, and the user observed camera-only movement. Verify actual supported yaw range, feedback and sustained control on this aircraft/firmware before relying on it; the capability processor initially defaults to true.
- Use actual camera orientation, gimbal offset, field of view and zoom to calculate framing, with margin for GPS uncertainty and message delay. Define how gimbal movement and aircraft yaw aiming cooperate without fighting each other.
- Define message handling, acknowledgement, expiry, duplicates, ride-end/reset behaviour and fallback when inputs or gimbal control are unavailable. A framing signal must not start or reclaim flight control, bypass existing stop protections, or resume after STOP, RTH or landing.
- Display the requested framing and active/fallback stage; log received signals, decisions, SDK results and recovery actions.

Acceptance: test left/right rides, direction changes, stale/duplicate signals, pan limits, GPS uncertainty and manual intervention. Confirm permanent stops cancel automatic framing. Record the detailed design in the implementing version's own file before implementation.

## Completion tracking

E02â€“E04 are implemented locally in v2.3; source/method details and desktop validation are recorded in its design file. E01, E05, E06, E07 and E08 remain pending. All IDs and original problem history are retained. Actual hardware behavior still needs device checks.

## v2.4 implementation follow-up

LoRa Wi-Fi integration and the Phone GPS selector are implemented locally; see [v2.4 detailed design](detailed_design/detailed_design_v2.4.md) for files, methods, wire format, call hierarchy, accuracy bypass and failure behavior. APK build and aiming tests passed. Full Android lint failed; live phone/TTGO/DJI checks remain pending.

E06 and E07 retain their original phone-oriented proposals above. Neither is implemented by v2.4; any future design must explicitly define applicability to the selected LoRa/phone source. Neither GPS-jump filtering nor predicted positions are silently introduced by source selection. E01 and E05 are unchanged.

## v2.5 implementation follow-up

Full/minimal logging is implemented locally; see [v2.5 detailed design](detailed_design/detailed_design_v2.5.md). Full mode creates accessible JSONL session files with target coordinates/raw input. OFF retains private minimal gaps, invalid-data reasons and control/connection events. This supersedes the earlier diagnostic-only privacy/export behavior, without implementing GPS filtering, prediction, framing or release recovery. Existing pending enhancement IDs remain pending. Desktop tests pass; Android storage and flight-device validation remain pending.

## v2.7 worktree implementation follow-up

E07 is PARTIALLY ADDRESSED through the user-approved goalkeeper direction commitment, implemented directly from v2.5. This is neither forward prediction nor v2.6 coordinate smoothing. Both LoRa and phone sources use the same voting/lock rules. The 5 m gate is removed by explicit request. E06 raw outlier rejection, E01 control-release recovery and E08 framing remain pending. See [v2.7 detailed design](detailed_design/detailed_design_v2.7.md) for source methods, lifecycle, logging schema, tests and hardware limitations. Original local v2.5 remains unchanged.

## E09 â€” Increase acceptable target-coordinate age during poor LoRa reception

Status: PENDING design. Target version: not yet assigned. Requested 2026-09-18. No application behavior changed.

Current target GPS freshness limit is 3000 ms (3 seconds); an older target fix triggers stale_gps pause. Investigate allowing a longer, bounded age while LoRa reception is poor, to reduce interruptions during packet gaps.

Define how poor reception is detected (recent RSSI/SNR, loss and reception gaps), the extended maximum age, and when the normal 3-second limit returns. Choose these values during design; no new timeout is selected yet. Preserve the original fix timestamp and log the actual fix age, active limit and reason for extending it. Consider increasing position error while the surfer moves; a longer allowance does not make an old coordinate current.

Keep aircraft-telemetry freshness and STOP/RTH/landing/control-loss protections separate and unchanged. Define behavior after the extended limit expires and during recovery. Validate brief gaps, prolonged loss, changing signal quality and return to fresh readings before implementation is considered complete.

## E10 â€” Rename CAM3 references to VT (Virtual Tripod)

Status: PENDING. Target version: not yet assigned. Requested 2026-09-18. No application behavior changed.

Rename current product references from cam3/CAM3 to VT / Virtual Tripod. Review app display name, UI text, log labels and filenames, storage folder names, build artifact names, source comments/identifiers, project metadata and current documentation. Record any retained historical names explicitly so previous release documents and recorded logs remain understandable.

Treat application/package identity, DJI App Key registration, signing and existing log locations as compatibility decisions during implementation, rather than blindly replacing strings. Preserve existing saved logs and document how old/new names are handled. This item records the rename request only; no references are renamed yet.

## E11 â€” Offset camera aim to place the surfer to one side of the frame

Status: PENDING design. Target version: not yet assigned. Requested 2026-09-18. No application behavior changed. Related: E08 (surfer-requested framing).

Add a signed angular framing gap relative to the bearing from the drone to the surfer: desired camera bearing = surfer bearing + signed gap, wrapped through 360 degrees. The user describes the orientation as delayed while rotating clockwise, so the surfer remains on the right or left of the image instead of always being centred. This is a requested framing offset; an actual time delay is not specified.

Define the sign and desired frame side before implementation. With clockwise-positive bearings, lagging behind a clockwise-moving surfer means a negative gap and puts the surfer to the right of image centre. A positive gap points ahead clockwise and puts the surfer to the left. Define the corresponding anticlockwise behavior, gap magnitude/units and whether side selection is automatic or configurable. The user's phrase surfer + gap allows a signed gap; do not silently equate positive offset with lag.

Determine whether to apply the offset through the existing drone-yaw aim or supported gimbal yaw, accounting for the actual camera heading, field of view and zoom. Avoid competing yaw and gimbal controllers. Specify behavior on direction-lock capture/refresh/release and when the subject is stationary. Log raw surfer bearing, selected side, signed gap and desired camera bearing so framing can be reconstructed from a run.

Acceptance: demonstrate correct left/right frame placement for clockwise and anticlockwise movement, wraparound bearings, zero-gap centred behavior, and bounded transitions. Retain existing STOP, stale-input and control-loss handling. This item does not require the surfer button/protocol proposed in E08, and adds no autonomous following scope.

### E11 initial trigger idea â€” established movement before nearby entry

Initial proposal from the 2026-09-18 log/frame review; pending design, not an implementation decision.

- Enable framing only when the framing option is ON, aiming is active, target GPS is fresh and movement direction is established. For the reviewed anticlockwise pass, a candidate trigger is at least three Left votes exceeding Right votes; do not treat the default-Right fallback as confirmed movement. Define the corresponding confirmed clockwise trigger during design.
- Do not require crossing the 20 m direction-lock boundary to begin framing. In cam3_full_2026-09-18_064741_115_af21b666.jsonl, at 07:01:20.050 the subject was 38.55 m away with 0 Right / 3 Left votes. The Left lock was captured at 07:01:36.760, roughly 17 seconds later. The 38.55 m observation is evidence, not a proposed distance threshold.
- Once the nearby direction lock is captured, retain the selected framing side through brief vote changes. Define transitions and cancellation/recovery behavior before implementation.
- The user's intended placement for this pass is on the LEFT side of the image. Consider a comfortable target such as the left third, rather than the far-left edge; the exact target remains to be chosen. Control toward a selected framing position, not an offset that accumulates each cycle or blindly adds further rightward aim when the subject is already too far left.
- Frame 4 is timestamped 07:01:36.755. The nearest cycle at 07:01:36.762 records 19.21 m separation and the GPS target 9.29 degrees left of drone heading; the frame shows the person near the far-left edge. Near frames 1-3, the corresponding sampled heading errors are approximately 4.89, 5.44 and 7.93 degrees left. This shows existing off-centre tracking, not a calibrated relationship between yaw error and image position.
- The run does not log actual gimbal orientation or camera field of view. Establish the relationship among camera heading, gimbal pose, zoom and desired image position before selecting the angular gap. Validate that the offset does not push an already-left subject farther out of frame. Logs alone cannot determine an exact left-third offset or prove subject visibility.

## E12 â€” Log actual gimbal pitch

Status: PENDING. Target version: not yet assigned. Requested 2026-09-18. No application behavior changed. Related: E11 (framing offset).

Add the actual reported gimbal pitch (camera vertical angle) to Full Log so video frames and aiming-cycle tables can include camera tilt. Record degrees, the angle convention/reference supplied by the SDK, observation timestamp and age, and validity/unavailable status. Associate the pitch observation with each aiming_cycle using the latest available reading without pretending reused data is fresh. Use null when unavailable, not a fabricated zero.

Log reported orientation rather than assuming a commanded angle was reached. Keep SDK observation and asynchronous logging separate from steering; this enhancement does not add automatic gimbal control. Verify the supported pitch telemetry source and sign convention, then validate manual tilt changes, stale/missing readings and timestamp correlation with video frames.

## E13 â€” Enable Full Log and Nearby by default

Status: PENDING. Target version: not yet assigned. Requested 2026-09-18. No application behavior changed.

Make both the Full Log and Nearby options ON by default. Keep both UI controls available so the user can turn either option OFF. Enabling Nearby by default must not automatically start aiming; retain the existing Start action and control protections.

During implementation, define how defaults interact with previously saved user choices. Verify initial UI state matches the effective logging and nearby settings, Full Log automatically creates accessible session files, and both options can still be disabled using their existing controls.

## VT 2.8 implementation (isolated worktree)

Come-to-me movement, configuration, two screen statuses and structured movement logs
are implemented for review. See [detailed changes and verification](Docs/VT_2.8.md).
Existing historical enhancement entries above are retained. This build has not been
installed or flight-tested.


## VT 2.9

See [VT 2.9 changes](Docs/VT_2.9.md). Before release, review RTH control-release confirmation (`RELEASE_UNCONFIRMED`); deferred technical debt, not changed by this implementation.

## VT 3.1 - implemented in existing worktree; device validation pending

See [VT 3.1 summary and validation](Docs/VT_3.1.md). Retained-target aiming addresses
finishing corrections during surfer packet gaps; it does not filter GPS positions or
solve the observed camera/heading mismatch. Qualification is 20 s, default band 50 m,
GPS and ride credit freezes, GPS-only recovery has no extra dwell, and the configurable
re-approach margin defaults to 15 m. Existing saved lineup settings are preserved.
Manual/other recovery, movement GPS requirements and RTH release debt remain unchanged.


### VT 3.1 orientation logging addition

Full Log now includes `orientation_cycle`, joined by session/cycleId to aiming and movement.
It records raw SDK aircraft and main-gimbal pitch/roll/yaw (degrees), plus the explicit
gimbal yaw relative to aircraft heading. Raw gimbal yaw is not relabelled as camera
compass heading. Each source has availability, freshness, read error, request-start
elapsedRealtime timestamp and age; missing/non-finite angles are null, never zero.
Optional reads use the existing 500 ms polling cadence; snapshots are written each
control cycle. Repeated snapshots are not new sensor samples. Unsupported keys do not
gate aiming, movement, startup or recovery. No control behavior changed.


VT 3.1: Approach excursion limit increased from 200 m to 250 m from the saved central point.

VT 3.1 GPS completion update: qualification timer continues through packet gaps; new approach waits for fresh in-band GPS. Saved approach/return rotation and translation continue with fresh aircraft telemetry. Logs and regression scenarios cover both paths.

## VT 3.2 implemented

Zero-second qualification, no return/band recreation on ride end, one-metre approach/return completion tolerance, 249 m outward excursion stop and diagnostic residuals. Updated within 3.2: both directions cruise at 2 m/s with 0.25 m/s2 acceleration, slowdown from 10 m and preserved arrival speeds. See [release notes](Docs/VT_3.2.md) and [design](detailed_design/detailed_design_v3.2.md). Device validation pending; existing unrelated backlog retained.

## VT 3.3 — implemented locally

Fixed configurable 90-second ride duration; initial detection cancels no-ride timer; temporary foreground touch lock; recording-required Start; configurable close-range gimbal tilt with logs. See [release summary](Docs/VT_3.3.md). Device validation remains pending. Background operation and recording-stopped warning are excluded. Existing RTH release-confirmation work above remains pending.

### VT 3.3 absolute close-range pitch update
Replace additional tilt percentage with -90 to 0 degree close-range target (default -35). Separate preference migration, signed settings input and degree logs; retain 30/35 m hysteresis, original-pitch restoration and 3 m/s movement.

### VT 3.3 - configurable long-range pitch

Add Long-range pitch (-90 to 0 degrees, default -6) beside Close-range pitch. Apply long-range at startup and above 35 m; below 30 m use close-range; preserve mode between thresholds. Replace manual baseline capture, retain fresh-input/control gates and hardware limits. Persist both angles and log startup/transition commands and measured pitch. Navigation and version remain unchanged.

## VT 3.3 - height readout and configurable yaw limits

- Always-visible height strip above the VT footer, visible through the transparent touch lock. Height is barometric metres above takeoff, not terrain/water clearance. Optional SDK KeyAltitude polling at 500 ms; stale (>1500 ms), absent or nonfinite values show Height: —. Missing height never pauses flight.
- Each orientation_cycle records heightAboveTakeoffM, heightUnits, heightReference, heightSampleAtMs, heightAgeMs, heightFresh, heightAvailable and heightError. Stale finite values remain in logs with freshness false.
- Rotation speed configurable 1-30 deg/s (default 15), acceleration 0.5-30 deg/s squared (default 8). Captured per session for normal/ride aiming, saved approach and return headings. Preserve existing near-target slope, normal/navigation 3-degree tolerance, ride behavior and surfer-only reversal blocking. Actual configured limits recorded in aiming_cycle. Final command submission enforces session speed cap; factory enforces hard 30 deg/s ceiling.
- Long-range/close-range pitch defaults now -10/-25 degrees. Existing saved angles are preserved. New rotation preferences use 15/8 when absent. Settings editable only while stopped; Come to me toggle retains both pitch and rotation settings.
- No altitude commands added. Version stays 3.3/code 17. Tests cover configurable rate/acceleration limits and invalid values, unchanged near-target behavior and height freshness/formatting. Physical UI/flight validation remains required.

## VT 3.4 — automatic gimbal distance bands

JSON-configured bands with a 5 m outward buffer; automatic startup selection; full configuration, band-switch and gimbal-result logging. Replaces long/close pitch UI only. See [VT 3.4](Docs/VT_3.4.md). Device validation pending.

## VT 3.5 — timed backward retreat implemented locally

Independent configurable 25 m minimum / 10 s duration / 3 m/s fixed speed / 5 s Come to me cooldown. Immediate repeated retreats, retained GPS including NOFIX, active surfer aiming and gimbal, protection cancellation and structured retreat events. See [VT 3.5](Docs/VT_3.5.md). Flight validation pending.

## VT 3.6 — one 5 m approach margin

Status: IMPLEMENTED in VT 3.6 (2026-10-02). The configurable margin defaults to 5 m and applies to all approaches; stored user values are preserved.

Use one 5 m approach-start margin for both the first approach and all later approaches. Remove the separate fixed 2 m first-approach rule. The same margin applies after Stop/Start, manual repositioning and retreat; no first-versus-subsequent distinction.

Example: with filming distance 30 m, an approach may start only when actual separation is above 35 m, subject to the existing ride, GPS, cooldown and protection gates. The saved approach destination still uses filming distance 30 m; do not add the margin to the destination. Retreat distance and the 5-second Come to me cooldown remain separate and unchanged.

Acceptance: verify the same threshold at initial Start, after filming hold, after manual intervention, and after retreat/cooldown; equality at filming distance + 5 m does not start an approach. Preserve saved-destination navigation, aiming, gimbal behaviour and all existing protections. Current VT 3.5 source and APK remain unchanged by this backlog entry.
## VT 3.6 — retreat maximum and default 18 km/h

Status: IMPLEMENTED in VT 3.6 (2026-10-02). 18 km/h maximum/default; stored user settings are preserved. Prior VT 3.5 APK retained.

Raise the configurable fixed retreat speed maximum to 18 km/h (5 m/s), and set its default to that maximum. Keep retreat speed independent of Come to me speed, acceleration and arrival slowdown. VT 3.5 used a 3 m/s maximum/default; VT 3.6 now implements the higher retreat limit.

Implementation must update retreat configuration validation, settings UI/ranges, default preference loading, retreat command limits and the aircraft velocity allowance during authorized retreat so the new speed is not rejected by the existing 3 m/s movement envelope. Preserve approach/return speed limits and all other protection rules; do not globally raise unrelated movement limits. Log the configured retreat speed with explicit units. Preserve deliberately saved user speeds; use 5 m/s as the default when no retreat speed is stored.

Acceptance: verify 5 m/s is accepted and is the default, values above it are rejected, commanded retreat is not inadvertently capped at 3 m/s, fresh measured velocity is assessed against the appropriate retreat allowance, and non-retreat movement retains its existing limits. Timers, distance triggers, aiming/gimbal behaviour and Come to me cooldown remain unchanged.
## VT 3.6 — implemented locally

Immediate distance-limited approach/return commands with existing arrival slowdown, one configurable approach margin (default 5 m), retreat maximum/default 18 km/h (5 m/s), default separation 23 m and period 3 seconds. Existing 5-second cooldown and protection rules retained; higher telemetry allowance scoped to recent actual retreat submissions. See [VT 3.6](Docs/VT_3.6.md). Flight validation pending.

## Implemented in VT 3.7

External normal/ride rotation curves and a user-selected Download/VT configuration folder for both JSON files, with create-only migration preserving the old gimbal file. See [release notes](Docs/VT_3.7.md). Device validation pending.

## Implemented in VT 3.8

Retreat settings moved to a default-seeded JSON file without migrating saved retreat values. Starts without fresh GPS are configurable, default one; active timers finish and fresh fixes reset the allowance. All three JSON configurations must validate before Start, with visible errors and no fallback. See [release notes](Docs/VT_3.8.md).

## VT 4.0 — fixed shoreline presets and JSON-only movement tuning

Implemented in the existing working tree (2026-10-03), versionName 4.0 / versionCode 23. Front and Sideways presets compute one fixed endpoint per fresh planning fix. Two-axis BODY velocity keeps surfer yaw/gimbal active. Alignment-only travel preserves the planning projected separation; wrong-side or retreat-circle-crossing routes hold. Retreat retains direct horizontal distance, body-backward timing and cooldown; saved returns retain navigation ownership.

All movement/ride/yaw-limit tuning moves to `vt_settings.json`, with no preference migration or tuning form. `vt_shorelines.json` stores named A/B lines and explicit seaward side. LoRa stationary A/B capture, manual entry, straight-line preview, Maps point links and saved selection are operational stopped-only UI. Existing JSON is preserved, all five files validate before control acquisition, and missing/malformed configuration visibly blocks Start. Both modes enforce filming >= retreat + 5 m. Default filming 28 m matches retreat 23 m; first movement requires selecting a shoreline.

Validation: complete `tools/test-aiming.ps1` suite passed, including 105 VT 4.0 checks and independently parsed movement logs. Offline `:sample:assembleDebug` succeeded. VT 3.8 APK preserved in `build/releases/VT-3.8.apk`; VT 4.0 APK in `build/releases/VT-4.0.apk`. Physical BODY axis direction and flight behavior remain unverified.

See [VT 4.0 behavior/setup](Docs/VT_4.0.md) and [VT 4.0 design](detailed_design/detailed_design_v4.0.md).


# VT 4.0.5

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
