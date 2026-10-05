"""Print logged VT4.0.3 wave ride starts and timestamps to the console."""
import argparse
import json
import struct
from datetime import datetime, timedelta, timezone
from pathlib import Path
import hashlib
import zipfile
import math

# EDIT THESE VALUES. None = use the log setting, or the VT default if not logged.
# Units are explicit. These affect recalculated detection only, not logged retreats.
PARAMETERS = {
    'ride_start_kmh': 10,             # log: rideStartKmh; default 18
    'ride_duration_ms': None,           # log: rideDurationMs; default 20000
    'speed_reference_min_ms': None,     # log: fastWindowMs; default 1000
    'speed_reference_max_ms': None,     # VT constant: 2000
    'jump_limit_kmh': None,             # log: jumpLimitKmh; default 40
    'packet_gap_reset_ms': None,        # VT constant: 3000
    'fresh_packet_max_age_ms': None,    # VT constant: 3000
    'jump_history_ms': None,            # VT constant: 3000
    'speed_history_ms': None,           # VT constant: 7000
    'reset_on_backward_receipt': None,  # VT default: True
    'reset_on_nonincreasing_sender': None,  # VT default: True
}

LOG_PARAMETERS = {
    'ride_start_kmh': ('rideStartKmh', 18),
    'ride_duration_ms': ('rideDurationMs', 20000),
    'speed_reference_min_ms': ('fastWindowMs', 1000),
    'speed_reference_max_ms': (None, 2000),
    'jump_limit_kmh': ('jumpLimitKmh', 40),
    'packet_gap_reset_ms': (None, 3000),
    'fresh_packet_max_age_ms': (None, 3000),
    'jump_history_ms': (None, 3000),
    'speed_history_ms': (None, 7000),
    'reset_on_backward_receipt': (None, True),
    'reset_on_nonincreasing_sender': (None, True),
}


def settings(record):
    values = {key: PARAMETERS[key] if PARAMETERS[key] is not None else record.get(field, default)
              for key, (field, default) in LOG_PARAMETERS.items()}
    for key, value in values.items():
        if value is None or (not isinstance(value, bool) and value <= 0):
            raise ValueError(f'Invalid parameter {key}: {value}')
    if values['speed_reference_max_ms'] < values['speed_reference_min_ms']:
        raise ValueError('speed_reference_max_ms must be >= speed_reference_min_ms')
    return values


class RideDetector:
    """Port of VT4.0.3 RideDetector.java; fixes=(lat, lon, receiptMs, senderMs)."""
    def __init__(self):
        self.history, self.jumps = [], []
        self.last = None
        self.expires = self.rearm = -1
        self.speed = None

    def clear(self):
        self.history, self.jumps = [], []
        self.last = None
        self.speed = None

    def advance(self, now):
        if self.expires >= 0 and now >= self.expires:
            self.expires = -1
            self.rearm = now
            self.clear()
            return True
        return False

    @staticmethod
    def reference(points, fix, minimum):
        result = None
        for point in points:
            if fix[3] - point[3] >= minimum:
                result = point
            else:
                break
        return result

    @staticmethod
    def velocity(a, b):
        if b[3] <= a[3]:
            return float('nan')
        lat, lon, lat2, lon2 = map(math.radians, (a[0], a[1], b[0], b[1]))
        h = math.sin((lat2-lat)/2)**2 + math.cos(lat)*math.cos(lat2)*math.sin((lon2-lon)/2)**2
        metres = 6371000 * 2 * math.asin(math.sqrt(max(0, min(1, h))))
        return metres * 3600 / (b[3]-a[3])

    def observe(self, fix, now, config, seaward_bearing):
        if self.expires >= 0 or fix[2] <= self.rearm:
            return False
        if self.last and fix[2] == self.last[2]:
            return False
        if self.last and ((config['reset_on_backward_receipt'] and fix[2] < self.last[2])
                         or fix[2]-self.last[2] > config['packet_gap_reset_ms']
                         or (config['reset_on_nonincreasing_sender'] and fix[3] <= self.last[3])):
            self.clear()
        self.last = fix
        self.jumps = [p for p in self.jumps if fix[3]-p[3] <= config['jump_history_ms']]
        base = self.reference(self.jumps, fix, config['speed_reference_min_ms'])
        jump = self.velocity(base, fix) if base else None
        self.jumps.append(fix)
        if jump is not None and jump > config['jump_limit_kmh']:
            self.history = []
            self.speed = None
            return False
        self.history.append(fix)
        while len(self.history) > 1 and fix[3]-self.history[0][3] > config['speed_history_ms']:
            self.history.pop(0)
        base = self.reference(self.history, fix, config['speed_reference_min_ms'])
        span = fix[3]-base[3] if base else 0
        self.speed = self.velocity(base, fix) if config['speed_reference_min_ms'] <= span <= config['speed_reference_max_ms'] else None
        if self.speed is not None:
            # Project GPS travel onto the shoreward normal. The configured
            # seaward bearing points the opposite way; seaward speed becomes 0.
            lat1, lon1, lat2, lon2 = map(math.radians, (base[0], base[1], fix[0], fix[1]))
            travel_bearing = math.atan2(math.sin(lon2-lon1)*math.cos(lat2),
                                       math.cos(lat1)*math.sin(lat2)-math.sin(lat1)*math.cos(lat2)*math.cos(lon2-lon1))
            self.speed = max(0.0, -self.speed * math.cos(travel_bearing-math.radians(seaward_bearing)))
        if self.speed is not None and self.speed >= config['ride_start_kmh']:
            self.expires = now + config['ride_duration_ms']
            return True
        return False

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





def video_timing(path):
    """Read recording start and duration from the MP4 movie header."""
    with path.open('rb') as stream:
        def boxes(end):
            while stream.tell() + 8 <= end:
                start = stream.tell()
                size, kind = struct.unpack('>I4s', stream.read(8))
                header = 8
                if size == 1:
                    size = struct.unpack('>Q', stream.read(8))[0]
                    header = 16
                elif size == 0:
                    size = end - start
                if size < header or start + size > end:
                    raise ValueError('Invalid MP4 box size')
                if kind == b'moov':
                    result = boxes(start + size)
                    if result:
                        return result
                elif kind == b'mvhd':
                    version = stream.read(4)[0]
                    if version == 0:
                        created, _, scale, duration = struct.unpack('>IIII', stream.read(16))
                    elif version == 1:
                        created, _, scale, duration = struct.unpack('>QQIQ', stream.read(28))
                    else:
                        raise ValueError('Unsupported movie header version')
                    if not created or not scale:
                        raise ValueError('Missing recording time or timescale')
                    epoch = datetime(1904, 1, 1, tzinfo=timezone.utc)
                    return (epoch + timedelta(seconds=created)).timestamp(), duration / scale
                stream.seek(start + size)
        result = boxes(path.stat().st_size)
        if not result:
            raise ValueError('No MP4 movie header')
        return result


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--logs', default=r'C:\Users\gildo\temp\vt 4.03\logs')
    p.add_argument('--videos', default=r'E:\DCIM\100MEDIA', help='Folder containing MP4 files')
    a = p.parse_args()
    if not Path(a.logs).is_dir():
        p.error('Missing logs folder: ' + a.logs)
    if not Path(a.videos).is_dir():
        p.error('Missing videos folder: ' + a.videos)
    videos = []
    for path in sorted(Path(a.videos).rglob('*')):
        if path.suffix.lower() != '.mp4':
            continue
        try:
            start, duration = video_timing(path)
            videos.append((path, start, duration))
        except (OSError, ValueError, struct.error, IndexError) as error:
            print(f'Skipping {path.name}: {error}')
    def media(timestamp):
        instant = datetime.fromisoformat(timestamp.replace('Z', '+00:00'))
        if instant.tzinfo is None:
            raise ValueError('Timestamp must include its timezone')
        epoch = instant.timestamp()
        matches = [(path, epoch - start) for path, start, duration in videos
                   if start <= epoch < start + duration]
        if not matches:
            return 'UNMATCHED | N/A'
        return ('AMBIGUOUS: ' if len(matches) > 1 else '') + '; '.join(
            f'{path.name} | {int(round(offset * 1000)) // 60000:02d}:'
            f'{(int(round(offset * 1000)) % 60000) / 1000:06.3f}'
            for path, offset in matches)

    table = []
    total_mismatches = 0
    effective_settings = []
    ride_count = 0
    for name, stream in sources(a.logs):
        ride_rows, retreat_rows = [], []
        detector = RideDetector()
        aim = None
        session = None
        mismatches = 0
        last_settings = None
        for line in stream:
            r = json.loads(line)
            if r.get('event') == 'aiming_cycle':
                aim = r
            if r.get('event') == 'retreat_started':
                retreat_rows.append(r)
            if r.get('event') != 'movement_cycle' or aim is None:
                continue
            if r.get('session') != session or r.get('phase') == 'OFF':
                detector = RideDetector()
                session = r.get('session')
            now = aim['cycleAtMs']
            config = settings(r)
            if config != last_settings and r.get('phase') != 'OFF':
                if config not in effective_settings:
                    effective_settings.append(config)
                last_settings = config
            expired = detector.advance(now)
            detected = False
            if aim.get('state') == 'AIMING' and not r.get('gpsMovementPaused'):
                lat, lon = aim.get('targetLatitude'), aim.get('targetLongitude')
                receipt, sender = aim.get('targetFixMs'), aim.get('targetSenderMs')
                bearing = r.get('seawardBearingDeg')
                if bearing is None and r.get('phase') != 'OFF':
                    raise ValueError('Missing logged seawardBearingDeg: cannot calculate shoreward speed')
                if not expired and None not in (lat, lon, receipt, sender, bearing) and 0 <= now-receipt <= config['fresh_packet_max_age_ms']:
                    detected = detector.observe((lat, lon, receipt, sender), now, config, bearing)
            elif detector.expires < 0:
                detector.clear()
            # Logged starts are used ONLY to verify the recalculated result.
            if detected != (r.get('rideEvent') == 'ride_started'):
                mismatches += 1
            if detected:
                calculated = dict(r)
                calculated['calculatedSpeedKmh'] = detector.speed
                calculated['rideDurationMs'] = config['ride_duration_ms']
                ride_rows.append(calculated)
        total_mismatches += mismatches
        for r in ride_rows:
            ride_count += 1
            ride_media = media(r['timestamp']).split(' | ', 1)
            base = [str(ride_count), r['timestamp'][11:23], r.get('positioningMode', 'unknown'),
                    ride_media[0], ride_media[1], f'{r["calculatedSpeedKmh"]:.2f}']
            nearby = [t for t in retreat_rows
                      if t.get('session') == r.get('session')
                      and r['epochMs'] - 20000 <= t['epochMs'] <= r['epochMs'] + r.get('rideDurationMs', 20000)]
            if not nearby:
                table.append(base + ['-', '-', '-', '-'])
            for index, t in enumerate(nearby):
                delta = (t['epochMs'] - r['epochMs']) / 1000
                retreat_media = media(t['timestamp']).split(' | ', 1)
                table.append((base if index == 0 else [''] * 6) +
                             [t['timestamp'][11:23], retreat_media[0], retreat_media[1], f'{delta:+.3f}'])
    headers = ['#', 'Ride time', 'Mode', 'Ride MP4', 'Offset', 'Shore km/h', 'Retreat time', 'Retreat MP4', 'Offset', 'Delta s']
    if table:
        widths = [max(len(headers[i]), max(len(row[i]) for row in table)) for i in range(len(headers))]
        def display(row):
            print(' | '.join(value.ljust(width) for value, width in zip(row, widths)))
        display(headers)
        print('-+-'.join('-' * width for width in widths))
        for row in table:
            display(row)
        print(f'\nCalculated rides: {ride_count} | Differences from recorded detection: {total_mismatches}')
        print('Delta: negative = retreat before detection; positive = after. Extra rows are additional actual retreat starts.')
        print('Retreat window: 20 s before detection through the configured ride duration. Times include no date/timezone; see log for those.')
        print('Threshold / duration used: ' + ', '.join(sorted({f'{c["ride_start_kmh"]:g} km/h / {c["ride_duration_ms"]/1000:g} s' for c in effective_settings})))
    if not ride_count:
        print('No ride starts found.')

if __name__ == '__main__':
    main()
