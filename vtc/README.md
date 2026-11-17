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

