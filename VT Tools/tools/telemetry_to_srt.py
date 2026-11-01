#!/usr/bin/env python3
"""Add telemetry calculated directly from JSONL logs to DJI SRT labels.

Requires telemetry_around.py and telemetry_xlsx.py beside this script.
No spreadsheet or MP4 is read. Original DJI labels, frame clocks and cue timings survive.
Each original frame clock selects the latest completed telemetry cycle at or
before that clock. No future readings or interval-wide flags are used.
"""
import argparse
import html
import bisect
import json
import math
import re
import sys
from pathlib import Path

from telemetry_around import (
    offset_timezone, parse_time, record_milliseconds, combine_cycles,
    add_waiting_estimates, sample_gps_records, format_report_values, travel_bearing,
)

FIELDS = ['windowSecond', 'droneKmlPoint', 'droneMovingOnCommand',
          'surferKmlPoint', 'timestamp', 'fileIdPlayback',
          'droneSurferDistanceM', 'cameraTiltDeg', 'surferGpsAgeMs',
          'distanceFromStartM', 'retreatHappened', 'waveRiding', 'waitingArea',
          'surferSpeedKmh', 'surferDisplacementMetres']
EVENTS = {'aiming_cycle', 'movement_cycle', 'retreat_cycle',
          'orientation_cycle', 'yaw_command', 'lora_packet'}
CLOCK = re.compile(r'\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}:\d{2}\.\d{3}(?:[+-]\d{2}:\d{2}|Z)?')
TIMING = re.compile(r'^\d{2,}:\d{2}:\d{2}[,.]\d{3}\s*-->\s*\d{2,}:\d{2}:\d{2}[,.]\d{3}')
BEGIN = '[VT TELEMETRY BEGIN]'
END = '[VT TELEMETRY END]'


def read_cues(path, tz):
    cues = []
    text = path.read_text(encoding='utf-8-sig').replace('\r\n', '\n')
    for block in re.split(r'\n\s*\n', text.strip()):
        lines = block.splitlines()
        if len(lines) < 3 or not lines[0].strip().isdigit() or not TIMING.match(lines[1]):
            raise ValueError(f'{path.name}: malformed SRT cue; nothing overwritten')
        original = '\n'.join(lines[2:])
        # Remove only our leading alignment override before regenerating it.
        if original.startswith(r'{\an1}'):
            original = original[len(r'{\an1}'):]
        if BEGIN in original or END in original:
            if original.count(BEGIN) != 1 or original.count(END) != 1 or original.index(END) < original.index(BEGIN):
                raise ValueError(f'{path.name}: malformed telemetry block; nothing overwritten')
            original = re.sub(r'(?:<font size="\d+">\n)?' + re.escape(BEGIN) + r'[\s\S]*?' + re.escape(END) + r'(?:\n</font>)?', '', original).rstrip('\n')
        match = CLOCK.search(original)
        if not match:
            raise ValueError(f'{path.name} cue {lines[0]}: missing frame wall-clock timestamp')
        clock = match.group()
        cues.append((lines[0], lines[1], clock, parse_time(clock, tz).timestamp() * 1000, original))
    if not cues:
        raise ValueError(f'{path.name}: no cues')
    return cues


def build_index(paths, tz, start, end, granularity):
    """One log file in memory at a time; retain only relevant telemetry events."""
    index = []
    for file_id, path in enumerate(paths, 1):
        print(f'Reading {path.name}', flush=True)
        records = []
        with path.open(encoding='utf-8-sig') as source:
            for line_no, line in enumerate(source, 1):
                if not line.strip():
                    continue
                try:
                    r = json.loads(line)
                except ValueError as error:
                    raise ValueError(f'{path}:{line_no}: invalid JSON') from error
                if not isinstance(r, dict) or r.get('event') not in EVENTS:
                    continue
                t = record_milliseconds(r, tz)
                if not start - max(8000, granularity * 1000) <= t <= end + 1000:
                    continue
                records.append(dict(source_file=str(path), source_line=line_no,
                                    time_ms=t, clock=parse_clock(t, tz), record=r))
        records.sort(key=lambda r: (r['time_ms'], r['source_line']))
        cycles = combine_cycles(records, start - 1000, end)
        add_waiting_estimates(cycles, records)
        points = sample_gps_records(records, start, 0, False)
        labels = {p['source_line']: f'L{file_id}-{p["displaySequence"]}' for p in points}
        packets = [p for p in points if p['gpsKind'] == 'surfer']
        packet_times = [p['time_ms'] for p in packets]
        sessions = {}
        for row in cycles:
            v = row['values']
            ready = max(s['time_ms'] for s in row['sources'])
            v['_cycleTimeMs'] = row['time_ms']
            distance_source = next((s for event in ('aiming_cycle', 'retreat_cycle', 'movement_cycle')
                                    for s in row['sources'] if s['record'].get('event') == event
                                    and any(s['record'].get(k) is not None for k in ('distanceM', 'surferDistanceM'))), None)
            v['_ageTimeMs'] = distance_source['time_ms'] if distance_source else row['time_ms']
            v['droneKmlPoint'] = labels.get(distance_source['source_line'], '') if distance_source else ''
            seq = distance_source['record'].get('targetSequence') if distance_source else None
            stop = bisect.bisect_right(packet_times, row['time_ms'])
            v['surferKmlPoint'] = ''
            # Match the exact packet used by the selected distance, never a later fix.
            if seq is not None:
                for p in reversed(packets[max(0, stop - 100):stop]):
                    if p['record'].get('sequence') == seq:
                        v['surferKmlPoint'] = labels[p['source_line']]
                        break
            for key in ('surferSpeedKmh', 'surferDisplacementMetres', 'surferTotalSpeedKmh', 'surferTotalDisplacementMetres'):
                v[key] = None
            # Trailing motion window, ending no later than this cycle.
            before = bisect.bisect_right(packet_times, row['time_ms'] - granularity * 1000) - 1
            if stop and before >= 0 and before < stop - 1:
                a, b = packets[before], packets[stop - 1]
                times = packet_times[before:stop]
                if row['time_ms'] - b['time_ms'] <= 1500 and all(y - x <= 1500 for x, y in zip(times, times[1:])):
                    la, lb = math.radians(a['record']['latitude']), math.radians(b['record']['latitude'])
                    dl = math.radians(b['record']['longitude'] - a['record']['longitude'])
                    h = math.sin((lb-la)/2)**2 + math.cos(la)*math.cos(lb)*math.sin(dl/2)**2
                    distance = 12742000 * math.asin(math.sqrt(min(1, max(0, h))))
                    elapsed = (b['time_ms'] - a['time_ms']) / 1000
                    v['surferTotalDisplacementMetres'] = distance
                    v['surferTotalSpeedKmh'] = distance / elapsed * 3.6
                    movement = next((s['record'] for s in row['sources'] if s['record'].get('event') == 'movement_cycle'), {})
                    sea = movement.get('seawardBearingDeg')
                    if isinstance(sea, (int, float)) and math.isfinite(sea):
                        bearing = travel_bearing(a['record']['latitude'], a['record']['longitude'], b['record']['latitude'], b['record']['longitude'])
                        forward = distance * math.cos(math.radians(bearing - sea - 180))
                        v['surferDisplacementMetres'] = forward
                        v['surferSpeedKmh'] = forward / elapsed * 3.6
            sessions.setdefault(row['session'], []).append((ready, v))
        for entries in sessions.values():
            entries.sort(key=lambda x: x[0])
            index.append(([e[0] for e in entries], [e[1] for e in entries]))
        print(f'  {len(cycles)} telemetry cycles indexed by availability time', flush=True)
    return index


def parse_clock(t, tz):
    from datetime import datetime
    return datetime.fromtimestamp(t / 1000, tz).isoformat(timespec='milliseconds')


def render(cues, video_id, index, start, granularity, font_size=14, gps_age_red_seconds=1.0):
    blocks = []
    matched = 0
    for number, timing, clock, t, original in cues:
        slot = round(math.floor((t - start) / (granularity * 1000)) * granularity, 6)
        candidates = []
        for times, values in index:
            i = bisect.bisect_right(times, t) - 1
            if i >= 0 and 0 <= t - values[i]['_cycleTimeMs'] <= 1000:
                candidates.append(values[i])
        # Full frame clock is deliberately retained for subsequent tool runs.
        body = [f'<font size="{font_size}">', BEGIN]
        if len(candidates) == 1:
            values = dict(candidates[0])
            values['windowSecond'] = slot
            # Age advances from the logged age to the master frame clock.
            if values.get('surferGpsAgeMs') is not None:
                values['surferGpsAgeMs'] = round(values['surferGpsAgeMs'] + t - values['_ageTimeMs'])
            h, m, s = map(float, timing.split('-->')[0].strip().replace(',', '.').split(':'))
            seconds = int(h * 3600 + m * 60 + s)
            values['fileIdPlayback'] = f'{video_id}_{seconds // 60}_{seconds % 60:02}'
            display = format_report_values(values, FIELDS)
            for key in FIELDS:
                value = display.get(key)
                if isinstance(value, float):
                    value = f'{value:.1f}'.rstrip('0').rstrip('.')
                value = 'n/a' if value is None or value == '' else str(value)
                line = f'{key}: {html.escape(value)}'
                if key == 'surferGpsAgeMs' and isinstance(values.get(key), (int, float)) and values[key] > gps_age_red_seconds * 1000:
                    line = f'<font color="#FF0000">{line}</font>'
                body.append(line)
            matched += 1
        else:
            body.append('Ambiguous telemetry: overlapping log sessions' if candidates else 'No telemetry for this frame')
        body.append(END)
        body.append('</font>')
        blocks.append('\n'.join([number, timing, r'{\an1}' + original] + body))
    return '\n\n'.join(blocks) + '\n\n', matched


def run(args):
    if not math.isfinite(args.granularity) or args.granularity <= 0:
        raise ValueError('--granularity must be positive and finite')
    tz = offset_timezone(args.utc_offset)
    folder = Path(args.folder).resolve()
    srt_folder = Path(args.srt_folder).resolve()
    paths = sorted(folder.rglob('*.jsonl') if args.recursive else folder.glob('*.jsonl'))
    subtitles = sorted(p for p in srt_folder.glob('*') if p.suffix.lower() == '.srt' and re.match(r'DJI_\d+', p.stem, re.I))
    if not paths or not subtitles:
        raise ValueError('No JSONL logs or DJI SRT files found')
    if input('Have you backed up the SRT files? Type yes to overwrite: ').strip().lower() != 'yes':
        print('Cancelled. No files changed.')
        return 0
    # Validate all subtitles and calculate replacements before touching originals.
    cue_sets = [(p, read_cues(p, tz)) for p in subtitles]
    start = math.floor(min(c[3] for _, cs in cue_sets for c in cs) / 1000) * 1000
    end = max(c[3] for _, cs in cue_sets for c in cs)
    index = build_index(paths, tz, start, end, args.granularity)
    if not index:
        raise ValueError('No telemetry overlaps the subtitles; nothing overwritten')
    replacements = []
    for path, cues in cue_sets:
        video_id = str(int(re.match(r'DJI_(\d+)', path.stem, re.I)[1]))
        text, matched = render(cues, video_id, index, start, args.granularity, args.font_size, args.gps_age_red_seconds)
        if matched:
            replacements.append((path, text, matched, len(cues)))
        else:
            print(f'Skipped {path.name}: no unambiguous matching telemetry')
    for path, text, matched, count in replacements:
        # Stage in the same directory, then replace atomically per file.
        temp = path.with_name(path.name + '.telemetry.tmp')
        if temp.exists():
            raise ValueError(f'Staging file already exists: {temp}')
        try:
            with temp.open('x', encoding='utf-8', newline='\r\n') as f:
                f.write(text)
            temp.replace(path)
        finally:
            if temp.exists():
                temp.unlink()
        print(f'Updated {path.name}: {matched}/{count} cues with telemetry', flush=True)
    print(f'Done: {len(replacements)} SRT files overwritten. Point IDs are local to this run, prefixed by log number; they are not IDs from an earlier XLSX/KML. windowSecond starts at the earliest SRT frame, rounded down to a second.')
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--folder', required=True, help='JSONL log folder')
    parser.add_argument('--srt-folder', '--video-folder', default='E:/DCIM/100MEDIA')
    parser.add_argument('--granularity', type=float, default=1,
                        help='Trailing motion lookback and windowSecond spacing; frame matching always uses the exact SRT clock (default 1s)')
    parser.add_argument('--font-size', type=int, default=14, help='Telemetry font size (default 14); player support varies')
    parser.add_argument('--gps-age-red-seconds', type=float, default=1.0,
                        help='Colour surferGpsAgeMs red when age exceeds this many seconds (default 1)')
    parser.add_argument('--utc-offset', default='+10:00')
    parser.add_argument('--recursive', action='store_true')
    args = parser.parse_args()
    if args.font_size <= 0:
        parser.error('--font-size must be positive')
    if not math.isfinite(args.gps_age_red_seconds) or args.gps_age_red_seconds < 0:
        parser.error('--gps-age-red-seconds must be finite and nonnegative')
    try:
        return run(args)
    except (ValueError, OSError, EOFError) as error:
        parser.exit(2, f'Error: {error}\n')


if __name__ == '__main__':
    sys.exit(main())
