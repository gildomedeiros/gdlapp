# cam3 v2.3 detailed design — recoverable aiming pauses

Status: IMPLEMENTED LOCALLY; desktop validation below. Device validation pending.
Date: 2026-09-15 (Australia/Brisbane).
Workspace: C:/Users/gildo/gdlapp/cam3. Version name 2.3; version code 5.

## 1. Scope and manual v2.2 change

An explicit START opens an aiming session. Temporary input failures now pause it; once all conditions are valid continuously for 2 seconds, it resumes from zero requested yaw rate. Pilot-stick movement pauses aiming. The timer starts only after all sticks are neutral, hover is established and every other check passes. Another movement resets it, including brief listener events between polls.

The user manually changed v2.2 to a 10 m separation minimum. Its Details/log formula still used max(20, 4 * (accuracy + 5)), causing the discrepancy. The latest instruction supersedes that manual threshold with **5 m**. YawAimingMath.MIN_AIMING_DISTANCE_METERS is now the single source for control, UI and logs. Phone accuracy must still be positive and at most 10 m; that accuracy limit is separate from separation. No settings slider is added.

STOP, RTH, landing, unsupported modes, not-airborne state, remote pause, confirmed control loss, screen closure and SDK/loop faults permanently cancel this session's automatic recovery. They stop cam3 aiming commands; they do not block aircraft takeoff, RTH or landing.

Excluded: GPS-jump filtering, zoom, automatic Downloads logging, and repair of unresolved Virtual Stick release. Prior version designs remain unchanged. Existing-code changes have nearby CAM3 v2.3 comments.

## 2. State policy

| Condition | Action | Recovery |
|---|---|---|
| Aircraft reads missing/stale/failed or temporary disconnection | PAUSED; continue/retry hardware reads | Valid inputs, fresh enabled/advanced observation after recovery request, then 2 s continuously valid |
| Phone fix missing/stale/invalid or accuracy above 10 m | PAUSED; continue receiving fixes | Valid fix and all other conditions for 2 s |
| Horizontal separation below 5 m | PAUSED | At least 5 m and all other conditions for 2 s |
| Any pilot stick magnitude above 30 | Immediate yaw veto, then PAUSED | All sticks neutral, steady hover and other conditions for 2 s |
| Aircraft GPS insufficient, invalid heading/compass, hover not established | PAUSED | Valid conditions for 2 s |
| STOP, remote pause, RTH, landing, unsupported mode, not airborne, screen closure | Cancel automatic recovery; existing release handling | Explicit START after release/readiness checks |
| Competing owner, takeover reason, enabled/advanced loss after activation | Cancel even during PAUSED | Never automatically reclaim control |
| Enable/advanced timeout, SDK exception, loop stall | Cancel and use existing release handling | Explicit START when release settles |

The worker remains fixed-delay 100 ms. A recovery-loop gap over 500 ms or backwards clock cancels recovery; unseen time does not count as healthy. Initial acquisition retains a 5 s deadline even if inputs pause it. Aircraft readings must be no older than 1500 ms, phone fixes 3000 ms. Existing horizontal hover limit 0.5 m/s and vertical limit 0.3 m/s remain.

PAUSED retains the current grant. It does not disable/re-enable Virtual Stick for ordinary recovery. Nonzero aiming submissions are suppressed immediately by the pause latch. One zero-yaw command is requested on pause entry when confirmed grant and current aircraft conditions permit. If stale telemetry prevents neutral, it may be requested after telemetry/control confirmation. Repeated invalid ticks do not repeatedly send neutral. Submission is not proof of physical stopping.

For telemetry/connection recovery, the session calls setVirtualStickAdvancedModeEnabled(true) once after inputs become valid and waits for a subsequent onVirtualStickStateUpdate with enabled/advanced true. This requests state confirmation, not control reacquisition. No callback is guaranteed by this design: if none arrives, cam3 stays PAUSED/control_wait. Another telemetry failure permits a new confirmation attempt after inputs recover. It never calls enableVirtualStick to take control back automatically.

UNKNOWN ownership retains v2.2's enable-success plus fresh advanced-confirmation policy. RC/OTHER owner or takeover reason cancels. Once MSDK was observed, returning to UNKNOWN cannot silently continue. Disabled/advanced-off evidence while an activated session is paused is immediately latched, so a later positive callback cannot erase that loss before the next tick.

## 3. Read retries and urgent listeners

AircraftAimingTelemetry.poll() considers all keys every 500 ms while foreground. Each Slot stores request ID, request-start time, pending flag, accepted data and retry status.

- An unanswered request is superseded after at least 2 s, on the next poll.
- Failed/null reads are attempted again on the next eligible poll.
- Callbacks must match both the lifecycle generation and latest request ID for that field.
- Late superseded or detached callbacks are ignored and logged.
- Accepted freshness uses request-start time, not callback arrival. A slow reply can remain stale.
- Exceptions invalidate the field and log the exception class.
- Known landing/RTH/not-airborne values outrank other missing fields.

start() registers connection, flight-mode and stick lambdas once. stop() removes them. DJI later invokes these registered functions when values change. Pressing LAND does not call start() again. The lambda latches the named action before logging its key and old/new values. Independent connection/mode vetoes prevent a healthy update of one field from clearing the other's neutral veto. Polling separately validates all inputs.

## 4. Changed files and methods

Package root P = SampleCode-V5/android-sdk-v5-uxsdk/src/main/java/dji/v5/ux/sample/showcase/defaultlayout/.
Paths are workspace-relative.

| File | Detailed change |
|---|---|
| P/aiming/AimingSession.java | Add PAUSED, pauseImmediately, pauseAiming, recoveryRemainingMs and recoverable reason classification. tick monitors pauses, resets/advances the 2 s timer, refreshes control after telemetry failure and resumes from zero. Activation tracking distinguishes delayed initial confirmation from later loss. stopAiming cancels recovery. Inputs.validate distinguishes distance from GPS quality. |
| P/aiming/AircraftAimingTelemetry.java | start sends named pause/stop events and key old/new diagnostics; lifecycle fields visible to callback threads. getSnapshot prioritizes permanent flight states and separates pilot_stick. read/Slot implement timeout replacement, retry identities and obsolete-response rejection. |
| P/aiming/YawAimingController.java | telemetryEvent routes pauses/permanent cancellation. interruptAiming and control-state listener include PAUSED. tick publishes countdown; recordDiagnostics records recovery and historical command age; phoneDetails uses shared minimum. sendYaw allows bounded pause neutral and timestamps actual submissions. |
| P/aiming/YawAimingMath.java | Shared MIN_AIMING_DISTANCE_METERS=5.0 in isBearingUsable; retain finite/accuracy checks. |
| P/DefaultLayoutActivity.java | PAUSED state label, recovery countdown and shared distance text in renderAimingState; v2.3 export filename. |
| UXSDK src/main/res/values/strings.xml | Pause, pilot-stick, countdown, distance and control-wait messages; telemetry retry explanation. |
| P/aiming/AimingDiagnosticLogger.java | v2.3 process/export headers; existing bounded storage preserved. |
| SampleCode-V5/android-sdk-v5-sample/build.gradle | versionName 2.3, versionCode 5. |
| UXSDK src/test/java/.../aiming/AimingSessionTest.java | Distance boundaries, recoverable causes, neutral once, timer reset, fresh control waiting, stop/control-loss precedence, loop stall and paused acquisition tests. |
| UXSDK src/test/java/.../aiming/AimingDiagnosticLoggerTest.java | v2.3 header expectation; retain logger regression coverage. |

No commanded translation or automatic gimbal control is added.

## 5. Call hierarchy

### Stick movement and recovery

    AircraftAimingTelemetry.start() registers stick lambda
      DJI calls (old, value) -> { ... }
        -> YawAimingController.telemetryEvent("pilot_stick", false)
          -> AimingSession.pauseImmediately("pilot_stick") [atomic veto]
        -> log listener key and old/new values

    YawAimingController.tick() [100 ms]
      -> AircraftAimingTelemetry.poll() [500 ms eligibility]
      -> AimingSession.tick()
        -> pauseAiming("pilot_stick")
          -> PAUSED; request zero once if eligible
        -> later Inputs.validate() returns valid
          -> recovery_timer: require 2000 ms
          -> any invalid input or urgent event: recovery_reset
          -> two continuous valid seconds: AIMING + resumed event
            -> YawAimingMath.calculateYawRate(previousRate=0)
            -> YawAimingController.sendYaw()
              -> sendVirtualStickAdvancedParam(YawOnlyCommand.build(rate))

### Missing telemetry

    AircraftAimingTelemetry.poll() -> read(slot)
      -> KeyManager.getValue(key, callback)
        -> onFailure(error), null or no reply
      -> AimingSession.tick() -> PAUSED
      -> later polls retry; log request/result identity
      -> current request succeeds and all inputs become valid
      -> AimingSession: recovery_control_request
        -> YawAimingController.advanced()
          -> setVirtualStickAdvancedModeEnabled(true)
      -> DJI onVirtualStickStateUpdate(state)
        -> store new Authority observation
      -> AimingSession: recovery_control_confirmed
        -> two-second valid timer -> resumed

### LAND / RTH while aiming or paused

    DJI flight-mode lambda receives AUTO_LANDING / GO_HOME
      -> AircraftAimingTelemetry.modeReason(value)
      -> YawAimingController.telemetryEvent("landing"/"returning_home", true)
        -> interruptAiming(reason)
          -> cancelImmediately() [atomic veto]
          -> queued AimingSession.stopAiming(reason)
            -> recovery_cancelled if paused
            -> settleRelease()
              -> disableVirtualStick(callback), where release policy permits
                -> onFailure(error), e.g. CONTROL_AUTH_LANDING
                  -> log exact SDK error -> RELEASE_UNCONFIRMED

The control reason/state callback can arrive first and independently trigger permanent cancellation. cam3 never automatically reacquires after this event. CONTROL_AUTH_LANDING remains enhancement E01: it is not interpreted as successful release.

## 6. Screen and log stages

The bottom status shows PAUSED and its reason, then a recovery countdown, then active aiming. Reasons include pilot sticks, distance below 5 m, retrying missing/stale aircraft reads, and waiting for fresh control confirmation. Permanent stop/release states remain distinct. START is unavailable while a pause retains the session. STOP cancels it and keeps the existing acknowledgement. Details lists independently detected blockers, phone quality, distance, owner, state and remaining recovery time.

| Information | Frequency |
|---|---|
| Flight-mode/connection/stick listener key, old/new values | Every delivered listener callback |
| Owner, enabled, advanced, sequence; authority change reason | Every delivered control callback, including repeats |
| Pause, reset, recovery timer, control refresh, resume, cancellation, state transition | Each event/transition |
| Read retry timeout/request/result and ignored obsolete callback | Each affected request/response |
| Independently detected field problems, ages, phone quality, separation | Sampled every 500 ms; changed summaries emitted; unchanged repeated every 5 s |
| Last submitted yaw rate/age, sending eligibility, recovery remaining | Readiness summaries |

Logs retain wall-clock/monotonic receipt timestamps. Session transitions carry session IDs; key names and request IDs distinguish read attempts. Coordinates remain omitted. lastSubmittedYawRate and lastCommandAgeMs describe a past API call, not ongoing rotation; sending=false distinguishes a paused/stopped session. A zero is not fabricated when none was sent.

Logging remains best effort on a separate bounded writer: 256 queued entries, with droppedEntries reporting overload. Current and previous private files each remain capped at 512 KiB. Old history can rotate out; file errors report to Logcat. Added diagnostic coverage is not unlimited retention. Export still copies retained logs. Opening Android's save picker still closes/pauses the flight screen and permanently cancels aiming.

## 7. Validation and limitations

./tools/test-aiming.ps1: PASS, 140 control/math/recovery assertions and 37 logger assertions, plus compiled zero-translation/velocity-mode verification. Includes all recoverable categories, two-second timing, reset by transient stick event, neutral once, resume acceleration, waiting for new control evidence, STOP/RTH cancellation, control loss during bad inputs, loop stalls and paused acquisition timeout.

Android build: :sample:assembleDebug with cached DJI 5.18.0 dependencies and JDK 21: BUILD SUCCESSFUL (24 s, 59 actionable tasks). Output metadata confirms versionName 2.3 and versionCode 5. APK: SampleCode-V5/android-sdk-v5-sample/build/outputs/apk/debug/sample-debug.apk. git diff --check passes. Local only; no installation/publishing. Existing Java 8/deprecation and Gradle packaging warnings remain.

Device checks remain: callback cadence after advanced-mode refresh, stuck-read replacement and late response rejection, actual pilot-stick/RC behavior, display readability and physical yaw response. Session tests use a fake port, not DJI hardware. If the SDK does not deliver fresh confirmation, telemetry recovery stays paused.

5 m is the requested test threshold, not a demonstrated GPS accuracy boundary. Close-range accepted GPS error can change the bearing substantially. Existing yaw limit 8 degrees/s, acceleration limit 4 degrees/s², 3-degree deadband and zero translation remain. Plausible wrong coordinates are not filtered. Pausing suppresses new nonzero aiming submissions; missing aircraft telemetry cannot prove physical stopping.
