"""Read MP4 movie timestamps by seeking box headers; never decode or modify video."""
from datetime import datetime,timedelta,timezone
from pathlib import Path
import math
import re
import struct

UTC=timezone.utc
BRISBANE=timezone(timedelta(hours=10))
EPOCH=datetime(1904,1,1,tzinfo=UTC)


def boxes(stream,start,end):
    offset=start
    while offset+8<=end:
        stream.seek(offset);header=stream.read(8)
        if len(header)!=8:raise ValueError('Truncated MP4 box header.')
        size,kind=struct.unpack('>I4s',header);header_size=8
        if size==1:
            raw=stream.read(8)
            if len(raw)!=8:raise ValueError('Truncated extended MP4 box.')
            size=struct.unpack('>Q',raw)[0];header_size=16
        elif size==0:size=end-offset
        if size<header_size or offset+size>end:raise ValueError('Invalid MP4 box size.')
        yield kind,offset+header_size,offset+size
        offset+=size


def read_metadata(path):
    """ISO BMFF mvhd v0/v1 creation field (1904 UTC epoch), movie duration."""
    with Path(path).open('rb') as stream:
        for kind,start,end in boxes(stream,0,Path(path).stat().st_size):
            if kind!=b'moov':continue
            for child,a,b in boxes(stream,start,end):
                if child!=b'mvhd':continue
                stream.seek(a);raw=stream.read(min(32,b-a))
                if len(raw)<20:raise ValueError('Truncated MP4 movie header.')
                version=raw[0]
                if version==0:creation,_,scale,duration=struct.unpack('>IIII',raw[4:20])
                elif version==1 and len(raw)>=32:creation,_,scale,duration=struct.unpack('>QQIQ',raw[4:32])
                else:raise ValueError('Unsupported MP4 movie header version.')
                try:created=EPOCH+timedelta(seconds=creation) if creation else None
                except OverflowError:created=None
                if created and created.year<1970:created=None
                duration=None if duration in (0xffffffff,0xffffffffffffffff) or not scale else duration/scale
                return dict(start=created.isoformat() if created else None,duration=duration,source='MP4 moov/mvhd creation_time (1904 UTC epoch)')
    return dict(start=None,duration=None,source='No MP4 movie creation_time found')


def video_folder(raw):
    if not isinstance(raw,str) or not raw.strip() or not Path(raw).expanduser().is_dir():raise ValueError('Enter an existing SX video folder.')
    return Path(raw).expanduser().resolve()


def catalog(folder):
    root=video_folder(folder)
    return [p.name for p in sorted(root.iterdir(),key=lambda p:p.name.casefold()) if p.is_file() and p.suffix.lower() in ('.mp4','.mov')]


def playback_seconds(raw):
    value=str(raw).strip()
    if not re.fullmatch(r'\d+(?::\d{1,2}){0,2}(?:\.\d+)?',value):raise ValueError('Playback: use MM:SS, HH:MM:SS or seconds (fractions allowed).')
    parts=[float(x) for x in value.split(':')]
    if len(parts)>1 and any(v>=60 for v in parts[1:]):raise ValueError('Playback minutes/seconds after a colon must be below 60.')
    result=sum(v*60**i for i,v in enumerate(reversed(parts)))
    if not math.isfinite(result):raise ValueError('Playback time is too large.')
    return result


def options(raw):
    raw=raw or {}
    if not isinstance(raw,dict):raise ValueError('Invalid video settings.')
    basis=raw.get('clockBasis','utc')
    if basis not in ('utc','brisbane'):raise ValueError('Choose UTC or Brisbane camera-clock metadata.')
    try:correction=float(raw.get('correctionSeconds',0))
    except (TypeError,ValueError):raise ValueError('Clock correction must be seconds.')
    if not math.isfinite(correction) or abs(correction)>604800:raise ValueError('Clock correction must be within ±7 days.')
    return dict(folder=str(raw.get('folder','')),clockBasis=basis,correctionSeconds=correction)


def resolve(row,settings):
    settings=options(settings);root=video_folder(settings['folder']);name=row.get('video')
    if not isinstance(name,str) or not name or Path(name).name!=name or '/' in name or '\\' in name:raise ValueError('Choose a video filename from the video folder.')
    path=(root/name).resolve()
    if path.parent!=root or not path.is_file() or path.suffix.lower() not in ('.mp4','.mov'):raise ValueError('Selected video is not available in this folder.')
    seconds=playback_seconds(row.get('playback',''))
    override=str(row.get('recordingStart','')).strip()
    try:metadata=read_metadata(path)
    except (OSError,ValueError) as error:
        if not override:raise ValueError(str(error)+' Enter a recording-start override for this video.') from error
        metadata=dict(start=None,duration=None,source='Metadata unreadable: '+str(error))
    if override:
        try:start=datetime.fromisoformat(override.replace('Z','+00:00'))
        except ValueError:raise ValueError('Recording start override: use YYYY-MM-DDTHH:MM:SS, optionally with a timezone.')
        if start.tzinfo is None:start=start.replace(tzinfo=BRISBANE)
        source='Manual recording-start override'
    else:
        if not metadata['start']:raise ValueError('Recording time is missing in '+name+'. Enter a recording-start override; filesystem dates are not used.')
        start=datetime.fromisoformat(metadata['start'])
        if settings['clockBasis']=='brisbane':start=start.replace(tzinfo=BRISBANE)
        source=metadata['source']+(' · interpreted as Brisbane camera wall time' if settings['clockBasis']=='brisbane' else ' · UTC')
    if metadata['duration'] is not None and seconds>metadata['duration']+.001:raise ValueError('Playback exceeds this video’s '+str(round(metadata['duration'],3))+' second duration.')
    result=start+timedelta(seconds=seconds+settings['correctionSeconds'])
    return dict(video=name,playbackSeconds=seconds,recordingStart=start.astimezone(BRISBANE).isoformat(timespec='milliseconds'),
        correctionSeconds=settings['correctionSeconds'],timestamp=result.astimezone(BRISBANE).isoformat(timespec='milliseconds'),
        source=source,durationSeconds=metadata['duration'])
