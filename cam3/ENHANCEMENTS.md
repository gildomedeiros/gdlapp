# cam3 pending enhancements

Updated: 2026-09-15. This file is the pending-work list; per-version detailed designs remain separate. Items below are not implemented unless explicitly marked complete.

## E01 — Recover after landing/RTH without restarting the app

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

## E02 — Shared distance rule and consistent reporting

Status: IMPLEMENTED locally in v2.3; device validation pending.

The user manually changed v2.2 to 10 m, then explicitly requested 5 m for v2.3. That latest instruction is integrated: YawAimingMath.MIN_AIMING_DISTANCE_METERS drives eligibility, Details, distance messages and logs. The former reporting discrepancy is removed.

Boundary/accuracy tests and release metadata are updated. Other quality limits remain. Below-distance conditions now pause and recover after 2 s valid inputs.

See [v2.3 detailed design](detailed_design/detailed_design_v2.3.md) for source changes, validation and remaining device checks.

## E03 — Identify exactly which callback stopped aiming

Status: IMPLEMENTED locally in v2.3; device validation pending.

Named telemetry events distinguish flight mode, connection and pilot sticks. Listener logs include key and old/new values; state/control callbacks retain timestamps and session context. Pause/cancel latches run before logging. Coordinate privacy and bounded asynchronous logging remain.

Acceptance: a log can identify whether flight-mode AUTO_LANDING, pilot sticks, connection loss or an authority callback initiated a stop, without inferring it from later snapshots.

## E04 — Clarify last submitted command in logs

Status: IMPLEMENTED locally in v2.3; device validation pending.

Readiness logs now use lastSubmittedYawRate, lastCommandAgeMs and sending. Actual submissions update the timestamp; no zero is fabricated when none was sent. The first_command event still describes its single actual submission.

Acceptance: a stopped-state summary cannot be mistaken for evidence of continuing yaw commands.

## E05 — Clarify why the ownership field does not change

Status: PENDING investigation. Target version: not yet assigned.

Observed in the user's v2.2 logs: owner remains UNKNOWN through successful Virtual Stick enable, advanced-mode confirmation and successful disable. Separate MSDK_REQUEST reason callbacks are received. The expected RC/MSDK ownership transitions were not observed.

Investigation:

- Log the raw result of getCurrentFlightControlAuthorityOwner() before cam3 maps it. Distinguish null, SDK UNKNOWN and no state callback received yet.
- Correlate raw owner with enabled/advanced flags, authority-change reasons, callback sequence and SDK/aircraft firmware versions.
- Check DJI documentation and model/firmware support to determine whether this is reporting behavior, an SDK issue or an adapter issue. Do not label it a DJI bug without evidence.
- Document which control observations are usable on this device. Do not fabricate RC/MSDK values from enable success or change-reason callbacks.

Acceptance: explain the observed unchanged field with supporting evidence, or clearly record the remaining limitation and its effect on cam3's control checks.

## E06 — Reject implausible phone GPS jumps

Status: PENDING. Target version: not yet assigned.

Compare each new phone fix with the last accepted fix using distance, elapsed time, reported GPS uncertainty and a configurable maximum plausible surfer speed. Reject and log implausible jumps rather than immediately steering toward them. Thresholds and configuration scope must be defined in the implementing version's design.

Retain the last accepted position without refreshing its original timestamp. Define recovery so a bad initial fix or genuine GPS correction cannot prevent later good fixes. Since v2.3, stale fixes pause a live session with conditional recovery; this future filter must respect that policy and never restart a permanently stopped session. The user explicitly excluded this filter from v2.3.

Acceptance: test isolated spikes, repeated rejected fixes, ordinary surfer movement, uncertainty changes, invalid time intervals, stale-data stopping and recovery to consistent valid fixes. Include clear rejection/recovery diagnostics. This is a plausibility filter, not proof that accepted coordinates are correct; consistent position errors can still pass.

## Completion tracking

E02–E04 are implemented locally in v2.3; source/method details and desktop validation are recorded in its design file. E01, E05 and E06 remain pending. All IDs and original problem history are retained. Actual hardware behavior still needs device checks.
