## VTC 1.0.38 — Larger manual pan steps

Install [VTC 1.0.38](VTC-1.0.38-debug.apk) with your current setup.json.

- After calibration, **Wave Ride → Position the camera** adds **5°**, **15°**, and **30°** pan steps and defaults to **15°**. The **0.1°**, **0.5°**, and **1°** steps are available for fine alignment.
- VTC checks each requested move against the configured movement limits. If a step would cross a limit, VTC leaves the mount in place and keeps calibration.

## VTC 1.0.37 — Return, calibrate and pan offshore

Install [VTC 1.0.37](VTC-1.0.37-debug.apk) with your current setup.json.

- In **Calibrate**, use **Go to landmark and calibrate here** for a saved landmark on the same physical setup. VTC returns both axes to that position and calibrates after both have stopped. Use **Calibrate here** after manually centring the landmark for first setup or after moving the equipment.
- After either calibration, VTC pans to the saved offshore heading when it is within the pan limits. The tilt stays at its calibration angle. If the heading is blank, point offshore manually and use **Save offshore heading** on **Wave Ride**.

## VTC 1.0.36 — Save offshore heading

Install [VTC 1.0.36](VTC-1.0.36-debug.apk) with your current setup.json.

- Calibrate the GTi, open **Wave Ride**, and use the pan buttons to point straight offshore. When the mount stops, tap **Save offshore heading**. VTC reads the calibrated heading and saves it as offshoreHeadingDegrees in setup.json.
- Ride detection uses that fixed bearing while the camera follows the surfer. Live Map and the Google Earth preview show the seaward arrow from the camera position. Existing two-point settings convert automatically, and older replays can still display their A and B points.

## VTC 1.0.35 — Manual pan on Wave Ride

Install [VTC 1.0.35](VTC-1.0.35-debug.apk) with your current setup.json.

- On **Wave Ride**, select the pan angle and tap **Pan anticlockwise** or **Pan clockwise**. Each press makes one finite move. The GTi must be connected and aiming must be stopped.

## VTC 1.0.34 — Recording limit in minutes

Install [VTC 1.0.34](VTC-1.0.34-debug.apk) with your current setup.json.

- **Setup → Optional Canon camera → Maximum recording length** is now in minutes and defaults to 20. Use 0 to disable timed rollover.
- Existing positive limits in seconds convert to minutes. A saved 1.0.33 zero uses the new 20-minute default.

## VTC 1.0.33 — Recording while aiming

Install [VTC 1.0.33](VTC-1.0.33-debug.apk) with your current setup.json.

- **Start Aiming** starts recording on the configured camera if it is idle. In **Setup → Optional Canon camera**, set **Maximum recording length** in seconds (the setup.json field is maxRecordingSeconds). At the limit, VTC stops the clip and starts a new one while aiming is active. Leave it at 0 to use the camera's own clip limit without timed rollover.
- **Stop Aiming** ends automatic starts and rollover and leaves the current clip recording. Recording commands run on the camera worker. A camera connection or recording error leaves aiming active.
- The camera reports recording events rather than the elapsed time of an existing clip. For a clip already running when VTC first observes it, the timer counts from that observation.

## VTC 1.0.32 — Camera Preview

Install [VTC 1.0.32](VTC-1.0.32-debug.apk) with your current setup.json.

- Join the camera's Wi-Fi network and open **Camera Preview**. Each press of **Update** requests one image from the configured Canon camera. VTC keeps the last successful image on screen. Aiming can continue while the camera request runs.

## VTC 1.0.31 — Wave ride replay and map zoom

Install [VTC 1.0.31](VTC-1.0.31-debug.apk) with your current setup.json.

- The camera–surfer arrow labels distance in metres beside the zoom multiplier. Live Map fits to the greatest recorded camera–surfer range in the run and expands when that range grows. Replay fits to the full run's maximum range. Use + or − to zoom, and Fit largest range to restore the fit.
- A wave indicator appears when VTC reports an active ride. Choose a wave ride in either replay mode to play the detected ride and up to 10 seconds of recorded context on each side. The selector offers Full run or each detected ride in the run. Logs without ride data offer the full run.

## VTC 1.0.30 — Live Map and replay

Install [VTC 1.0.30](VTC-1.0.30-debug.apk) using your current setup.json. New or missing pan limits default to -170 and +170 degrees from the landmark. Existing saved limits take precedence.

- **Live Map** replaces the map on Calibration. It shows the camera, landmark, saved waveline and seaward arrow, surfer GPS, and the GTi-reported aim. When range and confirmed zoom are known, a wedge shows the estimated field of view to scale. The camera-to-surfer arrow carries the zoom label. Its colour is green when settled and on target, orange while panning, red when panning and the GPS marker is potentially out of shot, or grey when stopped and unconfirmed. The wedge marks stale data separately.
- **Replay last run** reads the latest completed aiming run on the phone, including one just stopped for replay. **Replay from log file** opens the latest completed aiming run in a selected VTC JSONL file. Use Play, Pause, the timeline and 0.25x to 8x speed. Both show a notice before stopping aiming and disconnecting GTi, LoRa and camera polling automatically. An unconfirmed mount stop blocks replay. Use Return to live when done, then reconnect the devices.
- The **Google Earth** calibration preview shows waveline A/B and the seaward arrow. Calibration shows a Connect GTi prompt when needed and explains that LoRa is only needed for coordinate capture on that screen.

## VTC 1.0.29 — Automatic zoom follows Start Aiming

Install [VTC 1.0.29](VTC-1.0.29-debug.apk) with the existing 1.0.28 setup. Bands and Auto-fit can send zoom commands only during a surfer aiming session. Connecting LoRa, calibrating or returning to a landmark does not start automatic zoom. Stop Aiming leaves the lens where it is and keeps camera zoom readback and estimated width available. A command already accepted by the camera may finish. Each aiming session starts the zoom scale sequence from its first entry.

## VTC 1.0.28 — Wave ride framing

Before installing [VTC 1.0.28](VTC-1.0.28-debug.apk), copy [the replacement settings](VTC-1.0.28-setup.json) to Download/VTC/Settings/setup.json. The file uses the latest supplied setup, with 2 m pan tolerance and 0.5 m tilt tolerance.

- Open **Wave Ride → Capture waveline**. Capture two stationary LoRa GPS points along the shore, then select the seaward side when facing from A to B. Ride detection counts shoreward and alongshore speed and ignores the seaward component.
- A ride starts at 12 km/h on two consecutive fresh speed estimates by default and ends after 2 seconds below 10.8 km/h. These settings are editable on the Wave Ride tab. During a ride, framing applies the configured offset immediately in the direction of sideways motion around the mount. Outside a ride, without a waveline, or below 0.25 m of sideways movement over the measurement interval, the offset is zero.
- **Off / Bands / Auto-fit** remain in Setup. Edit zoomBands, zoomMapping and zoom_scales in setup.json. The camera panel estimates frame width from the last confirmed zoom and shows the age of that reading. KML keeps the camera aim line.
- The replacement setup removes window, observations, minNet, consistency, supports, supportChange, quietRate, quietSeconds, offsetRate and hfov.

Verification passed 229 unit tests and lint with zero errors. The APK signer matches 1.0.27. The waveline wizard and aiming still need a field test.

## VTC 1.0.27 — Off, Bands and Auto-fit

Setup's Zoom mode selector restores editable distance bands alongside Auto-fit. Off sends no zoom commands and continues readback/logging; it does not reset the lens. A command already sent to the camera may finish. Both automatic modes use the configured zoom mapping, scales and switching interval, on the independent camera worker.

Bands retains the structured bufferMetres/bands JSON. At 220 m the default band is 5x; scales [1,3] request 5x then 15x. Scales also apply below 40 m. Exact maximum distance belongs to the lower band on initial selection. The default 5 m buffer requires crossing the boundary plus/minus 5 m to change bands. Large distance jumps can traverse multiple bands. Auto-fit retains its 5% width tolerance; Bands waits for the selected command instead of applying that width tolerance. Stale GPS holds zoom. Logs include mode, band index/limits/base zoom, buffer, scale, final command and camera limits.

Existing autoFit 0/1 migrates automatically to Off/Auto-fit. Imported older autoZoom settings with bands restore Bands when enabled. Explicit zoomMode takes precedence. Saved bands and calibration are preserved. If bands were removed by an earlier version, the original default bands are restored; import/edit an old custom table to recover custom values. No manual settings conversion is needed.

## VTC 1.0.26 — Supplied camera zoom calibration

The default mapping now contains all 102 user-supplied points unchanged, from 1x/0 to 65x/200. Mapping validation accepts up to 1000 points and nondecreasing CCAPI values. Both 3.2x and 3.3x request 60; readback 60 estimates their geometric midpoint, approximately 3.25x. Other values retain logarithmic interpolation. Repeated endpoint values cannot cause division by zero; a mapping with no distinct command values is rejected.

Existing saved mappings remain in use. Paste the contents of VTC-1.0.26-zoomMapping.json into Setup's Zoom mapping JSON field to apply this table to an existing installation. The file is an array, not a complete setup.json. No other settings need conversion.

## VTC 1.0.25 — Save the current setup.json and verify it

Each public settings save now discovers the current canonical filename in Download/VTC/Settings. That file takes precedence over a saved URI, including after a PC rename or replacement. A stale reference is checked before use. If only a numbered active file exists and it is accessible, it is renamed to the canonical filename in place; unrelated backups and bookmark files are retained. Discovery/permission failures never trigger a replacement insert. The existing durable creation guard still prevents repeated MediaStore inserts.

Public saves use truncating writes followed by exact content readback. The durable recovery copy remains pending until verification succeeds. Setup and Calibration show Saving, Saved and verified (with time), or the actual failure. Coordinate capture no longer claims the public file was saved before the asynchronous write completes. Invalid form edits are reported on both screens. Reading status does not wait for file I/O. Logs distinguish edits queued from verified public writes and include the resolved file URI and verification outcome.

Setup > Allow Settings folder access is a recovery action if Android denies access to a file renamed/replaced outside VTC. Select Download/VTC/Settings once in Android's folder picker. The persisted directory permission allows discovery of the current child file after replacement. It retries pending edits automatically; granting permission alone does not overwrite an unreadable existing file with fallback defaults. No settings conversion or manual save is required. Existing files are not deleted.

Regression coverage includes canonical replacement after a stale URI, external rename, canonical preference over old numbered files, repeated saves/restarts, rename migration, permission failures, readback mismatch, close failure, deletion during save, and truncation. Android MediaStore/SAF behavior still requires confirmation on the user's phone; no phone was attached for this build.


## VTC 1.0.24 — Do not discard the saved settings URI

The user's screenshot did not establish which version created the newest duplicate. Review nevertheless found a remaining path in 1.0.23: metadata lookup could discard a remembered settings URI and lead to a replacement insert.

The store now directly reuses its persisted MediaStore URI without requiring a successful metadata query. A failed read/write preserves both the identity and private recovery settings and reports an error; it does not silently create another settings file. A durable creation-attempt marker is committed before a new insert. If insertion or identity persistence is interrupted, discovery may recover an existing file, but subsequent retries cannot insert another copy. If no URI/file can be recovered after that attempt, public saves remain blocked with a storage error rather than generating additional numbered files. This policy also means deleting the active file externally does not automatically create a replacement.

Logs now include settings_uri_reused, settings_file_create_attempt and the actual URI in settings_public_saved. Existing files are not deleted. Keep the current app data/settings and update in place; no conversion required. Full unit suite/build/lint verify regressions but the Android MediaStore persistence path still needs verification on the phone.


## VTC 1.0.23 — Reuse settings files

PublicSettingsStore now retains the actual Android content URI in separate private metadata. Clearing/replacing the settings recovery cache cannot erase this reference. Each save reuses the remembered file even if Android assigned a collision name such as setup (18).json. It verifies that the reference still belongs to the settings directory and expected file family before use.

Without a remembered reference, discovery uses exact setup.json when visible; otherwise it reuses the newest MediaStore entry matching Android's setup (N).json naming pattern. It does not select setup - Copy.json, migration backups or landmark bookmarks. The same behavior applies independently to the last-landmark store. Actual saved filenames appear in storage status and settings_public_saved logs.

If discovery fails or an existing canonical file is inaccessible, settings remain in private recovery and VTC reports/retries the storage error instead of knowingly creating a colliding file. Inserted URIs are remembered immediately, including Android's resulting display name. Existing phone files are not deleted or consolidated automatically. Keep them until the active file and latest values have been verified on device.

Validation: filename-family tests for collision names, exclusion of backups and separation of setup/bookmark families; full unit suite, build and lint. Android MediaStore behavior and existing-file ownership still require a phone check.


## VTC 1.0.22 — Current coordinates in maps

Google Maps launch, Google Earth launch and the embedded calibration map now use current saved camera/landmark coordinates from Settings, not last-landmark.json. Successful LoRa captures or manual edits are immediately reflected without pressing Calibrate here. Launch buttons apply valid pending form edits first. Both coordinate pairs are required; missing coordinates are reported, never replaced with an old bookmark. Heights and a prior calibration are not required just to inspect positions, including coincident points.

Earth receives a new KML copy each launch, with current pins and geographic bearing line. It is labelled Not yet calibrated unless this connection has a validated mount calibration. The line is geographic direction to the landmark, not a measured camera heading. Embedded maps refresh when saved coordinates or calibration state changes. Historical automatic calibration KML and the motor bookmark for Go to last landmark retain their original meaning; settings/bookmark files remain separate and no migration is required.

Successful LoRa captures continue to save automatically. Capture failure/timeout or inability to apply a capture clears the selected camera or landmark pair and attempts to persist that blank pair without replacing unrelated settings. Save errors remain explicit. User cancellation remains distinct from capture failure and retains the prior values.

Regression tests cover changed coordinates overriding old exports, no-calibration/no-height previews, missing-coordinate rejection, coincident points and existing map/KML behavior. Device-side Google Maps/Earth and LoRa behavior still need physical testing.


## VTC 1.0.21 — Launch calibration in Maps / Earth

Two buttons near the top of Calibration use the last saved calibration, including after Wi-Fi changes or app restart. No settings conversion or API key is needed for these external app launches. The embedded Google Maps view still has its separate key requirement.

See calibration in Google Maps opens a standard Maps directions URL with the saved camera as origin and landmark as destination, preferring the installed Google Maps app and falling back to a URL handler/browser. It does not start turn-by-turn navigation. Google may draw a route or report that no route is available: that is not VTC's calibration heading line.

See calibration in Google Earth prepares a KML copy from that saved snapshot and opens it with Google Earth using a temporary read-only content URI grant. It contains both pins and the calibrated heading line, as in the automatically exported calibration KML. If Earth cannot handle the intent, Android offers another KML viewer and VTC explains the fallback. No saved calibration produces a clear message. Launching another app does not stop foreground-service aiming; stop/disconnect GTi before switching device Wi-Fi.

The sharing provider is non-exported and exposes only generated calibration KML names inside a dedicated private cache folder, with read-only access. Files older than seven days in that feature-specific cache are cleaned on a later Earth launch. Original automatic calibration KML files in Download/VTC/logs remain unchanged.

Validation includes saved-coordinate URL encoding under different locales, absence of automatic navigation, strict shared-file names, existing XML/snapshot tests, unit suite, APK build and lint. Actual Google Maps/Earth app handling has not been tested on the phone.


## VTC 1.0.20 — Calibration map and KML

Every successful calibration queues a separate VTC_calibration_<timestamp>_<id>.kml in Download/VTC/logs. It contains camera/landmark pins and a camera-to-landmark line representing heading at calibration, with distance, true bearing, elevation, configured water-relative heights and capture time. Positions are clamped to ground for map inspection, not interpreted as sea-level GPS altitudes. An asynchronous private pending spool retries public-file failures every five seconds and resumes pending files next service start. Session logs record queued/saved/error events. Existing session KML is unchanged.

Calibration includes a saved-position map preview. It uses the last saved calibration, including after app restart or switching Wi-Fi, and labels it as a historical reference rather than live heading or calibration validation. A no-key/offline north-up direction diagram shows the camera, landmark and direction. Refresh or re-enter Calibration after joining internet Wi-Fi. Stop aiming and disconnect GTi before switching Wi-Fi; reconnect and validate calibration before aiming again.

Google Maps can be configured later using the optional key field on Calibration. This implementation uses Google Maps JavaScript API in Android WebView (not the native Maps SDK). Enable that API and the required Google Cloud billing configuration; add a website/referrer restriction for https://vtc.local/* and restrict the key to Maps JavaScript API. A valid configured key loads Google's hybrid map, camera/landmark markers and a directional line, fitting both positions. Authentication/network errors show explanatory text; without a key there is no Google API request. Key storage is separate private SharedPreferences and is not included in setup.json or VTC session logs. Displayed coordinates are shared with Google when its map loads. WebView has no native JavaScript bridge or local-file access. No SSL-error bypass is implemented.

No settings conversion is required. Map/KML tests validate XML, longitude/latitude order, pin and line counts, offline output, Google map features and persistence roundtrip. Actual Google tiles/authentication could not be tested without a key; physical device validation remains outstanding.


## VTC 1.0.19 — LoRa calibration capture

LoRa now has an independent connection worker and Connect/Disconnect LoRa controls on Track and Calibration. Connect GTi + LoRa requests both, but LoRa starts independently and survives GTi failure/disconnection. Disconnect GTi stops and closes only the mount. The LoRa worker rechecks Wi-Fi/endpoints and retries ended sockets every two seconds; receiver HELLO retries remain every five seconds. Network/receiver failures retain explicit status/log details. GPS loss during aiming retains the existing timeout/stop policy. Camera polling may use the LoRa-selected Wi-Fi when GTi is disconnected. Exit closes all resources.

Calibration now contains camera and landmark coordinates, camera/landmark heights, target height, axis signs, manual movement controls, confirmations, calibration status, Go to last landmark, live LoRa telemetry and Start/Stop aiming. The same aiming session and readiness checks serve both Track and Calibration buttons. General network/tracking/Auto-fit settings stay in Setup. Reference settings remain editable only with GTi disconnected; LoRa may remain connected for capture. Heights remain manual.

Use LoRa GPS for camera / landmark starts an explicit stationary capture. Like VT's wave-line capture, it requires 10 distinct accepted fixes captured after starting, spanning at least four seconds, with every sample within 5 m of the averaged coordinates. Polling is every 500 ms. Stale (>3 s), unavailable or invalid fixes reset the batch; continuity changes reset evidence. A rolling ten-sample batch continues through excess scatter. Timeout is 120 seconds. Progress, samples, scatter and final saved coordinates/settings are logged. Cancel, leaving the foreground, or connecting GTi cancels capture. Successful capture updates only the chosen coordinates and saves automatically. This consistency check does not guarantee absolute GPS accuracy.

Settings schema is unchanged; keep the current setup.json. No conversion/import is needed. Automated tests cover capture average, duplicate suppression, freshness, minimum time, excess scatter/recovery, timeout and date-line averaging, alongside existing aiming and Auto-fit tests. Physical LoRa/GTi testing remains outstanding.


## VTC 1.0.18 — Auto-fit

Auto-fit replaces distance bands. Set `autoFit` to 1 and `desiredFrameWidthMetres` to the desired horizontal scene width at the surfer's GPS distance. The default is 40 m; `autoFit` defaults to 0 and sends no zoom commands when off. Camera reads and logs continue independently of aiming.

Required optical zoom = wideFrameFactor * GPS distance / desiredFrameWidthMetres * active zoom scale. `wideFrameFactor` defaults to 1.648 and is editable (0.01–10). Desired width accepts 1–10000 m. Existing `zoom_scales` and `zoomSwitchSeconds` remain: scales [1,2] at 40 m alternate 40 m and 20 m. Settings changes restart the scale cycle, except manual observations.

`zoomMapping` is an editable JSON array of numeric zoomX/ccapiValue points, initially (1,0), (6,89), (10,102). Enter 2–100 points, starting at 1x, with both coordinates strictly increasing. Optical values are 1–65; command values are nonnegative integers up to 1000000, further constrained by the connected camera's advertised limits. Interpolation is logarithmic in optical zoom; beyond calibration points the final segment is extrapolated. Commands round to the nearest legal camera step. The factor and initial mapping are estimates requiring calibration in the recording mode used.

A 5% estimated width deadband avoids small repeated commands. The existing independent camera worker checks after a minimum 2-second poll delay, retains its pending-command/readback handling, and holds zoom without fresh valid GPS. Zoom requests are limited to 1–65x and camera ability. Status/logs flag limits and record desired/effective width, factor, scale, GPS distance/age, calibration, calculated zoom, rounded command, current readback, estimated current/target widths, action and confirmation. Physical movement and network latency mean width cannot be exact at every instant.

Existing autoZoom migrates to autoFit, retaining its on/off value. Zoom bands are retired, not interpreted as calibration. Missing new settings use the defaults above. The supplied VTC-1.0.18-setup-autofit.json derives from the previous converted setup, retains all aiming/network settings, has width 40 m, factor 1.648, scale [1], and autoFit 0. Import through Setup > Import setup while disconnected, then enable Auto-fit when wanted. Original local settings files are unchanged.

Validation: unit tests include mapping calibration/inverse and interpolation, width calculation at multiple distances, camera rounding/limits, scale cycling, off/stale GPS behavior, width deadband, settings validation and delivered-file roundtrip. No physical camera/mount test was performed.


## VTC 1.0.17 — metre aiming and structured zoom bands

- Settings schema 2 uses `aimingUnits: metres` and explicit keys `panToleranceMetres`, `tiltToleranceMetres`, `magnitudeMetres`, `acquisitionTriggerMetres`. Values describe lateral/vertical displacement in the target plane at the surfer's horizontal distance, not movement along the water. Conversion is atan(metres / distance). Live aiming recalculates angles per accepted GPS fix; saved GoTo retains its captured position and tolerances at the captured range. Camera tilt offset, motor limits, motor speeds and direction-detection thresholds remain angular.
- Continuous correction stops within the converted tolerance and restarts beyond the angle corresponding to 1.5 times the metre tolerance. Reversal blocking is unchanged. Magnitude stays automatic and its angular size adapts with range.
- `offsetRate: 0` is the default and applies the calculated angular offset immediately once direction is established. Nonzero 0–2 degrees/s retains the existing smoothing. GPS-unavailable holding and direction evidence remain in effect. Existing explicitly saved nonzero rates are retained on automatic migration; the supplied converted setup deliberately sets 0 as requested.
- `zoomBands` is now a JSON object with `bufferMetres` and `bands`, each containing `maxDistanceMetres` and `zoomX`. Maxima must increase; the final band has null maximum. Both distances and zooms are configurable. Default buffer is 5 m, with transition outward at boundary + buffer and inward below boundary - buffer.
- `zoom_scales` replaces `zoomMultipliers` and is saved as a JSON array. The UI accepts manually entered comma-separated values 0.5–3.0 in 0.1 increments. The active scale multiplies the selected band's zoomX; it never rewrites saved base bands. `zoomSwitchSeconds` is seconds per scale. Automatic zoom off leaves zoom untouched while reads/logging continue. Camera work remains independent of mount aiming.
- Old degree keys migrate using the agreed 143 m reference. Units/version metadata prevents accidental reinterpretation; mixed old/new aiming keys are rejected. Old zoom strings migrate to explicit distance-band objects. On device startup, the app attempts a backup of legacy setup as Download/VTC/Settings/setup-before-1.0.17.json before saving migrated settings. JSON import/export and public settings preserve nested bands/scale arrays.
- Supplied VTC-1.0.17-setup-metres.json is derived from the user's original setup: pan 2.00 m, tilt 0.50 m, magnitude 12.51 m, acquisition 7.49 m, offsetRate 0, autoZoom 0, reversal hold 2 seconds; all unrelated settings retained. Import it through Setup > Import setup while disconnected. The original local source file remains untouched.
- Aiming logs include metre settings, range and derived angular thresholds/magnitude. Continuous logs include exact metre/angle restart margins. GoTo logs retain captured range and metre/angle tolerances. Zoom diagnostics and manual observations remain enabled.

Validation includes migration idempotence and actual delivered-file import/export, geometry across ranges, instantaneous offsets, reversal hold, 1.5x metre restart, user-defined band boundaries, scale cycling and JSON validation. Physical mount/camera testing remains required.

## VTC 1.0.16 — automatic distance-band zoom

In Setup / Optional Canon camera, set Automatic zoom to 1 to enable (default 0/off). Enter multiplier sequence such as `1.0, 1.2, 2.0` and switch interval (default 10 seconds, range 2–3600). Multipliers accept 0.5–3.0 in 0.1 increments; one entry holds continuously. All bands receive the multiplier, including below 40 m. Phase starts at the first entry after connection or automatic-zoom/sequence/interval/band changes. Camera polling and HTTP time can delay the actual switch; logs retain scheduled switch times.

Band lower distances (m): 0, 40, 60, 100, 150, 200, 250, 300, 350, 400, 450.
Default base optical zoom: 1, 1, 1.5, 2.5, 4, 5, 6.5, 8, 9.5, 10.5, 12.
The 11 optical values are editable in the same order. Above 500 m retains the last band. After initial selection, 5 m hysteresis applies on either side of boundaries. Fresh accepted GPS and configured camera coordinates are required; stale GPS holds zoom. Automatic zoom operates while connected, independently of whether mount aiming is started.

Provisional mapping: log interpolation between (1x, CCAPI 0), (6x, 89), (10x, 102), with the last segment extrapolated above 10x. This is an estimate authorized for testing, not a calibrated lens model. Optical requests are limited to 1–65x and CCAPI requests to camera-advertised min/max/step. Frame width estimate is distance * 1.648252 / optical zoom; video cropping/stabilisation is not accounted for. Existing configured hfov/KML sectors are unchanged and not replaced with these estimates.

Zoom settings and Manual camera zoom (x) remain editable while connected. Other settings stay locked. Entering a manual x value logs an observation, never a camera command; the Log manual zoom observation button records the same value again. Turn automatic zoom off for manual calibration. Entries contain CCAPI readback/age, GPS distance/age, settings, and whether the pair qualifies as a current manual observation. Persisted manual values alone are not treated as new measurements.

Camera HTTP runs on its own existing worker, with one operation at a time. A slow or failed zoom request cannot block the mount executor. POST responses are acknowledgements; subsequent GET readback confirms zoom. Pending requests get up to 5 seconds for readback before another command. Settings/network identity and GPS validity are checked again before POST; no unlimited command queue is created.

Automatic JSONL logs under Download/VTC/logs include full settings, band boundaries/values, distance and GPS validity/age, nominal and selected band, hysteresis, active multiplier/index and next switch, requested optical/CCAPI values, ability limits and clamping, estimated requested/current frame widths, POST timing/response/errors, readback confirmation and reasons for skipped/delayed commands. Aiming telemetry retains the same timestamp scheme for correlation.

Validation: automated policy tests cover every band, >500 m, near-distance multipliers, hysteresis, cycling/reset behavior, stale GPS/off mode, mapping anchors, camera limits, input validation and live-setting scope. Existing camera-worker isolation regression remains included. Physical camera zoom and UI behavior still require device testing.

## VTC 1.0.14 — lost Wi-Fi recovery

- Stop retries can use the currently available single Wi-Fi network when the original Android network handle has disappeared. Only Stop and motor-status commands may use this recovery path; movement requires a new connection and calibration.
- Explicit Disconnect and Exit complete after the bounded Stop attempts even if confirmation fails. Logs retain `stopConfirmed: false`; Disconnect shows an unconfirmed-stop message and Exit shows a warning. Closing the app does not guarantee a physical stop when the mount cannot be reached.
- New connections verify both axes stopped before initializing the session. Removed the redundant encoder read after a confirmed Stop, which could otherwise turn a successful Stop into another failure.
- Regression tests cover failed/successful Stop completion and rejection of movement commands on the recovery network. Physical Wi-Fi-loss testing remains required.

# VTC 1.0.13

Removed the validation coupling horizontal framing magnitude and pan tolerance to field of view. A 3° automatic magnitude and 1° pan tolerance now save normally, including with the previous 6.48° field-of-view value. Magnitude retains its existing 0–30° range; motor sector and other aiming limits remain enforced.

Horizontal field of view is optional and defaults to blank for new settings. Existing saved values are retained and can be cleared. It is used only for the estimated frame width and KML viewing sector. With the field blank, VTC omits the width estimate and sector polygon; camera position, surfer data, heading/tilt lines and aiming continue normally. It does not control zoom or aiming, and the camera zoom readout does not calculate it automatically. The older FOV validation/default described below is superseded.

Valid settings still autosave while disconnected. Rejected edits from an older build were not applied; enter the desired magnitude/tolerances again after installing. Pan/tilt tolerance defaults are unchanged by this fix.

## Previous release documentation

# VTC 1.0.12

High-speed catch-up now uses the configured maximum directly (subject to the existing 800× sidereal AZ-GTi command cap, about 3.34°/s). It does not recalculate intermediate high-speed rates from each pointing error. The existing driver skips unchanged timer periods, so a run of same-direction high-speed corrections retains its rate without repeated Stop/Start commands.

Low-speed correction still uses error / 2 seconds, capped at 0.5°/s or the configured maximum if lower. The internal switch-down error is `max(1°, effective maximum × 0.5 seconds, aiming tolerance × 1.5)`. High speed starts only above that angle plus 0.25°, providing hysteresis. For a 3°/s maximum and ordinary tolerances: start high-speed catch-up above **1.75°**, hold **3°/s**, switch back to low-speed at **1.5° or below**. This early-return margin is a heuristic, not a prediction of the surfer's velocity or a guarantee against overshoot with GPS/command latency. No new settings are added.

The same policy applies independently to tilt. Normal tolerance stops, reversal blocking and fixed-target GoTo at `acquisitionTriggerDegrees` retain priority. Thus a lower acquisition trigger can invoke GoTo before high-speed catch-up begins. GPS gaps still hold the last rate until timeout, subject to motor-sector limits. Sector limiting now switches straight from maximum high speed to low speed instead of generating changing high-speed rates near a boundary.

Mode changes, reversals, GoTo and explicit/safety stops can still pause movement. This change removes repeated *same-direction high-speed rate-change* stops; it does not promise continuous footage in every condition. Default maximum remains 0.5°/s; saved settings remain unchanged. The effective rate and switch thresholds are logged in `continuous_decision`.

## Previous release documentation

The changing-high-speed policy described below for 1.0.11 is superseded by the fixed-maximum policy above.

# VTC 1.0.11

`maxPanningSpeed` now accepts **0.01–5°/s**. The default remains **0.5°/s**, and existing saved values are retained. This setting also caps tilt. The effective AZ-GTi command ceiling is **800× sidereal = approximately 3.34°/s**, exposed on the Mount card and in `continuous_decision` logs; a setting of 5 is not a promise of physical 5°/s.

Above the existing 0.5°/s low-speed boundary, VTC reads the per-axis `g` high-speed ratio, calculates the scaled timer period and uses native G3 slewing. It confirms Stop before entering/leaving that mode, changing direction, or changing a running high-speed period. An unchanged high-speed rate continues without Stop/Start or redundant setters. Low-speed same-direction updates remain uninterrupted. This follows SkyWatcher's protocol requirement that high-speed timer periods cannot be changed while running; higher speed settings can therefore reintroduce pauses.

Reference: [SkyWatcher command set](https://inter-static.skywatcher.com/downloads/skywatcher_motor_controller_command_set.pdf), [SkyWatcher sample implementation](https://github.com/skywatcher-pacific/skywatcher_open/blob/master/BasicApi_CS/Mount_Skywatcher.cs), [AZ-GTi driver range](https://drivers.indilib.org/mounts/generic/azgti/azgti/).

All 1.0.10 tracking, GPS timeout, framing, watchdog, settings, camera and logging rules remain. The 0.5°/s setting ceiling and low-speed-only description in the historical notes below are superseded. High-speed behaviour has software tests but is not yet verified on the physical mount.

## Previous release documentation

# VTC 1.0.10

Android shore-camera aiming for the AZ-GTi and VT LoRa receiver. Package `au.com.gildo.vtc`; installs over VTC, separately from VTC-Test. No camera recording, zoom movement or preview in this release.

## Current behaviour

- Normal pan **and tilt** use low-speed continuous G1/I/J commands. Same-direction updates change I (timer period), with no G/S destination and no Stop/Start between updates. Unchanged periods are not resent. Rate decisions are serialized, at most four per second, subject to reply latency; motion continues between updates. Reversal and switching to GoTo require a confirmed stop.
- `maxPanningSpeed`: **0.01–0.5 degrees/second, default 0.5**, also caps continuous tilt. Requested signed speed is `error / 2 seconds`, capped at this setting. Within each axis's tolerance it stops; it restarts beyond **1.5 × tolerance**. These fixed internal rules avoid extra gain/acceleration settings. This remains error-only control: a moving surfer can still produce stop/start corrections near tolerance; no angular-velocity estimate is claimed.
- The 0.5 cap is this release's low-speed operating range, not the GTi's maximum slew rate. SkyWatcher's reference changes high-speed rates with a stop; staying below the low-speed boundary avoids those transitions. Faster correction is via native GoTo. Reference: https://github.com/skywatcher-pacific/skywatcher_open/blob/master/BasicApi_CS/Mount_Skywatcher.cs
- `acquisitionTriggerDegrees` replaces `gotoThreshold`, retaining its saved value (default 3°, range 0–30). It applies on initial acquisition **and during tracking**, respecting reversal blocking. Both axes stop before GoTo; the latest accepted target including the current applied framing offset is captured once, not changed during travel. After arrival VTC reassesses latest GPS and may acquire again. Zero requests GoTo for any correction outside tolerance. `gotoMaxMove` still bounds each native leg. Obsolete `maxStep` and `waitForGoto` are migrated away; acquisition always waits for arrival, never tries rejected overlapping G/S commands.
- Framing is always automatic: `magnitude` is the absolute offset size; confirmed bearing direction selects +/−. Clockwise surfer movement selects +offset (surfer left in frame), anticlockwise selects −offset (surfer right). Existing trend confidence, quiet handling and smoothing remain. `magnitude=0` gives zero offset. Legacy `framing` and `fixedOffset` are accepted only for migration and are omitted on save.
- `gpsGapTimeout`: **default 3 seconds, range 0.3–30**, measured from the last **accepted** packet, never raw NOFIX/READY or rejection. Normal freshness uses the smaller of this and `gpsAge`. Between accepted packets, keep the last rate instead of repeatedly correcting toward the old stationary GPS target. Without usable GPS, hold the last commanded rates and framing offset until the deadline, while checking motor limits. On expiry both axes stop; aiming stays armed and resumes automatically with usable GPS. A fixed acquisition GoTo remains fixed even if GPS disappears. Limits/reversal rules still apply after resumption.
- Continuous rates reduce near a configured sector boundary, reserving 12 seconds of travel and a 0.05° margin for delayed control/Stop; below 0.005°/s at a limit the axis stops. This is a software margin, not a guarantee against link/power failure.
- An independent 250 ms watchdog checks for a control cycle stalled for 5 seconds; a separate emergency executor cancels pending STARTs and attempts Stop on both axes. The same independent monitor enforces the GPS-gap deadline even while a control read is waiting. Stop acknowledgements are followed by status checks, with up to three attempts. Both axes are attempted even if one fails. Stop failure is explicitly **Stop unconfirmed** and blocks new motion until a successful retry.
- **STOP & EXIT VTC** confirms both axes stopped before disconnecting/closing. Disconnect uses the same confirmation. Swiping the task away requests the same orderly exit; switching apps or locking the screen continues through the foreground service. Service destruction and uncaught process exceptions attempt Stop as well. No phone-side watchdog can stop a mount after loss of phone power, Android force-stop/process kill or loss of the Wi-Fi path. A mount-side heartbeat watchdog would be required for that guarantee.
- Valid edits automatically apply/save after 600 ms while disconnected, also flushed on leaving the screen/connecting. Invalid or incomplete fields retain the last valid setup and show why. Public settings and saved landmark remain `Download/VTC/Settings`; automatic JSONL/KML remain `Download/VTC/logs`. Initial Start still requires valid GPS and calibration; this release does not add arming before the first fix.
- Camera zoom reads remain isolated from mount work. On each camera/network connection, the same independent camera worker GETs the clock, PUTs Android time/timezone/DST, then GETs and verifies within five seconds. Failures retry no faster than once per minute and never block aiming. Format follows Canon's supplied `CCAPIVER1401` Android sample `DateTimeSettingDialogFragment` (`datetime` RFC-1123-style string and boolean `dst`). Clock sync is not frame-accurate video synchronisation.
- Logs include `continuous_decision`, `continuous_rates`, `gps_gap_hold`, GPS timeout stop results, watchdog/exit stop results, raw protocol requests/replies, camera clock request/readback, all setup values and existing telemetry/KML. Motor counters and calibration estimate pointing; they do not verify the actual image.

## Validation

Software tests cover timer quantisation, rate updates without destination stops, unchanged-rate suppression, independent axes, reversal ordering, cancellation before START, failed-rate and failed-Stop paths, timeout/hysteresis/limit rules, watchdog latching, setup migration, zero-offset framing and timezone roundtrips, plus the existing regression suite. Build and Android lint are run before APK delivery. Physical smoothness, watchdog Stop delivery and SX70 clock readback have not been tested on the user's hardware in this build.

## Historical documentation (1.0.9 and earlier)

The following describes older releases and is superseded by the current behaviour above.

# VTC 1.0.9

Android shore-camera aiming with a Sky-Watcher AZ-GTi and the existing VT LoRa GPS receiver. Optional Canon CCAPI zoom readout is included. Camera recording, preview, autofocus and zoom commands are outside this version. Package `au.com.gildo.vtc` installs separately from VTC-Test.


## Version 1.0.9

The Surfer GPS card refreshes every 250 ms independently of the mount worker. It shows trusted receiver datagram count/age, latest data-message count/age, latest received GPS coordinates/satellites/HDOP/RSSI/SNR where present, raw NOFIX/unknown messages, acceptance/rejection status, and the last accepted aiming fix with its own age. Receiver READY replies update receiver activity without making the surfer message or GPS fix fresh. Untrusted sources remain excluded. Parsing, sequence/jump validation and aiming eligibility are unchanged. Raw NOFIX fields are displayed without guessing their undocumented meanings.

**Wait for GoTo** is a saved setting accepting exactly `1` or `0`, default `1`:

- `1`: existing finite move, stop, check behaviour. Saved acquisition destinations remain fixed until arrival.
- `0`: experimental replacement commands may be sent during a VTC-owned move when a newer fresh accepted GPS fix arrives. A replacement uses the current GPS, held framing offset, configured target limits and reversal hold. It can replace a captured surfer GoTo; explicit last-landmark returns retain their fixed destination. Manual positioning still waits. Acknowledged replacements are finite G/S/J moves, never continuous speed commands. This setting does not promise smooth tracking.

Sky-Watcher's [motor controller command set](https://inter-static.skywatcher.com/downloads/skywatcher_motor_controller_command_set.pdf) requires a stopped motor for G and S and defines `!2` as motor not stopped. The default therefore remains 1. In mode 0, a `!2` rejection before any setter has been acknowledged leaves the old motion, destination and calibration intact; VTC logs/shows it and avoids repeated overlap attempts for that leg. A partly accepted/uncertain update remains subject to existing command-failure handling. Raw TX/RX and `goto_no_wait_attempt`, `goto_no_wait_accepted`, `goto_no_wait_rejected`, `goto_no_wait_skipped` events distinguish attempted commands from actual replies. An ACK alone does not verify physical retargeting; a hardware test is still needed.

Initial Start still requires fresh GPS. This version does not add arming before the first fix. Existing auto-wait for target range/sector and resumption after GPS returns during an active session remain unchanged.

## Version 1.0.8

- Arrival discrepancies are corrected from current motor counters without clearing calibration. Recovery after a missing reply follows the same rule. Real axis initialization loss, explicit motor errors and configured movement limits remain enforced. Fixed-target GoTo corrections now also respect the reversal hold.
- Maximum fine correction accepts 0.02–10° (default 0.25°). This is angular distance, not speed. The acquisition trigger remains 0.5–30° (default 3°); zero is not accepted. Fine moves retain the native slow mode and receive a distance-based timeout allowance.
- Initial Start accepts an out-of-range/out-of-sector target and waits automatically. Fresh GPS, valid calibration and a stopped responding mount are still required to arm aiming. Stop cancels the waiting session.
- Setup and the saved landmark automatically migrate to `Download/VTC/Settings/setup.json` and `last-landmark.json`. Public JSON is loaded at service startup; app-private copies recover failed/incomplete writes, with automatic retries. No manual export is needed. To edit files externally, end the service before reopening VTC; live file edits are not applied during tracking. Android-scoped-storage files imported from another app can still be selected with Import setup.
- New JSONL and KML files go to `Download/VTC/logs`. Existing VTC session logs in the old root folder are moved automatically where Android permits; failed moves are logged and retried without deleting originals.
- Optional camera IP and port are configurable under Setup. Blank IP disables polling; port defaults to 8080. After Connect selects Wi-Fi, a separate worker requests `GET /ccapi/ver100/shooting/control/zoom`, waiting two seconds after each attempt. Only one request is outstanding; network/read timeouts and response-size limits are applied. Errors retain the last reading with its age and never stop the mount. No POST/PUT/DELETE, recording, zoom movement, preview or automatic field-of-view conversion is included.
- A connected-device foreground service owns the controller, GPS, logs and camera reader. The screen binds to the existing service; screen changes and task removal do not stop tracking. CPU/Wi-Fi locks are held during connected/busy/aiming sessions and released when disconnected or the service is destroyed. A persistent notification provides Stop aiming. Allow notification permission on Android 13+ to see that control normally. Force-stop or process termination ends the runtime; a new service session requires connecting and validating calibration again, and never silently resumes tracking.

Android service reference: [foreground service types and connected-device permissions](https://developer.android.com/about/versions/14/changes/fgs-types-required).

Validation: host unit tests cover finite command bounds, reversal hold after overshoot, recovery with counter discrepancies and isolation of a blocked camera poll from another worker. No phone/emulator hardware validation was available: background/screen-off operation, public-file migration, real CCAPI and GTi behaviour still need field verification.

## First use

1. Open this project in Android Studio with Gradle JVM **17 or 21**, or install `VTC-1.0.9-debug.apk`.
2. Join the Wi-Fi network that reaches **both** the GTi and LoRa receiver. Set each device's IPv4 address under **Setup**. GTi UDP defaults to 11880; LoRa control/source defaults to 5006 and phone reception to 5005. Putting the GTi on home Wi-Fi alone does not put the LoRa receiver on that network.
3. Paste camera and landmark coordinates in **Google Maps format: latitude, longitude**, for example `-28.165513983264137, 153.55045722684628`. Each location has one field. Enter **lens height above water**, the height to aim at on the surfer, and the exact landmark point's height above the **same water level**. The supplied Point Danger coordinates are editable. Lens height and landmark details are deliberately blank. Existing saved coordinates are retained automatically.
4. Set pan/tilt limits to suit the mounting hardware and cables. Pan limits are relative to the landmark direction; tilt limits are absolute angles from horizontal. Save while disconnected.
5. Close SynScan and other apps controlling the mount or receiving LoRa packets. Connect VTC.
6. Level the mount, centre the configured landmark using the camera's own screen, and check the manual Right/Up directions. If either is reversed, disconnect and change the corresponding axis sign. Centre the landmark again, confirm the two checks, then tap **Calibrate here**.
7. With fresh surfer GPS, tap **Start aiming**. An error of 3° or more on either axis triggers fast native GoTo to a captured position; smaller errors use fine corrections. Start also accepts a surfer outside the target limits and waits for an in-limit fix. Aiming continues when switching apps or locking the screen. Stop in the app or notification cancels aiming and any saved GoTo.

The top panel reports **GTi**, **LoRa receiver / GPS**, and **Calibration** separately. GTi Connected confirms the mount session; it does not confirm fresh surfer GPS. A LoRa reply confirms the receiver, while a fresh accepted GPS packet is required for aiming. Ready to aim and Start are available only when settings, calibration, GPS, target range and target sector permit it.

Missed routine position/status replies now enter automatic recovery rather than cancelling aiming or clearing calibration. The top panel says **RETRYING GTi**. VTC retries a full position sample on later control ticks, issues no new movement while that sample is unavailable, and keeps the existing finite destination. Calibration, LoRa reception and the saved GoTo remain intact. A successful complete reading resumes the same job automatically.

After five seconds of missed polling (or an earlier movement deadline), VTC attempts Stop on both axes and shows **WAITING FOR GTi**. It continues polling and, if necessary, retries Stop at least two seconds apart. After Stop has been requested, resumption waits for a complete stopped reading. A counter discrepancy alone does not invalidate calibration; the controller replans from the measured position and still enforces the configured sector. A saved surfer or landmark target stays fixed; if Stop interrupted a leg, VTC continues toward that same destination. Live aiming uses fresh GPS. No manual Start or recalibration is required for this timeout recovery. Stop and Disconnect cancel automatic resumption; backgrounding does not.

Recovery applies only to timeouts during routine position/status polling. A rejected or uncertain movement command, blocked/uninitialized motor, unexpected movement or lost Android Wi-Fi network still stops aiming and clears calibration. If Stop and a fresh stopped position are confirmed, connections are retained; otherwise VTC closes both connections. Recalibrate after moving the tripod, releasing a clutch, changing the camera's mounting angle, or restarting the mount. One landmark assumes a level mount and fixed camera alignment. Motor counters cannot detect movement of the tripod or slippage between camera and mount.

**Go to last landmark**, on Track and Calibrate, stops surfer tracking and returns both axes to the last saved motor position, without fresh surfer GPS. Each successful calibration saves its pan/tilt counters, mount resolution/firmware, site/axis settings and save time in separate app preferences. The bookmark survives disconnects, setup saves and app restarts. Calibrate once after upgrading to 1.0.6 to create the first persistent bookmark; earlier versions kept calibration only in memory.

The saved bookmark is separate from the current validated calibration. After reconnecting, the button can return to a compatible saved reference while tracking remains blocked. Check the camera alignment, confirm the existing levelling/direction checks, and use **Calibrate here** to validate the new session and update the saved position. Returning never validates calibration or starts surfer tracking. Current movement limits and bounded GoTo legs apply; framing offsets are ignored. Stop cancels the return; backgrounding does not. The bookmark remains saved even if it cannot currently be used.

Changing the configured mount, camera/landmark geometry or axis directions blocks the old bookmark. A detected motor initialization/reset invalidates it persistently until a new calibration is saved. Motor counters are not a persistent geographic reference: the mount protocol cannot identify every restart already initialized by another app, tripod movement, clutch release or camera slippage. Recalibrate after those changes. The button and saved-position status explain whether a compatible bookmark is available.

**Automatic resume at target limits:** once aiming has been started, an out-of-range or out-of-sector target enters **WAITING TARGET**. No new movement is issued; an already commanded finite move retains its destination and arrival check. Fresh accepted GPS returning inside the configured range and pan/tilt sector resumes aiming automatically. The last applied framing offset is held while waiting and is included in the sector check. Captured GoTos still finish their fixed destination before evaluating a new live target. Manual Stop, Disconnect and hard faults cancel this automatic resumption. The mount itself being outside its allowed sector still requires intervention; it is not treated as a temporary target crossing.

## Automatic framing

Select **Automatic left / right lead** under Setup → Framing. Centred is the initial mode; Fixed signed offset is also available.

- Sustained increasing geographic bearing selects a positive offset: aim right, surfer left.
- Sustained decreasing bearing selects a negative offset: aim left, surfer right.
- Decisions use accepted GPS packets once, independently of commanded mount corrections.
- Bearings unwrap across north. New packets with unchanged coordinates count as quiet evidence; duplicates do not.
- Ambiguous movement holds the existing decision. A confirmed opposite trend changes the desired sign immediately.
- Applied offset moves gradually through zero on reversal.
- Continuous quiet evidence recentres. During live aiming, missing/invalid GPS clears evidence and the quiet timer, holds the offset, and pauses mount movement. Fresh GPS resumes aiming and rebuilds evidence. During saved-target GoTo, the captured offset stays fixed with the destination until arrival; new or missing GPS does not change that journey.
- Stop and a new aiming session reset both desired and applied offsets to zero.

Starting values are configurable and **not yet field tuned**:

| Setting | Default |
| --- | --- |
| Automatic offset magnitude | 1° |
| Observation window / minimum observations | 1.5 s / 3 |
| Minimum net bearing change | 0.3° |
| Direction consistency | 80% |
| Separate supporting changes | 2 of at least 0.03° |
| Quiet travel rate / continuous quiet time | below 0.08°/s / 3 s |
| Offset transition rate | 0.3°/s |
| Manual horizontal field of view | 6.48° |
| Pan / tilt tolerance | 0.15° / 0.10° |
| Direction reversal hold | 2 s after last actual command in the old direction |
| Maximum fine correction | 0.25° |
| Saved-target GoTo trigger | 3° aiming error on either axis |
| Maximum saved-target GoTo leg | 45° per axis |
| GPS maximum age | 2 s |
| Rejected GPS jump speed | above 30 m/s |

At about 530 m, 6.48° corresponds to a 60 m wide frame and a 1° offset to about 9.3 m. Set field of view manually when zoom changes. The offset check reserves the aiming tolerance within the half-frame angle; it does not guarantee room for GPS error, backlash or subject size.

## Motion behavior

All moves use **finite native GoTo**: a G mode, an absolute S destination, then J start. Fine corrections use G20/G21 and remain capped at ten degrees per axis (default 0.25°). Manual positioning offers 0.1°, 0.5° and 1°. Saved-target acquisition uses G00/G01 for axes moving more than one degree and G20/G21 for smaller axes. Every GoTo leg is capped at 45° per axis. VTC requires an empty normal acknowledgement for each setter and start command and never emits continuous G10/G11 speed commands.

Under **GoTo for large aiming errors**, configure the trigger and maximum movement per leg. At capture VTC saves the GPS packet, geographic target, pan/tilt destination and applied framing offset. It finishes that saved journey without chasing newer surfer positions. GPS reception and logging continue independently, but GPS changes, stale packets and NOFIX do not interrupt or redirect this finite GoTo. A turn larger than the configured leg limit uses successive legs toward the **same** saved destination, through the permitted landmark-relative sector. After arrival VTC checks the latest GPS and resumes live aiming, or waits for fresh GPS. Reversal holds still apply before a new GoTo is captured. Stop, mount/Wi-Fi faults and movement limits remain active.

The recorded home-test error of about 37° pan and 9° tilt now fits into one native GoTo with the defaults, instead of roughly 148 quarter-degree pan corrections. Physical completion time depends on the mount. Fine moves retain a 15-second timeout; GoTo legs use a bounded distance-based timeout allowance of 15–100 seconds, not a speed prediction.

VTC checks both axes are stopped and requires acknowledgement of all requested destinations before starting either axis. It waits for a complete stopped motor sample, logs arrival error and plans any remaining correction from the measured position. A small overshoot or undershoot does not stop aiming or clear calibration. The 0.03° value is now a diagnostic logging threshold, not an arrival fault. Movement cancellation is checked between transactions, including immediately before each start. Routine polling timeouts follow the recovery process above; failed movement commands, unexpected movement, blocked motors remain faults. A temporary target range/angle crossing pauses new commands and resumes automatically on a valid in-limit target.

The 1.0.1 field log showed successful calibration followed by four pre-start failures: firmware 0336C5 acknowledged S but h returned 0x800000 instead of the new target. The old check aborted and disconnected the app before J. Version 1.0.2 removes that pre-start h requirement, retaining the documented acknowledged finite-GoTo sequence and post-move position verification. Regression tests include the recorded 2,073,600 counts/revolution and target calculation. Protocol reference: [Sky-Watcher motor controller command set](https://inter-static.skywatcher.com/downloads/skywatcher_motor_controller_command_set.pdf).

The 1.0.2 field log confirmed working GoTo commands and continuous GTi/LoRa connectivity. On move 41, the app read pan position 8331991 during deceleration, then a stopped status later. Comparing that earlier position with target 8331818 produced a false arrival error of 0.0300347°. The next position was only four counts from target and subsequent readings reached it. Version 1.0.3 reads both motor statuses before and after the position counters. If either axis reports movement at either check, that sample cannot confirm arrival or start another move; the next poll supplies a new sample. That version retained the 0.03° arrival fault; 1.0.8 removes it. Logs now include both sets of status replies and the positions used for every arrival check.

These are position increments, not a guaranteed angular rate. GTi firmware determines native GoTo speed and acceleration; the native low-speed selection can still look quick. Each step is finite even if the app or Wi-Fi disappears, but this relies on the mount executing its native GoTo correctly. An immediate Stop cannot be guaranteed across a broken connection. Smooth filming, mechanical backlash and arrival behavior still require verification on the GTi.

The reversal hold requests no new movement while it is active; it does not extend the old direction. Zeros and blocked requests do not refresh the reversal clock. Pan targets stay within the calibrated sector, rather than choosing a full turn around a limit.

## GPS and network

Wire format:
```text
RX,GPS,sequence,senderMs,latitude,longitude,satellites,hdop,rssi,snr,missed
```

A HELLO is sent every five seconds. Only datagrams from the configured receiver address and source port are accepted. Sequence and sender time are unsigned 32-bit values, including rollover. Three increasing lower-counter packets are required to establish a transmitter restart. NOFIX invalidates the live GPS target immediately; an already captured GoTo destination is retained until arrival or cancellation. A rejected GPS jump breaks live framing evidence; duplicates and older packets never refresh freshness.

The control worker serializes mount commands. GPS reception and disk logging have separate bounded queues. Network sockets bind to the selected Wi-Fi network, so cellular internet is not used for mount control. VTC does not change device station-mode settings or receiver firmware.

Read-only mount queries allow 1.5 seconds; movement and Stop acknowledgements allow 700 ms. In the 22:01 field run, a status read timed out after 700 ms, and a status-shaped reply arrived during the following Stop transaction. The transport checks each reply against the expected status, counter or empty-acknowledgement shape, logs mismatches, and keeps receiving within the original absolute deadline. It never retransmits movement commands or extends that transaction's deadline. Version 1.0.5 retries complete routine position reads at the controller level after timeouts, with the recovery rules above. Explicit controller errors still propagate. A fresh local port is used per transaction and logged with its timeout. Shape checks cannot supply a transaction ID that the mount protocol does not include.

An exception caused by an intentionally closed LoRa socket is recorded as a requested-close result, not as an independent receiver failure. Actual receive failures remain errors.

Version 1.0.7 discards unused GPS framing observations while waiting for mount replies or while the app is backgrounded. It retains the latest accepted GPS position, its original freshness timestamp, sequence validation and raw telemetry logging. The 1.0.6 morning run received GPS continuously during a three-minute GTi outage, but the unconsumed 64-entry framing queue overflowed about every 33 seconds and misleadingly logged `gps_invalid`. Deliberate discards now log `gps_observations_discarded` with count, reason and original reception times, without resetting GPS continuity. Unexpected queue overload during normal live aiming still invalidates framing evidence.

Field evidence from 17 November: the saved bookmark loaded across an app restart; both motor axes then reported uninitialized and the bookmark was correctly invalidated. The second run recovered automatically from one missing status reply in 2.026 seconds before Android subsequently lost the selected Wi-Fi network. These logs do not identify the cause of the mount or Wi-Fi outages. No target-limit crossing occurred in either run, so automatic resumption at those limits remains covered by software regression tests rather than these field runs.

## Logs and setup files

Full logs start automatically when VTC opens and write continuously to **Download/VTC/logs**. There is no Save or Export step. Each session gets its own `VTC_1.0.9_...jsonl` file, visible while the app is running. The writer flushes continuously (normally within 200 ms), including while disconnected or waiting for calibration. Android 10+ needs no storage permission; Android 8–9 asks once for the standard storage permission. Public-storage failures are shown on the Logs screen; VTC keeps a private recovery copy and retries automatically every five seconds, backfilling that session's public file.

Logs include full saved settings and form edits, UI actions, lifecycle events, selected network details, raw GPS datagrams (text and Base64), all parsed GPS fields, acceptance/rejection reasons and counters, mount command/reply transaction IDs and timing, raw motor status/positions, calibration attempts/results/errors, every aiming cycle and correction decision, reversal holds, and all framing evidence. A **status snapshot every second** includes effective settings and explicit blockers even before calibration. Errors include stack traces. The Logs screen shows the destination, written-record count and dropped-record count. Logging uses a separate bounded worker so slow storage does not block mount control; any overflow is recorded explicitly.

The Aim / next steps panel lists missing settings, calibration steps, GPS/connectivity blockers and the actual target range against the configured limits. Setup can still be exported/imported as JSON; importing fills the form for review and Save applies it. Calibration and active aiming state are never restored from setup files.

Each session also creates a matching **KML automatically in Download/VTC/logs**, with no export step. It records camera coordinates, accepted surfer GPS points and track segments, camera aiming direction and configured horizontal field-of-view sectors at the one-second status cadence, and captured/completed/cancelled GoTo targets. Heading is true geographic bearing calculated from landmark calibration and motor counts; tilt is degrees from horizontal. Fresh surfer range sets the display-ray length during live aiming; saved range is used during GoTo; a clearly labelled 100 m display ray is used when neither is available. Geometry is clamped to terrain, while heights above water remain metadata rather than being misrepresented as sea-level altitude.

Open the KML in Google Earth and use its time slider for camera aiming history. Each completed disk update retains the closing XML tags, so the document is usable while the session is running. A separate bounded disk worker incrementally appends placemarks, keeps a private recovery document, and automatically retries/backfills the public file after storage errors. KML errors and dropped events appear on Logs. GPS gaps are not bridged with invented track segments. Format reference: [Google KML 2.2 reference](https://developers.google.com/kml/documentation/kmlreference).

## Build and verification

Requires Android SDK 35, Build Tools 36.0.0, JDK 17, and Gradle 8.12 (wrapper included). Android 8.0+ is supported.

```powershell
$env:JAVA_HOME='C:\Users\gildo\.jdks\jbr-17.0.14'
.\gradlew.bat --offline --no-daemon testDebugUnitTest assembleDebug lintDebug
```

Automated unit tests cover bearing/elevation geometry, calibration signs and wrap, sector limits, reversal timing, GPS parsing/restarts/duplicates, framing trends/quiet/gaps, finite motor-command failure paths, movement transitions during position reads, immutable GoTo destinations, bounded legs, automatic KML generation/recovery, delayed/mismatched replies and response deadlines. New cases cover 1.6-second missed reads, intermittent and sustained packet loss, lost Stop acknowledgements, cancellation during recovery, unexpected counter changes, motor errors/restarts, and returning both landmark axes with either motor sign. New tests replay the recorded target boundary crossing and cover saved-landmark serialization, compatibility checks, reset invalidation, both-axis return and calibration gating. These are software checks using a fake motor transport; automatic boundary resume and saved-landmark reuse still need confirmation on the GTi.

No Android device or emulator was connected during the build. First hardware check: confirm the calibrated camera stays on the landmark after small left/right and up/down moves, then aim at a stationary GPS target before following a surfer.

