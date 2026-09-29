# cam3 v2.2 detailed design — APAS hover and control request flow

Status: IMPLEMENTED LOCALLY; final build verification recorded in section 11. Device validation pending.
Recorded: 2026-09-15 (Australia/Brisbane).
Workspace: C:/Users/gildo/gdlapp/cam3. Local work only.
Baseline: locally implemented v2.1, DJI SDK 5.18.0.
App version: versionName 2.2, versionCode 4.

Preserve v2.0 and v2.1 design files. Every existing-code edit has a nearby CAM3 v2.2 comment explaining the change and reason. Fail-safe behavior remains the priority. Sections 1–10 preserve the agreed design; section 11 records implementation decisions and verification and takes precedence where implementation details differ. This document does not claim flight validation.

## 1. Observed problem

The user's exported v2.1 log, cam3-v2.1-aiming-1789440862716.txt, shows:

- From 12:53:59, isFlying=true and flightMode=APAS, rejected as flight_mode_rejected. Later velocity and sticks are zero.
- Cam3's owner value remains UNKNOWN. No start/enable request is recorded; the log does not establish what DJI would report after an enable request.
- Phone accuracy later remains approximately 21.5–50 m, exceeding the existing 10 m limit. Later separation is approximately 4–7 m, also below the bearing-quality requirement.

These are separate blockers. Correcting APAS handling and startup does not make the recorded phone location suitable for aiming.

## 2. User-visible outcome and scope

The user presses START AIMING while hovering with usable telemetry and phone location. Cam3 may request Virtual Stick when its initial ownership information is unavailable. Cam3 waits for the enable request to succeed and for an SDK state update confirming Virtual Stick and advanced mode enabled. It then sends yaw-only commands, provided no takeover or other stop condition has occurred.

Cam3 blocks/stops only its own aiming during takeoff, RTH, landing or unsupported flight states. It never blocks those aircraft operations. It does not automatically reclaim control or restart aiming.

Proposed changes:

1. Accept APAS as an eligible reported hover mode, subject to the remaining flight checks and compatibility verification below.
2. Remove the requirement to observe RC ownership before requesting Virtual Stick.
3. Use enable success and current enabled/advanced state to activate aiming; a missing ownership value alone does not block activation.
4. Treat explicit contradictory ownership, control-loss reasons, disabled flags and existing safety conditions as stop conditions.
5. Log the entire request/callback sequence and show all independently assessable readiness blockers.

No new translation, gimbal automation, CV, automatic retry, automatic takeoff or GPS-threshold relaxation is included.

## 3. SDK contract versus cam3 policy

DJI references:

- [Virtual Stick manager and authority reasons](https://developer.dji.com/api-reference-v5/android-api/Components/IVirtualStickManager/IVirtualStickManager.html)
- [Virtual Stick state listener](https://developer.dji.com/api-reference-v5/android-api/Components/IVirtualStickManager/IVirtualStickManager_VirtualStickStateListener.html)
- [Virtual Stick state getters](https://developer.dji.com/api-reference-v5/android-api/Components/IVirtualStickManager/IVirtualStickManager_VirtualStickState.html)

| API | Meaning / use |
|---|---|
| setVirtualStickStateListener(listener) | Register before requesting control. DJI delivers state/reason callbacks. |
| enableVirtualStick(completion) | Request enable; onSuccess/onFailure reports the request result. |
| setVirtualStickAdvancedModeEnabled(true) | Request advanced mode; this method has no completion callback. |
| onVirtualStickStateUpdate(state) | Read isVirtualStickEnable(), isVirtualStickAdvancedModeEnabled(), getCurrentFlightControlAuthorityOwner() from the supplied object. These getters do not initiate a fresh aircraft query. |
| onChangeReasonUpdate(reason) | Receive the reason for an authority change. |
| disableVirtualStick(completion) | Request release; completion is distinct from physical aircraft stopping. |

Waiting for a separate MSDK-owner notification is an additional cam3 policy, not an explicit requirement found in the referenced DJI documentation. v2.2 proposes removing it as a positive prerequisite, while preserving negative evidence of takeover as a veto.

Do not promise callback ordering, periodic state delivery or immediate state delivery on registration; these are not established by the cited interface. State updates and completion callbacks may arrive in either order.

UNKNOWN in the log may mean no state callback has yet been received. The current adapter also maps an SDK UNKNOWN value. v2.2 must distinguish these cases rather than label both as a confirmed aircraft transition. A null/unrecognized owner is unavailable evidence, not RC or MSDK.

### Ownership flow requested by the user

```text
You control the drone
    Expected owner: RC (cam3 may not yet have received this)
          |
You press START AIMING
    Cam3 calls enableVirtualStick(...)
          |
DJI grants control
    Enable callback: onSuccess()
    State callback: owner = MSDK
    Reason callback: MSDK_REQUEST
          |
Cam3 calls setVirtualStickAdvancedModeEnabled(true)
    State callback: enabled = true, advanced = true
          |
Cam3 sends aiming commands
          |
You press the remote pause button, or RTH starts
    Reason callback: RC_PAUSE_STOP or RC_ONE_KEY_GO_HOME
    State callback: owner = RC
          |
Cam3 stops aiming; it does not automatically reclaim control
```

This is the expected logical flow, not a guaranteed callback sequence. MSDK_REQUEST and a positive MSDK owner report are logged when delivered; neither substitutes for enable success or advanced-mode confirmation. An explicit RC/OTHER report after acquisition begins must not be ignored merely because enable previously succeeded.

## 4. Request and command eligibility

### Before START

- Retain valid/fresh aircraft and phone inputs, neutral pilot sticks, eligible hover and no unresolved prior session.
- Initial RC ownership with Virtual Stick disabled is acceptable.
- No ownership observation, or UNKNOWN ownership without an observed enabled session, is acceptable for an explicit request. Log the assumption; do not pretend UNKNOWN means RC.
- An existing enabled Virtual Stick session or known OTHER/MSDK owner without a cam3 session remains a blocker. Do not silently take over another SDK feature.
- Listener registration must have succeeded. A thrown registration must leave listening=false so readiness cannot claim monitoring is active.

### STARTING

1. Allocate session token; record listener generation, state sequence and request time.
2. Call enableVirtualStick once. Keep all yaw commands suppressed.
3. On success for this live session, request advanced mode once, without waiting for owner=MSDK.
4. Require a state callback received after the advanced request began with enabled=true and advanced=true. Capture the sequence boundary before the call so a synchronous callback is not lost. Pre-request cached true flags are insufficient.
5. Start only if enable succeeded, that state confirmation exists, inputs remain valid, and no cancellation/takeover is latched. UNKNOWN owner alone is not a blocker; explicit RC/OTHER in a state update during this attempt cancels conservatively. A pre-request RC snapshot is not such an event.
6. Retain the existing total five-second startup timeout. Report which confirmation was missing. No callback or advanced-state update means no aiming commands.

Advanced and ownership fields come in the same state object. It is possible that the advanced confirmation also resolves the owner; the log will establish this. Removing the owner check cannot solve a completely absent state listener.

### AIMING

- Share one command-eligibility predicate between the session and controller. Require the live session's enable success, confirmed enabled/advanced state, valid inputs and no cancellation.
- Explicit RC/OTHER, enabled=false, advanced=false, or a non-MSDK_REQUEST authority reason stops the session. UNKNOWN after previously observing MSDK also stops conservatively because previously available ownership evidence has been lost. Initial UNKNOWN that remains UNKNOWN is logged under the proposed startup policy.
- A later MSDK_REQUEST or MSDK state never clears a stop latch. Only another explicit Start after settled release can create a session.
- Preserve immediate cancellation before diagnostic I/O, single control executor, final pre-send recheck and lifecycle/pilot/RTH protections.
- State callback age is logged, but not treated as a periodic heartbeat: DJI documents change callbacks, not a fixed update frequency.

### Stop, failure and late callbacks

- Latch cancellation first and cease normal yaw commands.
- When this session may have acquired Virtual Stick and no explicit handover to another controller is known, issue one disable request even if ownership remains UNKNOWN. This fixes the old cleanup path that could only disable after observing MSDK.
- A neutral yaw command is optional only under the existing fresh/safe-neutral conditions with positively observed MSDK ownership and enabled/advanced state. Do not send neutral during RTH, landing or reported takeover. Disable cleanup does not depend on sending neutral.
- If RC/OTHER has explicitly taken over, yield without competing commands. Record whether Virtual Stick disable/release is actually confirmed; contradictory enabled state remains unresolved.
- Release is settled only after no enable request remains pending and a post-attempt disabled state is observed, with no contradictory new grant. Disable success alone is logged as API completion, not physical stop.
- If release cannot be confirmed within five seconds, retain RELEASE_UNCONFIRMED and block another Start. Keep the listener for late results.
- A late enable success after Stop/timeout triggers cleanup, never advanced mode or aiming. If an earlier cleanup finished before that late grant, allow one new cleanup for that grant; do not retry disable continuously.
- Session tokens protect completion callbacks; listener generations reject callbacks from detached listeners. Global state callbacks have no SDK request token, so do not falsely assign them to a request. Suppress overlapping attempts until cleanup is resolved.

## 5. APAS flight-state policy

AircraftAimingTelemetry.allowedMode currently permits only GPS_NORMAL and VIRTUAL_STICK. Proposed eligible reported values are GPS_NORMAL, APAS and VIRTUAL_STICK, with the other safety gates still required. VIRTUAL_STICK does not permit attaching to an unrelated active SDK session.

Use the same classification in getSnapshot(), urgent mode listeners and safe-to-neutral calculation; otherwise an accepted APAS snapshot could still trigger a listener stop. Keep unknown/null and all unreviewed modes rejected. In particular AUTO_TAKE_OFF, motor-start, RTH and landing states must stop/prevent cam3 aiming.

Implementation verification must inspect SDK 5.18 FlightMode and KeyFCFlightMode/FCFlightMode semantics to establish whether APAS alone distinguishes the intended normal hover. If an additional mode key is needed, document its exact supported values and freshness checks here before using it. Do not infer Normal from the UI label or accept every flight mode.

DJI's linked Virtual Stick documentation does not list Mini 3 Pro among aircraft supporting obstacle avoidance under Virtual Stick. APAS eligibility must not be described as guaranteed obstacle protection during aiming. This limitation also applies when requesting Virtual Stick from GPS_NORMAL.

## 6. Detailed source-file and method changes (planned)

Paths below are relative to the workspace. The aiming package root is:
SampleCode-V5/android-sdk-v5-uxsdk/src/main/java/dji/v5/ux/sample/showcase/defaultlayout/aiming/.

| File | Planned changes |
|---|---|
| aiming/AircraftAimingTelemetry.java | Update allowedMode, start's mode listener, getSnapshot and diagnostics together. Produce all independently assessable blocker results instead of only first failure. Preserve field read timestamps/errors and null handling. |
| aiming/AimingSession.java | Extend Authority with observation presence, sequence, generation and arrival time. Update canStart, startAiming, tick, maySendYaw and settleRelease for sections 4–5. Store request/advanced sequence boundaries, enable completion, takeover latch and cleanup identity. Centralize command eligibility; distinguish unavailable owner from reported RC. |
| aiming/YawAimingController.java | Fix resume listener registration bookkeeping; timestamp/sequence state callbacks; log registration and raw state receipt; preserve raw owner separately from effective cancellation. Update both callbacks, authority(), advanced(), sendYaw(), tick and recordDiagnostics. Remove duplicated unconditional owner==MSDK send restriction only as part of the shared eligibility change. |
| aiming/AimingDiagnosticLogger.java | Update version header to 2.2. Retain bounded asynchronous logging, rotation and export behavior. |
| defaultlayout/DefaultLayoutActivity.java | Render acquisition stages and blocker summary; change export filename to cam3-v2.2-aiming-<timestamp>.txt. Extend Observer data handling for blocker details. |
| UXSDK src/main/res/layout/uxsdk_activity_default_layout.xml | Keep Start, Stop and Export visible; add a compact expandable readiness-details area so multiple reasons do not enlarge the persistent footer. Validate landscape layout/font scaling. |
| UXSDK src/main/res/values/strings.xml | Add plain-language startup/timeout/release messages and separate location accuracy/distance reasons. Never say cam3 blocks RTH. |
| SampleCode-V5/android-sdk-v5-sample/build.gradle | At implementation time set versionName 2.2, versionCode 4. |
| UXSDK src/test/java/.../aiming/AimingSessionTest.java | Add callback-order, unknown-start, takeover and cleanup cases below; preserve existing fail-safe tests. |
| tools/test-aiming.ps1 | Include new pure-Java classification/eligibility tests as needed; retain logger tests and compiled zero-translation checks. |

No control math changes planned in YawAimingMath.java or YawOnlyCommand.java. PhoneTargetLocationSource.java remains unchanged unless implementation finds a concrete issue; this version does not relax GPS quality.

## 7. UI and logging details

Persistent status examples:

- Ready to request aiming control.
- Requesting control from DJI…
- Waiting for advanced mode…
- Aiming active.
- Aiming stopped — remote controller took control.
- Aiming stopped — aircraft returning home.
- Start timed out — no advanced-mode confirmation.
- Aiming stopped — release not confirmed.

Readiness details list all computable blockers, for example: phone accuracy 22 m / required <=10 m; distance 5 m / required distance; telemetry unavailable. If an input is missing, show unavailable rather than calculate a misleading value. Internal owner enums remain in logs/details, not the main pilot-facing message. Initial ownership unavailable alone is informational under this proposal.

Log each request and callback receipt with monotonic time, session where applicable, listener generation, sequence, exact raw owner/reason and enabled/advanced flags. Include initial assumption, enable result/error, advanced request, activation basis, first command submission, stop latch, cleanup request/result and release observation. Log arrival order separately from executor processing order. SDK acceptance is not logged as physical command execution.

Retain 500 ms diagnostic summaries and five-second unchanged summaries. Include commanded yaw rate and measured heading/velocity in bounded summaries to help assess response without logging every control tick. No precise coordinates or automatic uploads. Log failures never delay commands or cancellation. Export picker still stops aiming through onPause.

## 8. File-labelled call hierarchy

```text
DefaultLayoutActivity.java: onCreate/resume
  -> YawAimingController.java: resume
     -> SDK setVirtualStickStateListener(listener)
     -> AircraftAimingTelemetry.java: start / poll

DefaultLayoutActivity.java: START click
  -> YawAimingController.java: startAiming
     -> AimingSession.java: canStart / startAiming
        -> YawAimingController.java: enable
           -> SDK enableVirtualStick(completion)
              -> onSuccess/onFailure -> control executor -> session completion
        -> AimingSession.java: tick after enable success
           -> YawAimingController.java: advanced
              -> SDK setVirtualStickAdvancedModeEnabled(true)

SDK -> YawAimingController.java: onVirtualStickStateUpdate(state)
  -> snapshot owner / enabled / advanced + arrival sequence
  -> latch cancellation immediately if takeover/loss
  -> AimingSession.java: tick evaluates current-session confirmation
     -> AIMING only when all gates pass
        -> YawAimingController.java: sendYaw + final eligibility recheck
           -> YawOnlyCommand.java: build
              -> SDK sendVirtualStickAdvancedParam

SDK -> YawAimingController.java: onChangeReasonUpdate(reason)
  -> latch stop for takeover/RTH/etc before logging
  -> control executor -> AimingSession.java: stopAiming / settleRelease
     -> conditional disable request -> completion + observed release

All stages -> AimingDiagnosticLogger.java: bounded queue -> writer/files
Controller readiness -> DefaultLayoutActivity.java: status + details
```

## 9. Preserved limits

- Aircraft freshness <=1500 ms; phone fix <=3000 ms, no future timestamps.
- Phone accuracy <=10 m; separation >=max(20 m, 4*(phone accuracy + 5 m)). The 5 m aircraft allowance remains an assumption.
- GPS LEVEL_4/LEVEL_5, valid heading and no compass error.
- Hover horizontal speed <=0.5 m/s, vertical speed <=0.3 m/s; stick magnitude <=30.
- Yaw limit 8 degrees/s, acceleration limit 4 degrees/s², deadband 3 degrees.
- Zero roll, pitch and vertical velocity; BODY frame; angular-velocity yaw.
- 100 ms fixed-delay loop, stop on >500 ms loop gap or >200 ms calculation/send delay.
- Explicit Start, immediate Stop/pause/takeover cancellation, no automatic resume.

## 10. Validation required before marking implemented

1. UNKNOWN before request allows one enable call only when other gates pass. Known OTHER/existing enabled session remains blocked.
2. Enable failure/exception/timeout sends no yaw. Success without advanced confirmation sends no yaw. Pre-request true flags cannot activate.
3. Enable success followed by fresh enabled+advanced with UNKNOWN may activate under this policy; RC/OTHER during the attempt cancels. Verify state/reason callbacks before and after completion in both orders.
4. Stop, pause, pilot stick, RTH or landing between every startup step prevents activation. Late MSDK events never resume it.
5. Explicit control loss, enabled=false or advanced=false while aiming stops further normal commands. Recheck cancellation immediately before SDK send.
6. Unknown-owner cleanup attempts disable without neutral; unresolved release blocks new sessions; late enable after earlier cleanup receives bounded new cleanup.
7. APAS classification is consistent across snapshot, listener and neutral policy. Unknown/takeoff/RTH/landing values stay ineligible. Add tests against actual SDK 5.18 enum mappings.
8. Poor phone accuracy and short separation are both visible; missing data is not fabricated. Replay recorded values as test fixtures, not proof of aircraft behavior.
9. Run existing control/logger tests and zero-translation checks; build offline with Java 21. No device install as part of design work.
10. Device validation must capture listener registration, enable result, advanced update, owner/reason order, yaw response and Stop/pilot handover. An absent callback is an unresolved result, not permission to bypass its gate. Confirm UI fit/export after stopping.

Before implementation completion, resolve the APAS/Normal key mapping and review cleanup callback races against SDK 5.18. Record actual test/build results and remaining device limitations here. Do not describe this proposal as verified safe operation or claim obstacle avoidance support for Mini 3 Pro under Virtual Stick.

## 11. Local implementation record

Implemented on 2026-09-15 after the user requested the new version in the local folder only. No commit, push, installation or aircraft commands were performed by this development task.

### Actual behavior and method changes

- New **AimingFlightModes.java**, method allows(String), is the shared exact allowlist: GPS_NORMAL, APAS, VIRTUAL_STICK. AircraftAimingTelemetry.allowedMode delegates to it, so polling, urgent mode listener and neutral gating all agree.
- SDK review: bundled Docs/Android_API/en/Components/IKeyManager/DJIValue.html explicitly defines FlightMode.APAS separately from AUTO_TAKE_OFF, AUTO_LANDING, FORCE_LANDING and GO_HOME. Inspection of the SDK 5.18 provided FCFlightMode enum also shows APAS and separate takeoff/landing/GO_HOME entries. The existing DJI AvoidanceShortcutWidgetModel uses KeyFCFlightMode, but that enum also contains APAS; switching to it does not establish that the aircraft would report GPS_NORMAL. v2.2 therefore keeps the existing KeyFlightMode and accepts its APAS value with all independent hover checks. It does not assert APAS means exclusively Normal mode or add an unverified second required key.
- **AimingSession.Authority** adds callback sequence and monotonic receipt time. Fresh observation object identity establishes before/after request boundaries; listener generation is managed by the controller rather than stored in Authority. Test construction retains a three-argument overload.
- **canStart()** permits RC or UNKNOWN with Virtual Stick off and valid inputs. Existing enabled/OTHER/MSDK sessions and unresolved cleanup remain blocked. The controller additionally requires listener registration before showing Start ready or dispatching it.
- **startAiming()/tick()** record the initial owner, request enable once, request advanced after enable success, and require a new enabled+advanced observation after the advanced request boundary. Initial UNKNOWN is allowed; a new RC/OTHER report cancels. Loss of previously observed MSDK to UNKNOWN also cancels. Startup timeout is still five seconds, now distinguishing missing enable from missing advanced confirmation.
- **commandEligible()** is shared by session activation, every tick, final pre-send checks and the controller's command adapter. Positive MSDK-owner reporting is optional; enable success, fresh advanced confirmation and absence of cancellation/control-loss evidence are mandatory.
- **Port.controlLost() / controller.yielding** preserve an urgent reason-first takeover separately from raw ownership. A later positive state does not clear the cancellation. The next explicit eligible Start clears the prior latch.
- **settleRelease()** allows cleanup of an unknown-owner grant. A merely pending enable with no observed grant waits for its result or evidence; it does not repeatedly disable. Unknown-owner cleanup sends no neutral. Explicit handover suppresses competing cleanup. A disabled observation after the relevant boundary is required to settle release; API success alone is insufficient. Pending enable still prevents overlapping sessions. A late grant after or during a prior disable creates one further bounded cleanup obligation, and requires a new release observation.
- **Controller.resume()** sets listening only after successful registration, wraps callbacks with a registration generation and ignores callbacks from detached generations. **onVirtualStickStateUpdate()** logs every received state and immediately latches contradictory ownership/flag transitions, including UNKNOWN-to-RC during startup. **onChangeReasonUpdate()** logs every reason and distinguishes go-home and landing messages when the SDK reason identifies them.
- **AircraftAimingTelemetry.blockers(now)** lists all assessable aircraft failures, freshness and exact reported mode. **Controller.phoneDetails()/readinessDetails()** add independently evaluated phone age, accuracy, separation and required separation. Coordinates are omitted. The existing Observer signature stays unchanged; the Details UI reads a precomputed snapshot.
- **Controller.stopFromUser()** invokes the existing immediate cancellation, then returns the processed session state on the main thread. Activity STOP always acknowledges the tap: "Aiming is stopped" when settled, or "Aiming commands stopped; waiting for control release" when unresolved. Every tap and feedback state is logged. Stopping does not claim physical aircraft stop.
- **DefaultLayoutActivity** opens a snapshot Details dialog; this replaces the initially proposed expandable footer area. It keeps diagnostics available without expanding the persistent footer. Start/Stop are 104 dp wide at 12 sp; Details is 80 dp and Export remains 96 dp. All retain at least 48 dp height. Landscape and large-font device inspection remain pending.
- **Activity/logger/build.gradle** identify release 2.2 (versionCode 4), with export name cam3-v2.2-aiming-<timestamp>.txt. Existing package, SDK version and storage limits are unchanged.

### Logging cadence implemented

| Record | Frequency |
|---|---|
| Raw ownership/enabled/advanced state | Every SDK state callback, including repeated values; arrival sequence/time retained |
| Authority-change reason | Every SDK reason callback |
| Start, Stop, listener registration, enable/advanced/disable requests | Every event |
| Enable/disable completion | Every callback receipt and separate executor-processing event |
| Flight mode, aircraft/phone quality, blockers, last submitted yaw rate | Summarized at most every 500 ms when changed; unchanged summaries repeat every five seconds |
| First aiming command submission | Once per session; subsequent commands are represented by the bounded summaries |

Callbacks are not polled. No-callback startup is distinguished by sequence=0; an SDK UNKNOWN/null owner in a callback has a nonzero sequence. Listener generation is included in readiness logs. State callback absence is not replaced by an assumed advanced-mode confirmation. Heading/velocity summaries can help assess response but do not prove individual command execution.

### Validation

- Standalone regression harness: **89 passing control/classification assertions**. Includes APAS/unsupported classification, unknown initial acquisition, fresh advanced confirmation, stale true-state rejection, reason-first takeover, cancellation before activation, advanced loss, and late grant during/after release.
- Logger harness: **37 passing diagnostic assertions**, including bounded storage, export, overflow and I/O failure handling.
- Compiled command-factory checks pass: explicit zero roll/pitch/vertical throttle and the required velocity/yaw modes.
- Earlier v2.0/v2.1 design files remain unchanged. Existing source modifications carry CAM3 v2.2 comments.
- Final Android build/package verification follows below. Desktop tests do not exercise the actual DJI callback delivery or prove physical hover/stop. Device validation is still required for callback order, APAS-to-Virtual-Stick transition, yaw response, pilot handover, STOP feedback, Details layout and export.

### Final build result

Java 21 / existing Gradle cache, offline `:sample:assembleDebug`: **BUILD SUCCESSFUL**. Existing DJI resource/deprecation and packaging warnings remain; no build errors. `git diff --check` passes. No new APK installation was performed.

- APK: `C:/Users/gildo/gdlapp/cam3/SampleCode-V5/android-sdk-v5-sample/build/outputs/apk/debug/sample-debug.apk`.
- Verified output metadata: `com.dji.sampleV5.aircraft`, versionName **2.2**, versionCode **4**.
- SHA-256: `A3B918749D4C2129A30162F394C5224A7A56DC2270045C21906B65F890DDF832`.
- Prior v2.0/v2.1 design files have no diff. The unrelated pre-existing `mini3-sdk-starter` modification was not touched.
