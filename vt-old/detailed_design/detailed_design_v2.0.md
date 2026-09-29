# cam3 v2.0 detailed design

This file records the v2.0 design. Create a separate detailed_design/detailed_design_vX.0.md file for each new version; preserve earlier version files. For each version, record scope, source revision, files and methods, call flow, failure handling, validation and unresolved questions. Mark proposals separately from implemented and device-tested behavior.

Every modification to existing code must have a nearby CAM3 version comment explaining what changed and why. Update this document when the implementation differs from the plan. Never treat an SDK callback as proof of aircraft response.

## v2.0 - GPS yaw aiming

Status: IMPLEMENTED LOCALLY. Desktop regression checks and APK build verified; NOT DEVICE-TESTED.
Recorded: 2026-09-15 (Australia/Brisbane).
Reviewed repository HEAD: 8e9e89092ff9efe4c46bc3de316083671772e1e6.
Project directory: C:/Users/gildo/gdlapp/cam3.
Git root at review: C:/Users/gildo/gdlapp (cam3 is part of the parent repository).

## Implemented v2.0 (2026-09-15)

User authorized implementation directly in the local cam3 folder. No worktree, commit, push, deployment or aircraft commands were performed. VersionName is 2.0; versionCode is 2. Existing-code edits have nearby CAM3 v2.0 comments explaining their purpose. The source revision above is the baseline; this implementation remains local uncommitted changes.

### Implemented scope and UI

Automatic yaw toward the phone running cam3, manual gimbal tilt, no CV and no commanded translation. The phone must be with the intended target. The aircraft must be manually placed into a steady hover before Start. RTH/home settings are not modified.

Start aiming and Stop aiming are in a dedicated footer, reserving space rather than covering the camera or flight widgets. Start is disabled until readiness is verified. Stop stays enabled during startup, active aiming, data failure and unresolved release. Android system-bar and display-cutout insets are received at the root and applied as content/drawer margins, because DrawerLayout measures its children using margins rather than root padding. Navigation space is reserved even when system bars are hidden. Actual sizing and touch access still need checking on the user's phone in landscape, with both navigation modes and larger font settings.

Displayed states: Off, Starting, Aiming, Stopping, Aiming stopped, Control release unconfirmed. Waiting for GPS is conveyed in the readiness/phone-quality text. GPS accuracy and original fix age are shown alongside “Gimbal manual.” An aiming-stopped state describes this feature, not independently verified physical aircraft motion. No automatic restart on recovery, reconnect or screen reopening.

### Actual files and methods

Paths here are relative to `SampleCode-V5/android-sdk-v5-uxsdk/` unless specified otherwise.

| File | Implemented methods / changes |
|---|---|
| `src/main/java/dji/v5/ux/sample/showcase/defaultlayout/DefaultLayoutActivity.java` | `onCreate()` obtains controller and applies insets; `initClickListener()` wires explicit Start/Stop; `onResume()` observes readiness; `onPause()` / `onDestroy()` detach using a stable observer identity; `renderAimingState()` renders state, reason, GPS quality; `applySystemBarInsets()` reserves system UI space. |
| `src/main/java/dji/v5/ux/sample/showcase/defaultlayout/aiming/PhoneTargetLocationSource.java` | `start()`, `onLocationChanged()`, `getLatestFix()`, `stop()`; separate GPS subscription, immutable fix, monotonic timestamp. No minimum movement distance. No cached last-known fix used to invent freshness. Missing precise permission/provider blocks readiness. Mock fixes are not accepted. |
| `.../aiming/AircraftAimingTelemetry.java` | `start()` registers urgent connection/mode/stick listeners; `poll()` / `read()` request hardware values; `getSnapshot()` copies validated scalar data; `stop()` invalidates outstanding generations and removes this feature's listeners. |
| `.../aiming/YawAimingMath.java` | Coordinate checks, bearing, distance, heading wraparound, bearing usability and capped yaw rate. Pure Java, no aircraft commands. |
| `.../aiming/AimingSession.java` | New pure state machine, extracted from planned controller to test real lifecycle/failure behavior. `canStart()`, `startAiming()`, `tick()`, `cancelImmediately()`, `stopAiming()`, `settleRelease()`; injected clock/input/command port. |
| `.../aiming/YawAimingController.java` | Process-scoped singleton with application context; `resume()` / `pause(observer)` attach one screen safely; `startAiming()` / `stopAiming()` dispatch intents; `interruptAiming()` latches transient takeover; `tick()` polls, runs session and renders; SDK callbacks dispatched onto one executor. `sendYaw()` rechecks authority/cancellation; `buildYawOnlyCommand()` delegates to the sole factory. |
| `.../aiming/YawOnlyCommand.java` | New command factory `build()` rejects nonfinite/out-of-range yaw and explicitly sets BODY coordinates, horizontal/vertical VELOCITY, yaw ANGULAR_VELOCITY, zero roll/pitch/vertical throttle. Only yaw is variable. |
| `src/main/res/layout/uxsdk_activity_default_layout.xml` | Dedicated footer with Start/Stop and state/quality text. Existing widget content occupies remaining height. |
| `src/main/res/values/strings.xml` | Commented labels, readiness explanations and failure reasons. |
| `SampleCode-V5/android-sdk-v5-sample/build.gradle` (project relative) | Commented versionName 2.0 / versionCode 2 change. |
| `src/test/java/dji/v5/ux/sample/showcase/defaultlayout/aiming/AimingSessionTest.java` | Deterministic executable JVM regression harness using the production math and state machine with a fake command port/clock. |
| `tools/test-aiming.ps1` (project relative) | Runs regression harness and checks the compiled command factory's explicit zero-velocity assignments and modes. |

### Implemented call hierarchy

```text
DefaultLayoutActivity.onCreate()
  -> YawAimingController.getInstance(applicationContext)
     -> once per process: single ScheduledExecutorService
        -> scheduleWithFixedDelay(controller::tick, 0, 100 ms)
  -> applySystemBarInsets()
  -> initClickListener()

DefaultLayoutActivity.onResume()
  -> controller.resume(aimingObserver)
     -> phone.start(): phone GPS subscription
     -> aircraft.start(): telemetry/takeover listeners
     -> register this feature's VirtualStickStateListener

User presses Start aiming
  -> click handler -> controller.startAiming()
     -> enqueue latest explicit user intent, reject cancelled/obsolete intent
     -> AimingSession.startAiming()
        -> validate inputs and RC ownership / Virtual Stick disabled
        -> enableVirtualStick(callback), remember session + pre-request authority
           -> SDK callback -> executor -> record success/failure

Java scheduler invokes controller.tick() (one at a time)
  -> aircraft.poll(): bounded asynchronous hardware reads, no flight sending
  -> AimingSession.tick()
     -> while STARTING: require success AND observed MSDK ownership/enabled state
        -> request advanced mode once; wait for observed advanced mode
     -> while AIMING: read -> validate -> calculate yaw -> recheck -> sendYaw()
        -> YawOnlyCommand.build(rate)
        -> sendVirtualStickAdvancedParam(command)
  -> post UI snapshot (no flight command in UI renderer)
  -> return; scheduler waits 100 ms after completion

Stop / pause / invalid input / delayed loop / SDK error
  -> latch cancellation immediately, invalidate queued Start intents
  -> AimingSession.stopAiming(reason)
     -> stop nonzero requests
     -> if still known MSDK owner, advanced mode and fresh suitable flight state:
        request zero yaw once
     -> if still known MSDK owner: disableVirtualStick(callback)
     -> wait for callback resolution and observed released authority/state
     -> if unresolved: Control release unconfirmed; Start blocked

Pilot stick / mode / authority takeover event
  -> immediate cancellation latch, executor stops aiming
  -> no competing commands to a different owner; never reclaim automatically

Cancelled start's late callback or late MSDK ownership
  -> remain cancelled, release the late grant when ownership is known
  -> never start yaw; block overlapping starts while request is unresolved
```

Difference from the original proposal: the single worker exists before Start so it can monitor readiness and cleanup, but its state machine sends aiming commands only after explicit Start and confirmation. It is not an independent sender. The process-scoped controller prevents a recreated Activity from creating a second flight controller or forgetting late callbacks. It retains no Activity observer after pause. Phone and telemetry subscriptions stop on pause; the Virtual Stick listener remains only while release/acquisition is unresolved, otherwise this feature removes its own listener. The idle worker is retained for the process lifetime and emits no flight commands.

### Freshness and failure policy

DJI's documented callback `KeyManager.getValue(key, callback)` reads from hardware; the synchronous overload reads the SDK cache. This implementation uses callback reads and dates each accepted value from the request's monotonic start time, not callback arrival. Each key has at most one outstanding request. Delayed, unsupported or absent values fail closed. Read generations invalidate callbacks from a prior screen subscription. Fields are sampled separately; the snapshot is a consistent immutable software copy, not a claim that all sensors were sampled at exactly the same instant.

Required telemetry: aircraft connection, airborne state, position, heading, Normal/Virtual Stick flight mode, GPS level, compass error, 3D velocity and four RC stick channels. An unchanged reading is usable when a new hardware request completes. Listeners additionally latch transient connection/mode/stick changes that might occur between polls. Aircraft-specific callback behavior and these keys' actual availability remain device checks.

| Initial engineering setting | Value |
|---|---|
| Worker delay after each completed tick | 100 ms; no overlapping ticks or catch-up bursts |
| Hardware polling interval | 500 ms; one pending request per key |
| Maximum age of oldest required aircraft field | 1,500 ms from request start |
| Phone GPS request interval / movement filter | 500 ms / 0 m (provider delivery may differ) |
| Maximum phone fix age | 3,000 ms, original monotonic fix time |
| Maximum phone reported horizontal accuracy | 10 m |
| Minimum horizontal separation | max(20 m, 4 × (phone accuracy + 5 m aircraft allowance)) |
| Aircraft GPS gate | LEVEL_4 or LEVEL_5; reject all other/unknown levels |
| Steady-hover velocity gate | Horizontal at most 0.5 m/s, vertical magnitude at most 0.3 m/s |
| RC stick cancellation | Any of four channels outside ±30 SDK units |
| Yaw rate / acceleration | 8 degrees/s / 4 degrees/s² maximum |
| Heading deadband | ±3 degrees; zero yaw on entering deadband or reversing error sign |
| Delayed active loop cancellation | More than 500 ms between ticks |
| Calculation-to-send age check | More than 200 ms cancels |
| Acquisition and release reporting timeout | 5 seconds; unresolved operations continue to block restart |

These are conservative starting parameters, not proven safety limits. The 5 m aircraft allowance is an assumption, not a measured GPS error bound. Latitude near the poles, malformed/nonfinite values, future or stale timestamps, uncertain bearing, poor GPS, unsuitable flight mode, compass error, excessive movement and pilot stick input all block/cancel aiming. No altitude target or ascent/descent command is generated.

Normal Stop sends one zero-yaw request only when appropriate and attempts release even if that request throws. It does not endlessly resend zero or an old yaw command. Release failure/timeout is visible and blocks Start; there is no automatic retry loop or authority reacquisition. A late unattempted grant is still released after timeout. An old pre-enable RC snapshot cannot prove release. Success callbacks do not prove physical aircraft response. A command already inside an SDK call cannot be recalled by a later cancellation event; interruption response remains a hardware validation requirement.

### Verification performed and remaining checks

- Built `:sample:assembleDebug --offline` with Java 21 and DJI SDK 5.18.0. The Android Studio bundled Java 25 runtime was incompatible with the project's Gradle 8.12; no project dependency changes were made to work around that.
- `tools/test-aiming.ps1`: 53 passing assertions for math, input gating, double Start, callback/ownership ordering, cancelled/late grants, no automatic restart, takeover, stale/future data, delayed ticks, send exceptions and release failures. Observed cancelled MSDK grants are released even if the enable callback is still missing; the unresolved callback still blocks another Start.
- Compiled command factory checked for explicit zero roll, pitch and vertical throttle, required VELOCITY/ANGULAR_VELOCITY modes and BODY coordinates. This is static bytecode verification. DJI's provided jar contains non-executable stubs, so a desktop attempt to instantiate its command class was not a valid runtime test; no aircraft-response claim is made from it.
- APK package/version metadata checked locally. No APK installed and no flight commands issued by this development task.

Final local artifact: `SampleCode-V5/android-sdk-v5-sample/build/outputs/apk/debug/sample-debug.apk`, package `com.dji.sampleV5.aircraft`, versionName `2.0`, versionCode `2`, 259,903,149 bytes. SHA-256: `633AEAEE8F8A9B97680EB9FDDC37BA5BFCDA2A5448DFF2702E1EF9B5E9E4EB4A`. A later rebuild may produce a different hash; use this identity for the first device-validation record.

Required before operational use: verify the footer and Android navigation insets on the actual phone; verify fresh telemetry/readiness while stationary, permissions/provider loss, GPS uncertainty rejection, gentle yaw direction and hover, Stop during acquisition and aiming, RC stick/pause/mode takeover, native RTH and landing while aiming, pause/reopen, USB/aircraft disconnect, and interrupted command delivery/process death. Record APK hash, firmware, SDK/app versions and observed timing. App death cannot run cleanup; it is not evidence of signal-loss RTH. The recorded v1.0 observations do not establish these v2.0 behaviors.

Official API reference used for the control contract: https://developer.dji.com/api-reference-v5/android-api/Components/IVirtualStickManager/IVirtualStickManager.html . Local SDK 5.18.0 signatures and bundled IKeyManager documentation were also inspected.

## Original v2.0 proposal (retained planning record)

The following sections preserve the pre-implementation review and original conceptual hierarchy. Where they differ, the implemented description above is authoritative; “proposed” and “not implemented” below describe that earlier planning stage.

### Scope

Fail-safe behavior comes first; gentle aiming comes second. Aircraft is manually positioned and remains hovering while cam3 requests yaw toward the phone coordinates. No requested forward/backward, lateral, ascent or descent velocity. Aircraft stabilization may still correct its position. No computer vision. Gimbal tilt remains manual; automatic vertical centering is not included. RTH, landing and pilot control remain separate aircraft functions and take priority.

Initial target source is the phone running cam3. That phone must be with the intended subject. A separate subject phone would require a separately designed location transport. This version supersedes the earlier Come to me translation proposal.

All source paths below are relative to this project directory. New methods and filenames are proposed and do not yet exist.

### UI changes

Status: proposed, not implemented.

| Change | User-visible behavior |
|---|---|
| Start aiming button | Enables automatic yaw toward the phone. Disabled until required location, aircraft state and control prerequisites are usable. |
| Stop aiming button | Stops automatic aiming and releases this feature's control. Remains clearly accessible while aiming, including when target data becomes invalid. |
| Aiming status | Shows Off, Waiting for GPS, Aiming, or Stopped, with a readable reason where applicable. |
| Phone location quality | Shows GPS accuracy and age of the latest fix so the user can judge whether the target position is usable. |
| Navigation-bar spacing | Keeps Record and other controls clear of Android Home/Back/navigation buttons. |

Gimbal tilt remains manual. Existing camera, recording, flight information and RTH controls stay available. Aiming starts only after an explicit Start aiming action; it never automatically resumes after a fault, reconnect or reopening the screen. No computer-vision controls or automatic translation controls are added.

Implementation mapping: DefaultLayoutActivity.java handles buttons, state rendering and system-bar insets; uxsdk_activity_default_layout.xml defines the controls and status area; strings.xml supplies labels and readable reasons. Exact placement and spacing remain to be checked on the phone, including both button and gesture navigation.
### 1. Existing DefaultLayoutActivity.java

Path: SampleCode-V5/android-sdk-v5-uxsdk/src/main/java/dji/v5/ux/sample/showcase/defaultlayout/DefaultLayoutActivity.java

- onCreate(): create YawAimingController, connect status rendering, apply Android system-bar insets.
- initClickListener(): register Start aiming and Stop aiming click handlers.
- onPause(): stop aiming and release the feature's control and location subscriptions when leaving the screen.
- onDestroy(): dispose remaining feature resources; cleanup must be idempotent.
- New renderAimingState(): show Off, Waiting for GPS, Aiming, or Stopped, including the reason.
- New applySystemBarInsets(): keep recording and aiming buttons clear of Android navigation controls.
- Returning to the screen must not automatically resume aiming.

### 2. New PhoneTargetLocationSource.java

Proposed directory for all four new aiming classes: SampleCode-V5/android-sdk-v5-uxsdk/src/main/java/dji/v5/ux/sample/showcase/defaultlayout/aiming/

- start(): check location permission and provider; subscribe to phone GPS.
- onLocationChanged(): retain an immutable fix with latitude, longitude, accuracy and original monotonic fix timestamp.
- getLatestFix(): return the latest fix without refreshing its age merely because it was read.
- stop(): remove only this feature's listener.

Use a separate location subscription with no minimum movement distance so a stationary phone can produce fresh fixes. The existing MobileGPSLocationUtil requests a one-metre minimum movement; leave its map behavior intact. Review merged permissions and runtime permission handling before adding anything redundant. The sample already requests coarse and fine location at runtime.

### 3. New AircraftAimingTelemetry.java

- start(): subscribe to aircraft position, heading, connection, flight state and relevant RC inputs.
- getSnapshot(): return a consistent immutable snapshot with freshness information.
- stop(): remove only this feature's listeners.

Check whether DJI listeners deliver periodic updates or only changed values. Unchanged coordinates alone must not be classified as a broken connection. Define how aircraft telemetry freshness is established before enabling automatic control.

### 4. New YawAimingMath.java

Pure calculations, with no DJI commands:
- bearingToTarget(): calculate aircraft-to-phone bearing.
- shortestHeadingError(): normalize wraparound, e.g. 359 to 1 degrees requires a 2-degree turn.
- calculateYawRate(): apply a gentle maximum yaw rate, acceleration limit and heading tolerance.
- isBearingUsable(): reject ambiguous bearings where location uncertainty is too large relative to horizontal separation, including a target nearly directly below the aircraft.

Numerical thresholds remain to be selected and validated; do not present them as tested safety limits.

### 5. New YawAimingController.java

- startAiming(): validate prerequisites, request Virtual Stick authority and wait for required ownership/mode confirmation.
- startControlLoop(): start one dedicated ScheduledExecutorService; schedule tick with fixed delay, initially 100 ms after each completed tick.
- tick(): read snapshots, validate, calculate and send sequentially. No separate sender repeats the last command. No overlapping ticks or catch-up bursts.
- validateInputs(): reject invalid/stale inputs, unsuitable flight state, lost connection or lost control ownership.
- buildYawOnlyCommand(): explicitly configure horizontal and vertical VELOCITY and yaw ANGULAR_VELOCITY. Always request zero horizontal and vertical velocities; only yaw varies.
- stopAiming(reason): stop calculations; while still owning control, request zero yaw and release Virtual Stick. Do not send competing stop commands after another controller or RTH has taken ownership.
- onControlAuthorityChanged(): stop on takeover and never automatically reclaim control.
- dispose(): cancel scheduling and remove owned listeners.

Safety/lifecycle details:
- Use a session identifier to reject a late enable callback after Stop or a newer session. Handle any late control grant so it does not leave unintended ownership behind.
- Serialize state transitions and sending; recheck cancellation/ownership immediately before sending.
- Exceptions leave aiming stopped; failure to release must be visible rather than reported as success.
- Stop/RTH/takeover must not trigger automatic re-enabling.
- No SDK callback proves the aircraft actually stopped.
- A process crash cannot execute cleanup. Command-loss stopping and takeover with Virtual Stick active still require hardware verification.
- The sample VirtualStickVM defaults horizontal control to ANGLE and onCleared() removes listeners without disabling Virtual Stick. Do not copy these behaviors into the new controller. The testing-tools VM is not the planned implementation target.

### 6. Existing uxsdk_activity_default_layout.xml

Path: SampleCode-V5/android-sdk-v5-uxsdk/src/main/res/layout/uxsdk_activity_default_layout.xml

Add Start/Stop aiming controls, a compact status label, and an identifiable container for system-bar-safe spacing. Preserve usable recording and flight controls.

### 7. Existing strings.xml

Path: SampleCode-V5/android-sdk-v5-uxsdk/src/main/res/values/strings.xml

Add labels and stop reasons, including location unavailable/too old, heading unavailable and pilot takeover. Add XML comments identifying the v2.0 additions.

### 8. Existing build.gradle

Path: SampleCode-V5/android-sdk-v5-sample/build.gradle

Set versionName to 2.0 and increment versionCode when implementing this version. Keep package/App Key pairing unchanged. Comment the version change.

### 9. New focused tests

Place tests in the UXSDK module test source tree under the corresponding aiming package. Test bearing wraparound, rate limiting, stale fixes, ambiguous bearings, zero translation requests, cancellation/late enable callbacks and takeover. Inject a clock and command interface where needed to test failures without controlling an aircraft.

### Call hierarchy

Proposed source files and methods; not implemented yet.

```text
FILE: DefaultLayoutActivity.java — existing file
│
├─ onCreate()
│  ├─ Creates YawAimingController                          [new]
│  └─ initClickListener()
│     └─ Registers Start aiming button's click handler    [new]
│
└─ You press “Start aiming”
   └─ Android invokes the click handler
      └─ yawAimingController.startAiming()
         │
         FILE: aiming/YawAimingController.java — new file
         │
         └─ startAiming()
            ├─ Validate prerequisites using:
            │  ├─ PhoneTargetLocationSource.java          [new file]
            │  │  └─ getLatestFix()
            │  └─ AircraftAimingTelemetry.java            [new file]
            │     └─ getSnapshot()
            │
            └─ VirtualStickManager.enableVirtualStick(callback)
               │
               DJI SDK library — existing dependency
               └─ Invokes callback.onSuccess()
                  │
                  FILE: aiming/YawAimingController.java
                  ├─ Reject cancelled/stale start request
                  ├─ Enable advanced mode
                  └─ When SDK state confirms ownership/mode:
                     └─ startControlLoop()
                        └─ scheduler.scheduleWithFixedDelay(
                               this::tick, 0, 100, MILLISECONDS)
                           │
                           Java concurrency library
                           └─ Invokes tick() repeatedly
                              │
                              FILE: aiming/YawAimingController.java
                              └─ tick()
                                 ├─ Read latest input snapshots
                                 ├─ validateInputs()
                                 ├─ Calculate yaw using:
                                 │  FILE: aiming/YawAimingMath.java
                                 │  ├─ bearingToTarget()
                                 │  ├─ shortestHeadingError()
                                 │  └─ calculateYawRate()
                                 ├─ buildYawOnlyCommand()
                                 └─ VirtualStickManager
                                    .sendVirtualStickAdvancedParam()
                                       └─ DJI SDK sends command
```

This is a conceptual asynchronous flow. Callback and state-listener ordering must be handled explicitly rather than assumed. A failed or cancelled start must not schedule tick(). After each completed tick, the scheduler waits 100 ms before invoking it again; there are no overlapping ticks or catch-up bursts.
### Validation and release status

Completed: read-only source/design review. No v2.0 build, automated tests, flight commands or device validation performed.

Required before operational use: focused tests, build verification, Android navigation-button layout check, and controlled hardware validation of yaw direction, hover behavior, Stop, pilot takeover, RTH while aiming and interrupted command delivery. Preserve exact firmware/app identity and observations. Do not infer these from v1.0 tests.

## v1.0 - DJI sample baseline

User reports successful flight, directional controls, camera operation, battery-triggered RTH and signal-loss RTH on Mini 3 Pro / RC-N1. These are user observations, not independently instrumented results. Installed APK correspondence, precise test conditions and whether Virtual Stick was active were not established. Recording control exists; user reported Android Home/navigation overlap preventing access. No retrospective claim that all sample safety features were validated.


