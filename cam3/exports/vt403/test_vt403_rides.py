"""Print logged VT4.0.3 wave ride starts and timestamps to the console."""
import argparse
import json
import struct
from datetime import datetime, timedelta, timezone
from pathlib import Path
from test_shoreward_warning import sources


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
    print('Ride | Timestamp | Mode | MP4 file | Time into file (min:sec)')
    ride_count = 0
    for name, stream in sources(a.logs):
        for line in stream:
            r = json.loads(line)
            if r.get('event') != 'movement_cycle' or r.get('rideEvent') != 'ride_started':
                continue
            ride_count += 1
            instant = datetime.fromisoformat(r['timestamp'].replace('Z', '+00:00'))
            if instant.tzinfo is None:
                raise ValueError('Ride timestamp must include its timezone')
            epoch = instant.timestamp()
            matches = [(path, epoch - start) for path, start, duration in videos
                       if start <= epoch < start + duration]
            if matches:
                label = 'AMBIGUOUS: ' if len(matches) > 1 else ''
                match = label + '; '.join(
                    f'{path.name} | {int(round(offset * 1000)) // 60000:02d}:'
                    f'{(int(round(offset * 1000)) % 60000) / 1000:06.3f}'
                    for path, offset in matches)
            else:
                match = 'UNMATCHED | N/A'
            print(f'{ride_count:>2} | {r["timestamp"]} | {r.get("positioningMode", "unknown")} | {match}')
    if not ride_count:
        print('No ride starts found.')

if __name__ == '__main__':
    main()


