VT Tools

Start the map:
  Double-click Start Map.cmd
or run:
  python "C:\Users\gildo\gdlapp\VT Tools\tools\surfer_gps_map.py" --serve

Open the printed local address. Select logs/videos, rides, timestamp, pitch AND
distance or framing samples in the browser. Keep the console open while using
the map; Ctrl+C stops the server.

Folders:
  tools                     Reusable scripts, map HTML/JavaScript and Python tests.
  tools/models              Local person/surfboard detection model.
  dependencies/frame-tools  Bundled FFmpeg with package metadata/license.
  exports/surfer-map-app     Saved settings, generated maps, captures and CV cache.

Included tools:
  surfer_gps_map.py          Map server and offline map exports.
  surfer_subtitles.py        Non-destructive batch subtitle export from the map.
  surfer_subtitle_layout.py  Fixed ASS card layout and compact SRT fallback.
  telemetry_around.py       Reports around timestamps or wave rides.
  telemetry_selected_frames.py  Extract frames from selected telemetry rows.
  telemetry_selected_kml.py     Export selected positions to KML.
  telemetry_to_srt.py        Telemetry subtitle generation.
  gopro_wifi_probe.py        Experimental GoPro probe; see its companion guide.
  setup_surfer_fov.py        Restore the model if removed.
  Other surfer/telemetry modules are required helpers; keep them together.

Python:
  Uses the existing python command on this computer.
  Basic maps need only Python. Frame overlays need Pillow; person detection
  also needs Ultralytics. These packages are installed in the current Python.
  On another computer, install Python, then for those optional features:
    python -m pip install -r requirements-cv.txt
  FFmpeg and the model are bundled; no CAM3 project is needed.

Verification, from this folder:
  python -m unittest discover -s tools -p "test_surfer_*.py" -q

Export video subtitles (on the map page):
  Open "Export video subtitles". Select individual card fields, Select all,
  or Clear all, then "Export subtitles for all videos". Uses every MP4 directly in
  the Video / SRT folder and all packets from the selected log folder. Map
  selections, ride filters and hidden positions do not limit this export.
  Every video needs an original same-stem SRT containing its per-frame recording
  timestamps. Missing or malformed SRTs are reported and skipped; other videos
  continue. MP4s are not decoded, edited or re-encoded.
  Backward recording-clock steps are flagged in the report; each frame still
  uses its own original recording timestamp, with original playback timing.
  Each frame holds the latest GPS packet RECEIVED AT OR BEFORE that frame's
  recording timestamp, never a future packet. GPS age is frame time minus GPS
  receipt time, in seconds with millisecond precision. Before the first receipt,
  GPS fields show unavailable. After the last receipt they remain held and age.
  GPS age and received-packet gaps turn red above the Red gap threshold setting.
  Packet numbers identify records across videos; map S labels are selection-local.
  Movement direction uses previous-to-current GPS, without a future packet.
  Each MP4 gets two subtitle files, with the same base name as the video:
    DJI_xxxx.ass  The card: explicit top-left position, font size, translucent white panel,
                  orange/green/purple highlights and selected-band emphasis.
                  Panel and highlight fills are 50% transparent; text is opaque.
                  Layout scales from a 1920x1080 reference canvas. Camera
                  metadata remains in a smaller section under the selected data.
    DJI_xxxx.srt  Compatibility fallback: explicit 28px font tags and top-left
                  alignment hint. SRT styling still depends on player support.
  Load the .ass file for the intended card appearance. SRT cannot specify a
  consistent card layout. ASS players honour its embedded styles by default.
  ASS uses centisecond timestamps: boundaries round up by at most 9 ms, without
  overlaps or showing the next cue early. SRT keeps exact original timings.
  New files: <Output folder>/subtitles/<unique batch>/DJI_xxxx.ass and .srt.
  Original SRT cue text is retained and the chosen fields are appended.
  Original SRTs and MP4s are never overwritten. Every run gets a new folder.
  Download individual subtitles, the report, or the complete ZIP from the map page.
  The current map remains open. Field choices are remembered after an export.
  To play, load the new .ass as an external subtitle in your video player.
  Browser integration check: node tools/test_surfer_subtitles_browser.cjs
  (Playwright on NODE_PATH, optional PLAYWRIGHT_CHROMIUM_EXECUTABLE.)

Migration:
  Reusable tools, model, FFmpeg and saved settings were copied from CAM3.
  Output now goes under VT Tools; input log/video folders retain their settings.
  Archived flight logs/reports, generated check maps, old selection snapshots,
  Android sources, APKs, SDK docs, IDE files and Android test scripts were not copied.
  CAM3 was left intact. Preserve any old flight logs/reports you want before
  deleting it.

Map interactions:
  Press W on a highlighted surfer/drone/connection or in a surfer frame to
  estimate the nearby breaking-wave crest. The clean MP4 frame is analysed;
  telemetry text is excluded. A magenta overlay shows the detected edge in the
  frame, and a dashed magenta orientation marker is saved at the surfer GPS.
  Hover or focus a surfer, drone or connection line, then press Enter to preview
  its nearest video frame. At shared coordinates this uses the selected Point;
  focus an individual position card and press Enter to preview that receipt.
  Enter keeps its usual behaviour in inputs, buttons and measurement mode.
  Ctrl + left-click a surfer marker to capture its nearest frame.
  At shared coordinates, Ctrl-click the exact position in the scrollable card.
  Ctrl-click a position in the left sidebar to capture it without changing its checkbox.
  Use Map layers to hide drones, connection lines or surfers independently.
  These layer switches keep your selected samples and ride filters intact.

UI regression checks:
  With jsdom available to Node.js, run: node tools/test_surfer_map_ui.cjs
  This generates a temporary synthetic fixture and tests the actual rendered map scripts.

Ride detector markers:
  A purple ring marks GPS receipts where the latest logged movement_cycle.riding
  state was true. A dashed purple ring means selected positions at the same
  coordinates have mixed ride states. Selecting one position updates its ring.
  Sidebar RIDE badges, position cards and saved frame cards show this state too.
  Padded ride-selection windows do not count as detector-active periods.
  Unknown or missing detector state is shown as unknown, without a purple ring.

CV wave crest orientations:
  W first runs the bundled Wave-by-Wave U-Net on the original clean frame.
  It estimates a nearby crest axis from the model's breaking-water mask.
  If the model cannot run or provides no reliable nearby edge, W uses the
  previous whitewater detector. The status identifies Wave U-Net or Whitewater
  fallback; no extra shortcut or command-line option is needed.
  It is an estimated breaking crest, not a classification of every wave/ripple.
  Check the magenta edge on the frame: foam boundaries are only a crest proxy.
  Only edges within 25% of image width of the surfer reference are candidates.
  The existing person model locates a single visible surfer when available
  (overlapping duplicate detections are merged). Otherwise GPS projection is
  used. A clear breaking lip close to the surfer can be accepted alone; distant
  parallel foam cannot win simply by contributing more segments.
  Distant foam is rejected even if there is no local match. This detector does
  not identify unbroken crests from water shading; those may be unavailable.
  Existing kept marks remain visible. Press W to recalculate an older result
  with the new model. Ignored marks stay ignored until explicitly retried.
  The 19 MB model runs locally through OpenCV on CPU. No TensorFlow, downloads
  or package changes are needed while using the map. The model is loaded on
  first W, with serialized inference for concurrent requests.
  Model source, pinned revision and checksums: tools/models/wave_crest_unet.json.
  Source: https://github.com/fj23eslaonda/Wave_by_Wave_Identification
  The upstream model targets breaking waves. A model result is not evidence
  that a smooth unbroken crest was detected. Both methods may return unavailable.
  No line is added when edges are weak/ambiguous or camera telemetry is missing.
  Map projection uses fresh logged aircraft yaw + gimbal relative yaw, gimbal
  pitch/roll and a horizontal FOV estimate. The sidebar default is 74 degrees,
  an uncalibrated starting estimate; adjust it for the camera/crop in use.
  Flat water, square pixels, negligible lens distortion and water at the takeoff
  height datum are assumed. These are orientation estimates, not surveyed lines.
  Bearings are undirected axes (0-180 degrees), not wave travel directions.
  Each marker follows the detected image segment projected onto the water plane.
  With a single detected surfer, relative water offsets are registered to that
  surfer's GPS; otherwise endpoints use the logged drone GPS and camera pose.
  It can appear ahead of, across or behind the surfer. Its estimated length is
  the detected segment, not a wavelength. Hover for the projection assumptions.
  Results accumulate under exports/<map-output>/wave-orientations and are
  restored for the same source log even when a different selection changes IDs.
  Position/layer filters preserve the pattern. Hide wave crest detections in
  Map layers hides both map marks and frame overlays without discarding them.
  Fit saved orientations brings the complete pattern into view.
  Press I on the same position or frame to ignore its detection. This persists
  across reloads and selections; press W explicitly to retry an ignored frame.
  Repeat W reuses a matching cached result, or replaces that frame's result
  after changing FOV/source video. Raw Ctrl+arrow video frames require returning
  to a surfer-position frame before W, so unrelated telemetry is not reused.
  Source methods: https://docs.opencv.org/4.x/d9/db0/tutorial_hough_lines.html
  Projection: https://docs.opencv.org/4.x/d9/d0c/group__calib3d.html
  OpenCV and NumPy are needed (included with the existing CV installation).
  Restart and regenerate the map after this update; existing source video/logs
  are read only.

Surfer highlights:
  Ride detected toggles the purple rings. Speed threshold toggles cyan rings
  for GPS speed at or above an editable threshold (default 15 km/h).
  This uses speed since the previous packet, as shown in each position card.
  Both highlights can show together. Changes update currently displayed positions
  immediately without changing sample selection. Unknown speeds are excluded.
  Dashed rings at shared coordinates mean only some displayed positions qualify.

Seaward position filter:
  Hide seaward positions is checked by default when a map opens.
  In Map layers, Hide seaward positions removes surfer positions whose arrows
  point toward the logged sea direction, plus their paired drones and lines.
  Direction uses the next original GPS receipt, even outside the selected window.
  Stationary, sideways and unknown directions remain visible. The sea bearing
  comes from the latest prior movement cycle in the same log/session.
  Shared coordinates retain any matching visible positions. Cards, rings and
  position navigation use only the remaining positions. Switching the filter
  off restores positions without changing manual selections or other layers.

Map scale:
  The scale is drawn directly on the bottom-left complete visible grid square:
  its left edge, bottom edge and rising 45-degree diagonal. Labels show the
  actual grid distances (for example 29.2 m sides / 41.2 m diagonal), not a
  separate 10 m ruler. The metre values stay constant while zooming, and the
  scale follows the lowest, leftmost complete visible cell when panning.
  If no complete cell is visible, the scale hides until a cell returns to view.
  The SVG export labels the original bottom-left grid square too.

Surfer keyboard navigation:
  Hover over a surfer position to select it, then press Right for the next
  position or Left for the previous position in time order. The selection
  stays active after the pointer leaves; hovering another surfer selects it.
  Hover also gives the map keyboard focus, including when it is embedded in
  the server page or a settings input previously had focus.
  Clicking also selects; Up/Down remain available as aliases on the map.
  The marker, details card and side view
  update together, including separate receipts at shared coordinates.
  In a multi-position view, navigation keeps the checked positions unchanged
  and skips hidden positions. In single-position mode, it advances the selection.
  Ride and seaward filters remain active. Navigation stops at either end, keeps
  the zoom level and pans only if the next marker is outside the visible area.
  Editing fields retain normal keyboard behaviour.

Frame viewer navigation:
  While a captured frame is open, Right loads the next surfer position's frame
  and Left loads the previous one. The viewer stays open and uses the same
  ride, seaward and position selection as map navigation, without wrapping.
  Each frame includes the existing details card and its download link.
  The old frame remains visible while loading. Only one capture runs at a time;
  repeated keys while busy do not queue extra requests. Failures keep the old
  frame and selection, with an error in the viewer. Closing while loading
  prevents a late response from reopening the viewer or changing selection.

Frame download options:
  With telemetry: the displayed frame and its complete information card.
  Clean: the original full-resolution MP4 frame, without a telemetry overlay.
  Enhanced: a clean frame with deeper midtones, richer teal colours, contrast
  and light sharpening. This preserves the original resolution and framing;
  it does not crop, upscale or reconstruct the surfer.
  Clean/enhanced variants are generated on demand and stored separately under
  each map selection's download-frames folder with distinct PNG filenames.
  The telemetry preview and its arrow-key navigation remain available.
  Restart Start Map.cmd and generate the map again after this server update.

Actual video frame stepping:
  In the frame viewer, Ctrl+Right advances one actual MP4 frame and Ctrl+Left
  goes back one actual frame. This uses presentation timestamps decoded from
  the video, including variable frame rates, independently of GPS sampling.
  The first/last frame stops navigation at the current MP4's boundaries.
  Video-only frames show their playback time and GPS reference, without a
  telemetry overlay or any invented GPS position. Clean and enhanced downloads
  export exactly the displayed video frame; map selection remains unchanged.
  Plain Left/Right returns to the nearest eligible surfer record before/after
  the displayed video frame. Ride and seaward filters still apply to records.
  On the map, hover selects a surfer and Left/Right steps between positions;
  Up/Down also works outside the frame viewer.
  Restart Start Map.cmd, then regenerate the map to load this server update.

Speed visibility filter:
  Map layers: Hide speed below threshold uses the existing Speed >= input.
  It hides positions with known GPS speed below that value, plus their paired
  drones and connection lines. Exactly-at-threshold and unknown speeds remain
  visible. The speed-ring checkbox is independent. Threshold edits update the
  filter immediately; invalid/empty values temporarily suspend speed filtering.
  Ride and seaward filters combine with it. Position cards, lists and record
  navigation use the remaining positions. Turning it off preserves manual
  selections and other layer switches. Raw video stepping remains independent.

Full map grid and logged references:
  The grid covers the whole visible map, including after pan, zoom and resize.
  Wave line: the selected profile A/B coordinates from the logged configuration.
  Boundary line: through the logged original anchor, perpendicular to the
  logged seaward bearing. Start: the first valid, non-rejected surfer GPS fix
  in the log session, including fixes before VT control starts. References follow
  the selected log/session and only use information available at each sample.
  Hover a reference line or start point to highlight it and show its label;
  labels stay hidden otherwise. Tab focus also reveals a reference label.
  Fit includes these reference positions. Map layers provides separate Hide
  wave line, Hide boundary line and Hide start point switches. Missing logged
  coordinates are not invented. SVG/KML and map-landmarks.json retain references.

Marker distance ruler:
  Expand Map controls & info and enable Measure distance. Left-click any two
  surfer/drone markers, Start points, wave-line endpoints or boundary anchors.
  Reference endpoint/anchor dots appear while measurement is on.
  A purple ruler connects the exact coordinates, with metre ticks and the total
  horizontal GPS distance on the map. The label keeps its size during zoom.
  A third click starts a new measurement; Clear measurement resets it.
  Layer/position filter changes also clear it. Enter/Space selects a focused
  marker. The movable 1.75 m scale figure is not a GPS position.

Connection-line hover labels:
  Hover the dashed line between a surfer and drone for a floating label with
  the same complete field list as both position cards and saved-frame telemetry.
  This includes distance, movement, ride/retreat/controller state, drone height,
  gimbal readings/bands, LoRa signal, packet timing, video time and coordinates.
  Text wraps inside the map; selected bands stay green and packet gaps stay red.
  Missing values remain unknown. Labels disappear on leaving, dragging,
  filtering or measuring.
  The hidden hover stroke spans the gaps between dashes; GPS markers keep
  priority at the endpoints. Tab focus also shows the connection label.

Drone height:
  All cards and connection hover text use the logged heightAboveTakeoffM,
  explicitly labelled "above takeoff". It is not a measured height above water.
  Missing/invalid readings show unknown; zero and below-takeoff readings remain
  valid. This uses the same logged height as the side view. Restart and regenerate
  maps and saved frames to include the shared fields.

Drone heading:
  Translucent red arrows show the logged aircraft compass heading (north = 0,
  east = 90), drawn behind the other map lines. Hide drone headings in Map
  layers toggles these independently. They follow all position filters and only
  appear while the paired surfer, drone or connection is hovered. Leaving the
  target hides the ray; a pinned card or keyboard focus does not keep it visible.
  Ray length matches the paired surfer distance for comparison; zero distance
  uses a 10 m guide. This is direction, not a predicted flight path.
  Only prior, fresh aircraft yaw in the same log/session is used (sensor age
  plus receipt delay at most 1.5 seconds). Missing/stale readings get no ray.
  Cards, connection hover text and saved telemetry also show Drone heading.

Drone controller status:
  Surfer/drone cards, connection hover labels and captured telemetry frames
  include Aiming, Come to Me and Boundary status. These identify controller
  state and the current reason (ride wait, retreat cooldown, route/clearance
  failure, GPS pause, return, stopped, etc.). Boundary blocks are red and name
  the affected destination, route or retreat; a nearby position alone is not
  treated as proof of a block. The boundary row also shows logged distance
  seaward/shoreward of the line when available. Sent yaw/movement rates describe
  commands, not measured physical motion.
  Status uses only records at or before that GPS receipt in the same file and
  control session, breaking equal timestamps by source line. Start/stop/session
  transitions clear previous evidence. Records older than one second are shown
  as unknown with their age. The exported droneStatus field retains source
  lines, cycle/session IDs and times. Restart and regenerate existing maps.

LoRa signal:
  Surfer/drone cards, connection hover labels and captured telemetry frames
  show RSSI in dBm and SNR in dB from that GPS receipt's own lora_packet.
  Missing or invalid readings are shown as unknown; readings are not borrowed
  from adjacent packets. Restart and regenerate existing maps to include them.
