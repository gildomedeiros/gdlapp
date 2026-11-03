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

Migration:
  Reusable tools, model, FFmpeg and saved settings were copied from CAM3.
  Output now goes under VT Tools; input log/video folders retain their settings.
  Archived flight logs/reports, generated check maps, old selection snapshots,
  Android sources, APKs, SDK docs, IDE files and Android test scripts were not copied.
  CAM3 was left intact. Preserve any old flight logs/reports you want before
  deleting it.

Map interactions:
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

Surfer highlights:
  Ride detected toggles the purple rings. Speed threshold toggles cyan rings
  for GPS speed at or above an editable threshold (default 15 km/h).
  This uses speed since the previous packet, as shown in each position card.
  Both highlights can show together. Changes update currently displayed positions
  immediately without changing sample selection. Unknown speeds are excluded.
  Dashed rings at shared coordinates mean only some displayed positions qualify.

Seaward position filter:
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
  Hover the dashed line between a surfer and drone for a compact floating label:
  pair IDs with [horizontal distance][filming distance], ride detection, retreat
  status with its minimum distance, and actual gimbal tilt plus active target.
  These use the same logged values as the full position card. Missing values
  remain unknown. Labels disappear on leaving, dragging, filtering or measuring.
  The hidden hover stroke spans the gaps between dashes; GPS markers keep
  priority at the endpoints. Tab focus also shows the connection label.
