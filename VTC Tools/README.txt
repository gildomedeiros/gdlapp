VTC Tools 0.4.0 — VTC 1.0.40 activity cards

Wave ride, GoTo and Continuous pan status now appear immediately below Aim
on position and hover cards (also retained in KML/CSV detail text). Ride shows
the logged rideActive flag, qualifying detection speed, confirmations, reason
and age. Raw GPS speed and ride-detection speed remain distinct.
GoTo shows activation/progress/completion/cancellation and target kind; last
activation includes time, packet and acquisition trigger. Continuous pan shows
commanded active left/right, hold-pan, reversal wait, stopped or stale/unknown.
Activity uses only earlier log events at each GPS receipt, with ages shown.
A command/active status describes logged control, not proof of physical motion.
Latest VTC 1.0.40 adds metre ride framing, centreline pan hold, recording and
run events; these diagnostics are read without changing source JSONL files.

VTC Tools 0.3.2 — fixed shore-camera log map

SX video filename + playback selection:
  Set SX footage timing > Video folder, then Read video filenames. Each of the
  three selection rows can choose an MP4/MOV and playback time independently,
  or retain a direct log timestamp. Playback accepts MM:SS, HH:MM:SS, seconds
  and decimal fractions. Video + playback takes precedence over that row's
  disabled direct log timestamp. Following packets works as before.
  Calculated log time = recording start + playback time + clock correction.
  Positive correction moves the log timestamp later; negative moves it earlier.
  Correction is shared by all three clips (seconds, fractional values allowed).
  Every row previews the exact calculated Brisbane log timestamp and its source.

  Recording start and duration come from the MP4 movie header (moov/mvhd), using
  the 1904 epoch, version 0 or 1. Only headers are read; large video payloads are
  skipped by seeking. No decoder, ffprobe or package installation is needed.
  Default metadata interpretation is UTC, per MP4. Some camera files store local
  wall time in that field: choose Camera local time (Brisbane) if required.
  No filesystem creation/modification dates are substituted for missing metadata.
  A per-row Recording start override supports missing/incorrect metadata. Use a
  full date/time; timezone-free overrides mean Brisbane UTC+10. Clock correction
  also applies to an override. Playback beyond known clip duration is rejected.
  All video settings, chosen filenames, playback values and overrides persist
  after Show positions. Full session clears all three video/timestamp selections.
  Source MP4/MOV files and their metadata are never modified.

Updated against local VTC build 1.0.27 and the supplied 1.0.25-format session.
VTC's current Android SessionLog.VERSION still says 1.0.25 while build.gradle
versionName is 1.0.27. The map labels that value as the recorded log header;
it does not infer the installed APK version from the filename.

New native telemetry support:
  Schema 2 metre aiming: magnitude, pan/tilt tolerance and acquisition trigger
  remain metres, with atan(metres / current GPS range) degree equivalents.
  Applied framing metres uses VTC's range*tan(angle) target-plane convention.
  Older angular settings remain supported and are not silently migrated.
  Cards, connection hover, CSV and KML descriptions include the new fields.

  Zoom modes Off, Bands and Auto-fit, requested optical/CCAPI zoom, selected
  band and buffer, active scale, clamping, pending commands and confirmations.
  A POST/ack is not treated as lens readback. Only earlier camera_zoom_response
  reports supply the readback; their age and stale status are shown. Read errors
  invalidate current zoom until another report. The display uses a 5 s freshness
  limit (an explicit Tools display policy, not an inferred camera guarantee).
  Optical zoom is inferred using ONLY the mapping recorded in that session,
  including JSON-encoded mappings and duplicate-command geometric midpoints.
  No new default mapping is applied to an older recording.
  Zoom frame width = current GPS range * logged wideFrameFactor / optical zoom.
  This remains separate from configured-HFOV projection and desired/effective
  widths. The optional purple zoom FOV fan is a readback-derived estimate.
  Off leaves the recorded lens value visible; it does not mean 1x.

  Session diagnostics lists zoom decisions, commands, confirmation/errors,
  calibration, mount recovery, settings verification and camera clock events,
  including events outside the selected GPS range. Filter by event type or
  download the full session diagnostic JSON. Original logs are not modified.
  Cards retain latest-earlier-at-GPS-receipt timing; independent camera events
  are never moved backwards to the preceding GPS point. Diagnostic timestamps
  and source-line numbers preserve when those events actually occurred.

Start:
  Double-click Start Map.cmd, then open the local URL printed in the window.
  Python 3.10+ is required. No Python packages, internet or map API key needed.
  Keep the console open; Ctrl+C stops the server.
  Alternative: python tools/vtc_map.py --folder "C:/Users/gildo/temp"

Choose a folder containing VTC_*.jsonl logs copied from Download/VTC/logs.
Refresh logs, choose a session and click Show positions. Only actual accepted
GPS packets produce surfer points; snapshots repeating a fix do not add points.
Malformed JSON and invalid accepted coordinates are reported and skipped.
Sessions without accepted GPS show an explicit empty result; choose another log.

Features adapted from VT Tools:
  Local north-up metric map, wheel zoom, drag pan, Fit visible, scale reference.
  Up to three timestamps with independent following-packet counts and history.
  Blank timestamps show the full log. Timestamp start = last accepted receipt at
  or before the requested time. Overlapping ranges are combined without repeats.
  Times without an offset use Brisbane UTC+10; use full date/time across dates.
  Show all / Hide all / per-point visibility; optional minimum speed filter.
  Previous/next buttons, Arrow keys and a GPS timeline; Delete hides selection.
  Same fields on position cards and floating connecting-line / marker hover.
  Ruler between any two surfer/camera/landmark markers: distance and bearing.
  KML and CSV for checked, speed-eligible points. Camera/landmark references are
  included by default in KML; raw geographic coordinates and timestamps retained.
  Exports downloaded by the browser; source logs are read only.

VTC-specific display:
  Fixed camera and calibration landmark coordinates from logged settings.
  Latest earlier fresh motor/calibration heading and tilt; translucent heading
  and target rays, with the logged horizontal-FOV angular fan for selected/hovered
  pairs. Layer checkboxes control tracks, connections, references and heading.
  Logged aiming state, target/error, framing reason, applied offset, magnitude,
  pan/tilt decision rates, reversal blocks, LoRa RSSI/SNR and saved GoTo context.
  These are logged VTC framing values, not the VT offline yaw simulator.
  New GPS is joined only to context recorded at or before that receipt (timestamp,
  then source-line order). The aim-cycle age and position report age are shown.
  A later aim cycle responding to a packet never supplies earlier card values.
  Motor/calibration values estimate camera orientation; they are not image CV.
  Speed is distance/time between consecutive accepted receipts from the full
  source log. Tracks do not join across GPS invalidation, continuity changes,
  camera changes or GPS gaps beyond logged gpsAge. Hidden intermediate points
  are not bridged into invented track segments.
  Sideways metres = range * abs(sin(logged applied offset)). Frame width uses
  logged HFOV, actual tilt and lens/target height difference at the surfer depth,
  assuming zero roll and alignment to the applied target. It is an estimate, not
  the entire ground footprint. Stale/missing motor pointing makes width unknown.

Separate VTC preferences live in exports/preferences.json. VT Tools files,
preferences and exports are not touched. Existing VT drone controls, ride-only
filters, drone simulation and DJI video capture/subtitles are not exposed here:
this first release focuses on real VTC map data. VTC logs do not define a wave
or no-fly boundary line; none is invented.

Reuse: geographic bearing/destination helpers copied from VT Tools, provenance
in reuse-manifest.json. VT map workflows adapted to VTC's native event schema.
The existing bundled licence is retained in LICENSE.txt.

Tests: python -m unittest discover -s tools -p "test_*.py"

Automatic session matching (0.3.1):
  Refresh logs now defaults to Automatic. Video recording metadata + playback +
  correction selects the session covering that log timestamp. Three rows may
  match three different logs. No manual session choice is required.
  Overlapping logs require the optional log override; a time outside all logs
  reports no matching session instead of using an unrelated earlier GPS packet.
  With all rows blank, Automatic shows the full sessions in the chosen folder.

Video-selected point labels use [clip,MM:SS], e.g. [635,00:50] for MVI_0635.MP4.
Playback is calculated individually for every following GPS packet, displayed
to whole seconds (fractional seconds retained in point data). Clip references
also appear in hover/card text and exported KML/CSV details. Direct log-time
selections retain log-clock labels. Points beyond clip duration have no label.

Aim cards use metres relative to the surfer: Pan target / Actual pan error
(left or right), Tilt target / Actual tilt error (above or below). These are
calculated pointing intersections with the vertical plane at the surfer GPS
range; they are not image measurements. Tilt requires logged lens and target
heights. Actual comes from fresh motor pointing, not controller error-to-target.
Stale target cycles and pointing behind the camera show unavailable. Raw angles
remain in diagnostics/CSV, and motor rates retain their angular-rate units.

The primary Sideways / estimated frame width row uses the VTC zoom estimate:
current surfer distance * logged wideFrameFactor / current readback-mapped
optical zoom. hfov is not used for this width; stale/missing zoom is unavailable.

Map display: one blue FOV fan now uses VTC estimated width from GPS range,
wideFrameFactor and current zoom readback. Its far edge spans that width at the
selected range. Actual heading (red) has an independent switch, initially off.
The selected surfer keeps its green marker with a thin orange outline ring.
Surfer arrows use consecutive full-log GPS receipts, including hidden points.
Far-edge blue arrows show observed camera-heading movement since the previous
GPS receipt; stationary/missing/gap heading evidence produces no pan arrow.

FOV freshness: green requires fresh zoom readback (5 s policy) and fresh logged
heading (gpsAge policy). Red uses the last available readback and heading at
the current GPS distance, with ages in the tooltip/card. No prior usable values
means no fan. Calibration reset or a different camera site invalidates the
heading reference. Ordinary fresh-only zoom telemetry still shows unavailable
when stale; only the explicitly red historical FOV uses retained values.

Generate subtitles for a selected MP4:
  Read the SX video filenames, expand Generate subtitles for selected MP4,
  choose the clip/data fields and click Generate SRT / ASS. The video folder,
  metadata clock and correction above apply. Recording start override can be
  provided here or taken from a selection row using that clip.
  Refresh the log folder first; no map generation is required. Automatic uses
  full logs, with optional log override for overlaps. Map filters do not apply.
  Downloads: matching-name SRT, matching-name ASS, report JSON and ZIP. Output
  is a new exports/subtitles/<unique-id> folder; original MP4/SRT are kept.
  Timing = recording start + playback + correction. Last received GPS fields
  remain held until the next receipt. GPS age/clock refresh every 100 ms, plus
  exact GPS/session boundaries. No future telemetry or frame-rate guess is used.
  Overlapping sessions, gaps and times before GPS display unavailable.
  The translucent ASS card uses VT Tools' existing explicit-position layout;
  SRT colour/bold tags depend on player support. ASS boundaries round up to
  centiseconds (at most 9 ms), while SRT retains millisecond timing.
