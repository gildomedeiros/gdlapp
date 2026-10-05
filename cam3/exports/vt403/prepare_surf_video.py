"""Create a 1080p review clip and numbered sample frames using FFmpeg.

Requires ffmpeg on PATH (or --ffmpeg path). No Python packages required.
Does not classify surfing events or change the original video.
"""
import argparse
from pathlib import Path
import shutil
import subprocess
import csv


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('video', help='Input MP4 path')
    p.add_argument('--start', type=float, default=0, help='Start offset in seconds')
    p.add_argument('--duration', type=float, default=300, help='Review duration in seconds')
    p.add_argument('--interval', type=float, default=2, help='Seconds between sample frames')
    p.add_argument('--output', help='New output folder; default beside input')
    p.add_argument('--ffmpeg', default='ffmpeg')
    args = p.parse_args()
    source = Path(args.video).resolve()
    if not source.is_file():
        p.error('Video does not exist')
    if args.start < 0 or args.duration <= 0 or args.interval <= 0:
        p.error('start must be >=0; duration and interval must be >0')
    binary = shutil.which(args.ffmpeg)
    if not binary:
        p.error('FFmpeg not found. Install FFmpeg or pass --ffmpeg with its full path.')
    output = Path(args.output) if args.output else source.parent / (source.stem+'_review')
    if output.exists():
        p.error('Output folder already exists; choose a new --output folder')
    output.mkdir(parents=True)
    frames = output / 'frames'
    frames.mkdir()
    clip = output / 'review_1080p.mp4'
    # Preserve aspect ratio; never upscale; respect portrait and landscape limits.
    scale = "scale=w='min(1920,iw)':h='min(1080,ih)':force_original_aspect_ratio=decrease:force_divisible_by=2"
    command = [binary, '-hide_banner', '-loglevel', 'error', '-n',
               '-ss', str(args.start), '-i', str(source), '-t', str(args.duration),
               '-map', '0:v:0', '-map', '0:a?', '-vf', scale,
               '-c:v', 'libx264', '-crf', '20', '-preset', 'fast',
               '-c:a', 'aac', '-b:a', '128k', '-movflags', '+faststart', str(clip)]
    print('Creating review clip...')
    subprocess.run(command, check=True)
    # select uses presentation time; showinfo provides actual timestamps rather
    # than assuming perfect frame rate or assigning synthetic sample times.
    selection = f"select='isnan(prev_selected_t)+gte(t-prev_selected_t,{args.interval})',showinfo"
    command = [binary, '-hide_banner', '-loglevel', 'info', '-n', '-i', str(clip),
               '-vf', selection, '-fps_mode', 'vfr', '-q:v', '3', str(frames/'frame_%05d.jpg')]
    print('Sampling frames...')
    result = subprocess.run(command, capture_output=True, text=True, check=True)
    import re
    times = [float(t) for t in re.findall(r'\bpts_time:([-+0-9.eE]+)', result.stderr)]
    files = sorted(frames.glob('frame_*.jpg'))
    if len(times) != len(files):
        raise RuntimeError('Frame timestamp count mismatch; preserve output for inspection')
    with (output/'frame_times.csv').open('w', newline='', encoding='utf-8') as f:
        writer = csv.writer(f)
        writer.writerow(['frame', 'review_offset_seconds', 'original_video_offset_seconds'])
        for path, time in zip(files, times):
            writer.writerow([path.name, f'{time:.3f}', f'{args.start+time:.3f}'])
    print(f'Created {clip}\nSample frames: {len(files)}\nTimestamp index: {output / "frame_times.csv"}')
    print('These are review materials, not automatic ride detections. Use smaller intervals for fast takeoffs.')


if __name__ == '__main__':
    main()
