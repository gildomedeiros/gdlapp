"""Log-first map samples around wave rides, with optional pitch/distance filters."""
import bisect
import json
import math
from datetime import datetime
from pathlib import Path

from surfer_fov_samples import log_candidates, within_ride_window


def select_pitch_samples(points, pitch_range, before, after, interval, maximum, distance_range=None):
    low, high = sorted(pitch_range)
    near, far = sorted(distance_range) if distance_range is not None else (0, math.inf)
    eligible = [p for p in points
                if within_ride_window(p, before, after)
                and isinstance(p.get('gimbalTiltDeg'), (int, float))
                and not isinstance(p['gimbalTiltDeg'], bool)
                and math.isfinite(p['gimbalTiltDeg'])
                and low <= p['gimbalTiltDeg'] <= high
                and isinstance(p.get('droneSurferDistanceMetres'), (int, float))
                and not isinstance(p['droneSurferDistanceMetres'], bool)
                and math.isfinite(p['droneSurferDistanceMetres'])
                and near <= p['droneSurferDistanceMetres'] <= far]
    candidates = log_candidates(eligible, None, interval)
    return candidates[:maximum], len(eligible), len(candidates)


def select_ride_samples(points, before, after, granularity, maximum=None,
                        pitch_range=None, distance_range=None):
    """Last real packet in each interval of each ride window; deduplicate overlaps."""
    def in_range(value, limits):
        return limits is None or (isinstance(value, (int, float)) and not isinstance(value, bool)
                                  and math.isfinite(value) and min(limits) <= value <= max(limits))

    eligible = [p for p in points if within_ride_window(p, before, after)
                and in_range(p.get('gimbalTiltDeg'), pitch_range)
                and in_range(p.get('droneSurferDistanceMetres'), distance_range)]
    ordered = sorted(eligible, key=lambda p: (p['time_ms'], p['sourceFile'], p.get('sourceLine', 0)))
    if granularity == 0:
        candidates = ordered
    else:
        slots = {}
        for point in ordered:
            starts = point['waveRideStartTimesMs']
            left = bisect.bisect_left(starts, point['time_ms'] - after*1000)
            right = bisect.bisect_right(starts, point['time_ms'] + before*1000)
            for start in starts[left:right]:
                slot = math.floor((point['time_ms'] - (start-before*1000)) / (granularity*1000))
                slots[point['sourceFile'], start, slot] = point
        unique = {(p['sourceFile'], p['time_ms'], p.get('sourceLine', 0)): p for p in slots.values()}
        candidates = sorted(unique.values(), key=lambda p: (p['time_ms'], p['sourceFile'], p.get('sourceLine', 0)))
    return candidates[:maximum], len(eligible), len(candidates)


def annotate_ride_windows(points, chosen_rides, before, after, tz, ride_numbers=None):
    """Keep every matching same-log window, including overlapping rides."""
    windows = {}
    for number, (start, source) in zip(ride_numbers or range(1,len(chosen_rides)+1), chosen_rides):
        windows.setdefault(source, []).append(dict(
            id=f'R{number}', number=number, startMs=start,
            timestamp=datetime.fromtimestamp(start/1000, tz).isoformat(timespec='milliseconds'),
            beforeSeconds=before, afterSeconds=after))
    for point in points:
        point['mapRideWindows'] = [dict(window) for window in windows.get(point['sourceFile'], [])
                                  if window['startMs']-before*1000 <= point['time_ms'] <= window['startMs']+after*1000]


def run_log_samples(packets, drones, args, tz, all_rides=False, prepared_points=None, timeline_data=None):
    from surfer_gps_map import select_packets, pair_drones, drone_next_bearing, add_video_playback

    valid = [p for p in packets if all(isinstance(p.get(k), (int, float))
             and not isinstance(p[k], bool) and math.isfinite(p[k]) and abs(p[k]) <= limit
             for k, limit in [('latitude', 90), ('longitude', 180)])]
    if not valid:
        raise ValueError('No valid received surfer coordinates')
    rides = {p['sourceFile']: p.get('waveRideStartTimesMs', []) for p in valid}
    ordered_rides = sorted({(start, source) for source, starts in rides.items() for start in starts})
    total_rides = len(ordered_rides)
    if not total_rides:
        raise ValueError('No movement_cycle rideEvent=ride_started found; no wave-ride windows to sample.')
    number_of_rides = getattr(args, 'number_of_rides', None)
    chosen_rides = ordered_rides[:number_of_rides]
    specific = getattr(args, 'ride_numbers', None)
    if specific:
        if max(specific)>total_rides:
            raise ValueError(f'Only {total_rides} rides exist; requested ride {max(specific)}.')
        chosen_rides = [ordered_rides[n-1] for n in specific]
    rides = {source: [] for source in rides}
    for start, source in chosen_rides:
        rides[source].append(start)
    ride_count = len(chosen_rides)

    # Annotate before filtering: a previous/next packet means the actual
    # neighbouring receipt, not a potentially distant selected sample.
    if prepared_points is None:
        _, points = select_packets(valid, valid[0]['receivedTimestamp'], len(valid)-1, tz)
        pair_drones(points, drones)
        for i, point in enumerate(points):
            point['droneNextBearingDeg'] = drone_next_bearing(points, i)
    else:
        points = prepared_points
    # Limit windows, not packets: keep original full-log IDs and neighbours,
    # and never admit a later ride just because earlier windows have no matches.
    points = [dict(point, waveRideStartTimesMs=rides[point['sourceFile']]) for point in points]
    if all_rides:
        selected, eligible, candidates = select_ride_samples(
            points, args.sample_before, args.sample_after, args.sample_interval,
            args.max_samples, args.pitch_range, args.distance_range)
    else:
        selected, eligible, candidates = select_pitch_samples(
            points, args.pitch_range, args.sample_before, args.sample_after,
            args.sample_interval, args.max_samples, args.distance_range)
    filters = []
    if args.pitch_range is not None:
        low, high = sorted(args.pitch_range)
        filters.append(f'Actual logged gimbal pitch {low:g}° to {high:g}°')
    if args.distance_range is not None:
        filters.append(f'horizontal GPS distance {min(args.distance_range):g} to {max(args.distance_range):g} m')
    ride_description = (f'First {ride_count} of {total_rides} logged wave rides (requested {number_of_rides}). '
                        if number_of_rides is not None else 'All logged wave rides. ')
    if specific:ride_description='Logged rides '+', '.join(map(str,specific))+'. '
    filter_description = ride_description + (' AND '.join(filters) + '; range endpoints inclusive. ' if filters else '')
    if all_rides:
        sampling_description = (f'Last received packet per {args.sample_interval:g}s interval of each window; overlapping windows deduplicated'
                                if args.sample_interval else 'Every received packet in the windows')
    else:
        sampling_description = f'{args.sample_interval:g}s spacing per log (also keep tilt changes of at least 3°)'
    limit_description = f'maximum {args.max_samples} samples' if args.max_samples is not None else 'no sample cap'
    description = (filter_description +
                   f'{args.sample_before:g}s before to {args.sample_after:g}s after each logged ride start, '
                   f'using {ride_count} ride windows from the same source logs. '
                   f'Chronological selection. {sampling_description}; {limit_description}. '
                   f'{len(selected)} selected from {candidates} spaced candidates / {eligible} matching packets. '
                   'S/D IDs retain their original full-log order; gaps between selected IDs are expected. '
                   'Speeds, receipt gaps and arrows use the original neighbouring packets.')
    print(description, flush=True)
    output = Path(args.output) if args.output else Path.cwd() / ('wave-rides-map' if number_of_rides is not None else 'all-wave-rides-map' if all_rides else 'surfer-pitch-samples')
    if not selected:
        return [], output, description
    annotate_ride_windows(selected, chosen_rides, args.sample_before, args.sample_after, tz, specific)
    # Only selected points need SRT lookup; no MP4 decoding or CV is needed.
    if timeline_data is None:add_video_playback(selected, args.video_folder, tz)
    else:add_video_playback(selected, args.video_folder, tz, timeline_data)
    output.mkdir(parents=True, exist_ok=True)
    summary = dict(mode='number-of-rides' if number_of_rides is not None else 'all-rides' if all_rides else 'pitch-range',
                   numberOfRides=number_of_rides, totalRideCount=total_rides, selectedRideCount=ride_count,
                   pitchRangeDegrees=sorted(args.pitch_range) if args.pitch_range is not None else None,
                   distanceRangeMetres=sorted(args.distance_range) if args.distance_range is not None else None,
                   maxSamples=args.max_samples,
                   sampleBeforeSeconds=args.sample_before, sampleAfterSeconds=args.sample_after,
                   sampleIntervalSeconds=args.sample_interval, tiltChangeDegrees=None if all_rides else 3,
                   rideStartsBySource=rides, eligiblePackets=eligible, spacedCandidates=candidates,
                   selectedCount=len(selected), invalidGpsPackets=len(packets)-len(valid),
                   selection=sampling_description + '; actual received GPS packets and same-log ride windows',
                   points=[dict(point=p['point'], timestamp=p['receivedTimestamp'],
                                pitch=p['gimbalTiltDeg'], distanceMetres=p['droneSurferDistanceMetres'],
                                sourceFile=p['sourceFile']) for p in selected])
    (output / ('ride-sample-summary.json' if all_rides else 'pitch-sample-summary.json')).write_text(json.dumps(summary, indent=2), encoding='utf-8')
    print('\nPoint     Received timestamp                   Pitch       Distance    Video')
    for p in selected:
        pitch = f'{p["gimbalTiltDeg"]:.1f}°' if p.get('gimbalTiltDeg') is not None else 'unknown'
        distance = f'{p["droneSurferDistanceMetres"]:.1f} m' if p.get('droneSurferDistanceMetres') is not None else 'unknown'
        print(f'{p["point"]:<9} {p["receivedTimestamp"]:<36} '
              f'{pitch:>7} {distance:>12}    {p.get("fileIdPlayback")}')
    return selected, output, description


def run_pitch_samples(packets, drones, args, tz):
    return run_log_samples(packets, drones, args, tz)


def run_ride_samples(packets, drones, args, tz):
    return run_log_samples(packets, drones, args, tz, all_rides=True)
