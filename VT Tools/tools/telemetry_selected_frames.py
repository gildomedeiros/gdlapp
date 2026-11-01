#!/usr/bin/env python3
r"""Extract frames for KML=y report rows and blend them by ride/video.

python telemetry_selected_frames.py --report "C:\Users\gildo\temp\telemetry-report\records.xlsx" --video-folder "E:\DCIM\100MEDIA"

Requires Pillow (python -m pip install pillow) and FFmpeg. FFmpeg is discovered
in the bundled dependencies/frame-tools folder or on PATH; --ffmpeg overrides it.
Keep summary.json beside records.xlsx for the date/timezone, or supply --date
YYYY-MM-DD and --utc-offset +10:00. SRT wall-clock timestamps locate exact frames;
the rounded fileIdPlayback display is NOT used as the extraction seek time.

Outputs individual PNGs, an Overlay-mode composite and a labelled contact sheet
per ride/video, plus frame-index.csv. Default: Overlay, 86% fill, 100% opacity.
The earliest frame is the background; later frames are layered in time order.
This is full-frame blending, not subject segmentation or a stabilized trajectory.
Use --overlay-mode average or darken for the previous methods.
"""
import argparse
import bisect
import csv
import datetime as dt
import json
import math
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

from telemetry_xlsx import read_xlsx
from telemetry_around import load_video_timeline, offset_timezone


def frame_filename(report_row, video_stem, window_second):
    value = str(window_second).strip()
    # Preserve fractional seconds; reject characters unsafe in Windows filenames.
    if not re.fullmatch(r'-?\d+(?:\.\d+)?', value):
        value = 'unknown'
    return f'window-{value}-row-{report_row:04}-{video_stem}.png'


def ffmpeg_path(explicit=None):
    if explicit:
        path = Path(explicit)
        if not path.is_file():
            raise ValueError(f'FFmpeg not found: {explicit}')
        return str(path.resolve())
    tools_dir = Path(__file__).resolve().parent.parent / 'dependencies/frame-tools'
    candidates = sorted((tools_dir / 'imageio_ffmpeg/binaries').glob('ffmpeg*.exe'))
    if candidates:
        return str(candidates[0])
    executable = shutil.which('ffmpeg')
    if executable:
        return executable
    raise ValueError('FFmpeg not found. Supply --ffmpeg "C:/path/to/ffmpeg.exe".')


def row_time(row, summary, date_override, timezone):
    text = row.get('timestamp', '')
    if re.match(r'^\d{4}-\d{2}-\d{2}', text):
        parsed = dt.datetime.fromisoformat(text)
        return parsed if parsed.tzinfo else parsed.replace(tzinfo=timezone)
    ride = str(row.get('rideNumber', ''))
    target = next((w['timestamp'] for w in summary.get('windows', []) if str(w['rideNumber']) == ride), summary.get('target'))
    if date_override:
        day = dt.date.fromisoformat(date_override)
    elif target:
        day = dt.datetime.fromisoformat(target).astimezone(timezone).date()
    else:
        raise ValueError('No full date available. Keep summary.json beside the report or supply --date YYYY-MM-DD.')
    clock = dt.time.fromisoformat(text)
    candidate = dt.datetime.combine(day, clock).replace(tzinfo=timezone)
    if target and not date_override:
        expected = dt.datetime.fromisoformat(target) + dt.timedelta(seconds=float(row.get('windowSecond') or 0) - float(summary.get('seconds_before', 0)))
        candidate = min((candidate + dt.timedelta(days=d) for d in (-1, 0, 1)), key=lambda t: abs((t - expected).total_seconds()))
    return candidate


def make_images(jobs, output, overlay_mode='overlay', overlay_fill=86):
    from PIL import Image, ImageChops, ImageDraw, ImageFont
    if overlay_mode not in ('overlay', 'darken', 'average'):
        raise ValueError('Unknown overlay mode')
    if not 0 <= overlay_fill <= 100:
        raise ValueError('Overlay fill must be between 0 and 100')
    font_path = Path('C:/Windows/Fonts/arial.ttf')
    font = ImageFont.truetype(str(font_path), 17) if font_path.exists() else ImageFont.load_default()
    groups = {}
    for job in jobs:
        if job['status'] == 'extracted':
            groups.setdefault((job['ride'], job['video']), []).append(job)
    for (ride, video), group in groups.items():
        group.sort(key=lambda j: j['clock'])
        overlay = None
        for count, job in enumerate(group, 1):
            with Image.open(output / job['frame']) as source:
                frame = source.convert('RGB')
            if overlay is None:
                overlay = frame
            else:
                if frame.size != overlay.size:
                    raise ValueError('Frame dimensions differ within the same video')
                if overlay_mode == 'overlay':
                    # Overlay branches on the underlying composite, not the new layer.
                    blended = ImageChops.overlay(overlay, frame)
                    overlay = Image.blend(overlay, blended, overlay_fill / 100.0)
                elif overlay_mode == 'darken':
                    overlay = ImageChops.darker(overlay, frame)
                else:
                    overlay = Image.blend(overlay, frame, 1.0 / count)
        stem = f'ride-{ride}-{Path(video).stem}'
        overlay.save(output / (stem + '-overlay.png'))
        cols = min(4, len(group)); width = 480; height = 320
        sheet = Image.new('RGB', (cols * width, math.ceil(len(group) / cols) * height), '#20242b')
        draw = ImageDraw.Draw(sheet)
        for i, job in enumerate(group):
            x, y = (i % cols) * width, (i // cols) * height
            with Image.open(output / job['frame']) as frame:
                frame.thumbnail((width - 12, 264))
                sheet.paste(frame, (x + 6, y + 6))
            draw.text((x + 6, y + 274), f'Window {job.get("windowSecond", "?")} | Row {job["reportRow"]} | {job["clock"][11:23]}', font=font, fill='white')
            draw.text((x + 6, y + 295), f'{job["droneKmlPoint"]} {job["surferKmlPoint"]} | {job["playback"]}', font=font, fill='white')
        sheet.save(output / (stem + '-contact-sheet.jpg'), quality=93)
    return len(groups)


def prepare_output(output, report, video_folder):
    """Reset only a selected-frames directory, never an input or its ancestor."""
    resolved = output.resolve()
    protected = (report.resolve(), report.parent.resolve(), Path(video_folder).resolve(), Path(__file__).resolve())
    if output.is_symlink() or (hasattr(output, 'is_junction') and output.is_junction()):
        raise ValueError('Output must not be a symbolic link or junction')
    if resolved == Path(resolved.anchor) or any(p == resolved or resolved in p.parents for p in protected):
        raise ValueError('Output overlaps a protected input location')
    if output.name.lower() == 'selected-frames' and resolved.exists():
        if not resolved.is_dir():
            raise ValueError('selected-frames exists but is not a directory')
        shutil.rmtree(resolved)
    resolved.mkdir(parents=True, exist_ok=True)


def run(args):
    try:
        from PIL import Image  # Check dependency before extracting videos.
    except ImportError as error:
        raise ValueError('Install Pillow first: python -m pip install pillow') from error
    report = Path(args.report).resolve()
    headers, rows = read_xlsx(report)
    if not {'KML', 'timestamp'}.issubset(headers):
        raise ValueError('Report must have KML and timestamp columns')
    selected = [(n, row) for n, row in enumerate(rows, 2) if str(row.get('KML', '')).strip().lower() == 'y']
    if not selected:
        raise ValueError('No rows marked y in KML column')
    summary_path = report.with_name('summary.json')
    summary = json.loads(summary_path.read_text(encoding='utf-8')) if summary_path.exists() else {}
    timezone = offset_timezone(args.utc_offset or summary.get('utc_offset', '+10:00'))
    timeline, errors = load_video_timeline(args.video_folder, timezone)
    if errors:
        print('\n'.join(errors), file=sys.stderr)
    if not timeline:
        raise ValueError('No usable SRT timestamps in video folder')
    ffmpeg = ffmpeg_path(args.ffmpeg)
    videos = list(Path(args.video_folder).iterdir())
    output = Path(args.output).absolute() if args.output else report.parent / 'selected-frames'
    prepare_output(output, report, args.video_folder)
    output = output.resolve()
    jobs = []
    for number, row in selected:
        when = row_time(row, summary, args.date, timezone); epoch = when.timestamp() * 1000
        job = dict(reportRow=number, windowSecond=row.get('windowSecond', ''), ride=row.get('rideNumber') or 'single', clock=when.isoformat(timespec='milliseconds'),
                   droneKmlPoint=row.get('droneKmlPoint', ''), surferKmlPoint=row.get('surferKmlPoint', ''),
                   video='', playback='', srtClock='', frame='', status='no subtitle coverage', error='')
        matches = [(ident, times, positions) for ident, times, positions in timeline if times[0] <= epoch <= times[-1] + 40]
        if len(matches) > 1:
            job['status'] = 'ambiguous subtitle coverage'
        elif matches:
            ident, times, positions = matches[0]
            candidates = [p for p in videos if p.suffix.lower() == '.mp4' and re.match(r'^DJI_0*' + re.escape(ident) + r'(?:_|$)', p.stem, re.IGNORECASE)]
            if len(candidates) != 1:
                job['status'] = 'video missing' if not candidates else 'ambiguous video filenames'
            else:
                video = candidates[0]
                i = min(bisect.bisect_left(times, epoch), len(times) - 1)
                if i and abs(times[i-1] - epoch) < abs(times[i] - epoch): i -= 1
                seconds = positions[i]
                job.update(video=video.name, playback=f'{int(seconds//60):02}:{seconds%60:06.3f}',
                           srtClock=dt.datetime.fromtimestamp(times[i]/1000, timezone).isoformat(timespec='milliseconds'),
                           frame=frame_filename(number, video.stem, job['windowSecond']))
                command = [ffmpeg, '-hide_banner', '-loglevel', 'error', '-nostdin', '-ss', str(seconds),
                           '-i', str(video), '-frames:v', '1', '-threads', '2', '-y', str(output / job['frame'])]
                try:
                    result = subprocess.run(command, capture_output=True, text=True, timeout=60,
                                            creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
                    job['status'] = 'extracted' if result.returncode == 0 and (output / job['frame']).is_file() else 'extraction failed'
                    job['error'] = result.stderr[-1000:] if result.returncode else ''
                except subprocess.TimeoutExpired:
                    job['status'] = 'extraction timed out'
        jobs.append(job)
        print(f'Row {number}: {job["status"]} {job["video"]} {job["playback"]}', flush=True)
    with (output / 'frame-index.csv').open('w', encoding='utf-8-sig', newline='') as f:
        writer = csv.DictWriter(f, fieldnames=list(jobs[0])); writer.writeheader(); writer.writerows(jobs)
    groups = make_images(jobs, output, args.overlay_mode, args.overlay_fill)
    (output / 'overlay-method.txt').write_text(
        f'Mode: {args.overlay_mode}\nOverlay fill: {args.overlay_fill}% (opacity 100%).\n'
        'Overlay uses the earliest frame as background, then applies later frames in time order. '
        'Each layer uses Overlay blending mixed at the specified fill. '
        'Darken uses per-channel minimum of the original frames. '
        'Average uses equal-weight full-frame blending. No subject masking, image registration '
        'or stabilization. Background/wave motion contributes; image positions are not a ground track.\n',
        encoding='utf-8')
    failed = sum(j['status'] != 'extracted' for j in jobs)
    print(f'{len(jobs)-failed}/{len(jobs)} frames; {groups} overlays/contact sheets. Output: {output}')
    return 2 if failed else 0


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument('--report', required=True)
    p.add_argument('--video-folder', default='E:/DCIM/100MEDIA')
    p.add_argument('--output')
    p.add_argument('--ffmpeg')
    p.add_argument('--overlay-mode', choices=['overlay', 'darken', 'average'], default='overlay',
                   help='Default: Overlay blending; darken and average retain previous methods')
    p.add_argument('--overlay-fill', type=float, default=86,
                   help='Overlay layer fill percentage, 0 to 100 (default: 86)')
    p.add_argument('--date', help='Override date YYYY-MM-DD when summary.json is unavailable')
    p.add_argument('--utc-offset', help='Default: summary timezone or +10:00')
    args = p.parse_args()
    if not 0 <= args.overlay_fill <= 100:
        p.error('--overlay-fill must be between 0 and 100')
    try:
        return run(args)
    except (ValueError, OSError) as error:
        p.exit(2, f'Error: {error}\n')


if __name__ == '__main__':
    sys.exit(main())
