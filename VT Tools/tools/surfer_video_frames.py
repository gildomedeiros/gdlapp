"""Step real MP4 presentation timestamps, independently of GPS packet timing."""
import bisect
from datetime import datetime, timedelta
from fractions import Fraction
from functools import lru_cache
import math
import os
from pathlib import Path
import re
import subprocess

from surfer_map_capture import resolve_video, enhance_frame

TOLERANCE = 1e-6  # FFmpeg seek timestamps are rounded to microseconds.


def run_ffmpeg(ffmpeg, arguments):
    result=subprocess.run([ffmpeg,'-hide_banner','-loglevel','info','-nostdin','-threads','2',*arguments],
        capture_output=True,text=True,timeout=60,
        creationflags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0)
    if result.returncode:
        raise ValueError('Video frame decoding failed: '+result.stderr[-500:])
    return result.stderr


def presentation_times(text):
    base=re.search(r'config in time_base:\s*(\d+)/(\d+)',text)
    if not base:return ()
    scale=Fraction(int(base[1]),int(base[2]))
    return tuple(sorted({int(value)*scale for value in re.findall(r'\bn:\s*\d+\s+pts:\s*(-?\d+)\s+pts_time:',text)}))


@lru_cache(maxsize=32)
def frame_window(video, modified, size, ffmpeg, start, span):
    # File identity invalidates the cache; adjacent steps reuse this small window.
    text=run_ffmpeg(ffmpeg,['-ss',str(start),'-t',str(span),'-copyts','-start_at_zero','-i',video,
        '-map','0:v:0','-vf','scale=2:2,showinfo=checksum=0','-fps_mode','passthrough','-an','-f','null','-'])
    duration=re.search(r'Duration:\s*(\d+):(\d+):(\d+(?:\.\d+)?)',text)
    end=(int(duration[1])*3600+int(duration[2])*60+float(duration[3])) if duration else None
    return presentation_times(text),end


def adjacent_timestamp(video, ffmpeg, seconds, step):
    stat=video.stat()
    for radius in (1,2,4,8,16,32,64):
        start=max(0,math.floor(seconds)-radius)
        span=radius*2+2
        times,duration=frame_window(str(video.resolve()),stat.st_mtime_ns,stat.st_size,ffmpeg,start,span)
        # The existing GPS capture takes the first decoded frame at/after its SRT seek.
        current=bisect.bisect_left(times,seconds-TOLERANCE)
        if current<len(times) and 0<=current+step<len(times):
            return times[current+step]
        if step<0 and start==0 and current==0:
            raise ValueError('First frame of this video.')
        if duration is not None and start+span>=duration and current+step>=len(times):
            raise ValueError('Last frame of this video.')
    raise ValueError('No adjacent video frame found near this time.')


def capture_video_frame(point, request, video_folder, output, ffmpeg):
    step=request.get('step')
    if type(step) is not int or step not in (-1,0,1):
        raise ValueError('Video step must be -1, 0 or 1.')
    seconds=request.get('videoTime',point.get('videoPlaybackSeconds'))
    if not isinstance(seconds,(int,float)) or isinstance(seconds,bool) or not math.isfinite(seconds) or seconds<0:
        raise ValueError('Invalid video playback time.')
    variant=request.get('variant','clean')
    if variant not in ('clean','enhanced'):
        raise ValueError('Video-only frames support clean or enhanced downloads, without telemetry.')
    video=resolve_video(point,video_folder)
    chosen=adjacent_timestamp(video,ffmpeg,seconds,step)
    playback=float(chosen)
    folder=Path(output)/'video-frames'
    folder.mkdir(parents=True,exist_ok=True)
    stem=f'{video.stem}-pts-{chosen.numerator}-{chosen.denominator}'
    clean=folder/(stem+'-clean.png')
    # Seek slightly before the requested frame, then select its actual PTS.
    text=run_ffmpeg(ffmpeg,['-ss',f'{max(0,playback-.1):.9f}','-copyts','-start_at_zero','-i',str(video),
        '-map','0:v:0','-vf',f'select=gte(t\\,{playback-TOLERANCE:.12f}),showinfo=checksum=0',
        '-frames:v','1','-fps_mode','passthrough','-threads','2','-y',str(clean)])
    decoded=presentation_times(text)
    if not clean.is_file() or not decoded or abs(float(decoded[0])-playback)>TOLERANCE:
        raise ValueError('Could not extract the requested video frame exactly.')
    destination=clean
    if variant=='enhanced':
        destination=folder/(stem+'-enhanced.png')
        enhance_frame(clean,destination)
    # Wall clock is relative to this point's SRT mapping; no GPS is fabricated.
    timestamp=None
    try:
        origin=datetime.fromisoformat(point['videoFrameTimestamp'])
        timestamp=(origin+timedelta(seconds=playback-point['videoPlaybackSeconds'])).isoformat(timespec='milliseconds')
    except (KeyError,TypeError,ValueError):pass
    return destination,dict(kind='video',variant=variant,point=point['point'],videoName=video.name,
        videoTime=playback,timestamp=timestamp,filename=destination.name,saved=str(destination))
