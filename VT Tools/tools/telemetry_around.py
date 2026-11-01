#!/usr/bin/env python3
r"""Export every JSONL record around a timestamp. Python 3.9+, no packages needed.

Example (PowerShell):
  python telemetry_around.py --folder "C:\Users\gildo\temp\311026" \
    --at "2026-10-30 14:07:49.457" --before 10 --after 5 --output "report"

Timestamps without an offset use +10:00 (Brisbane). Records are selected by
their top-level epochMs, or timestamp if epochMs is absent. Embedded GPS sender
times are preserved but are not used to select records. No events are filtered
or deduplicated in records.jsonl. XLSX/text combine matching control-cycle events.
Edit REPORT_FIELDS below to select the combined report columns.
gps.kml plots received surfer GPS packets and logged drone-position snapshots
inside the window. Points only: no interpolation, inferred altitude or gap lines.
"""
import argparse
import bisect
import csv
import json
import math
import re
import sys
import xml.etree.ElementTree as ET
from datetime import datetime, timedelta, timezone
from pathlib import Path
from telemetry_xlsx import write_xlsx


# EDIT THIS LIST: comment out a field with # to hide it from XLSX/report.txt.
# One row per source file + session + cycleId. No filling from another cycle.
# Missing readings remain blank. Order here is XLSX column order.
# Raw events remain in records.jsonl; cycle_sources.jsonl records the joins.
REPORT_FIELDS = [
    'droneMovingOnCommand',  # Displayed beside droneKmlPoint; any submitted translation in interval
    'timestamp',
    'fileIdPlayback',    # e.g. 639_4_20 = DJI_0639.MP4 at 4:20 (from SRT)
    'droneSurferDistanceM',
    'cameraTiltDeg',
    'surferGpsAgeMs',
    'distanceFromStartM',
    'retreatHappened',   # True if retreat was active at ANY point in this interval
    'waveRiding',        # VT logged riding=True at ANY point in this interval
    'waitingArea',       # GPS cluster estimate at the last cycle in this interval
    'surferSpeedKmh',    # Display [beachward][total] speed, e.g. [0][5] (km/h)
    'surferDisplacementMetres',  # Display [beachward][total] displacement, e.g. [0][5] (metres)
]

# XLSX/text defaults. CLI --granularity overrides this (0 keeps every cycle).
REPORT_GRANULARITY_SECONDS = 1.0
SKIP_UNCHANGED = True
WAITING_WINDOW_SECONDS = 5.0
WAITING_CLUSTER_DIAMETER_METRES = 1.5
WAITING_MAX_GPS_GAP_SECONDS = 1.5
WAITING_MIN_PACKETS = 6
VIDEO_FOLDER = 'E:/DCIM/100MEDIA'


def load_video_timeline(folder, local_timezone):
    """Map video wall-clock timestamps to playback using SRT, never file dates."""
    timelines, errors = [], []
    try:
        root = Path(folder)
        if not root.is_dir():
            return [], [f'Video subtitle folder unavailable: {root}']
        subtitles = sorted(p for p in root.iterdir() if p.suffix.lower() == '.srt')
    except OSError as error:
        return [], [f'Cannot read video subtitle folder {folder}: {error}']
    pattern = re.compile(r'(\d{2,}:\d{2}:\d{2}[,.]\d{3})\s*-->[^\n]*\n(?:(?!\n\s*\n)[\s\S])*?(\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}:\d{2}\.\d{3})')
    for path in subtitles:
        ident = re.match(r'DJI_(\d+)', path.stem, re.IGNORECASE)
        if not ident:
            continue
        try:
            matches = pattern.findall(path.read_text(encoding='utf-8-sig'))
            pairs = []
            for playback, clock in matches:
                h, minute, sec = map(float, playback.replace(',', '.').split(':'))
                pairs.append((parse_time(clock, local_timezone).timestamp() * 1000,
                              h * 3600 + minute * 60 + sec))
            pairs.sort()
            if pairs:
                timelines.append((str(int(ident[1])), [a for a, _ in pairs], [b for _, b in pairs]))
        except (OSError, UnicodeError, ValueError) as error:
            errors.append(f'{path}: {error}')
    if not timelines and not errors:
        errors.append(f'No usable DJI subtitle timestamps found in {folder}')
    return timelines, errors


def video_playback_label(time_ms, timelines):
    for ident, times, positions in timelines:
        if times[0] <= time_ms <= times[-1] + 40:
            i = min(bisect.bisect_left(times, time_ms), len(times) - 1)
            if i and abs(times[i - 1] - time_ms) < abs(times[i] - time_ms):
                i -= 1
            minute, second = divmod(int(positions[i]), 60)
            return f'{ident}_{minute}_{second:02}'
    return ''


def add_interval_surfer_speed(cycles, records, start, granularity_seconds):
    """Use last accepted packet in adjacent bins, before unchanged suppression.

    No interpolation across empty bins. GPS noise contributes to this estimate.
    Actual receipt-time difference is used, not an assumed exact interval width.
    Speed is projected opposite the cycle's logged seawardBearingDeg. No bearing
    means no speed or displacement estimate. Both use signed beachward movement.
    With granularity 0 there is no previous interval, so speed is unavailable.
    """
    latest = {}
    if granularity_seconds > 0:
        for row in records:
            r = row['record']
            lat, lon = r.get('latitude'), r.get('longitude')
            if r.get('event') != 'lora_packet' or r.get('accepted') is not True:
                continue
            if not (all(isinstance(v, (int, float)) and not isinstance(v, bool) and math.isfinite(v)
                        for v in (lat, lon)) and -90 <= lat <= 90 and -180 <= lon <= 180):
                continue
            slot = math.floor((row['time_ms'] - start) / (granularity_seconds * 1000))
            key = (row['source_file'], slot)
            if key not in latest or row['time_ms'] >= latest[key]['time_ms']:
                latest[key] = row
    for cycle in cycles:
        speed = None
        total_speed = None
        distance = None
        beachward_distance = None
        if granularity_seconds > 0:
            slot = math.floor((cycle['time_ms'] - start) / (granularity_seconds * 1000))
            current = latest.get((cycle['source_file'], slot))
            previous = latest.get((cycle['source_file'], slot - 1))
            if current is not None and previous is not None and current['time_ms'] > previous['time_ms']:
                a, b = previous['record'], current['record']
                la, lb = math.radians(a['latitude']), math.radians(b['latitude'])
                dl = math.radians(b['longitude'] - a['longitude'])
                h = math.sin((lb-la)/2)**2 + math.cos(la)*math.cos(lb)*math.sin(dl/2)**2
                distance = 12742000 * math.asin(math.sqrt(min(1.0, max(0.0, h))))
                total_speed = distance / ((current['time_ms'] - previous['time_ms']) / 1000) * 3.6
                movement = next((row['record'] for row in cycle.get('sources', [])
                                 if row['record'].get('event') == 'movement_cycle'), {})
                seaward = movement.get('seawardBearingDeg')
                if isinstance(seaward, (int, float)) and not isinstance(seaward, bool) and math.isfinite(seaward):
                    bearing = travel_bearing(a['latitude'], a['longitude'], b['latitude'], b['longitude'])
                    beachward_distance = distance * math.cos(math.radians(bearing - (seaward + 180)))
                    speed = beachward_distance / ((current['time_ms'] - previous['time_ms']) / 1000) * 3.6
        cycle['values']['surferSpeedKmh'] = speed
        cycle['values']['surferDisplacementMetres'] = beachward_distance
        cycle['values']['surferTotalSpeedKmh'] = total_speed
        cycle['values']['surferTotalDisplacementMetres'] = distance


def add_waiting_estimates(cycles, records):
    """Trailing observed cluster; no future fixes, no gap filling or CV claims."""
    streams = {}
    for row in records:
        r = row['record']
        lat, lon = r.get('latitude'), r.get('longitude')
        if r.get('event') == 'lora_packet' and r.get('accepted') is True and all(
                isinstance(v, (int, float)) and not isinstance(v, bool) and math.isfinite(v) for v in (lat, lon)) and -90 <= lat <= 90 and -180 <= lon <= 180:
            streams.setdefault(row['source_file'], []).append(row)
    indexed = {}
    for key, stream in streams.items():
        stream.sort(key=lambda r: (r['time_ms'], r['source_line']))
        indexed[key] = (stream, [r['time_ms'] for r in stream])
    for cycle in cycles:
        cycle['values']['waitingArea'] = None
        if cycle['values'].get('waveRiding') is True:
            cycle['values']['waitingArea'] = False
            continue
        stream, times = indexed.get(cycle['source_file'], ([], []))
        stop = bisect.bisect_right(times, cycle['time_ms'])
        if not stop or cycle['time_ms'] - times[stop - 1] > WAITING_MAX_GPS_GAP_SECONDS * 1000:
            continue
        # Include the first fix at or before the trailing five-second boundary.
        begin = max(0, bisect.bisect_right(times, times[stop - 1] - WAITING_WINDOW_SECONDS * 1000) - 1)
        window = stream[begin:stop]
        if len(window) < WAITING_MIN_PACKETS or times[stop - 1] - times[begin] < WAITING_WINDOW_SECONDS * 1000:
            continue
        if any(b['time_ms'] - a['time_ms'] > WAITING_MAX_GPS_GAP_SECONDS * 1000 for a, b in zip(window, window[1:])):
            continue
        coordinates = [(math.radians(r['record']['latitude']), math.radians(r['record']['longitude'])) for r in window]
        diameter = 0.0
        for i, (a, o) in enumerate(coordinates):
            for b, p in coordinates[i + 1:]:
                h = math.sin((b-a)/2)**2 + math.cos(a)*math.cos(b)*math.sin((p-o)/2)**2
                diameter = max(diameter, 12742000 * math.asin(math.sqrt(min(1.0, max(0.0, h)))))
        cycle['values']['waitingArea'] = diameter <= WAITING_CLUSTER_DIAMETER_METRES


def format_report_values(values, fields):
    """Display-only formatting; sampling and raw/KML data keep full precision."""
    result = {key: values.get(key) for key in fields}
    for key in ('timestamp', 'rideStartTimestamp'):
        if result.get(key):
            result[key] = datetime.fromisoformat(result[key]).strftime('%H:%M:%S.%f')[:-3]
    for key in ('droneSurferDistanceM', 'distanceFromStartM'):
        if result.get(key) is not None:
            result[key] = int(math.floor(result[key] + 0.5))
    if 'droneSurferDistanceM' in result:
        distance = result['droneSurferDistanceM']
        def setting_text(key):
            value = values.get(key)
            return 'n/a' if value is None else f'{value:g}'
        result['droneSurferDistanceM'] = '' if distance is None else (
            f'[{distance}][{setting_text("minimumDistanceMetres")}][{setting_text("filmingSeparationMetres")}]')
    def number(value):
        if value is None:
            return 'n/a'
        rounded = round(value, 1)
        return '0' if rounded == 0 else f'{rounded:g}'
    for key, total_key in (('surferSpeedKmh', 'surferTotalSpeedKmh'),
                           ('surferDisplacementMetres', 'surferTotalDisplacementMetres')):
        if key in result:
            forward, total = values.get(key), values.get(total_key)
            result[key] = '' if forward is None and total is None else f'[{number(forward)}][{number(total)}]'
    return result


def sample_cycles(cycles, start, granularity_seconds, skip_unchanged, fields):
    """Keep last cycle per interval, then omit unchanged selected readings.

    Intervals are relative to the window start, not the target timestamp.
    Each source file/session is independent. GPS age changes are real changes;
    timestamp and elapsed time alone are not. Raw/KML records are unaffected.
    """
    if not math.isfinite(granularity_seconds) or granularity_seconds < 0:
        raise ValueError('--granularity must be finite and nonnegative')
    selected = []
    retreat_by_bucket = {}
    def bucket_key(row):
        slot = math.floor((row['time_ms'] - start) / (granularity_seconds * 1000)) if granularity_seconds else row['cycleId']
        return (row['source_file'], row['session'], slot)
    for row in cycles:
        key = bucket_key(row)
        states = retreat_by_bucket.setdefault(key, [])
        states.append(row['values'])
    if granularity_seconds:
        buckets = {}
        width_ms = granularity_seconds * 1000
        for row in cycles:
            slot = math.floor((row['time_ms'] - start) / width_ms)
            key = (row['source_file'], row['session'], slot)
            if key not in buckets or row['time_ms'] >= buckets[key]['time_ms']:
                buckets[key] = row
        selected = sorted(buckets.values(), key=lambda row: (row['time_ms'], row['source_file']))
    else:
        selected = cycles
    previous, output = {}, []
    compare = [key for key in fields if key not in ('timestamp', 'fileIdPlayback')]
    if 'droneSurferDistanceM' in fields:
        compare.extend(key for key in ('minimumDistanceMetres', 'filmingSeparationMetres') if key not in compare)
    if 'surferSpeedKmh' in fields:
        compare.append('surferTotalSpeedKmh')
    if 'surferDisplacementMetres' in fields:
        compare.append('surferTotalDisplacementMetres')
    for row in selected:
        row = dict(row, values=dict(row['values']))
        states = retreat_by_bucket[bucket_key(row)]
        for field in ('retreatHappened', 'waveRiding', 'droneMovingOnCommand'):
            flags = [state.get(field) for state in states]
            row['values'][field] = True if True in flags else (False if False in flags else None)
        if row['values']['waveRiding'] is True:
            row['values']['waitingArea'] = False
        stream = (row['source_file'], row['session'])
        signature = tuple(row['values'].get(key) for key in compare)
        if skip_unchanged and stream in previous and previous[stream] == signature:
            continue
        previous[stream] = signature
        elapsed = (row['time_ms'] - start) / 1000
        interval = math.floor(elapsed / granularity_seconds) * granularity_seconds if granularity_seconds else elapsed
        result = dict(row)
        result['values'] = dict(row['values'], windowSecond=round(interval, 6))
        output.append(result)
    return output


# Display length only: arrow length does not represent speed or distance travelled.
KML_DIRECTION_ARROW_METRES = 3.0


def travel_bearing(lat1, lon1, lat2, lon2):
    """Initial bearing from previous recorded coordinate to current coordinate."""
    a, b = math.radians(lat1), math.radians(lat2)
    delta = math.radians(lon2 - lon1)
    return math.degrees(math.atan2(math.sin(delta) * math.cos(b),
                                  math.cos(a) * math.sin(b) - math.sin(a) * math.cos(b) * math.cos(delta))) % 360


def offset_point(lat, lon, bearing, metres):
    a, o, h = map(math.radians, (lat, lon, bearing))
    d = metres / 6371000.0
    b = math.asin(math.sin(a) * math.cos(d) + math.cos(a) * math.sin(d) * math.cos(h))
    q = o + math.atan2(math.sin(h) * math.sin(d) * math.cos(a), math.cos(d) - math.sin(a) * math.sin(b))
    return math.degrees(b), (math.degrees(q) + 180) % 360 - 180


def sample_gps_records(records, start, granularity_seconds, skip_unchanged):
    """Last observed position per subject/interval; never forward-fill GPS gaps."""
    buckets = {}
    for index, row in enumerate(records):
        r = row['record']
        if r.get('event') == 'lora_packet':
            kind = 'surfer' if r.get('accepted') is True else 'surfer_other'
            lat, lon = r.get('latitude'), r.get('longitude')
        elif 'aircraftLatitude' in r or 'aircraftLongitude' in r:
            kind = 'drone'
            lat, lon = r.get('aircraftLatitude'), r.get('aircraftLongitude')
        else:
            continue
        if not (all(isinstance(v, (int, float)) and not isinstance(v, bool) and math.isfinite(v)
                    for v in (lat, lon)) and -90 <= lat <= 90 and -180 <= lon <= 180):
            continue
        stream = (row['source_file'], r.get('session'), kind)
        slot = math.floor((row['time_ms'] - start) / (granularity_seconds * 1000)) if granularity_seconds else index
        key = stream + (slot,)
        if key not in buckets or row['time_ms'] >= buckets[key][0]['time_ms']:
            buckets[key] = (row, (lat, lon), stream, slot)
    previous, selected = {}, []
    for row, position, stream, slot in sorted(buckets.values(), key=lambda v: (v[0]['time_ms'], v[0]['source_file'], v[0]['source_line'])):
        state = (position, row.get('retreatHappened'))
        if skip_unchanged and previous.get(stream) == state:
            continue
        previous[stream] = state
        selected_row = dict(row)
        selected_row['gpsKind'] = stream[2]
        selected_row['windowSecond'] = round(slot * granularity_seconds if granularity_seconds else (row['time_ms'] - start) / 1000, 6)
        selected.append(selected_row)
    counts = {'drone': 0, 'surfer': 0, 'surfer_other': 0}
    for row in selected:
        kind = row['gpsKind']
        counts[kind] += 1
        row['displaySequence'] = {'drone': 'D', 'surfer': 'S', 'surfer_other': 'SX'}[kind] + str(counts[kind])
    return selected


def write_gps_kml(records, destination):
    """Plot observations, not saved targets/waypoints or retained surfer fixes.

    Time is the log/receipt time, not an invented GPS measurement timestamp.
    Repeated drone snapshots remain separate; GPS age is included when logged.
    No KML TimeStamp filters: all points are visible when the file is opened.
    """
    ns = 'http://www.opengis.net/kml/2.2'
    ET.register_namespace('', ns)
    def element(parent, tag, value=None, **attrs):
        child = ET.SubElement(parent, '{' + ns + '}' + tag, attrs)
        if value is not None:
            child.text = str(value)
        return child
    root = ET.Element('{' + ns + '}kml')
    doc = element(root, 'Document')
    element(doc, 'name', 'Recorded surfer and drone GPS')
    element(doc, 'description',
            'Logged points within the requested window. Green: accepted surfer packets; '
            'orange: other/rejected surfer packets; blue: drone-position snapshots. '
            'Red overrides either subject colour when retreat was active at any point in that reporting interval. '
            'Repeated snapshots are retained, not new independent GPS fixes. '
            'Click a point for timestamp, GPS age if logged, source and original record. '
            'Times are observation/receipt times. Ground-clamped display, not flight altitude. '
            'Short arrows show bearing from the previous logged coordinate of the same subject, '
            'not aircraft heading or an observed path. Arrow length is display-only. '
            'No arrow for unchanged positions or the first point. A gap label identifies intervals over 1.5s. '
            'No connecting tracks or saved targets used as GPS observations.')
    folders = {}
    labels = {'surfer': ('Surfer — accepted packets', 'ff00cc00'),
              'surfer_other': ('Surfer — rejected or acceptance unknown', 'ff0088ff'),
              'drone': ('Drone — recorded position snapshots', 'ffff8800')}
    for key, (label, color) in labels.items():
        style = element(doc, 'Style', id=key)
        icon = element(style, 'IconStyle')
        element(icon, 'color', color)
        element(icon, 'scale', '0.65')
        element(element(icon, 'Icon'), 'href', 'https://maps.google.com/mapfiles/kml/shapes/placemark_circle.png')
        element(element(style, 'LabelStyle'), 'scale', '0.85')
        line_style = element(style, 'LineStyle')
        element(line_style, 'color', color)
        element(line_style, 'width', '2')
        arrow_style = element(doc, 'Style', id=key + '_arrow')
        element(element(arrow_style, 'LabelStyle'), 'scale', '0')
        arrow_line = element(arrow_style, 'LineStyle')
        element(arrow_line, 'color', color)
        element(arrow_line, 'width', '2')
        folders[key] = element(doc, 'Folder')
        element(folders[key], 'name', label)
    for key, scale in (('retreat', '0.85'), ('retreat_arrow', '0')):
        style = element(doc, 'Style', id=key)
        icon = element(style, 'IconStyle')
        element(icon, 'color', 'ff0000ff')
        element(icon, 'scale', '0.65')
        element(element(icon, 'Icon'), 'href', 'https://maps.google.com/mapfiles/kml/shapes/placemark_circle.png')
        label_style = element(style, 'LabelStyle')
        element(label_style, 'scale', scale)
        element(label_style, 'color', 'ff0000ff')
        line = element(style, 'LineStyle')
        element(line, 'color', 'ff0000ff')
        element(line, 'width', '2')
    counts = {key: 0 for key in folders}
    counts['invalid_coordinate_pairs'] = 0
    counts['direction_arrows'] = 0
    previous = {}
    arrows = element(doc, 'Folder')
    element(arrows, 'name', 'Travel direction arrows (display only)')
    for row in sorted(records, key=lambda item: (item['time_ms'], item['source_file'], item['source_line'])):
        r = row['record']
        candidates = []
        if r.get('event') == 'lora_packet':
            candidates.append(('surfer' if r.get('accepted') is True else 'surfer_other',
                               r.get('latitude'), r.get('longitude')))
        if 'aircraftLatitude' in r or 'aircraftLongitude' in r:
            candidates.append(('drone', r.get('aircraftLatitude'), r.get('aircraftLongitude')))
        for kind, lat, lon in candidates:
            if not (all(isinstance(v, (int, float)) and not isinstance(v, bool) and math.isfinite(v)
                        for v in (lat, lon)) and -90 <= lat <= 90 and -180 <= lon <= 180):
                counts['invalid_coordinate_pairs'] += 1
                continue
            counts[kind] += 1
            mark = element(folders[kind], 'Placemark')
            prefix = {'drone': 'D', 'surfer': 'S', 'surfer_other': 'SX'}[kind]
            label = row.get('displaySequence', f'{prefix}{counts[kind]}')
            element(mark, 'name', label)
            style_key = 'retreat' if row.get('retreatHappened') is True else kind
            element(mark, 'styleUrl', '#' + style_key)
            # ElementTree escapes XML characters in source paths and record strings.
            element(mark, 'description',
                    f'Logged time: {row["clock"]}\nSource: {row["source_file"]}:{row["source_line"]}\n'
                    + json.dumps(r, ensure_ascii=False, indent=2))
            extra = element(mark, 'ExtendedData')
            details = dict(displaySequence=label, loggedTimestamp=row['clock'], sourceFile=row['source_file'],
                           sourceLine=row['source_line'], latitude=lat, longitude=lon)
            details['retreatHappenedInInterval'] = row.get('retreatHappened', 'unknown')
            if 'windowSecond' in row:
                details['windowSecond'] = row['windowSecond']
            # Keep flights/files and accepted versus rejected packet streams separate.
            stream = (row.get('rideNumber'), row['source_file'], r.get('session'), kind)
            prev = previous.get(stream)
            previous[stream] = (lat, lon, row['time_ms'], row['clock'])
            if prev is None:
                details['travelDirectionStatus'] = 'First coordinate in window'
            elif (lat, lon) == prev[:2]:
                details['travelDirectionStatus'] = 'Unchanged reported coordinate; no direction arrow'
            else:
                bearing = travel_bearing(prev[0], prev[1], lat, lon)
                gap = (row['time_ms'] - prev[2]) / 1000
                details.update(travelBearingDegrees=bearing, previousCoordinateTimestamp=prev[3],
                               previousLatitude=prev[0], previousLongitude=prev[1],
                               secondsSincePreviousCoordinate=gap,
                               travelDirectionStatus='Bearing between recorded coordinates; not aircraft heading')
                arrow = element(arrows, 'Placemark')
                gap_label = f' — {gap:.3f}s gap' if gap > 1.5 else ''
                element(arrow, 'name', f'{label} {row["clock"]} — {bearing:.1f} deg{gap_label}')
                element(arrow, 'styleUrl', '#' + style_key + '_arrow')
                element(arrow, 'description',
                        f'Previous coordinate: {prev[3]} ({prev[0]}, {prev[1]})\n'
                        f'Current coordinate: {row["clock"]} ({lat}, {lon})\n'
                        f'Elapsed: {gap:.3f}s. Bearing: {bearing:.2f} degrees clockwise from north.\n'
                        f'{KML_DIRECTION_ARROW_METRES}m display arrow, not measured speed/path or drone heading. '
                        'GPS variation can produce apparent movement.\n'
                        f'Source: {row["source_file"]}:{row["source_line"]}')
                tip = offset_point(lat, lon, bearing, KML_DIRECTION_ARROW_METRES)
                left = offset_point(*tip, bearing + 150, KML_DIRECTION_ARROW_METRES * 0.35)
                right = offset_point(*tip, bearing - 150, KML_DIRECTION_ARROW_METRES * 0.35)
                geometry = element(arrow, 'MultiGeometry')
                for coords in (((lat, lon), tip), (left, tip, right)):
                    line = element(geometry, 'LineString')
                    element(line, 'altitudeMode', 'clampToGround')
                    element(line, 'coordinates', ' '.join(f'{o},{a}' for a, o in coords))
                counts['direction_arrows'] += 1
            for key in ('event', 'sequence', 'accepted', 'session', 'cycleId',
                        'satellites', 'hdop', 'missingBefore', 'aircraftAgeMs',
                        'aircraftFixMs', 'aircraftHeadingDeg', 'headingDeg'):
                if key in r:
                    details[key] = r[key]
            for key, value in details.items():
                element(element(extra, 'Data', name=key), 'value', value)
            point = element(mark, 'Point')
            element(point, 'altitudeMode', 'clampToGround')
            element(point, 'coordinates', f'{lon},{lat}')
    ET.ElementTree(root).write(destination, encoding='utf-8', xml_declaration=True)
    return counts


def combine_cycles(records, start, end):
    groups = {}
    events = ('aiming_cycle', 'retreat_cycle', 'movement_cycle', 'orientation_cycle', 'yaw_command')
    for row in records:
        r = row['record']
        if r.get('event') not in events or r.get('session') is None or r.get('cycleId') is None:
            continue
        key = (row['source_file'], r['session'], r['cycleId'])
        group = groups.setdefault(key, {})
        # Repeated IDs in a file must never silently combine unrelated records.
        if r['event'] in group:
            raise ValueError(f'Duplicate {r["event"]} for session/cycle {key}; cannot safely join')
        group[r['event']] = row
    output = []
    for key, group in groups.items():
        anchor = next(group[e] for e in events if e in group)
        if not start <= anchor['time_ms'] <= end:
            continue
        if max(x['time_ms'] for x in group.values()) - min(x['time_ms'] for x in group.values()) > 1000:
            raise ValueError(f'Cycle events over one second apart for {key}; cannot safely join')
        distance_row = next((group[e] for e in events[:3] if e in group and
                             any(group[e]['record'].get(k) is not None for k in ('distanceM', 'surferDistanceM'))), anchor)
        d = distance_row['record']
        distance = d.get('distanceM') if d.get('distanceM') is not None else d.get('surferDistanceM')
        # Prefer age from the same event as the distance; fallback only within this cycle.
        age = next((d[k] for k in ('targetAgeMs', 'gpsAgeMs') if d.get(k) is not None), None)
        if age is None:
            age = next((group[e]['record'][k] for e in events[:2] if e in group
                        for k in ('targetAgeMs', 'gpsAgeMs') if group[e]['record'].get(k) is not None), None)
        o = group.get('orientation_cycle', {}).get('record', {})
        m = group.get('movement_cycle', {}).get('record', {})
        retreat = group.get('retreat_cycle', {}).get('record', {})
        retreat_states = [v for v in (retreat.get('active'), m.get('retreatActive')) if isinstance(v, bool)]
        command = group.get('yaw_command', {}).get('record', m)
        components = [command.get(k) for k in ('submittedForwardMps', 'submittedRightMps')]
        valid_components = [v for v in components if isinstance(v, (int, float)) and not isinstance(v, bool) and math.isfinite(v)]
        moving = True if any(v != 0 for v in valid_components) else (False if len(valid_components) == 2 else None)
        values = dict(timestamp=anchor['clock'], droneSurferDistanceM=distance,
                      droneMovingOnCommand=moving,
                      cameraTiltDeg=o.get('gimbalPitchDeg'), surferGpsAgeMs=age,
                      distanceFromStartM=m.get('centralDistanceM'),
                      minimumDistanceMetres=retreat.get('minimumDistanceM'),
                      filmingSeparationMetres=m.get('filmingDistanceM'),
                      waveRiding=m.get('riding') if isinstance(m.get('riding'), bool) else None,
                      retreatHappened=any(retreat_states) if retreat_states else None)
        output.append(dict(values=values, time_ms=anchor['time_ms'], source_file=key[0],
                           session=key[1], cycleId=key[2], sources=list(group.values())))
    return sorted(output, key=lambda row: (row['time_ms'], row['source_file'], row['cycleId']))


def offset_timezone(value):
    match = re.fullmatch(r'([+-])(\d{2}):(\d{2})', value)
    if not match:
        raise ValueError('UTC offset must look like +10:00 or -05:00')
    hours, minutes = int(match[2]), int(match[3])
    if hours > 23 or minutes > 59:
        raise ValueError('Invalid UTC offset')
    return timezone((1 if match[1] == '+' else -1) * timedelta(hours=hours, minutes=minutes))


def parse_time(value, default_timezone):
    if not isinstance(value, str) or not re.match(r'^\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}', value):
        raise ValueError('Use a full date and time, e.g. 2026-10-30 14:07:49.457')
    result = datetime.fromisoformat(value.replace('Z', '+00:00'))
    return result if result.tzinfo else result.replace(tzinfo=default_timezone)


def record_milliseconds(record, default_timezone):
    epoch = record.get('epochMs')
    if isinstance(epoch, (int, float)) and not isinstance(epoch, bool) and math.isfinite(epoch):
        return epoch
    return parse_time(record.get('timestamp'), default_timezone).timestamp() * 1000


def run(args):
    local_timezone = offset_timezone(args.utc_offset)
    target = parse_time(args.at, local_timezone)
    target_ms = target.timestamp() * 1000
    for value in (args.before, args.after):
        if not math.isfinite(value) or value < 0:
            raise ValueError('--before and --after must be finite, nonnegative seconds')
    folder = Path(args.folder).resolve()
    if not folder.is_dir():
        raise ValueError(f'Log folder does not exist: {folder}')
    output = Path(args.output).resolve() if args.output else Path.cwd() / ('telemetry-' + target.strftime('%Y%m%d-%H%M%S-%f'))
    paths = {name: output / name for name in ('records.jsonl', 'records.xlsx', 'report.txt', 'summary.json', 'cycle_sources.jsonl', 'gps.kml')}
    files = sorted(p for p in (folder.rglob('*.jsonl') if args.recursive else folder.glob('*.jsonl'))
                   if p.resolve() not in paths.values())
    if not files:
        raise ValueError(f'No .jsonl files found in {folder}')
    start, end = target_ms - args.before * 1000, target_ms + args.after * 1000
    found, context, problems, event_counts = [], [], [], {}
    invalid_json = missing_timestamp = unreadable = scanned = 0
    for path in ([] if hasattr(args, '_preloaded') else files):
        print(f'Reading {path.name}', file=sys.stderr)
        try:
            with path.open(encoding='utf-8-sig') as source:
                for line_number, line in enumerate(source, 1):
                    if not line.strip():
                        continue
                    scanned += 1
                    try:
                        record = json.loads(line)
                        if not isinstance(record, dict):
                            raise ValueError('JSON record is not an object')
                    except ValueError as error:
                        invalid_json += 1
                        if len(problems) < 100:
                            problems.append(f'{path}:{line_number}: {error}')
                        continue
                    try:
                        time_ms = record_milliseconds(record, local_timezone)
                        clock = datetime.fromtimestamp(time_ms / 1000, local_timezone).isoformat(timespec='milliseconds')
                    except (TypeError, ValueError, OverflowError, OSError):
                        missing_timestamp += 1
                        continue
                    if hasattr(args, '_pitch_spans'):
                        starts, ends = args._pitch_spans.get(str(path), ([], []))
                        index = bisect.bisect_right(starts, time_ms) - 1
                        if index < 0 or time_ms > ends[index]:
                            continue
                    if start - (WAITING_WINDOW_SECONDS + WAITING_MAX_GPS_GAP_SECONDS + 1) * 1000 <= time_ms <= end + 1000:
                        row = dict(source_file=str(path), source_line=line_number,
                                          time_ms=time_ms, clock=clock,
                                          seconds_from_target=round((time_ms - target_ms) / 1000, 6),
                                          record=record)
                        context.append(row)
                    if start <= time_ms <= end:
                        found.append(row)
                        event = str(record.get('event', '(no event)'))
                        event_counts[event] = event_counts.get(event, 0) + 1
        except (OSError, UnicodeError) as error:
            unreadable += 1
            problems.append(f'{path}: {error}')
    if hasattr(args, '_preloaded'):
        pad = (WAITING_WINDOW_SECONDS + WAITING_MAX_GPS_GAP_SECONDS + 1) * 1000
        for original in args._preloaded:
            if start - pad <= original['time_ms'] <= end + 1000:
                row = dict(original, seconds_from_target=round((original['time_ms'] - target_ms) / 1000, 6))
                context.append(row)
                if start <= row['time_ms'] <= end:
                    found.append(row)
                    event = str(row['record'].get('event', '(no event)'))
                    event_counts[event] = event_counts.get(event, 0) + 1
    found.sort(key=lambda row: (row['time_ms'], row['source_file'], row['source_line']))
    print(f'Combining {len(context)} retained records', file=sys.stderr, flush=True)
    combined = combine_cycles(context, start, end)
    add_waiting_estimates(combined, context)
    add_interval_surfer_speed(combined, found, start, args.granularity)
    timelines, video_errors = args._timelines if hasattr(args, '_timelines') else load_video_timeline(args.video_folder, local_timezone)
    for row in combined:
        row['values']['fileIdPlayback'] = video_playback_label(row['time_ms'], timelines)
    fields = list(dict.fromkeys(REPORT_FIELDS))
    available_fields = {'droneMovingOnCommand', 'timestamp', 'fileIdPlayback', 'droneSurferDistanceM', 'cameraTiltDeg', 'surferGpsAgeMs', 'distanceFromStartM', 'retreatHappened', 'waveRiding', 'waitingArea', 'minimumDistanceMetres', 'filmingSeparationMetres', 'surferSpeedKmh', 'surferDisplacementMetres'}
    if any(key not in available_fields for key in fields):
        raise ValueError('REPORT_FIELDS contains an unknown combined column')
    report_rows = sample_cycles(combined, start, args.granularity, not args.keep_unchanged, fields)
    report_fields = ['windowSecond', 'droneKmlPoint'] + (['droneMovingOnCommand'] if 'droneMovingOnCommand' in fields else []) + ['KML', 'surferKmlPoint'] + [key for key in fields if key != 'droneMovingOnCommand']
    output.mkdir(parents=True, exist_ok=True)
    retreat_intervals = sample_cycles(combined, start, args.granularity, False, ['retreatHappened'])
    retreat_index = {}
    for c in retreat_intervals:
        retreat_index.setdefault((c['source_file'], c['values']['windowSecond']), []).append(c)
    gps_input = []
    for original in found:
        row = dict(original)
        if args.granularity:
            slot = round(math.floor((row['time_ms'] - start) / (args.granularity * 1000)) * args.granularity, 6)
            matching = [c for c in retreat_index.get((row['source_file'], slot), [])
                        if (row['record'].get('session') is None or c['session'] == row['record']['session'])]
        else:
            matching = [c for c in combined if c['source_file'] == row['source_file']
                        and ((c['cycleId'] == row['record'].get('cycleId') and c['session'] == row['record'].get('session'))
                             if row['record'].get('cycleId') is not None else 0 <= row['time_ms'] - c['time_ms'] <= 1000)]
            matching = matching[-1:]
        states = [c['values'].get('retreatHappened') for c in matching]
        row['retreatHappened'] = True if True in states else (False if False in states else None)
        gps_input.append(row)
    gps_rows = sample_gps_records(gps_input, start, args.granularity, not args.keep_unchanged)
    point_index = {}
    for p in gps_rows:
        if args.granularity:
            slot = math.floor((p['time_ms'] - start) / (args.granularity * 1000))
            point_index.setdefault((p['source_file'], slot), []).append(p)
    for row in report_rows:
        # References identify actual plotted observations in the same reporting
        # interval, not interpolated positions at the control-cycle timestamp.
        elapsed = (row['time_ms'] - start) / 1000
        interval_start = start + row['values']['windowSecond'] * 1000
        interval_end = interval_start + args.granularity * 1000
        candidates = point_index.get((row['source_file'], math.floor(elapsed / args.granularity)), []) if args.granularity else gps_rows
        points = [p for p in candidates if p['source_file'] == row['source_file'] and
                  ((interval_start <= p['time_ms'] < interval_end) if args.granularity else
                   p['record'].get('session') == row['session'] and p['record'].get('cycleId') == row['cycleId'])]
        row['values']['droneKmlPoint'] = '; '.join(p['displaySequence'] for p in points
                                                 if p['gpsKind'] == 'drone' and p['record'].get('session') == row['session'])
        row['values']['surferKmlPoint'] = '; '.join(p['displaySequence'] for p in points
                                                  if p['gpsKind'] in ('surfer', 'surfer_other'))
    pitch_range = getattr(args, 'pitch_range', None)
    if pitch_range is not None:
        low, high = sorted(pitch_range)
        report_rows = [row for row in report_rows
                       if isinstance(row['values'].get('cameraTiltDeg'), (int, float))
                       and not isinstance(row['values']['cameraTiltDeg'], bool)
                       and low <= row['values']['cameraTiltDeg'] <= high]
        labels = {label for row in report_rows for key in ('droneKmlPoint', 'surferKmlPoint')
                  for label in row['values'][key].split('; ') if label}
        gps_rows = [p for p in gps_rows if p['displaySequence'] in labels]
        selected_slots = {(row['source_file'], row['values']['windowSecond']) for row in report_rows}
        selected_cycles = {(row['source_file'], row['session'], row['cycleId']) for row in report_rows}
        def included(row):
            if args.granularity:
                slot = round(math.floor((row['time_ms'] - start) / (args.granularity * 1000)) * args.granularity, 6)
                return (row['source_file'], slot) in selected_slots
            r = row.get('record', row)
            return (row['source_file'], r.get('session'), r.get('cycleId')) in selected_cycles
        found = [row for row in found if included(row)]
        combined = [row for row in combined if included(row)]
        event_counts = {}
        for row in found:
            event = str(row['record'].get('event', '(no event)'))
            event_counts[event] = event_counts.get(event, 0) + 1
    if getattr(args, '_collect', False):
        return dict(report_rows=report_rows, report_fields=report_fields, gps_rows=gps_rows,
                    raw=found, cycles=combined, video_errors=video_errors)
    gps_counts = write_gps_kml(gps_rows, paths['gps.kml'])
    with paths['records.jsonl'].open('w', encoding='utf-8') as f:
        for row in found:
            f.write(json.dumps(row, ensure_ascii=False) + '\n')
    write_xlsx(paths['records.xlsx'], report_fields,
               (format_report_values(row['values'], report_fields) for row in report_rows))
    with paths['cycle_sources.jsonl'].open('w', encoding='utf-8') as f:
        for row in combined:
            f.write(json.dumps(row, ensure_ascii=False) + '\n')
    summary = dict(folder=str(folder), target=target.isoformat(), utc_offset=args.utc_offset,
                   seconds_before=args.before, seconds_after=args.after, window_inclusive=True,
                   files_scanned=len(files), lines_scanned=scanned, matching_records=len(found),
                   events=event_counts, invalid_json_records=invalid_json,
                   records_without_valid_timestamp=missing_timestamp, unreadable_files=unreadable,
                   problem_examples=problems,
                   report_fields=report_fields,
                   kml_references='D/S labels refer to plotted points in the same interval, not necessarily the exact report cycle timestamp. Blank means no point plotted in that interval. SX means rejected/unknown surfer packet.',
                   combined_cycle_rows=len(combined),
                   reported_rows=len(report_rows),
                   granularity_seconds=args.granularity,
                   skip_unchanged=not args.keep_unchanged,
                   report_sampling='Last control cycle per interval from window start, per source/session; then skip unchanged selected readings. GPS age counts; timestamps do not. Raw JSONL unsampled.',
                   kml_sampling='Same intervals as XLSX; last actual logged position per subject/interval, then omit unchanged coordinates unless --keep-unchanged. No GPS forward fill. Point times remain actual logged times.',
                   missing_readings={key: sum(row['values'][key] is None for row in combined) for key in fields},
                   raw_jsonl_contains_all_fields=True,
                   kml_points=gps_counts,
                   video_folder=str(args.video_folder),
                   video_mapping_errors=video_errors,
                   video_playback_definition='DJI file number_minutes_seconds from nearest SRT frame; seconds truncated for display. Blank means no subtitle coverage. No clock offset adjustment.',
                   surfer_speed_definition='Signed beachward km/h: displacement between last accepted GPS packets in adjacent intervals projected opposite logged seawardBearingDeg, divided by actual receipt-time difference. Positive toward beach; negative seaward; sideways excluded. Blank if bearing/GPS unavailable or granularity 0. Not VT ride-detection speed. XLSX/text speed and displacement display [beachward][total] pairs, rounded to one decimal. Negative first value means seaward. Total includes sideways motion. Both blank if no adjacent GPS; n/a first value if bearing missing.',
                   waiting_area_definition=f'GPS estimate at last cycle: at least {WAITING_WINDOW_SECONDS}s, {WAITING_MIN_PACKETS} accepted packets, cluster diameter <= {WAITING_CLUSTER_DIAMETER_METRES}m, gaps and latest GPS age <= {WAITING_MAX_GPS_GAP_SECONDS}s. Blank if insufficient/gapped GPS. False if VT riding during interval. Not verified body posture.',
                   selection='Top-level epochMs; fallback timestamp. No deduplication or event filtering.')
    if pitch_range is not None:
        summary.update(mode='pitch-range', pitch_range_degrees=sorted(pitch_range),
                       selection='Inclusive cameraTiltDeg (logged gimbalPitchDeg) at the last cycle of each interval, after normal sampling. Missing pitch excluded. Overrides --all-rides and --at; before/after ignored. Raw records include the selected intervals; KML contains only referenced points.')
    paths['summary.json'].write_text(json.dumps(summary, indent=2), encoding='utf-8')
    with paths['report.txt'].open('w', encoding='utf-8') as f:
        f.write(json.dumps(summary, indent=2) + '\n\n')
        writer = csv.DictWriter(f, fieldnames=report_fields, delimiter='\t')
        writer.writeheader()
        for row in report_rows:
            writer.writerow(format_report_values(row['values'], report_fields))
    print(f"Exported {len(report_rows)} report rows from {len(combined)} control cycles and {len(found)} raw records from {len(files)} files to {output}")
    if invalid_json or missing_timestamp or unreadable:
        print('Some input records/files could not be read or timed. See summary.json.', file=sys.stderr)
    if video_errors:
        print('Video mapping: ' + '; '.join(video_errors), file=sys.stderr)
    return 2 if unreadable else 0


def run_all_rides(args):
    """Two log scans total: discover starts, then load only their padded windows."""
    tz = offset_timezone(args.utc_offset)
    for value in (args.before, args.after, args.granularity):
        if not math.isfinite(value) or value < 0:
            raise ValueError('Window lengths and granularity must be finite and nonnegative')
    folder = Path(args.folder).resolve()
    if not folder.is_dir():
        raise ValueError(f'Log folder does not exist: {folder}')
    output = Path(args.output).resolve() if args.output else Path.cwd() / 'telemetry-all-rides'
    files = sorted(p for p in (folder.rglob('*.jsonl') if args.recursive else folder.glob('*.jsonl'))
                   if not p.resolve().is_relative_to(output))
    if not files:
        raise ValueError('No input JSONL logs found outside the output folder')
    problems, rides, seen = [], [], set()
    def read(path, report_problems):
        with path.open(encoding='utf-8-sig') as f:
            for line_no, line in enumerate(f, 1):
                if not line.strip():
                    continue
                try:
                    record = json.loads(line)
                    if not isinstance(record, dict):
                        raise ValueError('Not a JSON object')
                    time_ms = record_milliseconds(record, tz)
                    clock = datetime.fromtimestamp(time_ms / 1000, tz).isoformat(timespec='milliseconds')
                except (ValueError, TypeError, OverflowError, OSError) as error:
                    if report_problems and len(problems) < 100:
                        problems.append(f'{path}:{line_no}: {error}')
                    continue
                yield dict(source_file=str(path), source_line=line_no, time_ms=time_ms,
                           clock=clock, record=record)
    for path in files:
        print(f'Finding ride starts: {path.name}', file=sys.stderr)
        for row in read(path, True):
            r = row['record']
            if r.get('event') != 'movement_cycle' or r.get('rideEvent') != 'ride_started':
                continue
            # Identical exported copies of the same start are one detection.
            key = (row['time_ms'], r.get('session'), r.get('cycleId'))
            if key not in seen:
                seen.add(key)
                rides.append(row)
    rides.sort(key=lambda row: (row['time_ms'], row['source_file']))
    if not rides:
        raise ValueError('No movement_cycle rideEvent=ride_started detections found')
    pad = (WAITING_WINDOW_SECONDS + WAITING_MAX_GPS_GAP_SECONDS + 1) * 1000
    spans = [(r['time_ms'] - args.before * 1000 - pad, r['time_ms'] + args.after * 1000 + 1000) for r in rides]
    preloaded = []
    for path in files:
        print(f'Reading ride windows: {path.name}', file=sys.stderr)
        for row in read(path, False):
            if any(a <= row['time_ms'] <= b for a, b in spans):
                preloaded.append(row)
    timelines = load_video_timeline(args.video_folder, tz)
    output.mkdir(parents=True, exist_ok=True)
    all_rows, all_gps, all_raw, all_cycles, windows = [], [], [], [], []
    headers = None
    for number, ride in enumerate(rides, 1):
        opts = argparse.Namespace(**vars(args))
        opts.at, opts.output = ride['clock'], str(output)
        opts._preloaded, opts._timelines, opts._collect = preloaded, timelines, True
        bundle = run(opts)
        headers = ['rideNumber', 'rideStartTimestamp'] + bundle['report_fields']
        remap = {p['displaySequence']: f'R{number}-{p["displaySequence"]}' for p in bundle['gps_rows']}
        for row in bundle['report_rows']:
            row['values'].update(rideNumber=number, rideStartTimestamp=ride['clock'])
            for key in ('droneKmlPoint', 'surferKmlPoint'):
                row['values'][key] = '; '.join(remap[label] for label in row['values'][key].split('; ') if label)
            all_rows.append(row)
        for point in bundle['gps_rows']:
            point.update(rideNumber=number, displaySequence=remap[point['displaySequence']])
            all_gps.append(point)
        for key, collection in [('raw', all_raw), ('cycles', all_cycles)]:
            collection.extend(dict(row, rideNumber=number, rideStartTimestamp=ride['clock']) for row in bundle[key])
        windows.append(dict(rideNumber=number, timestamp=ride['clock'], sourceFile=ride['source_file'],
                            sourceLine=ride['source_line'], reportRows=len(bundle['report_rows'])))
    write_xlsx(output / 'records.xlsx', headers,
               (format_report_values(row['values'], headers) for row in all_rows))
    for name, rows in [('records.jsonl', all_raw), ('cycle_sources.jsonl', all_cycles)]:
        with (output / name).open('w', encoding='utf-8') as f:
            for row in rows:
                f.write(json.dumps(row, ensure_ascii=False) + '\n')
    counts = write_gps_kml(all_gps, output / 'gps.kml')
    summary = dict(mode='all-rides', rideCount=len(rides), windows=windows,
                   seconds_before=args.before, seconds_after=args.after, granularity_seconds=args.granularity,
                   utc_offset=args.utc_offset, reportRows=len(all_rows), kml_points=counts,
                   inputProblems=problems, video_mapping_errors=timelines[1],
                   notes='All logged ride detections included, not verified waves. Each window resets windowSecond. Overlapping windows repeat records under their own rideNumber. KML labels Rn-Dx/Rn-Sx identify the ride; arrows never cross ride windows.')
    (output / 'summary.json').write_text(json.dumps(summary, indent=2), encoding='utf-8')
    with (output / 'report.txt').open('w', encoding='utf-8') as f:
        f.write(json.dumps(summary, indent=2) + '\n\n')
        writer = csv.DictWriter(f, fieldnames=headers, delimiter='\t')
        writer.writeheader()
        for row in all_rows:
            writer.writerow(format_report_values(row['values'], headers))
    print(f'Exported {len(rides)} ride windows into one output set: {output}')
    return 0


def run_pitch_range(args):
    """Discover the full log time span, then use the normal reporting pipeline."""
    if not all(math.isfinite(v) for v in args.pitch_range):
        raise ValueError('--pitch-range requires two finite angles')
    if not math.isfinite(args.granularity) or args.granularity < 0:
        raise ValueError('--granularity must be finite and nonnegative')
    folder = Path(args.folder).resolve()
    output = Path(args.output).resolve() if args.output else Path.cwd() / 'telemetry-pitch-range'
    if not folder.is_dir():
        raise ValueError(f'Log folder does not exist: {folder}')
    tz = offset_timezone(args.utc_offset)
    first = last = None
    matching_times = {}
    low, high = sorted(args.pitch_range)
    for path in sorted(folder.rglob('*.jsonl') if args.recursive else folder.glob('*.jsonl')):
        if path.resolve().is_relative_to(output):
            continue
        print(f'Finding log time span: {path.name}', file=sys.stderr)
        with path.open(encoding='utf-8-sig') as source:
            for line in source:
                try:
                    r = json.loads(line)
                    t = record_milliseconds(r, tz) if isinstance(r, dict) else None
                    if t is None or not math.isfinite(t):
                        continue
                    first = t if first is None else min(first, t)
                    last = t if last is None else max(last, t)
                    pitch = r.get('gimbalPitchDeg')
                    if r.get('event') == 'orientation_cycle' and isinstance(pitch, (int, float)) and not isinstance(pitch, bool) and low <= pitch <= high:
                        matching_times.setdefault(str(path), []).append(t)
                except (ValueError, TypeError, OverflowError, OSError):
                    continue
    if first is None:
        raise ValueError('No timestamped log records found')
    opts = argparse.Namespace(**vars(args))
    # Whole-second origin makes 1s/2s bins easy to compare across the log.
    first = math.floor(first / 1000) * 1000
    # Preserve neighbouring bins and trailing GPS context for the existing
    # cycle join, waiting estimate and speed calculation; filter final rows later.
    pad = (WAITING_WINDOW_SECONDS + WAITING_MAX_GPS_GAP_SECONDS + 1) * 1000
    width = max(args.granularity * 1000, 1000)
    opts._pitch_spans = {}
    for path, times in matching_times.items():
        merged = []
        for t in sorted(times):
            slot_start = first + math.floor((t - first) / width) * width
            a, b = slot_start - max(pad, width) - 1000, slot_start + width + 1000
            if merged and a <= merged[-1][1]:
                merged[-1][1] = max(merged[-1][1], b)
            else:
                merged.append([a, b])
        opts._pitch_spans[path] = ([s[0] for s in merged], [s[1] for s in merged])
    print(f'Pitch scan found {sum(map(len, matching_times.values()))} matching observations; loading their surrounding context only', file=sys.stderr, flush=True)
    opts.at = datetime.fromtimestamp(first / 1000, tz).isoformat()
    opts.before, opts.after = 0, (last - first) / 1000
    opts.output = str(output)
    return run(opts)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--folder', required=True, help='Folder containing JSONL logs')
    parser.add_argument('--at', help='Full date/time, optionally with UTC offset; ignored with --all-rides')
    parser.add_argument('--all-rides', action='store_true', help='Use every logged ride start; overrides --at; combine all windows into one output set')
    parser.add_argument('--pitch-range', '--pitch_range', nargs=2, type=float, metavar=('MIN', 'MAX'),
                        help='Highest priority: report intervals whose last camera/gimbal pitch is in this inclusive range. Scans all logs; ignores --at, --all-rides, --before and --after. Example: --pitch-range -36 -29')
    parser.add_argument('--before', type=float, default=10, help='Seconds before target (default 10)')
    parser.add_argument('--after', type=float, default=10, help='Seconds after target (default 10)')
    parser.add_argument('--utc-offset', default='+10:00', help='Timezone for offsetless timestamps and report display')
    parser.add_argument('--recursive', action='store_true', help='Include subfolders')
    parser.add_argument('--video-folder', default=VIDEO_FOLDER, help='DJI SRT folder for fileIdPlayback (default E:/DCIM/100MEDIA)')
    parser.add_argument('--granularity', type=float, default=REPORT_GRANULARITY_SECONDS,
                        help='XLSX/text interval in seconds, anchored to window start; 0 keeps every cycle (default 1)')
    parser.add_argument('--keep-unchanged', action='store_true', default=not SKIP_UNCHANGED,
                        help='Keep interval rows even when selected readings are unchanged')
    parser.add_argument('--output', help='Output folder (existing report files here will be replaced)')
    args = parser.parse_args()
    if args.pitch_range is None and not args.all_rides and not args.at:
        parser.error('Provide --at, --all-rides or --pitch-range')
    try:
        return run_pitch_range(args) if args.pitch_range is not None else (run_all_rides(args) if args.all_rides else run(args))
    except (ValueError, OSError) as error:
        parser.exit(2, f'Error: {error}\n')


if __name__ == '__main__':
    sys.exit(main())
