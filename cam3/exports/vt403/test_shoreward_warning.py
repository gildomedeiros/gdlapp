"""Offline candidate replay; no flight commands. Python standard library only.

Thresholds default to Gemini's proposal, not validated settings. Uses VT movement
cycles marked newRidePacket and the latest aiming snapshot; timestamps are when
the controller consumed each packet, not GPS measurement timestamps.
"""
import argparse
import csv
import hashlib
import json
import math
from pathlib import Path
import zipfile


def sources(folder):
    seen = set()
    for path in sorted(Path(folder).rglob('*')):
        if path.suffix.lower() == '.jsonl':
            streams = [(str(path), path.open('rb'))]
        elif path.suffix.lower() == '.zip':
            with zipfile.ZipFile(path) as archive:
                for name in sorted(archive.namelist()):
                    if name.lower().endswith('.jsonl'):
                        with archive.open(name) as stream:
                            digest = hashlib.file_digest(stream, 'sha256').hexdigest()
                        if digest in seen:
                            continue
                        seen.add(digest)
                        with archive.open(name) as stream:
                            yield str(path) + '::' + name, stream
            continue
        else:
            continue
        for name, stream in streams:
            with stream:
                digest = hashlib.file_digest(stream, 'sha256').hexdigest()
                if digest in seen:
                    continue
                seen.add(digest)
                stream.seek(0)
                yield name, stream


def displacement(a, b, bearing):
    north = math.radians(b[0] - a[0]) * 6371000
    east = math.radians(b[1] - a[1]) * 6371000 * math.cos(math.radians((a[0] + b[0]) / 2))
    sea = north * math.cos(bearing) + east * math.sin(bearing)
    return math.hypot(north, east), -sea


def replay(name, stream, args):
    angle = math.radians(args.seaward_bearing)
    aim = previous = episode = None
    warnings, rides, retreats = [], [], []
    bad = gaps = rejected = packets = 0

    def finish(reason):
        nonlocal episode
        if episode is not None:
            episode['end_reason'] = reason
            warnings.append(episode)
            episode = None

    for line in stream:
        try:
            record = json.loads(line)
        except (ValueError, UnicodeDecodeError):
            bad += 1
            continue
        event = record.get('event')
        if event == 'retreat_started':
            retreats.append((record['epochMs'], record['timestamp']))
        if event == 'aiming_cycle':
            aim = record
        if event != 'movement_cycle':
            continue
        now = record['epochMs']
        if record.get('rideEvent') == 'ride_started':
            rides.append((now, record['timestamp']))
        if record.get('phase') == 'OFF' or not aim or aim.get('state') != 'AIMING':
            finish('inactive')
            previous = None
            continue
        if not record.get('newRidePacket'):
            continue
        lat, lon = aim.get('targetLatitude'), aim.get('targetLongitude')
        if lat is None or lon is None:
            continue
        packets += 1
        position = (float(lat), float(lon))
        if previous is None:
            previous = (now, position)
            continue
        old_time, old_position = previous
        previous = (now, position)
        dt = (now - old_time) / 1000
        if dt <= 0 or dt > args.max_gap:
            gaps += 1
            finish('packet_gap')
            continue
        distance, shoreward = displacement(old_position, position, angle)
        speed, shore_speed = distance / dt, shoreward / dt
        if speed * 3.6 > args.max_speed:
            rejected += 1
            finish('speed_outlier')
            continue
        qualifies = speed * 3.6 >= args.min_speed and shore_speed * 3.6 >= args.min_shore_speed
        if not qualifies:
            finish('below_threshold')
            continue
        if episode is None:
            episode = dict(log=name, warning_time=record['timestamp'], warning_epoch=now,
                           confirmation_time='', distance_m=record.get('surferDistanceM'),
                           speed_kmh=round(speed * 3.6, 3), shoreward_kmh=round(shore_speed * 3.6, 3),
                           phase=record.get('phase'), retreat_already_active=record.get('retreatActive'),
                           qualifying_samples=0)
        episode['qualifying_samples'] += 1
        if not episode['confirmation_time'] and now - episode['warning_epoch'] >= args.confirm_seconds * 1000:
            episode['confirmation_time'] = record['timestamp']
    finish('end_of_log')
    for warning in warnings:
        start = warning['warning_epoch']
        matched = next(((t, stamp) for t, stamp in rides if start <= t <= start + args.match_seconds * 1000), None)
        warning['ride_start_within_window'] = matched[1] if matched else ''
        # Compare only an actual retreat in this candidate's forward window.
        retreat = next(((t, stamp) for t, stamp in retreats if start <= t <= start + args.match_seconds * 1000), None)
        warning['next_actual_retreat'] = retreat[1] if retreat else ''
        warning['potential_lead_s'] = round((retreat[0] - start) / 1000, 3) if retreat else ''
    covered = sum(any(t - args.match_seconds * 1000 <= w['warning_epoch'] <= t for w in warnings) for t, _ in rides)
    print(f'{name}: packets={packets}, candidates={len(warnings)}, confirmed={sum(bool(w["confirmation_time"]) for w in warnings)}, '
          f'unmatched={sum(not w["ride_start_within_window"] for w in warnings)}, rides covered={covered}/{len(rides)}, '
          f'gap resets={gaps}, speed rejects={rejected}, malformed lines={bad}')
    return warnings


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--logs', default=r'C:\Users\gildo\temp\vt 4.03\logs')
    parser.add_argument('--output', default='shoreward_warning_candidates.csv')
    parser.add_argument('--seaward-bearing', type=float, default=88.4188024134645)
    parser.add_argument('--min-speed', type=float, default=12, help='km/h; exploratory Gemini value')
    parser.add_argument('--min-shore-speed', type=float, default=10, help='km/h; exploratory Gemini value')
    parser.add_argument('--confirm-seconds', type=float, default=5)
    parser.add_argument('--max-gap', type=float, default=2, help='seconds; explicit replay assumption')
    parser.add_argument('--max-speed', type=float, default=45, help='km/h; outlier cutoff assumption')
    parser.add_argument('--match-seconds', type=float, default=20)
    args = parser.parse_args()
    if not Path(args.logs).is_dir():
        parser.error('Logs folder does not exist: ' + args.logs)
    if min(args.max_gap, args.max_speed, args.match_seconds) <= 0 or min(args.min_speed, args.min_shore_speed, args.confirm_seconds) < 0:
        parser.error('Invalid negative or zero parameter')
    print('Offline warning replay. All thresholds are experimental. Unmatched is not video-validated false detection.')
    print(vars(args))
    rows = []
    for name, stream in sources(args.logs):
        rows.extend(replay(name, stream, args))
    if rows:
        with open(args.output, 'w', newline='', encoding='utf-8-sig') as stream:
            writer = csv.DictWriter(stream, fieldnames=list(rows[0]))
            writer.writeheader()
            writer.writerows(rows)
        print(f'Saved {len(rows)} candidates to {Path(args.output).resolve()}')
    else:
        print('No candidates; no CSV written.')


if __name__ == '__main__':
    main()
