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
