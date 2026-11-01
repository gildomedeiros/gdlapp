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

Surfer keyboard navigation:
  Click a surfer position, then press Up for the next position or Down for
  the previous position in time order. The marker, details card and side view
  update together, including separate receipts at shared coordinates.
  In a multi-position view, navigation keeps the checked positions unchanged
  and skips hidden positions. In single-position mode, it advances the selection.
  Ride and seaward filters remain active. Navigation stops at either end, keeps
  the zoom level and pans only if the next marker is outside the visible area.
  Editing fields retain normal keyboard behaviour.

Frame viewer navigation:
  While a captured frame is open, Up loads the next surfer position's frame
  and Down loads the previous one. The viewer stays open and uses the same
  ride, seaward and position selection as map navigation, without wrapping.
  Each frame includes the existing details card and its download link.
  The old frame remains visible while loading. Only one capture runs at a time;
  repeated keys while busy do not queue extra requests. Failures keep the old
  frame and selection, with an error in the viewer. Closing while loading
  prevents a late response from reopening the viewer or changing selection.
