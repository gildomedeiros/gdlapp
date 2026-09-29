# cam3 v2.1 detailed design — aiming diagnostic logs

Status: IMPLEMENTED LOCALLY; desktop tests/build verified; phone/device validation pending.
Recorded: 2026-09-15 (Australia/Brisbane).
Source baseline: `e88b5a063710f02c76ad54af813646530e6b29f6`.
Workspace: `C:/Users/gildo/gdlapp/cam3` (parent Git repository: `C:/Users/gildo/gdlapp`).
App version: versionName **2.1**, versionCode **3**; DJI SDK **5.18.0**.

Each version has its own detailed design file. The v2.0 file is preserved unchanged. Every modification to existing source has a nearby CAM3 v2.1 comment explaining what changed and why. This work remains local; no installation, flight commands, commit or push was performed.

## Problem and scope

The user reported that the aircraft was hovering while v2.0 displayed “Requires airborne Normal mode.” That message combined two independent failures: `KeyIsFlying` was false, or `KeyFlightMode` was outside the existing allowed set. The screenshot could not distinguish them; the actual rejected value has not yet been established.

v2.1 adds diagnostics that expose the exact reported values, read errors, freshness and session/control transitions. **No accepted flight mode, speed/accuracy limit, control frequency, authority gate or fail-safe rule is relaxed.** `GPS_NORMAL` and `VIRTUAL_STICK` remain the accepted modes. Yaw remains automatic only after explicit Start, translation requests remain zero, gimbal tilt remains manual, and CV is not added.

## 1. AircraftAimingTelemetry.java — existing file

Path: `SampleCode-V5/android-sdk-v5-uxsdk/src/main/java/dji/v5/ux/sample/showcase/defaultlayout/aiming/AircraftAimingTelemetry.java`.

### Named fields and read outcomes

`Slot<T>` now stores a diagnostic name and latest read outcome in addition to the existing value, request time and pending flag. `slot(name, keyInfo)` names each field explicitly. The constructor accepts a best-effort diagnostic callback alongside the existing urgent-stop callback.

| Diagnostic field | DJI key / value recorded |
|---|---|
| `connected` | `FlightControllerKey.KeyConnection`, Boolean |
| `isFlying` | `FlightControllerKey.KeyIsFlying`, Boolean |
| `position` | `FlightControllerKey.KeyAircraftLocation`, only `present` / `unavailable`; coordinates omitted |
| `heading` | `FlightControllerKey.KeyCompassHeading`, reported heading |
| `flightMode` | `FlightControllerKey.KeyFlightMode`, exact enum name |
| `gpsLevel` | `FlightControllerKey.KeyGPSSignalLevel`, exact enum name |
| `compassError` | `FlightControllerKey.KeyCompassHasError`, Boolean |
| `velocity` | `FlightControllerKey.KeyAircraftVelocity`, reported 3D velocity |
| `stickLeftHorizontal`, `stickLeftVertical` | Corresponding `RemoteControllerKey.KeyStickLeft...` values |
| `stickRightHorizontal`, `stickRightVertical` | Corresponding `RemoteControllerKey.KeyStickRight...` values |

### Method changes

- `read()`: captures SDK `errorCode()` on failure, `null_value` on an empty success, `none` on a populated success, and exception class on a thrown read. It retains the v2.0 request-start timestamp and validity behavior.
- `readOutcome()`: records outcome changes immediately, so a failure/recovery between periodic snapshots is not silently missed. Repeated identical errors are suppressed. Diagnostic-callback exceptions are isolated.
- `getSnapshot()`: splits the former `flight_state` reason into `not_airborne` and `flight_mode_rejected`. The same Boolean and mode checks still reject the same states.
- `diagnostics(now, target)`: returns an immutable `DiagnosticSnapshot` containing the input snapshot, named values, error outcomes, and each field's age/pending flag. The diagnostic and input copies are made under the same lock to avoid reporting a flight-mode value from a different software snapshot than the input problem.
- `DiagnosticSnapshot.signature` excludes advancing ages; its detail includes them. Age `-1` means no accepted request timestamp. The snapshot remains a software copy of separately sampled fields, not simultaneous sensor sampling.

No telemetry subscription, polling interval, stale threshold or ownership behavior is changed.

## 2. AimingSession.java — existing file

Path: `SampleCode-V5/android-sdk-v5-uxsdk/src/main/java/dji/v5/ux/sample/showcase/defaultlayout/aiming/AimingSession.java`.

- `Port.diagnostic(event, detail)`: new optional no-op-by-default diagnostic hook.
- `sessionId()`: exposes the current session for log correlation.
- `diagnostic()`: prefixes the session ID and catches diagnostic-port exceptions.
- `setState()`: performs existing state assignments and emits transitions only when the state changes. The diagnostic session is allocated before its initial Starting transition.
- `startAiming()`: logs enable completion, callback token, cancellation flag, late-callback cleanup and exception type. Existing cancellation/ownership checks remain in force.
- `tick()`: logs confirmed activation and loop exception types. No per-tick command stream is logged.
- `stopAiming()`: cancellation is still latched first; logs the stop reason and state transition.
- `settleRelease()`: logs observed release, callback result/release token, release exceptions and the existing five-second unconfirmed-release transition. SDK success is still distinct from observed authority and from actual aircraft response.

The command port, yaw calculation, all timing thresholds and the zero-translation factory remain unchanged apart from the optional diagnostic hook.

## 3. AimingDiagnosticLogger.java — new file

Path: `SampleCode-V5/android-sdk-v5-uxsdk/src/main/java/dji/v5/ux/sample/showcase/defaultlayout/aiming/AimingDiagnosticLogger.java`.

### Method responsibilities

| Method | Responsibility |
|---|---|
| Constructor | Configure private log filenames, bounded executor, timestamp formatting and version header. Directory creation/file I/O occurs on the writer. |
| `event()` | Queue a one-off event such as user action, state transition or SDK callback. |
| `changed()` | Queue a named observation with a stable signature and optional periodic repetition. |
| `record()` | Apply duplicate suppression on the writer, format timestamps and route output to Logcat and local file. |
| `submit()` | Non-waiting submission to a bounded queue; overflow drops the new entry and increments a counter. Never waits for writer completion. |
| `append()` | Append UTF-8 data and rotate the previous/current files within their configured limits. |
| `export()` / `copy()` | Serialize export with file writes; copy retained previous/current contents to the user-selected output stream. Return success only after writing/closing succeeds. |
| `close()` / `awaitClosed()` | Shut down/drain in tests; the production logger otherwise lives with the process-scoped controller. |

### Storage, scheduling and failure handling

- Android Logcat tag: **`CAM3_AIMING`**.
- Private Android directory: `Context.getFilesDir()/aiming-logs/`.
- Files: `aiming-current.log` and `aiming-previous.log`, **512 KiB each**, at most **1 MiB** retained local log content.
- Writer: one dedicated `cam3-diagnostic-writer` thread, queue capacity **256**. It contains no aircraft command API and cannot repeat commands.
- Individual type/detail strings are sanitized for embedded line breaks and capped at 4,096 characters each. File writes are additionally bounded by the file cap.
- Duplicate-signature bookkeeping is capped at 128 named observations.
- Every record includes wall-clock date/time with timezone offset and a monotonic event timestamp. Session-bearing events identify their control session.
- File failures are caught and reported to Logcat when available; a Logcat sink failure does not prevent attempting the local file. Neither path calls the control stop/start functions.
- A full queue drops diagnostic entries, not flight-control work. The next emitted entry reports accumulated dropped entries. This is a best-effort log, not a guaranteed complete flight recorder.
- Export shares the writer so its retained snapshot cannot race rotation. A stalled document provider can delay/drop logging, but does not block the UI or flight-control worker. Export reports failure when the queue is full, no log exists, the destination is unavailable, or an I/O operation fails.
- No automatic upload or sharing. Precise phone/aircraft coordinates and SDK error descriptions are omitted; only SDK error codes, GPS quality/age, heading, velocity, sticks and rounded separation are recorded.

## 4. YawAimingController.java — existing file

Path: `SampleCode-V5/android-sdk-v5-uxsdk/src/main/java/dji/v5/ux/sample/showcase/defaultlayout/aiming/YawAimingController.java`.

- Constructor: creates the logger with application context and connects telemetry read-outcome diagnostics.
- `resume()`: logs screen observation resumption. Existing `pause()` invokes logged screen-closed cancellation.
- `startAiming()` / `stopAiming()`: log user intent; cancellation remains ahead of Stop logging.
- Virtual Stick state listener: logs raw observed owner, enabled/advanced flags and exact authority-change reason. Any existing immediate takeover cancellation is latched before diagnostic submission.
- `tick()`: after the existing control tick, calls `recordDiagnostics()` at most every **500 ms**. It never logs every 100 ms command tick.
- `recordDiagnostics()`: records session/state, original stop reason, current input problem, Start readiness, owner, exact aircraft values/errors/ages, phone accuracy/fix age and rounded horizontal separation. Invalid separation is marked unavailable rather than zero.
- Readiness signatures include actual reported values and reasons, excluding ages alone. Changed values can therefore produce up to two summaries per second; unchanged summaries repeat every **5 seconds**, including while waiting for readiness and starting/aiming. No idle background summaries are emitted after clean release and screen closure.
- `callback(operation, completion)`: logs operation/session and SDK success or error code, then dispatches the callback on the existing control executor.
- `enable()`, `advanced()`, `disable()`: log requests separately from results/observations. Flight API calls are unchanged.
- `diagnostic()` / `logChanged()`: isolate logging exceptions.
- `exportLog(uri, result)`: passes an output-stream supplier to the logger. ContentResolver opening/copying occurs on the logger writer, with no authority acquisition or flight commands.

## 5. UI and export — existing files

### DefaultLayoutActivity.java

Path: `SampleCode-V5/android-sdk-v5-uxsdk/src/main/java/dji/v5/ux/sample/showcase/defaultlayout/DefaultLayoutActivity.java`.

- New `aimingLogExport` ActivityResult launcher uses `CreateDocument("text/plain")`.
- `initClickListener()` wires Export aiming log; suggests `cam3-v2.1-aiming-<timestamp>.txt` and temporarily disables the button until cancellation/result.
- Picker cancellation writes nothing. Export result returns to the UI for a success/failure toast; finished/destroyed activities are not updated.
- No broad storage permission added. Access is limited to the selected destination.
- Opening the picker pauses the activity. **The existing v2.0 onPause fail-safe therefore stops aiming.** Returning never resumes aiming automatically. Export is intended after reproducing the issue; it is not a way to keep aiming active in the background.

### uxsdk_activity_default_layout.xml

Path: `SampleCode-V5/android-sdk-v5-uxsdk/src/main/res/layout/uxsdk_activity_default_layout.xml`.

Adds a 96 dp Export aiming log button at the end of the existing footer. Start/Stop remain in their existing positions. Phone sizing, footer legibility, navigation spacing and picker interaction still require device inspection. This version does not redesign the oversized footer/top-bar issue noted from the v2.0 screenshot.

### strings.xml

Path: `SampleCode-V5/android-sdk-v5-uxsdk/src/main/res/values/strings.xml`.

Adds distinct labels for “DJI reports aircraft not airborne” and “DJI flight mode rejected; export log for exact value,” plus export-button/success/failure labels. Existing dynamic reason rendering selects these strings without accepting additional flight states.

## 6. Version, tests and documentation

- `SampleCode-V5/android-sdk-v5-sample/build.gradle`: versionName **2.1**, versionCode **3**, with explanatory comment. Package/App Key pairing and dependencies remain unchanged.
- `SampleCode-V5/android-sdk-v5-uxsdk/src/test/java/dji/v5/ux/sample/showcase/defaultlayout/aiming/AimingSessionTest.java`: retain v2.0 tests and add transition/session/reason observability, split blocked reasons and a throwing diagnostic port.
- New `.../src/test/java/dji/v5/ux/sample/showcase/defaultlayout/aiming/AimingDiagnosticLoggerTest.java`: real local file tests for payloads, duplicate suppression, periodic summaries, rotation, snapshot export, destination/write failures, queue overflow, stalled writer, invalid storage path and failing Logcat sink.
- `tools/test-aiming.ps1`: run both standalone JVM harnesses and retain compiled command-factory invariant verification. Test output stays under the local project's `build/aiming-tests/` directory.
- `detailed_design.md`: add v2.1 to the index.
- This file records v2.1 separately; `detailed_design/detailed_design_v2.0.md` is unchanged.

## Call hierarchy

```text
DefaultLayoutActivity.onCreate()
  -> YawAimingController.getInstance(applicationContext)
     -> AimingDiagnosticLogger(private directory, Logcat sink)
     -> AircraftAimingTelemetry(urgent stop, read outcome callback)
     -> existing single control scheduler

AircraftAimingTelemetry.read() SDK callback
  -> store accepted field / error using original request time
  -> readOutcome(field, error)
     -> controller.logChanged(read_<field>, error)
        -> logger.changed() -> bounded queue (no waiting for I/O)

Controller.tick() [same fixed delay after each tick]
  -> existing telemetry poll and AimingSession.tick()
  -> at most twice per second: recordDiagnostics()
     -> aircraft.diagnostics(now, phone fix) [immutable copied inputs + diagnostics]
     -> logger.changed(readiness signature, values/errors/ages, repeat=5 s)

User Start / Stop -> controller -> existing AimingSession decision
  -> setState() / diagnostic() -> logger.event(session, transition/reason)
  -> existing DJI enable/disable request
     -> callback(operation, session, result/error) -> logger.event()
     -> existing executor callback handling

DJI authority state/reason callback
  -> existing urgent cancellation if required
  -> logger.changed(raw owner/flags/reason)

Logger writer [separate thread; no flight APIs]
  -> suppress duplicate observations / allow periodic summary
  -> format timestamp + bounded message
  -> Android Logcat CAM3_AIMING
  -> append()/rotate() private logs

User presses Export aiming log
  -> DefaultLayoutActivity ActivityResult CreateDocument launcher
     -> existing onPause() stops aiming
  -> user selects destination URI
     -> controller.exportLog(uri)
        -> logger.export() on writer
           -> open destination -> header -> previous log -> current log -> close
           -> UI success/failure result
  -> screen return observes readiness; no automatic aiming restart
```

## Validation and first device diagnosis

Desktop validation: **60 passing control assertions**, **37 passing diagnostic assertions**, and compiled command-factory checks for zero roll/pitch/vertical throttle and explicit velocity/yaw modes. APK builds offline using Java 21 with the project's existing Gradle/DJI versions. No device export, runtime telemetry value or aircraft response has been verified by this development task.

Built APK: `SampleCode-V5/android-sdk-v5-sample/build/outputs/apk/debug/sample-debug.apk`.
Verified package/version metadata: `com.dji.sampleV5.aircraft`, versionName `2.1`, versionCode `3`.
SHA-256: `AEB20A7F626B06F98CB8DD36E94723B442019E8DC5DDF542531AD146D967E7A1`.
Later Android Studio rebuilds may have a different APK hash. The unchanged v2.0 design file has SHA-256 `F04A11895135EF404BEE8C93AE52E566CE7A416EC27F3532499688DBB8B77E09`.

First device check:

1. Run v2.1 and reproduce the reported hovering/blocked-Start condition.
2. Use Export aiming log and save the text file. Opening the picker stops any active aiming under the existing lifecycle policy.
3. Inspect the latest `readiness` entries: `isFlying`, `flightMode`, `inputProblem`, per-field ages and read errors. Android Studio Logcat can also be filtered by `CAM3_AIMING`.
4. Determine the actual failure from those values before proposing changes to the accepted flight-mode set. Do not assume `GPS_TRIPOD`, `UNKNOWN` or a false airborne flag without the log.

Example only, **not an observed aircraft result**:

```text
readiness session=0 state=OFF inputProblem=flight_mode_rejected canStart=false
isFlying=true error=none;flightMode=GPS_TRIPOD error=none;...
isFlyingAgeMs=240 pending=false;flightModeAgeMs=240 pending=false;...
phoneAccuracyM=4.5 separationM=50 phoneAgeMs=100
```

Remaining device checks: picker save/cancel/failure, saved log readability, UI layout, actual telemetry keys/results, and the outstanding v2.0 yaw/Stop/pilot/RTH/command-loss verification. Logs describe SDK/software observations; they do not prove a physical stop or make an unverified flight mode safe.
