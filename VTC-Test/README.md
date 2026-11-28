# VTC-Test Android camera controller

Standalone VTC-Test camera testing app, independent of VT and the future production VTC app. Project: C:\Users\gildo\gdlapp\VTC-Test. Android application ID: au.com.gildo.vtctest. Installs separately from the earlier au.com.gildo.vtc app. Controls the Canon SX70 HS over CCAPI Wi-Fi.

## Use
1. Install app/build/outputs/apk/debug/app-debug.apk on Android 8 or newer.
2. Connect Android to the SX70 Wi-Fi; remain connected without internet.
3. Enter the camera-displayed /ccapi address (default http://192.168.1.2:8080/ccapi).
4. Tap Connect. Confirm connection settings on the camera if prompted.
5. Start recording, choose a zoom target and tap Apply zoom, then Stop recording.

The app directs camera HTTP traffic over an available Wi-Fi network, even when cellular internet is active. Commands run sequentially off the UI thread. Zoom limits come from the camera. No background auto-recording or automatic retries. Recording labels report command acknowledgements, not independently observed recording state. Check the camera REC indicator. Closing the app does not stop camera recording. Reconnect after app recreation. There is no GPS tracking or automatic framing yet. GTi position tests are available in 0.3.0.

## Build
Open this folder in Android Studio with JDK 17 and Android SDK 35. Run gradlew.bat assembleDebug lintDebug. Build tools 36.0.0 are selected to match this machine. local.properties contains a local SDK location and is ignored.

## Verified camera protocol
GET /ccapi discovers advertised capabilities. Commands use camera-advertised same-origin URLs, preferring ver100.
- GET /shooting/control/zoom: value and ability min/max/step
- POST /shooting/control/zoom: {"value":40}
- POST /shooting/control/moviemode: {"action":"on"}
- POST /shooting/control/recbutton: {"action":"start"} / {"action":"stop"}

These commands were verified by the owner on the SX70 using PowerShell. Android hardware validation remains necessary. HTTP cleartext is enabled because the owner's camera serves HTTP on port 8080. No third-party runtime libraries, credentials, telemetry, or cloud service.

## Live preview (0.2.0)
Tap Start preview after connecting. The app requests small JPEG frames from the advertised same-version /shooting/liveview/flip endpoint, up to four per second. The same worker serializes preview and recording/zoom HTTP operations. Stop preview, app backgrounding and activity recreation cancel polling, clear the old image, and request liveviewsize off. They do not stop movie recording. Leaving the app during preview startup also queues cleanup. Preview failure clears the picture and reports the camera error instead of leaving a stale image labelled live. Start preview explicitly to retry.

A preview request can delay the next control by up to its 3-second connection/read timeouts if Wi-Fi fails. Compatibility during recording must be checked on the SX70. Preview is not full-quality recorded video and has no audio. Source recording remains on the camera SD card.

Run gradlew.bat testDebugUnitTest assembleDebug lintDebug. Frame-reader tests cover complete, truncated, empty, oversized, JSON error and fragmented responses. Device UI/lifecycle and simultaneous recording validation remain hardware checks.


## GTi tests and home Wi-Fi (0.3.0)
1. With SynScan connected to the mount, open Settings > Wi-Fi settings and configure Station mode with the home 2.4 GHz Wi-Fi SSID/password. Apply/restart as required by SynScan. Have that network available before powering on the GTi. Firmware/app menu names vary.
2. Join the same home Wi-Fi on Android. Find the GTi assigned IP through SynScan or the router. A router DHCP reservation keeps it stable. Do not use the default AP IP 192.168.4.1 unless testing on the GTi's own Wi-Fi.
3. Stop motion and astronomical tracking in SynScan, then disconnect/close SynScan to avoid competing controllers. Enter the GTi IP and UDP port 11880 in VTC-Test. Connect GTi does not start movement or overwrite the motor position origin.
4. Aim at a stationary landmark. Save A. Move away with a small finite pan/tilt step (0.1, 0.5, 1 or 5 degrees). Go to A. Save B for a second location and repeat A/B in both directions.
5. Compare actual landmark placement. Counter deviations are displayed in degrees; resolution is read from actual counts/revolution. These motor-shaft counts cannot reveal downstream gear backlash or mechanical drift. No backlash compensation is applied, so it does not conceal return errors.
6. Saved A/B live only in the current connection. Reconnection invalidates them. Do not release clutches, move the tripod, reinitialize the controller or use SynScan concurrently. A goto is limited to 10 degrees per axis from the current position. GOTO uses finite slow motion, not indefinite velocity tracking.
7. STOP requests an immediate stop for both axes, including cancellation checks before motion starts. Backgrounding requests stop. Lost communication triggers a best-effort stop, not a guaranteed stop: finite moves can finish on the mount independently of the app. Keep access to the mount power switch during initial hardware tests.

The app does not configure Wi-Fi credentials on the mount; use SynScan for station-mode provisioning and Android Wi-Fi settings to select the network. Camera connection has a separately configurable CCAPI URL and is optional. Both clients use the phone Wi-Fi network. No internet required once built/installed.

Protocol: https://inter-static.skywatcher.com/downloads/skywatcher_motor_controller_command_set.pdf
Commands used: a (counts/revolution), j (position), f (status), F (initialization only if needed), G (slow goto mode), S (absolute target counts), J (start), L (instant stop). ASCII commands use little-endian hex payloads, UDP 11880. No automatic UDP retries. No hardware motions have been performed by the development environment.

## Speed-controlled tests (0.3.1)
Default requested rate is 0.5 degrees/sec (about 4.6 m/sec transverse motion at 530 m). Selector: 0.05, 0.1, 0.25, 0.5, 1 degree/sec. Applies to step moves and saved-position returns. Step angle and speed are independent. Rate is computed from reported motor timer frequency and counts/revolution. Low-speed velocity mode replaces firmware-selected GOTO rate for these controls. The app polls position, slows within the last part of the move, and sends instant stop at/crossing the target; it does not reverse to hide backlash. Network delay means rate/endpoints require hardware verification and may overshoot slightly.

IMPORTANT: Unlike the 0.3.0 finite hardware GOTO, this mode depends on the app sending STOP. If Wi-Fi, app or phone fails during a move, the mount may continue until stopped or powered off. App cancellation, backgrounding, position-read errors and the travel deadline all attempt to stop both axes, but cannot guarantee delivery. Keep the mount power switch accessible during tests. Actual rate is a request, not measured physical speed.

## Native GoTo plus separate speed test (0.3.2 — current)
Pan/Tilt step buttons and Go to A/B now use native finite GOTO exclusively. Choose Slow (default) or Fast in the native selector. It sends motion mode G20/G21 or G00/G01, absolute target S and start J; no velocity timer commands or app-dependent arrival stop. Mount firmware handles arrival even if the link drops. Polling reports progress. Manual STOP, app backgrounding and communication errors may interrupt a move.

The separate Move at selected speed section retains requested rates 0.05/0.1/0.25/0.5/1 degree/sec for backlash/smoothness testing. Its own Pan/Tilt buttons use the chosen step distance, with app-controlled deceleration and stopping. The 0.3.1 communication-loss limitation applies ONLY to this separate speed test, not native GoTo. Saved-position buttons never use velocity mode. Native Fast/Slow and speed-test rate selections are stored separately.

## Speed diagnosis (0.3.3)
Native Slow/Fast have not yet been confirmed to produce different physical speeds on the owner's GTi. This version does not claim to fix firmware behaviour. The mount panel displays requested native mode, transmitted G payload and f status readback before starting, including the reported high-speed flag. A separate sample-average motor-counter speed readout uses position differences and monotonic elapsed time; it is not optical speed or peak speed. Short moves can finish between samples, so compare equal 5-degree test moves. The speed-test diagnostic shows requested degrees/sec and programmed timer/count values. Send a screenshot of these readouts for Slow and Fast to diagnose actual behaviour. Position polling remains independent of native target stopping.

## Diagnostic session logs (0.3.4)
Capture starts automatically for each app activity session in private app storage. Tap Save diagnostic log to Downloads/CAM3 to publish a flushed snapshot. This is the same public folder used by VT full logs, but VTC-Test files have their own VTC-Test_YYYYMMDD_HHMMSS_id.csv names. No VT code or files are modified. Repeat Save updates this session's exported file; later sessions get a new filename. Reopening the app starts a new capture, so export before closing.

Logs contain offset-aware wall time, monotonic elapsed milliseconds, Android/app version, GTi endpoint IP/port, every outgoing command and incoming raw reply, transaction latency, timeouts/errors, selected action/mode/rate, raw axis positions, CPR and diagnostic status/speed samples. CR/LF are escaped and CSV fields quoted. They contain no home Wi-Fi password or camera preview images. Android 10+ uses MediaStore; Android 8/9 requests legacy storage permission when exporting. Capture failures and export destination/errors are shown in the mount panel. The exported file is a snapshot, not a continuously updated public file: tap Save again after further tests.
