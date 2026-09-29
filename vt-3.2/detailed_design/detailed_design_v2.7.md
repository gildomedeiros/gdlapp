# CAM3 v2.7 detailed design — Goalkeeper direction commitment

Date: 2026-09-18, Australia/Brisbane.
Status: IMPLEMENTED IN WORKTREE; desktop validation recorded below; phone/aircraft validation pending.
Baseline: local v2.5, git commit `8ae863d`, copied and verified before edits. No v2.6 smoothing code is included.
Worktree: `C:/Users/gildo/.codex/worktrees/cam3-27/gdlapp/cam3`.
Branch: `codex/cam3-2.7`. Version name 2.7; version code 9; application ID unchanged.

## 1. User-approved scope

Prioritise filming a consistent wave ride over following nearby reversals during paddle-out. Keep a chosen clockwise or anticlockwise aiming direction throughout a nearby pass, accepting the long route when the subject reverses. Reconsider that commitment every five minutes, so the direction can change between rides without an indefinite unlocked period.

- Opt-in Nearby tracking menu toggle, OFF by default.
- Independent three-second direction voting, including while far away or toggle OFF.
- Capture dominant direction below 60 m, retain it despite opposing votes, refresh after five minutes, release at 60 m or farther.
- Use raw target coordinates and the remaining angle in the committed direction, not v2.6 fitted positions.
- Distance-based rate and acceleration multiplier, bounded at 12 degrees/s and 6 degrees/s squared.
- Remove the former 5 m input rejection/pause for both sources and both toggle states.
- Retain the three-degree alignment deadband, fresh-input checks, pause/recovery and permanent cancellation/authority rules.
- Full Log records every foreground control cycle and every existing received GPS record so scenario tables can be reconstructed.

The feature commits a direction, not a constant motor command. It stops yaw inside the alignment deadband, at exactly coincident coordinates, or when existing pause/stop conditions require it. Opposing votes alone do not stop or reverse a held lock.

## 2. Baseline inspection and isolation

The inspected local v2.5 includes LoRa Wi-Fi default source, phone selection, accepted-sequence freshness rules, 100 ms fixed-delay aiming loop, 8 degrees/s maximum and 4 degrees/s squared acceleration, automatic full JSONL files and private minimal diagnostics. It contains no v2.6 nearby toggle or trend smoothing. All 15,153 source and ancillary files were copied and compared using SHA-256 before editing. The local checkout is not the build directory.

New worktree creation uses the existing v2.5 commit on `codex/cam3-2.7`. The original project has no 2.7 edits. Existing old version design files remain unchanged. No publish, merge, install or aircraft command is part of desktop implementation.

## 3. Direction voting

`DominantDirectionTracker.observe(Inputs, now)` runs once per foreground controller tick. It processes the latest available selected-source GPS fix. Duplicate fix timestamps do not add votes; only a new fix can do so. At the expected 2 Hz there are approximately six directional comparisons in the rolling window. The first fix establishes a baseline and adds no vote.

Votes at least 3000 ms old expire every tick, including ticks without a new fix. There is no repeated vote on a 100 ms reuse of a 500 ms GPS sample. Fix timestamps remain original; nothing refreshes GPS age.

For a new fix, compute bearings from the **same current aircraft position** to the previous and current target coordinates. The wrapped change between those bearings determines the vote:

- Positive change: Right / clockwise, +1.
- Negative change: Left / anticlockwise, -1.
- Identical bearing, to numerical tolerance 1e-7 degrees: neutral, no directional vote.
- A numerically exact half-turn is ambiguous and gets no directional vote.
- A coincident previous/current target and aircraft position has no defined bearing: establish a baseline without a directional vote.

Both bearings use geographic north as zero; aircraft heading does not enter the vote. Comparing both target positions about the same aircraft location prevents yaw or translation alone from inventing surfer-motion votes. The vote is a local movement estimate, not proof of physical surfer direction or a wave classification.

`calculateDominantDirection()` returns Left only if left votes are at least three and strictly exceed right votes. Otherwise it returns Right. Logs distinguish confirmed Right votes from the intentional Right default. Zero votes and ties therefore choose Right, as requested.

Invalid coordinates or stale/missing aircraft/target positions break the comparison baseline. Existing votes continue expiring normally. A reversed fix clock or a new-fix gap of at least three seconds clears history. A controller clock rewind clears it too. No new-fix motion is inferred across a stale gap. GPS packet acceptance and raw-data logging remain in the existing source adapters; this helper does not reject otherwise accepted raw coordinates. The latest-fix adapter is sampled at the control-loop rate; a burst faster than that can contain raw packets that are logged but not individually voted.

## 4. Direction lock and lifecycle

`NearbyDirectionLock` is a separate, executor-owned helper. Its direction is None, Right or Left. It stores the monotonic capture time.

| Condition | Result |
| --- | --- |
| Toggle OFF | Clear lock; use shortest-route aiming |
| Aiming active, fresh position below 60 m, no lock | Capture current dominant direction |
| Fresh position below 60 m, locked | Keep direction regardless of new votes |
| Locked for at least 300000 ms, fresh position available below 60 m | Refresh from latest dominant direction, even if unchanged; restart timer |
| Fresh position at least 60 m away | Release lock, including during a temporary pause |
| Temporary input/pilot pause | No active yaw calculation; retain commitment and age; a held lock may refresh with fresh position |
| Missing/stale position at expiry | No commands; defer lock update until a valid position is available |
| Start new session | Clear prior lock, retain current foreground vote history |
| STOP/RTH/landing/takeover/session cancellation | Clear commitment; existing permanent-stop semantics retained |
| Source change or screen shutdown | Clear votes and lock |

Starting aiming already inside 60 m captures immediately using current votes (Right if insufficient). There is no requirement to approach from outside. A paused session cannot create a new lock, but can release or refresh an existing one when geometry is fresh. Capture resumes only when aiming has recovered. Mode and source changes are permitted only OFF/STOPPED and are rechecked on the executor.

There is no range hysteresis: precisely 60 m is outside. GPS noise around 60 m can cause exit/re-entry, as dictated by the agreed boundary. Time alone never leaves a nearby enabled session unlocked: the five-minute operation replaces the old commitment immediately when valid geometry is available.

## 5. Geometry, speed and close distance

The original input validation runs before the new yaw calculation, and final pre-send validation remains afterward. Raw coordinates are used directly.

`YawAimingMath.directedError(bearing, heading, direction)` first calculates the shortest signed error. If its magnitude is at most three degrees, retain that short error to produce zero yaw. Otherwise:

- No lock: shortest error in [-180, 180).
- Right lock: clockwise remaining angle in (0, 360).
- Left lock: anticlockwise remaining angle in (-360, 0).

For example, subject 30 degrees clockwise with a Left lock produces -330 degrees remaining. It follows the long route, rather than zeroing because of the opposing short-route request. An overshoot within the alignment tolerance does not initiate another revolution. An error outside that tolerance on the opposite side does use the long route; this is a consequence of the requested commitment.

Desired magnitude is `min(8, (abs(error)-3)*0.5) * multiplier`, with the selected error's sign. The multiplier is one outside the nearby lock, grows linearly from 1 at 60 m to 1.5 at 30 m, and stays 1.5 below 30 m. This does not depend on a v2.6 smoothing-confidence gate.

| Distance, with active nearby lock | Multiplier | Desired rate cap | Ramp acceleration |
| --- | ---: | ---: | ---: |
| 60 m / no lock | 1 | 8 degrees/s | 4 degrees/s squared |
| 45 m | 1.25 | 10 degrees/s | 5 degrees/s squared |
| 30 m or less | 1.5 | 12 degrees/s | 6 degrees/s squared |

The opt-in calculator has an absolute 12 degrees/s bound. On range exit a previous rate above 8 decelerates toward 8 using the original ramp; it can transiently exceed 8 until that ramp finishes. OFF uses the original calculator and command-factory 8-degree guard. A lock capture/refresh that genuinely changes commanded sign retains the original one-cycle zero before ramping in the new direction. Opposing votes while a lock stays unchanged do not invoke that brake.

The 5 m eligibility rejection is removed globally, including when Nearby tracking is OFF. Valid target coordinates at 4 m can aim normally. Exactly zero separation issues zero yaw while staying in AIMING because bearing is undefined; it does not enter a distance pause. No camera tilt or commanded translation is introduced. Pitch, roll and vertical velocity remain explicitly zero in the command factory.

## 6. UI

`DefaultLayoutActivity` adds one checkable Nearby tracking item to the existing overflow menu. It starts OFF and is not persisted across process restarts. It is disabled during an active/unreleased session. Details includes toggle status, dominant direction, right/left vote counts, locked direction and lock age. The compact footer is unchanged apart from removing the obsolete distance-pause message. Distance remains reported in Details with an explicit no-minimum-distance-pause description.

## 7. Full and minimal logs

The v2.5 destinations and bounded asynchronous writer remain: Full Log produces accessible `Downloads/CAM3/cam3_full_*.jsonl`; OFF retains private minimal diagnostics. Existing raw datagrams, accepted/rejected packets, missing-sequence events, phone fixes and storage-overflow reporting are retained. Packet sequence now travels atomically in the immutable LoRa Fix, making a cycle-to-packet join explicit. Phone sequence is null.

New `aiming_cycle` records are written on every foreground control tick (nominally 100 ms, not a real-time guarantee), including OFF, paused and stopped states. They contain:

- Cycle/session identifiers and cycle elapsed-realtime timestamp.
- State, reason, input validation problem, decision and selected GPS source.
- Exact decision target latitude/longitude, fix timestamp, sequence and age.
- Aircraft latitude/longitude, heading, telemetry timestamp and age.
- Horizontal separation, north-referenced subject bearing, heading-relative bearing.
- Vote observation timestamp, new-fix indicator, new vote, bearing change, vote counts, dominant direction and default-Right indicator.
- Toggle, under-60 m flag, lock direction and lock age.
- Directed remaining angle, applied multiplier and calculated/requested yaw rate.
- Whether a command was actually submitted in this cycle and its submitted yaw rate, or null when none was submitted.

The adapter logs `session.cycleInputs`, the same immutable snapshot used in that decision. Before/after asynchronous updates do not replace its coordinates with a newer observation. Voting can observe an earlier fix in the same cycle; its separate `voteFixMs` identifies this. Cycles with no aiming input decision use the foreground observation and carry `decision=no_command`. Undefined angles and unavailable values serialize as JSON null, not invalid NaN literals. Null requested speed in paused/off cycles does not imply a command was submitted. `yaw_command` records also include cycleId and are written after the SDK call returns; API submission is not measured aircraft response.

`nearby_mode` and `nearby_lock` events record enabled changes and capture/refresh/release reasons. They go to full and minimal diagnostics, without coordinates. Per-cycle traffic goes only to Full Log. Minimal missing/invalid-data and control event policies remain.

Log wall-clock `timestamp` includes timezone. Use explicitly named `cycleAtMs`, `targetFixMs`, and `aircraftFixMs` for elapsed-realtime comparisons; the legacy generic log `monoMs` uses System.nanoTime and must not be mixed with elapsedRealtime timestamps.

This supports a measured scenario table of timestamp, positions, distance, estimated movement, votes, lock, route, rate and pause/stop decisions. North can be rendered as fixed 12 o'clock; relative bearing can instead use drone heading as 12 when explicitly labelled. GPS cannot prove wave start, paddle-out or subject visibility in video. Such labels require user/video context.

Full logging remains bounded; overflow produces explicit loss records. Every cycle is attempted, but storage failure or overflow can prevent complete reconstruction. Logging exceptions cannot control the drone. No historic rate is fabricated as a new submission.

## 8. Source files and methods

Java paths below are relative to `SampleCode-V5/android-sdk-v5-uxsdk/src/main/java/dji/v5/ux/sample/showcase/defaultlayout`.

| File | Methods / purpose |
| --- | --- |
| New `aiming/DominantDirectionTracker.java` | `observe`, vote expiration, `calculateDominantDirection`, counts, reset and new-fix diagnostics |
| New `aiming/NearbyDirectionLock.java` | `update`, `clear`, `age`, `multiplier`; direction lifecycle without flight APIs |
| New `aiming/AimingCycleLog.java` | Production pure-Java cycle serialization adapter, directly tested with real writer |
| `aiming/AimingSession.java` | Optional Port toggle; tracker/lock ownership; observation and resets; directed yaw calculation; per-cycle decision fields; Fix sequence; remove distance validation |
| `aiming/YawAimingMath.java` | Directed angle, bounded nearby calculator, zero minimum-distance constant |
| `aiming/YawOnlyCommand.java` | Overloaded factory permits 12 only when nearby enabled; retains zero translation and SDK modes |
| `aiming/YawAimingController.java` | Queued toggle, monitoring observation, full-cycle adapter, status, source/lifecycle resets and submission-cycle correlation |
| `aiming/LoRaTargetLocationSource.java` | Add sequence to immutable accepted Fix; no packet acceptance changes |
| `DefaultLayoutActivity.java` | Toggle and Details; remove obsolete distance message |
| `src/main/res/values/strings.xml` | Nearby menu text |
| `aiming/FullSessionLog.java`, `aiming/AimingDiagnosticLogger.java` | Version identifiers 2.7 |
| `SampleCode-V5/android-sdk-v5-sample/build.gradle` | Version code 9/name 2.7 |
| `src/test/java/.../aiming/GoalkeeperTest.java` | New direction, lock, geometry, gain and session integration tests |
| `src/test/java/.../aiming/AimingCycleLogTest.java` | Real per-cycle JSONL adapter/writer test |
| `AimingSessionTest.java`, `LoRaTelemetryTest.java`, `AimingDiagnosticLoggerTest.java` under test tree | Adapt removed distance expectation, fake toggle and version identifiers; retain regression coverage |
| `tools/test-aiming.ps1` | Compile helpers, run new tests and independently parse cycle JSON |
| Design index / ENHANCEMENTS | Point to v2.7; retain historical releases and unrelated pending work |

## 9. File-labelled call hierarchy

```text
DefaultLayoutActivity menu
  -> YawAimingController.setNearbyTrackingEnabled
       -> executor eligibility recheck -> AimingSession.resetNearby

YawAimingController.tick
  -> AircraftAimingTelemetry.poll / inputs snapshot
  -> AimingSession.observeDirection
       -> DominantDirectionTracker.observe (new-fix votes + expiry)
       -> NearbyDirectionLock.update (existing lock exit/expiry, fresh position only)
  -> AimingSession.tick
       -> original session/ownership/input checks
       -> NearbyDirectionLock.update (capture permitted in AIMING)
       -> YawAimingMath.directedError
       -> calculateNearbyYawRate OR original calculateYawRate
       -> original final pre-send validation
       -> YawAimingController.sendYaw
            -> YawOnlyCommand.build(rate, nearbyEnabled)
            -> existing DJI Virtual Stick call
            -> yaw_command log with cycle ID
  -> AimingCycleLog.record -> FullSessionLog queue -> JSONL writer
  -> existing periodic diagnostic summary and UI update
```

## 10. Validation and pending hardware checks

Desktop tests exercise rolling vote counts with exact 500 ms arrivals and 3000 ms expiry, Right defaults/ties, clockwise/north wrap, stationary fixes, heading changes, aircraft translation, backwards time, gaps, nearby capture, opposing votes, range exit, five-minute recapture, temporary pause, mode exit and stop. Geometry tests cover both long routes, alignment before route selection, caps, acceleration, range-exit deceleration and original gain-one equivalence. Real session tests verify below-5 m aiming, coincident zero, stale GPS pause, stop, and takeover. Existing acquisition/race/recovery and full/minimal logging regressions remain.

Cycle logging tests use the real production adapter and asynchronous writer, with an intentionally newer observation than the actual decision input; coordinates/sequence must still match the original decision. Coincident null bearing, paused state, submission absence and independent JSON parsing are checked. Desktop tests cannot execute the DJI provided stub methods: factory bytecode checks verify the explicit zero-translation values and modes, while Android build verifies integration.

Phone/TTGO/DJI validation remains necessary for menu operation, actual storage throughput, film framing, rate tuning and real movement/noise. No flight performance is inferred from the desktop scenarios. Prior repository-wide lint findings and control-release confirmation behavior are not fixed by this release. E06 raw GPS-jump rejection, forward prediction, automatic gimbal framing and E01 release recovery remain separate work.

### Final validation record

All worktree tests passed: 137 control assertions, LoRa regression suite, command bytecode checks, 37 diagnostic assertions, full/minimal logging and independent JSON parsing, 123 goalkeeper assertions, production cycle adapter/writer checks and independent cycle JSON validation. Offline :sample:assembleDebug succeeded after final changes (21 seconds; 10 tasks executed, 49 up-to-date). Output metadata confirms version 2.7/code 9. APK: build/cam3-v2.7.apk. Existing SDK warnings remain; no claim of lint cleanliness or hardware validation. Original local 2.5 was rechecked: all 15,153 file hashes and file count unchanged.

