"""Local-only frame capture service for the generated surfer map."""
import json
import os
from pathlib import Path
import re
import subprocess
from http.server import BaseHTTPRequestHandler, HTTPServer

from telemetry_selected_frames import ffmpeg_path


def capture_frame(point, video_folder, output, ffmpeg):
    seconds = point.get('videoPlaybackSeconds')
    ident = point.get('videoId')
    if seconds is None or ident is None:
        raise ValueError('No unambiguous SRT frame mapping for this surfer point.')
    videos = [p for p in Path(video_folder).iterdir() if p.suffix.lower() == '.mp4'
              and re.match(r'^DJI_0*' + re.escape(ident) + r'(?:_|$)', p.stem, re.I)]
    if len(videos) != 1:
        raise ValueError('Matching MP4 is missing or ambiguous: DJI_' + ident)
    folder = Path(output) / 'captured-frames'
    folder.mkdir(parents=True, exist_ok=True)
    name = f'{point["point"]}-packet-{point["sequence"]}-{videos[0].stem}.png'
    dest = folder / name
    result = subprocess.run([ffmpeg, '-hide_banner', '-loglevel', 'error', '-nostdin',
                             '-ss', str(seconds), '-i', str(videos[0]), '-frames:v', '1',
                             '-threads', '2', '-y', str(dest)], capture_output=True, text=True,
                            timeout=60, creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
    if result.returncode or not dest.is_file():
        raise ValueError('Frame extraction failed: ' + result.stderr[-500:])
    return dest


def serve_map(points, output, video_folder, ffmpeg=None):
    executable = ffmpeg_path(ffmpeg)
    points_by_id = {p['point']: p for p in points}
    images = {}

    class Handler(BaseHTTPRequestHandler):
        def reply(self, status, data, kind='application/json'):
            self.send_response(status)
            self.send_header('Content-Type', kind)
            self.send_header('Content-Length', str(len(data)))
            self.send_header('Cache-Control', 'no-store')
            self.end_headers()
            self.wfile.write(data)

        def do_GET(self):
            if self.headers.get('Host') != address:
                return self.reply(403, b'{}')
            if self.path == '/':
                return self.reply(200, (Path(output) / 'surfer-gps.html').read_bytes(), 'text/html; charset=utf-8')
            if self.path in images:
                return self.reply(200, images[self.path].read_bytes(), 'image/png')
            self.reply(404, b'{}')

        def do_POST(self):
            if self.path != '/capture' or self.headers.get('Host') != address or self.headers.get('Origin') != 'http://' + address:
                return self.reply(403, b'{}')
            try:
                length = int(self.headers.get('Content-Length', '0'))
                if not 0 < length < 1024:
                    raise ValueError('Invalid request size')
                point = points_by_id.get(json.loads(self.rfile.read(length)).get('point'))
                if point is None:
                    raise ValueError('Unknown surfer point')
                dest = capture_frame(point, video_folder, output, executable)
                url = '/frames/' + point['point'] + '.png'
                images[url] = dest
                self.reply(200, json.dumps(dict(url=url, filename=dest.name,
                    timestamp=point['videoFrameTimestamp'], saved=str(dest.resolve()))).encode())
            except (ValueError, OSError, subprocess.TimeoutExpired) as error:
                self.reply(400, json.dumps(dict(error=str(error))).encode())

    server = HTTPServer(('127.0.0.1', 0), Handler)
    address = f'127.0.0.1:{server.server_port}'
    print(f'Interactive capture map: http://{address}/', flush=True)
    print('Keep this command running. Ctrl + left-click a surfer. Ctrl+C stops the server.', flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
