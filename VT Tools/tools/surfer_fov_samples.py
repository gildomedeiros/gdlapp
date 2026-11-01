"""Stratified person-position samples: image third × horizontal GPS distance band."""
import csv
import bisect
import html
import json
import math
from datetime import datetime
from pathlib import Path
import shutil
import subprocess

from surfer_fov import FovEstimator
from surfer_map_capture import capture_frame, stamp_card
from telemetry_selected_frames import ffmpeg_path
from telemetry_xlsx import write_xlsx

ZONES = ('TOP', 'MIDDLE', 'BOTTOM')


def print_sample_table(cells, edges, count):
    headers = ['Distance band', *ZONES]
    rows = []
    for band, (low, high) in enumerate(zip(edges, edges[1:])):
        row = [f'{low:g}-{high:g} m']
        for zone in ZONES:
            selected = cells.get((band, zone), [])
            ids = ', '.join(p['point'] for p in selected) or '(none)'
            row.append(f'{ids} [{len(selected)}/{count}]')
        rows.append(row)
    widths = [max(len(row[i]) for row in [headers, *rows]) for i in range(len(headers))]
    rule = '+' + '+'.join('-' * (width + 2) for width in widths) + '+'
    def line(row):
        return '| ' + ' | '.join(value.ljust(width) for value, width in zip(row, widths)) + ' |'
    print('\nSelected surfer positions by combination (found/requested):', flush=True)
    print(rule)
    print(line(headers))
    print(rule)
    for row in rows:
        print(line(row))
    print(rule, flush=True)


def distance_band(distance, edges):
    if distance is None or not math.isfinite(distance):
        return None
    for i, (low, high) in enumerate(zip(edges, edges[1:])):
        if low <= distance < high or (i == len(edges)-2 and distance == high):
            return i
    return None


def log_candidates(points, edges, interval_seconds=5):
    """Cheap log-only shortlist before SRT mapping or any video decoding.

    Keep one point per distance band per interval, or sooner when logged
    gimbal pitch changes by at least 3 degrees from that band's last point.
    With edges=None, space candidates per source log without distance bands.
    """
    last = {}
    selected = []
    for point in sorted(points, key=lambda p: (p['time_ms'], p['sourceFile'], p.get('sourceLine', 0))):
        band = distance_band(point.get('droneSurferDistanceMetres'), edges) if edges is not None else 0
        if band is None:
            continue
        key = (point['sourceFile'], band)
        previous = last.get(key)
        if previous is not None and point['time_ms']-previous['time_ms'] < interval_seconds*1000:
            if abs(point['gimbalTiltDeg']-previous['gimbalTiltDeg']) < 3:
                continue
        last[key] = point
        selected.append(point)
    return selected


def select_samples(candidates, edges, count, classify, seed=0, max_checks=0, progress=None, check_cancel=None):
    """Chronological order, unique original video frames, quota per cell."""
    ordered = sorted(candidates,key=lambda p:(p['time_ms'],p['sourceFile'],p.get('sourceLine',0)))
    cells = {(i, zone): [] for i in range(len(edges)-1) for zone in ZONES}
    attempts, seen = [], set()
    checks = 0
    for point in ordered:
        if check_cancel:check_cancel()
        if all(len(rows) == count for rows in cells.values()):
            break
        if max_checks and checks >= max_checks:
            break
        band = distance_band(point.get('droneSurferDistanceMetres'), edges)
        if band is None or all(len(cells[band, zone]) == count for zone in ZONES):
            continue
        frame = (point.get('videoId'), point.get('videoPlaybackSeconds'), point.get('videoFrameTimestamp'))
        if frame in seen or frame[0] is None or frame[1] is None:
            continue
        seen.add(frame)
        checks += 1
        if progress:progress(f'Checking frame {checks}: {point["point"]}; {sum(map(len,cells.values()))}/{len(cells)*count} samples found')
        print(f'Sample scan {checks}: {point["point"]}, {edges[band]:g}–{edges[band+1]:g} m; '
              f'{sum(map(len,cells.values()))}/{len(cells)*count} selected', flush=True)
        attempt = dict(point=point['point'], timestamp=point['receivedTimestamp'], distanceBand=band,
                       status='', sourceFile=point['sourceFile'])
        try:
            result = classify(point)
            zone = result['zone']
            if zone not in ZONES:
                raise ValueError('Unknown detected frame position')
            attempt['status'] = zone
            if len(cells[band, zone]) < count:
                sample = dict(point, personPosition=result, sampleBand=band, sampleZone=zone)
                cells[band, zone].append(sample)
                print(f'  Selected {zone}: {len(cells[band,zone])}/{count}', flush=True)
            else:
                print(f'  {zone} already full; not selected.', flush=True)
        except (ValueError, OSError, RuntimeError, ImportError, subprocess.TimeoutExpired) as error:
            attempt['status'] = 'unavailable: ' + str(error)
            print('  ' + attempt['status'], flush=True)
        attempts.append(attempt)
    return cells, attempts


def within_ride_window(point, before_seconds, after_seconds):
    starts=point.get('waveRideStartTimesMs',[])
    # A qualifying start must lie in [packet-after, packet+before].
    i=bisect.bisect_left(starts,point['time_ms']-after_seconds*1000)
    return i<len(starts) and starts[i]<=point['time_ms']+before_seconds*1000


def run_samples(packets, drones, args, tz, prepared_points=None, timeline_data=None, progress=None, check_cancel=None, cache_output=None):
    from surfer_gps_map import select_packets, pair_drones, add_video_playback
    valid = [p for p in packets if all(isinstance(p.get(k),(int,float)) and not isinstance(p[k],bool)
             and math.isfinite(p[k]) and abs(p[k]) <= limit for k,limit in [('latitude',90),('longitude',180)])]
    if not valid:
        raise ValueError('No valid received surfer coordinates')
    rides_by_source={p['sourceFile']:p.get('waveRideStartTimesMs',[]) for p in packets}
    ride_count=sum(len(starts) for starts in rides_by_source.values())
    if not ride_count:
        raise ValueError('No movement_cycle rideEvent=ride_started found; no wave-ride windows to sample.')
    print(f'Sampling only {ride_count} ride windows: {args.sample_before:g}s before to '
          f'{args.sample_after:g}s after each logged ride start, chronologically.',flush=True)
    if prepared_points is None:
        _, points = select_packets(valid, valid[0]['receivedTimestamp'], len(valid)-1, tz)
        pair_drones(points, drones)
    else:
        points = [dict(p) for p in prepared_points]
    edges = args.sample_bands
    candidates = [p for p in points if within_ride_window(p,args.sample_before,args.sample_after) and distance_band(p.get('droneSurferDistanceMetres'), edges) is not None
                  and isinstance(p.get('gimbalTiltDeg'),(int,float)) and math.isfinite(p['gimbalTiltDeg'])]
    log_count = len(candidates)
    candidates = log_candidates(candidates, edges, args.sample_interval)
    print(f'Log shortlist: {log_count} eligible packets -> {len(candidates)} candidates '
          f'(one per band every {args.sample_interval:g}s, or a tilt change of at least 3 degrees).', flush=True)
    if timeline_data is None:add_video_playback(candidates, args.video_folder, tz)
    else:add_video_playback(candidates, args.video_folder, tz, timeline_data)
    output = Path(args.output) if args.output else Path.cwd() / 'fov-samples'
    output.mkdir(parents=True, exist_ok=True)
    estimator = FovEstimator(Path(__file__).parent / 'models' / 'yolo11n-seg.pt')
    if not estimator.model_path.is_file():
        raise ValueError('Run tools/setup_surfer_fov.py to install the person detector model.')
    from ultralytics import YOLO  # Fail once for missing runtime, not for every candidate.
    executable = ffmpeg_path(args.ffmpeg)
    cache = {}
    cache_output=Path(cache_output) if cache_output else output
    for path in (cache_output / 'person-positions').glob('*.json'):
        try:
            result = json.loads(path.read_text(encoding='utf-8'))
            cache[(result['sourceFile'],result['gpsTimestamp'],result['frameTimestamp'])] = result
        except (ValueError, OSError, KeyError):
            continue

    def classify(point):
        key = (point['sourceFile'], point['receivedTimestamp'], point.get('videoFrameTimestamp'))
        if key in cache:
            return cache[key]
        frame = capture_frame(point, args.video_folder, output, executable, annotate=False)
        try:
            result = estimator.classify_person(frame, point, cache_output)
            cache[key] = result
            return result
        finally:
            frame.unlink(missing_ok=True)  # Only this scan's extracted PNG; never source media.

    cells, attempts = select_samples(candidates, edges, args.sample_count, classify,
                                    args.sample_seed, args.sample_max_checks, progress, check_cancel)
    headers = ['sample','distanceBand','framePosition','distanceMetres','gimbalTiltDeg','heightAboveTakeoffM',
               'personPercentFromTop','confidence','point','timestamp','frameTimestamp','fileIdPlayback',
               'acceptedGpsPacket','frame','frameError','sourceFile']
    rows, cards, summary = [], [], []
    frame_folder = output / 'sample-frames'
    frame_folder.mkdir(exist_ok=True)
    for (band, zone), selected in cells.items():
        label = f'{edges[band]:g}–{edges[band+1]:g} m'
        summary.append(dict(distanceBand=label, framePosition=zone, found=len(selected), requested=args.sample_count))
        cards.append(f'<h2>{html.escape(label)} · {zone} — {len(selected)}/{args.sample_count}</h2><div class="group">')
        if len(selected) < args.sample_count:
            cards.append(f'<p>Missing {args.sample_count-len(selected)} samples.</p>')
        for point in selected:
            if check_cancel:check_cancel()
            if progress:progress(f'Saving sample frame {len(rows)+1}')
            number = len(rows)+1
            result = point['personPosition']
            name = f'sample-{number:02}-{point["point"]}.png'
            error = ''
            try:
                raw = capture_frame(point,args.video_folder,output,executable,annotate=False)
                shutil.copyfile(raw,frame_folder/name)
                raw.unlink(missing_ok=True)
                stamp_card(frame_folder/name,point,args.gap_red_ms)
            except (ValueError,OSError,subprocess.TimeoutExpired) as ex:
                error = str(ex)
            row = [number,label,zone,point['droneSurferDistanceMetres'],point['gimbalTiltDeg'],point.get('heightAboveTakeoffM'),
                   100*result['yFraction'],result['detection']['confidence'],point['point'],point['receivedTimestamp'],
                   point.get('videoFrameTimestamp'),point.get('fileIdPlayback'),point.get('accepted'),
                   'sample-frames/'+name if not error else '',error,point['sourceFile']]
            rows.append(dict(zip(headers,row)))
            caption = f'#{number} {point["point"]} · {zone} · {point["droneSurferDistanceMetres"]:.1f} m · tilt {point["gimbalTiltDeg"]:g}° · {point.get("fileIdPlayback")} · {point["receivedTimestamp"]}'
            cards.append('<figure>' + (f'<a href="sample-frames/{name}"><img loading="lazy" src="sample-frames/{name}"></a>' if not error else '<p>'+html.escape(error)+'</p>') + '<figcaption>'+html.escape(caption)+'</figcaption></figure>')
        cards.append('</div>')
    write_xlsx(output/'fov-samples.xlsx',headers,rows)
    (output/'sample-summary.json').write_text(json.dumps(dict(cells=summary,scanOrder='chronological',rideStartsBySource=rides_by_source,sampleBeforeSeconds=args.sample_before,sampleAfterSeconds=args.sample_after,
        checked=len(attempts),candidatePackets=len(candidates),eligibleLogPackets=log_count,
        sampleIntervalSeconds=args.sample_interval,tiltChangeDegrees=3,invalidGpsPackets=len(packets)-len(valid),
        maxChecks=args.sample_max_checks,bandRule='Lower bound inclusive; upper exclusive except final band inclusive'),indent=2),encoding='utf-8')
    with (output/'sample-scan.csv').open('w',newline='',encoding='utf-8-sig') as stream:
        writer=csv.DictWriter(stream,fieldnames=['point','timestamp','distanceBand','status','sourceFile'])
        writer.writeheader();writer.writerows(attempts)
    (output/'fov-samples.html').write_text('<!doctype html><meta charset="utf-8"><title>Surfer framing samples</title>'
        '<style>body{font:15px Arial;margin:24px;color:#17324d}.group{display:flex;flex-wrap:wrap;gap:16px}figure{margin:0;width:440px}img{width:100%}figcaption{padding:8px}h2{margin-top:28px}</style>'
        f'<h1>Surfer framing samples — {len(rows)}/{len(cells)*args.sample_count}</h1>'
        '<p>Grouped by detected person centre (equal image thirds) and horizontal GPS distance. Tilt is logged, not a grouping dimension. Frames are nearest original SRT timestamps. Detection can be mistaken; review the images. GPS acceptance is listed in the workbook.</p>'
        '<p><a href="fov-samples.xlsx">Download sample table</a></p>'+''.join(cards),encoding='utf-8')
    print_sample_table(cells, edges, args.sample_count)
    print('Sample contact sheet:',(output/'fov-samples.html').resolve())
    print('Sample workbook:',(output/'fov-samples.xlsx').resolve())
    selected_points = sorted((p for samples in cells.values() for p in samples),
                             key=lambda p: (p['time_ms'], p['sourceFile'], p['sourceLine']))
    return selected_points, output
