# cam3 v2.4 detailed design — LoRa Wi-Fi target GPS

Status: IMPLEMENTED LOCALLY; APK build and desktop aiming tests passed. Full Android lint failed; live device validation pending.
Date: 2026-09-16 (Australia/Brisbane).
Workspace: C:/Users/gildo/gdlapp/cam3. Version name 2.4; version code 6. Application ID: com.dji.sampleV5.aircraft.

## 1. Scope and relationship to v2.3

Add a selectable source for the target coordinates used by aircraft yaw aiming: **LoRa Wi-Fi by default**, or **Phone GPS**. Both feed the existing AimingSession.Fix input. LoRa means the surfer/transmitter GPS position relayed through the TTGO receiver; it is not the receiver's position or the aircraft's GPS.

The user explicitly requested bypassing the reported horizontal-accuracy gate for both sources. The v2.3 positive/at-most-10-metre accuracy requirement no longer applies in v2.4. Accuracy, when present, is informational. LoRa HDOP is displayed/logged without converting it into a made-up accuracy in metres. LoRa fixes carry NaN accuracy.

Coordinate validity, a target age of at most 3000 ms, the shared 5 m horizontal separation minimum, aircraft telemetry checks and control-authority checks remain. The existing 100 ms control loop, yaw maximum 8 degrees/s, acceleration maximum 4 degrees/s², 3-degree deadband, zero commanded translation, manual gimbal and v2.3 pause/recovery policy remain. Map location behavior is unchanged.

There is no automatic fallback to Phone GPS, prediction, interpolation, replay of missing positions or GPS-jump filter. This release does not resolve enhancement E01 (unconfirmed release after landing/RTH). Historical version designs describe their own releases; this document supersedes their phone-only and accuracy-gate descriptions for v2.4.

## 2. Source selection and UI

The small map preview on the right is hidden with layout visibility GONE. Its existing initialization/lifecycle code remains; this change hides the display only and does not alter GPS subscriptions, LoRa reception or aiming logic. No map visibility toggle or version increment is added.

The horizontal situation indicator (compass/radar overlay) is hidden in the layout. Closing gimbal fine adjustment also keeps it hidden, so that action cannot restore the overlay. This is a fixed UI choice, without a new configuration toggle; GPS reception, aircraft telemetry and aiming calculations are unchanged. Version name/code remain 2.4/6.

- The compact flight footer is fixed at 48 dp (one row): Start, Stop, GPS selector, status/telemetry and overflow. Green Start and red Stop icons have 48 x 48 dp touch areas, accessible action labels and a dimmed disabled Start state. The GPS button (`GPS: LoRa ▾` or `GPS: Phone ▾`) is 112 dp wide beside those controls.
- Its dialog offers `LoRa Wi-Fi (GDL_LORA)` and `Phone GPS`, plus a Wi-Fi settings shortcut.
- Selection is permitted only in OFF or STOPPED. It is disabled during STARTING, AIMING, PAUSED, STOPPING and RELEASE_UNCONFIRMED. The controller rechecks eligibility when processing the selection on its executor.
- Switching stops both subscriptions, changes the selected source and starts that source if the screen is foreground. The previous source's fix is cleared; an explicit Start and the normal readiness checks remain necessary.
- Selection lives in the process-scoped controller. It survives flight-screen recreation in that process but is not saved as a preference. A new process defaults to LoRa.
- Status and telemetry share one line beside the controls, with state/reason first; overflow text is ellipsized rather than expanding the footer. Common telemetry/GPS waiting messages are shortened. Source, fix age and available LoRa RSSI/SNR share the telemetry line. Full explanations remain in Details.
- A 48 x 48 dp overflow icon opens Details and Export log. Export is disabled in the menu while its picker/write is pending and becomes available on cancellation, launch failure or completion. Stop remains outside the menu. Details adds receiver status, satellites, HDOP, sequence, gap/repeat/older/restart counters, freshness, informational accuracy and separation.
- Opening Wi-Fi settings or the export picker invokes the existing screen-pause behavior: it cancels an active aiming session. Returning starts monitoring, not aiming.

Connect Android Wi-Fi manually to `GDL_LORA` (receiver configuration password `gdl12345`). The app does not join the access point or verify its SSID. Close competing Python/lora_tester Wi-Fi receivers: the TTGO firmware tracks its current client, and another Android listener can occupy UDP port 5005.

## 3. Wi-Fi transport and packet acceptance

### Transport lifecycle

LoRaTargetLocationSource owns a daemon worker and a Wi-Fi high-performance lock. It finds a Wi-Fi Network, binds only its DatagramSocket to that network, and then binds local UDP port 5005. It does not bind the application's entire process or change DJI's network routing.

The worker sends `HELLO` to `192.168.4.1:5006` initially and every 5 seconds. Receives time out every 500 ms so the worker can check stop/network state. Only datagrams from that address and source port 5006 are processed. Newline-separated records are trimmed and parsed individually.

No Wi-Fi clears the fix and retries every 500 ms. Socket I/O failures clear the fix and retry after 1 second. A network change closes/reopens the socket. The tracker survives reconnects within the same source run. Stop clears the fix, marks the run inactive and closes its socket. Each run has an identity; obsolete workers cannot publish into a newer run. Other exceptions report unavailable and end the run; a later source start can retry.

### Wire format

Exactly eleven comma-separated fields:

```text
RX,GPS,sequence,senderMs,latitude,longitude,satellites,hdop,rssi,snr,receiverMissed
```

Sequence, senderMs and receiverMissed are unsigned 32-bit integers. Coordinates use the existing coordinate validator; satellites must be positive; HDOP must be finite and nonnegative. RSSI must be an integer in the parser's signed-16-bit range; SNR must be finite and within -128 to 127, including decimal values. These broad radio-field bounds validate the format, not reception quality thresholds.

`GDL_LILYGO_READY` changes connection status without refreshing GPS freshness. `RAW,NOFIX,` clears the target fix immediately. Other malformed/non-GPS records do not produce a fix or refresh its age. RSSI, SNR and HDOP are not aiming eligibility thresholds.

### Sequence tracking

1. Accept the first valid packet in a source run.
2. Ignore repeated sequence numbers without refreshing the target timestamp.
3. Accept forward sequence and sender-time progress using unsigned differences below half the 32-bit range; sender time must advance. Add sequence delta minus one to the gap counter.
4. Reject old/out-of-order packets and frozen sender timestamps. Unsigned counter wrap is supported.
5. Infer transmitter restart after three consecutive increasing sequence packets with both counters below the last accepted packet. Candidate sequence numbers must increment by one, sender time must advance, and adjacent candidates must arrive within 3000 ms. Repeated candidates do not count. Accept the third packet as the new baseline and increment restarts.

Gap counts are diagnostics; missing packets are not supplied to aiming. They measure holes in the accepted sequence stream, not proof of whether loss occurred over LoRa, Wi-Fi or parsing. The separately reported receiverMissed field is logged as received. Restart recognition is a heuristic, not authentication; a sufficiently consistent replay could resemble a restart.

### Time semantics

An accepted LoRa packet gets Android elapsedRealtime at datagram receipt. senderMs is used for ordering, not compared directly with Android's clock. Phone fixes retain the Android Location elapsed-realtime timestamp. Both enter the same 3000 ms freshness gate.

For LoRa, age is time since receipt, not a verified end-to-end GPS measurement age. The protocol does not provide synchronized clocks or authenticated measurement freshness. Sequence checks prevent ordinary duplicate refresh, but cannot guarantee detection of all delayed streams. A valid first packet after a new subscription is accepted without a previous baseline.

## 4. Source files and methods

Paths below are relative to the workspace. P = `SampleCode-V5/android-sdk-v5-uxsdk/src/main/java/dji/v5/ux/sample/showcase/defaultlayout`; U = `SampleCode-V5/android-sdk-v5-uxsdk`.

| File | Implemented changes |
|---|---|
| P/aiming/LoRaTelemetry.java (new) | `parse`, numeric helpers and constructor validate the TTGO record. `Tracker.accept` implements ordering, gaps, duplicates, wrap and restart candidates. Independent of Android and DJI. |
| P/aiming/LoRaTargetLocationSource.java (new) | `start`, `stop`, `receive`, `wifiNetwork`, `handle`, `live` and `connection` manage transport, lifecycle and publication. `getLatestFix`, `signalSummary` and `details` expose snapshots. No flight-control interface. |
| P/aiming/PhoneTargetLocationSource.java | `onLocationChanged` accepts locations without reported accuracy and stores NaN in that case. Mock rejection, increasing timestamps and the 500 ms/zero-distance GPS subscription remain. |
| P/aiming/YawAimingController.java | Constructor creates the LoRa source. `usesPhoneGps`, `canSelectGpsSource`, `selectPhoneGps`, `startTargetSource`, `getTargetFix` and `targetSummary` handle selection and display. `resume`/`pause` manage selected-source lifecycle on the controller executor. `inputs`, `tick`, `recordDiagnostics` and the historically named `phoneDetails` consume/report the selected source. |
| P/aiming/AimingSession.java | `Inputs.validate` no longer requires reported accuracy when testing the separation gate. State-machine recovery and authority behavior remain. |
| P/aiming/YawAimingMath.java | `isBearingUsable` keeps its signature but ignores the accuracy argument; finite separation at least 5 m is required. Yaw calculations/constants unchanged. |
| P/DefaultLayoutActivity.java | `initClickListener` adds GPS selection, Wi-Fi settings and Details/Export overflow menu. `aimingExportPending` tracks export availability without referencing removed buttons. `renderAimingState` updates source label, selection availability, disabled Start opacity and single-line status/telemetry. Export filename identifies v2.4. |
| U/src/main/res/layout/uxsdk_activity_default_layout.xml | Fixed 48 dp single-row footer: Start/Stop ImageButtons, GPS selector, combined status/telemetry and overflow icon. |
| U/src/main/res/drawable/cam3_aiming_play.xml, cam3_aiming_stop.xml, cam3_aiming_more.xml | Vector icons for compact controls. strings.xml supplies the accessible overflow label. |
| U/src/main/res/values/strings.xml | Add source-button text; replace phone-only GPS status wording with selected-target wording. |
| U/src/main/AndroidManifest.xml | Declare INTERNET, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE and WAKE_LOCK for the receiver. Existing location permissions retained. |
| P/aiming/AimingDiagnosticLogger.java | Change process/export identity to cam3=2.4; bounded logger unchanged. |
| SampleCode-V5/android-sdk-v5-sample/build.gradle | versionName 2.4 and versionCode 6; application ID unchanged. |
| U/src/test/java/.../aiming/LoRaTelemetryTest.java (new) | Parser, ordering/restart/wrap, accuracy bypass, freshness and existing session pause/recovery integration checks. |
| U/src/test/java/.../aiming/AimingSessionTest.java | Update accuracy expectations; expose the package-local Fake helper to the new test. |
| U/src/test/java/.../aiming/AimingDiagnosticLoggerTest.java | Expect the v2.4 log identity. |
| tools/test-aiming.ps1 | Compile/run LoRaTelemetry and its tests alongside existing aiming/logger/compiled-command checks. |

The design index's convention of a nearby version-labelled comment for every existing-code change was not fully followed in the 2.4 implementation. Some touched blocks retain earlier comments. This document records the implemented behavior; it does not claim that source-comment requirement is complete.

## 5. File-labelled call hierarchy: GPS to aiming

```text
DefaultLayoutActivity.java: GPS dialog selection
  -> YawAimingController.java: selectPhoneGps() [controller executor]
     -> recheck OFF/STOPPED; stop both sources; set selection
     -> startTargetSource() if foreground

LoRaTargetLocationSource.java: receive() [Wi-Fi worker]
  -> handle(record, receivedAt)
     -> LoRaTelemetry.java: parse() -> Tracker.accept()
     -> AimingSession.java: new Fix(lat, lon, NaN, receivedAt)
     -> publish latest fix (no DJI call)

PhoneTargetLocationSource.java: onLocationChanged() [Android callback]
  -> AimingSession.java: new Fix(lat, lon, accuracy or NaN, location time)
  -> publish latest fix

YawAimingController.java: tick() [existing 100 ms worker]
  -> AircraftAimingTelemetry.java: poll()
  -> AimingSession.java: tick()
     -> YawAimingController.java: inputs() -> getTargetFix()
        -> AircraftAimingTelemetry.java: getSnapshot(selected fix)
     -> AimingSession.java: Inputs.validate()
     -> existing acquisition / pause / recovery / active-state logic
     -> YawAimingMath.java: bearing and calculateYawRate()
     -> YawAimingController.java: sendYaw()
        -> YawOnlyCommand.java: build()
        -> DJI sendVirtualStickAdvancedParam()

YawAimingController.java: recordDiagnostics() / observer notification
  -> selected-source Details and source/age snapshot
  -> DefaultLayoutActivity.java: renderAimingState()
```

The source enters aiming through `getTargetFix()` into the existing aircraft-plus-target Inputs. Packet receipt does not directly rotate the aircraft. Aircraft position and heading remain DJI telemetry; source selection changes only the target position.

## 6. Failure handling and retained controls

| Situation | Implemented behavior |
|---|---|
| Single/multiple missing packets with last fix still fresh | Existing controller continues using last accepted position. No extrapolation or replay. |
| Duplicate, old or malformed packet | Does not refresh the accepted target timestamp. |
| NOFIX, network disconnect or socket failure | Clear target; active session follows existing missing-GPS pause policy. |
| No accepted fix for more than 3000 ms | Existing stale-GPS pause policy. |
| Fresh target returns | Resume only after all existing conditions pass continuously for 2 s; no packet bypasses recovery. |
| Accuracy absent, zero or above the former 10 m threshold | No accuracy-based rejection for either source. |
| Invalid coordinates or separation below 5 m | Existing invalid-target/distance handling remains. |
| STOP, RTH, landing, screen closure, takeover or permanent fault | Existing permanent cancellation and release handling; source traffic cannot restart aiming. |

Target-only failures retain v2.3's existing state behavior; aircraft telemetry/control failures still use its separate control-confirmation rules. Source switching is unavailable while a grant is unresolved. No automatic control reacquisition is added.

## 7. Diagnostics and storage

LoRa events include `lora_connected`, `lora_connection_error`, `lora_error`, `lora_no_fix`, `lora_packet` and `lora_rejected`. Packet diagnostics include acceptance, sequence, senderMs, RSSI, SNR, satellites, HDOP and receiverMissed. Malformed raw payloads and coordinates are not logged. Selection emits `target_source`.

Readiness includes source, `accuracyGate=bypassed`, targetAccuracyM and targetAgeMs. The existing Details/blockers snapshots include the sequence counters. Main-screen signal values represent the last accepted fix; Details metrics can describe the most recently parsed packet even when rejected by the tracker. Handshakes indicate TTGO connectivity, not necessarily a fresh GPS fix.

The existing asynchronous logger remains best effort: 256 queued entries, dropped-entry reporting, and current/previous private files capped at 512 KiB each. Export uses `cam3-v2.4-aiming-<timestamp>.txt`. This is not lora_tester's full telemetry/KML recording; old diagnostics rotate out and every packet is not guaranteed permanent retention.

## 8. Validation record and remaining checks

Single-row footer follow-up (same v2.4/version code 6): parsed layout XML confirms 48 dp footer height; the removed quality TextView has no remaining activity references. `:sample:assembleDebug` passed (47 s); `build/cam3-v2.4.apk` was replaced. The previous two-row footer is superseded. Aiming logic remains unchanged; on-device visual verification is pending.

Map-hide follow-up (same v2.4/version code 6): verified MapWidget visibility is GONE in parsed layout XML. `:sample:assembleDebug` passed (9 s); `build/cam3-v2.4.apk` was replaced. No device installation or visual revalidation was performed.

Compass-hide follow-up (same v2.4/version code 6): layout XML parsed successfully; both activity visibility assignments keep the indicator GONE. `:sample:assembleDebug` passed (41 s), and `build/cam3-v2.4.apk` was replaced with the rebuilt APK. Device rendering was not revalidated; no aiming/control logic changed.

Compact-footer follow-up (same v2.4/version code 6): layout XML parsed successfully, removed Details/Export view references were checked, and the Android APK was rebuilt. This follow-up changes layout, presentation and menu dispatch only; control math/state-machine code is unchanged. The earlier aiming-test results below were not rerun for this UI-only change. Updated screen rendering and touch behavior still require device review; this change does not install the APK on the connected phone.

Executed during implementation on 2026-09-16:

- `tools/test-aiming.ps1`: PASS, 140 existing control/math/recovery assertions, the new TTGO/accuracy/freshness/recovery test, compiled zero-translation/velocity-mode verification, and 37 diagnostic assertions.
- New checks cover decimal SNR, repeats, old packets, gaps, frozen sender time, restart candidates/duplicates, unsigned wrap, malformed records, non-finite coordinates/SNR, zero satellites, accuracy bypass, the 3000 ms freshness boundary, retained distance validation, outage pause and 2-second recovery.
- Final `:sample:assembleDebug` with JDK 21 and cached dependencies: BUILD SUCCESSFUL. Output metadata confirms versionName 2.4, versionCode 6 and com.dji.sampleV5.aircraft.
- APK: `build/cam3-v2.4.apk`, copied from `SampleCode-V5/android-sdk-v5-sample/build/outputs/apk/debug/sample-debug.apk`. The compact-footer follow-up replaces this APK without changing version name/code; the earlier APK hash no longer identifies the updated artifact.
- Full Android lint is **not passing**. Offline lint first lacked commons-codec; after downloading dependencies, `:uxsdk:lintDebug` reported 240 errors and 613 warnings, including a BaseMethodDeprecationDetector failure in DJI widget code. No issues were reported in the new LoRa source/parser. Reports also flag the existing phone-location permission helper call and a missing inflated ID in DefaultLayoutActivity. These findings were not repaired in this release. The combined lint invocation failed; a successful APK build does not imply clean lint.
- Lint report: `SampleCode-V5/android-sdk-v5-uxsdk/build/reports/lint-results-debug.html`.

Still pending on real hardware: simultaneous DJI USB and TTGO Wi-Fi operation, Android's handling of a Wi-Fi network without Internet, receiver reconnect/restart, lifecycle/socket release, port conflicts, GPS selector/readability on the flight screen, and actual yaw/pause/recovery behavior. Pure tests use a fake session port; they do not exercise Android networking or prove physical aircraft behavior. No APK installation or flight validation is claimed.

Accuracy bypass accepts coordinate errors that may still be geographically plausible. RSSI/SNR cannot establish GPS correctness. The existing 5 m separation threshold does not prove positional accuracy, and suppression of new yaw commands does not prove physical stopping. GPS-jump filtering and motion prediction remain pending enhancements.
