"""Local-only frame capture service for the generated surfer map."""
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
from http.server import BaseHTTPRequestHandler, HTTPServer
from html.parser import HTMLParser

from telemetry_selected_frames import ffmpeg_path


def stamp_card(path, point, gap_red_ms=1000):
    """Render the same surfer tooltip data into the PNG, with readable wrapping."""
    from PIL import Image, ImageDraw, ImageFont
    from surfer_gps_map import make_svg
    from surfer_drone_status import drone_status_color

    class CardParser(HTMLParser):
        card = None
        def handle_starttag(self, tag, attrs):
            attrs = dict(attrs)
            if attrs.get('class') == 'surfer-marker':
                self.card = attrs

    parser = CardParser()
    parser.feed(make_svg([point], gap_red_ms))
    card = parser.card
    red = set(int(i) for i in card['data-red-lines'].split(',') if i)
    with Image.open(path) as source:
        frame = source.convert('RGB')
    margin = max(8, round(frame.width * .01))
    width = min(frame.width - margin * 2, max(420, round(frame.width * .36)))
    font_size = max(12, round(frame.width / 100))
    normal_color, orange, green = '#17324d', '#a94700', '#08752e'

    def layout(size):
        fonts = []
        for name in ('arial.ttf', 'arialbd.ttf'):
            try:
                fonts.append(ImageFont.truetype(str(Path(os.environ.get('WINDIR', 'C:/Windows')) / 'Fonts' / name), size))
            except OSError:
                fonts.append(ImageFont.load_default(size=size))
        scratch = ImageDraw.Draw(Image.new('RGB', (1, 1)))
        pad = 12
        line_height = size + 5
        x, y = pad, pad
        runs = []
        for index, line in enumerate(card['data-detail'].split('\n')):
            # Split only the emphasized portions, preserving every field.
            chunks = re.split(r'(\[[^\]]+\]\[[^\]]+\] m|[^|]*\(selected\)|TILTING.*)', line)
            for chunk in chunks:
                if not chunk:
                    continue
                color, background, bold = normal_color, None, False
                if line.startswith(('Retreat active:', 'Gimbal tilt:')) or re.fullmatch(r'\[[^\]]+\]\[[^\]]+\] m', chunk):
                    color, background, bold = orange, '#fff0dc', True
                if line.startswith('Retreat active: YES ') or '(selected)' in chunk or chunk.startswith('TILTING'):
                    color, background, bold = green, '#e6f5e9', True
                if line=='Ride detected: YES':
                    color, background, bold = '#8226a0', '#f5e8fa', True
                status_color = drone_status_color(line)
                if status_color: color, bold = status_color, True
                if index in red:
                    color, bold = '#c40000', True
                font = fonts[int(bold)]
                for word in re.findall(r'\s+|\S+', chunk):
                    # Character fallback also handles long timestamps/coordinates.
                    pieces = [word] if scratch.textlength(word, font=font) <= width - 2 * pad else list(word)
                    for piece in pieces:
                        length = scratch.textlength(piece, font=font)
                        if x > pad and x + length > width - pad:
                            x, y = pad, y + line_height
                        runs.append((x, y, length, piece, font, color, background))
                        x += length
            x, y = pad, y + line_height
        return runs, y + pad, line_height

    runs, height, line_height = layout(font_size)
    while height > frame.height - 2 * margin and font_size > 6:
        font_size -= 1
        runs, height, line_height = layout(font_size)
    if height > frame.height - 2 * margin:
        raise ValueError('Frame is too small to fit the full telemetry card.')
    panel = Image.new('RGB', (width, height), 'white')
    draw = ImageDraw.Draw(panel)
    draw.rounded_rectangle((0, 0, width-1, height-1), radius=7, outline='#9baaba')
    for x, y, length, text, font, color, background in runs:
        if background:
            draw.rectangle((x, y, x+length, y+line_height-1), fill=background)
        draw.text((x, y), text, font=font, fill=color)
    frame.paste(panel, (margin, margin))
    frame.save(path)


def resolve_video(point, video_folder):
    ident = point.get('videoId')
    if point.get('videoPlaybackSeconds') is None or ident is None:
        raise ValueError('No unambiguous SRT frame mapping for this surfer point.')
    videos = [p for p in Path(video_folder).iterdir() if p.suffix.lower() == '.mp4'
              and re.match(r'^DJI_0*' + re.escape(ident) + r'(?:_|$)', p.stem, re.I)]
    if len(videos) != 1:
        raise ValueError('Matching MP4 is missing or ambiguous: DJI_' + ident)
    return videos[0]


def capture_frame(point, video_folder, output, ffmpeg, gap_red_ms=1000, annotate=True):
    seconds = point.get('videoPlaybackSeconds')
    video = resolve_video(point, video_folder)
    folder = Path(output) / ('captured-frames' if annotate else 'fov-frames')
    folder.mkdir(parents=True, exist_ok=True)
    name = f'{point["point"]}-packet-{point["sequence"]}-{video.stem}.png'
    dest = folder / name
    result = subprocess.run([ffmpeg, '-hide_banner', '-loglevel', 'error', '-nostdin',
                             '-ss', str(seconds), '-i', str(video), '-frames:v', '1',
                             '-threads', '2', '-y', str(dest)], capture_output=True, text=True,
                            timeout=60, creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
    if result.returncode or not dest.is_file():
        raise ValueError('Frame extraction failed: ' + result.stderr[-500:])
    if annotate:
        stamp_card(dest, point, gap_red_ms)
    return dest


def enhance_frame(source, destination):
    """A restrained teal surf grade; keep the original framing and resolution."""
    from PIL import Image, ImageEnhance, ImageFilter
    with Image.open(source) as image:
        frame=image.convert('RGB')
    # Deepen washed-out midtones while keeping the end points fixed.
    frame=frame.point([round(255*(v/255)**1.12) for v in range(256)]*3)
    frame=ImageEnhance.Contrast(frame).enhance(1.12)
    frame=ImageEnhance.Color(frame).enhance(1.18)
    # Tint the midtones slightly toward teal, leaving black and white neutral.
    lut=[max(0,min(255,round(v+strength*v*(1-v/255))))
         for strength in (-.06,.015,.04) for v in range(256)]
    frame=frame.point(lut)
    frame=frame.filter(ImageFilter.UnsharpMask(radius=1.2,percent=110,threshold=3))
    frame.save(destination,format='PNG')


def capture_variant(point, video_folder, output, ffmpeg, gap_red_ms=1000, variant='telemetry'):
    """Export separate files; clean/enhanced always start with the original MP4."""
    if variant not in ('telemetry','clean','enhanced'):
        raise ValueError('Unknown frame variant; use telemetry, clean or enhanced.')
    if variant=='telemetry':
        return capture_frame(point,video_folder,output,ffmpeg,gap_red_ms)
    source=capture_frame(point,video_folder,output,ffmpeg,gap_red_ms,annotate=False)
    folder=Path(output)/'download-frames'
    folder.mkdir(parents=True,exist_ok=True)
    destination=folder/(source.stem+'-'+variant+'.png')
    if variant=='clean':shutil.copyfile(source,destination)
    else:enhance_frame(source,destination)
    return destination


def precompute_positions(points, output, video_folder, ffmpeg=None, progress=None, check_cancel=None, cache_output=None):
    """Populate every selected point, retaining successes when others fail."""
    from surfer_fov import FovEstimator
    executable = ffmpeg_path(ffmpeg)
    estimator = FovEstimator(Path(__file__).parent / 'models' / 'yolo11n-seg.pt')
    cached = {}
    cache_output=Path(cache_output) if cache_output else Path(output)
    for path in (cache_output / 'person-positions').glob('*.json'):
        try:
            result = json.loads(path.read_text(encoding='utf-8'))
            if result.get('zone') in ('TOP', 'MIDDLE', 'BOTTOM'):
                cached[(result.get('sourceFile'), result.get('gpsTimestamp'), result.get('frameTimestamp'))] = result
        except (ValueError, OSError):
            continue
    successes = 0
    for index, point in enumerate(points, 1):
        if check_cancel:check_cancel()
        if progress:progress(f'Detecting framing {index}/{len(points)}: {point["point"]}')
        print(f'Auto-FOV {index}/{len(points)}: {point["point"]}...', flush=True)
        point['personPosition'] = None
        point['personPositionError'] = None
        key = (point.get('sourceFile'), point.get('receivedTimestamp'), point.get('videoFrameTimestamp'))
        try:
            result = cached.get(key)
            reused = result is not None
            if result is None:
                frame = capture_frame(point, video_folder, output, executable, annotate=False)
                result = estimator.classify_person(frame, point, cache_output)
                cached[key] = result
            point['personPosition'] = result
            successes += 1
            print(f'  {result["zone"]}' + (' (cached)' if reused else ''), flush=True)
        except (ValueError, OSError, RuntimeError, ImportError, subprocess.TimeoutExpired) as error:
            point['personPositionError'] = str(error)
            print(f'  Unavailable: {error}', flush=True)
    print(f'Auto-FOV complete: {successes}/{len(points)} positions detected; {len(points)-successes} unavailable.', flush=True)


def serve_map(points, output, video_folder, ffmpeg=None, gap_red_ms=1000):
    try:
        import PIL
    except ImportError as error:
        raise ValueError('Frame cards need Pillow. Install it with: python -m pip install Pillow') from error
    executable = ffmpeg_path(ffmpeg)
    points_by_id = {p['point']: p for p in points}
    images = {}
    from surfer_fov import FovEstimator
    estimator=FovEstimator(Path(__file__).parent / 'models' / 'yolo11n-seg.pt')

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
            if self.path == '/wave-results':
                from surfer_wave_orientation import wave_results
                return self.reply(200,json.dumps(wave_results(points,output)).encode())
            if self.path == '/fov-results':
                results = []
                identities = {(p.get('sourceFile'), p.get('receivedTimestamp'), p.get('videoFrameTimestamp')) for p in points}
                for path in (Path(output) / 'person-positions').glob('*.json'):
                    try:
                        result = json.loads(path.read_text(encoding='utf-8'))
                        if (result.get('sourceFile'), result.get('gpsTimestamp'), result.get('frameTimestamp')) in identities:
                            results.append(result)
                    except (ValueError, OSError):
                        continue
                return self.reply(200, json.dumps(results).encode())
            if self.path in images:
                return self.reply(200, images[self.path].read_bytes(), 'image/png')
            self.reply(404, b'{}')

        def do_POST(self):
            if self.path not in ('/capture','/fov','/video-frame','/wave') or self.headers.get('Host') != address or self.headers.get('Origin') != 'http://' + address:
                return self.reply(403, b'{}')
            try:
                length = int(self.headers.get('Content-Length', '0'))
                if not 0 < length < 1024:
                    raise ValueError('Invalid request size')
                request=json.loads(self.rfile.read(length))
                point = points_by_id.get(request.get('point'))
                if point is None:
                    raise ValueError('Unknown surfer point')
                if self.path=='/wave':
                    from surfer_wave_orientation import analyze_wave, ignore_wave
                    result=ignore_wave(point,output) if request.get('action')=='ignore' else analyze_wave(point,request,video_folder,output,output,executable)
                    return self.reply(200,json.dumps(result).encode())
                if self.path=='/video-frame':
                    from surfer_video_frames import capture_video_frame
                    dest,result=capture_video_frame(point,request,video_folder,output,executable)
                    url='/frames/'+dest.name
                    images[url]=dest
                    return self.reply(200,json.dumps(dict(result,url=url)).encode())
                if self.path == '/fov':
                    dest=capture_frame(point,video_folder,output,executable,gap_red_ms,annotate=False)
                    result=estimator.classify_person(dest,point,output)
                    return self.reply(200,json.dumps(result).encode())
                variant=request.get('variant','telemetry')
                dest = capture_variant(point, video_folder, output, executable, gap_red_ms,variant)
                suffix='' if variant=='telemetry' else '-'+variant
                url = '/frames/' + point['point'] + suffix + '.png'
                images[url] = dest
                self.reply(200, json.dumps(dict(url=url, filename=dest.name,variant=variant,
                    timestamp=point['videoFrameTimestamp'], saved=str(dest.resolve()))).encode())
            except (ValueError, OSError, RuntimeError, ImportError, subprocess.TimeoutExpired) as error:
                self.reply(400, json.dumps(dict(error=str(error))).encode())

    server = HTTPServer(('127.0.0.1', 0), Handler)
    # Return to Python regularly: an idle socket wait can delay console
    # KeyboardInterrupt delivery on Windows.
    server.timeout = 0.25
    address = f'127.0.0.1:{server.server_port}'
    print(f'Interactive capture map: http://{address}/', flush=True)
    print('Keep this command running. Ctrl + left-click a surfer. Ctrl+C stops the server.', flush=True)
    try:
        while True:
            server.handle_request()
    except KeyboardInterrupt:
        print('\nCapture server stopped.', flush=True)
    finally:
        server.server_close()
