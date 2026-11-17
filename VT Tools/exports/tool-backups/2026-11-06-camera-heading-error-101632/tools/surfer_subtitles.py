"""Non-destructive, per-frame SRT export of the last received surfer packet."""
from bisect import bisect_right
from collections import Counter
from datetime import datetime
from hashlib import sha256
from html import escape
import json
import math
from pathlib import Path
import re
from zipfile import ZipFile, ZIP_DEFLATED


# A field is a card row (bands can occupy several rows), not raw JSON.
FIELDS = [
    ('frame_time', 'Video frame timestamp'),
    ('gps_age', 'GPS age relative to this frame'),
    ('separation', 'Surfer–drone distance / filming distance'),
    ('movement', 'Surfer movement / speed since previous packet'),
    ('direction', 'GPS movement direction (previous → current)'),
    ('ride', 'Ride detected'), ('retreat', 'Retreat state / minimum distance'),
    ('height', 'Drone height'), ('heading', 'Drone heading'),
    ('tilt', 'Gimbal tilt / active target'), ('tilt_reported', 'Gimbal report time'),
    ('aiming', 'Aiming status'), ('come_to_me', 'Come to Me status'),
    ('boundary', 'Boundary status'), ('lora', 'LoRa RSSI / SNR'),
    ('drone_displacement', 'Drone displacement since previous packet'),
    ('video_playback', 'Video playback'), ('packet', 'Packet / accepted'),
    ('received', 'GPS received timestamp / packet gap'),
    ('surfer_coordinates', 'Surfer coordinates'), ('drone_coordinates', 'Drone coordinates'),
    ('bands', 'Gimbal bands / selected band'), ('buffer', 'Band buffer'),
    ('source', 'Source log / line'),
]
FIELD_IDS = [key for key, _ in FIELDS]
PREFIXES = {
    'movement': 'Surfer since previous packet:', 'ride': 'Ride detected:',
    'retreat': 'Retreat active:', 'height': 'Drone height:', 'heading': 'Drone heading:',
    'tilt': 'Gimbal tilt:', 'tilt_reported': 'Gimbal reported:', 'aiming': 'Aiming:',
    'come_to_me': 'Come to Me:', 'boundary': 'Boundary:', 'lora': 'LoRa signal:',
    'drone_displacement': 'Drone displacement', 'packet': 'Packet:', 'received': 'Received:',
    'surfer_coordinates': 'Surfer:', 'drone_coordinates': 'Drone:',
    'bands': 'Gimbal bands:', 'buffer': 'Band buffer:',
}
TIMING = re.compile(r'^(\d{2,}:\d{2}:\d{2},\d{3}) --> (\d{2,}:\d{2}:\d{2},\d{3})(?:\s+.*)?$')
CLOCK = re.compile(r'(?m)^\s*(\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?(?:Z|[+-]\d{2}:\d{2})?)\s*$')
NORMAL, ORANGE, GREEN, PURPLE, RED = '#17324d', '#a94700', '#08752e', '#8226a0', '#c40000'


def validate_fields(fields):
    if not isinstance(fields, list) or not fields or any(not isinstance(k, str) or k not in FIELD_IDS for k in fields):
        raise ValueError('Select at least one valid subtitle field.')
    return [key for key in FIELD_IDS if key in fields]


def styled(text, color=NORMAL, bold=False):
    text = escape(str(text), quote=False)
    return f'<font color="{color}" face="Arial" size="28">' + (f'<b>{text}</b>' if bold else text) + '</font>'


def style_card_line(line, red=False):
    """Same foreground colours/emphasis as the map card; SRT has no CSS backgrounds."""
    from surfer_drone_status import drone_status_color
    if red:
        return styled(line, RED, True)
    status_color = drone_status_color(line)
    if status_color:
        return styled(line, status_color, True)
    if line.startswith('Ride detected: YES'):
        return styled(line, PURPLE, True)
    pieces = re.split(r'(\[[^\]]+\]\[[^\]]+\] m|[^|]*\(selected\)|TILTING.*)', line)
    result = []
    for piece in pieces:
        if not piece:
            continue
        color, bold = NORMAL, False
        if line.startswith(('Retreat active:', 'Gimbal tilt:')) or re.fullmatch(r'\[[^\]]+\]\[[^\]]+\] m', piece):
            color, bold = ORANGE, True
        if line.startswith('Retreat active: YES') or '(selected)' in piece or piece.startswith('TILTING'):
            color, bold = GREEN, True
        result.append(styled(piece, color, bold))
    return ''.join(result)


def valid_coordinates(p):
    return all(isinstance(p.get(k), (float, int)) and not isinstance(p[k], bool)
               and math.isfinite(p[k]) and abs(p[k]) <= limit
               for k, limit in [('latitude', 90), ('longitude', 180)])


def prepare_packets(packets, drones):
    """Copy full log data, never map-filtered samples or future GPS direction."""
    from surfer_gps_map import pair_drones, pair_distance, travel_bearing
    points = [dict(p) for p in sorted(packets, key=lambda p: (p['time_ms'], p['sourceFile'], p['sourceLine']))]
    valid = [p for p in points if valid_coordinates(p)]
    pair_drones(valid, drones)
    previous = {}
    for p in points:
        before = previous.get(p['sourceFile'])
        p.update(point=f'GPS {p.get("sequence", "?")}', dronePoint='Drone', surferNextBearingDeg=None,
                 surferTravelSincePreviousMetres=None, surferSpeedSincePreviousKmh=None, surferTravelBearingDeg=None,
                 sincePreviousReceivedMs=None, droneDisplacementMetres=None, previousDronePoint=None)
        if not valid_coordinates(p):
            p.update(droneLatitude=None, droneLongitude=None)
        if before is not None:
            elapsed = round(p['time_ms'] - before['time_ms'])
            p['sincePreviousReceivedMs'] = elapsed
            if valid_coordinates(p) and valid_coordinates(before):
                distance = pair_distance(dict(latitude=p['latitude'], longitude=p['longitude'],
                                              droneLatitude=before['latitude'], droneLongitude=before['longitude']))
                p['surferTravelSincePreviousMetres'] = distance
                if elapsed > 0:
                    p['surferSpeedSincePreviousKmh'] = distance / elapsed * 3600
                if distance > 0:
                    p['surferTravelBearingDeg'] = travel_bearing(before['latitude'], before['longitude'], p['latitude'], p['longitude'])
            if before.get('droneLatitude') is not None and p.get('droneLatitude') is not None:
                p['droneDisplacementMetres'] = pair_distance(dict(latitude=before['droneLatitude'], longitude=before['droneLongitude'],
                                                                droneLatitude=p['droneLatitude'], droneLongitude=p['droneLongitude']))
                p['previousDronePoint'] = f'GPS {before.get("sequence", "?")}'
        previous[p['sourceFile']] = p
    return points


class PacketIndex:
    def __init__(self, packets, drones, fields, gap_red_ms=1000):
        self.fields = validate_fields(fields)
        self.points = prepare_packets(packets, drones)
        self.times = [round(p['time_ms']) for p in self.points]
        sources = {}
        for t, p in zip(self.times, self.points):
            sources.setdefault(t, set()).add(p['sourceFile'])
        self.ambiguous = {t for t, names in sources.items() if len(names) > 1}
        self.gap_red_ms = gap_red_ms
        self.cache = {}

    def lookup(self, frame_ms):
        i = bisect_right(self.times, frame_ms) - 1
        if i < 0:
            return None, 'no earlier GPS packet'
        if self.times[i] in self.ambiguous:
            return None, 'conflicting logs at the latest GPS timestamp'
        return i, None

    def static_fields(self, i):
        from surfer_gps_map import position_detail_lines
        if i in self.cache:
            return self.cache[i]
        p = self.points[i]
        lines = position_detail_lines(p)
        result = {'separation': [style_card_line(lines[0])],
                  'source': [styled(f'Source log: {Path(p["sourceFile"]).name} · line {p["sourceLine"]}')]}
        bearing = p.get('surferTravelBearingDeg')
        result['direction'] = [styled(f'GPS movement direction: {bearing:.1f}° (previous → current)' if bearing is not None
                                      else 'GPS movement direction: unchanged or unavailable')]
        for key, prefix in PREFIXES.items():
            result[key] = [style_card_line(line, red=key == 'received' and (p.get('sincePreviousReceivedMs') or 0) > self.gap_red_ms)
                           for line in lines if line.startswith(prefix)] or [styled(prefix + ' unavailable')]
        if not valid_coordinates(p):
            result['separation'] = [styled(f'GPS {p.get("sequence", "?")} | separation unavailable (invalid surfer coordinates)')]
            result['surfer_coordinates'] = [styled('Surfer: invalid or unavailable GPS coordinates')]
        self.cache[i] = result
        return result

    def render(self, frame_ms, frame_clock, video_name, playback):
        i, reason = self.lookup(frame_ms)
        age = frame_ms - self.times[i] if i is not None else None
        static = self.static_fields(i) if i is not None else {}
        dynamic = {
            'frame_time': [styled('Video frame: ' + frame_clock)],
            'video_playback': [styled(f'Video playback: {video_name} {playback.replace(",", ".")}')],
            'gps_age': [styled(f'GPS age: {age / 1000:.3f} s' if age is not None else 'GPS age: unavailable (' + reason + ')',
                               RED if age is not None and age > self.gap_red_ms else NORMAL, age is not None and age > self.gap_red_ms)],
        }
        output = []
        for key in self.fields:
            output.extend(dynamic.get(key) or static.get(key) or [styled(dict(FIELDS)[key] + ': unavailable (' + reason + ')')])
        return '\n'.join(output), age, reason


def playback_ms(value):
    hours, minutes, rest = value.split(':')
    seconds, millis = rest.split(',')
    if int(minutes) >= 60 or int(seconds) >= 60:
        raise ValueError('Invalid subtitle playback timestamp.')
    return ((int(hours) * 60 + int(minutes)) * 60 + int(seconds)) * 1000 + int(millis)


def read_cues(path, tz):
    """Keep source cue numbers, full timing lines and every original subtitle line."""
    raw = path.read_bytes()
    text = raw.decode('utf-8-sig').replace('\r\n', '\n').replace('\r', '\n')
    cues = []
    previous_end = -1
    for block in re.split(r'\n[ \t]*\n', text.strip()):
        lines = block.split('\n')
        match = TIMING.fullmatch(lines[1]) if len(lines) >= 3 and lines[0].strip().isdigit() else None
        if not match:
            raise ValueError('Malformed SRT cue; no output written for this video.')
        start, end = map(playback_ms, match.groups())
        if end <= start or start < previous_end:
            raise ValueError('SRT cue times overlap or run backwards.')
        clock_match = CLOCK.search('\n'.join(lines[2:]))
        if not clock_match:
            raise ValueError('SRT lacks a per-frame recording timestamp; cannot align GPS safely.')
        clock = datetime.fromisoformat(clock_match[1])
        if clock.tzinfo is None:
            clock = clock.replace(tzinfo=tz)
        frame_ms = round(clock.timestamp() * 1000)
        # DJI clocks occasionally step backwards while playback remains monotonic.
        # Honour the clock on each cue, not an invented fixed-fps interpolation.
        cues.append((block, match[1], frame_ms, clock.isoformat(timespec='milliseconds')))
        previous_end = end
    if not cues:
        raise ValueError('SRT has no frames.')
    return cues, sha256(raw).hexdigest()


def export_folder(video_folder, output, index, tz, progress=lambda _: None, check_cancel=lambda: None):
    """One new folder per batch. Never open source MP4s/SRTs for writing."""
    from surfer_subtitle_layout import HEADER,AssWriter,card_events,compact_srt
    folder, output = Path(video_folder), Path(output)
    if not folder.is_dir():
        raise ValueError('Video folder does not exist: ' + str(folder))
    entries = [p for p in folder.iterdir() if p.is_file()]
    videos = sorted((p for p in entries if p.suffix.lower() == '.mp4'), key=lambda p: p.name.casefold())
    if not videos:
        raise ValueError('No MP4 files found in the selected video folder.')
    output.mkdir(parents=True, exist_ok=False)
    srts = {}
    for path in entries:
        if path.suffix.lower() == '.srt':
            srts.setdefault(path.stem.casefold(), []).append(path)
    report = dict(videoFolder=str(folder.resolve()), output=str(output.resolve()), fields=index.fields,
                  timing='Each source SRT frame timestamp uses the last GPS receipt at or before that time. No future GPS.',
                  styling='ASS: fixed top-left card, explicit font size, 50% transparent white panel and card highlights; opaque text. SRT: compact fallback; styling depends on player support.',
                  assTiming='ASS uses centisecond timing; cue boundaries round up by at most 9 ms. SRT preserves original millisecond timing.',
                  files=[], totals={})
    exports = []
    for n, video in enumerate(videos, 1):
        check_cancel()
        progress(f'Subtitles {n}/{len(videos)}: {video.name}…')
        item = dict(video=video.name, status='skipped')
        report['files'].append(item)
        source = srts.get(video.stem.casefold(), [])
        if len(source) != 1:
            item['reason'] = 'No matching original SRT.' if not source else 'Multiple matching SRT files.'
            continue
        dest = output / (video.stem + '.srt')
        temp = dest.with_suffix('.srt.part')
        ass_dest=dest.with_suffix('.ass')
        ass_temp=ass_dest.with_suffix('.ass.part')
        try:
            cues, digest = read_cues(source[0], tz)
            backwards = [a[2] - b[2] for a, b in zip(cues, cues[1:]) if b[2] < a[2]]
            if backwards:
                item['warning'] = (f'{len(backwards)} recording timestamp step(s) backwards '
                                   f'(largest {max(backwards)} ms). Each frame uses its own recorded timestamp.')
            check_cancel()
            counts = Counter()
            with temp.open('x', encoding='utf-8', newline='\n') as writer, ass_temp.open('x', encoding='utf-8-sig', newline='\n') as ass:
                ass.write(HEADER)
                card_writer=AssWriter(ass)
                for j, (original, playback, frame_ms, clock) in enumerate(cues):
                    if j % 200 == 0:
                        check_cancel()
                        progress(f'Subtitles {n}/{len(videos)}: {video.name} · frame {j + 1}/{len(cues)}')
                    added, age, reason = index.render(frame_ms, clock, video.name, playback)
                    writer.write(compact_srt(original,added))
                    timing=TIMING.fullmatch(original.split('\n',2)[1])
                    card_writer.write(card_events(playback_ms(timing[1]),playback_ms(timing[2]),added,original.split('\n',2)[2]))
                    counts['frames'] += 1
                    counts['unavailable' if reason else 'matched'] += 1
                    if age is not None and age > index.gap_red_ms:
                        counts['stale'] += 1
                card_writer.flush()
            check_cancel()
            temp.rename(dest)
            ass_temp.rename(ass_dest)
            exports.extend([ass_dest,dest])
            item.update(status='exported', subtitle=dest.name, cardSubtitle=ass_dest.name, sourceSubtitle=source[0].name, sourceSha256=digest,
                        **{key: counts[key] for key in ('frames', 'matched', 'unavailable', 'stale')})
        except (ValueError, UnicodeError, OSError) as error:
            item.update(status='skipped', reason=str(error))
        finally:
            if temp.exists():
                temp.unlink()
            if ass_temp.exists():ass_temp.unlink()
    check_cancel()
    count=sum(row['status']=='exported' for row in report['files'])
    report['totals'] = dict(videos=len(videos), exported=count, skipped=len(videos) - count,
                           frames=sum(row.get('frames', 0) for row in report['files']),
                           unavailable=sum(row.get('unavailable', 0) for row in report['files']),
                           stale=sum(row.get('stale', 0) for row in report['files']))
    report_path = output / 'export-report.json'
    report_path.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding='utf-8')
    exports.append(report_path)
    progress('Packing subtitle downloads…')
    archive = output / 'VT-subtitles.zip'
    with ZipFile(archive, 'x', compression=ZIP_DEFLATED) as zipped:
        for path in exports:
            check_cancel()
            zipped.write(path, path.name)
    return report, [archive, *exports]
