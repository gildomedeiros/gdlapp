"""VT Tools subtitle styling/layout reused with SX movie clocks and VTC logs."""
from bisect import bisect_right
from datetime import datetime
from html import escape
import json
from pathlib import Path
import re
from zipfile import ZipFile,ZIP_DEFLATED

FIELDS=[('video','Video filename / playback'),('clock','Calculated log time'),('age','GPS age'),
    ('ride','Wave riding'),('goto','GoTo / activation'),('pan','Continuous panning'),('aim','Aiming state'),
    ('pan_position','Pan target / actual error (metres)'),('tilt_position','Tilt target / actual error (metres)'),
    ('framing','Framing / magnitude'),('zoom','Zoom / estimated frame width'),('movement','Surfer distance / speed'),
    ('lora','LoRa signal'),('coordinates','Surfer / camera coordinates'),('source','GPS packet / source')]
IDS=[key for key,_ in FIELDS]
PREFIXES=dict(ride=('Wave ride:',),goto=('GoTo:','Last GoTo activation:'),pan=('Continuous pan:',),aim=('Aim:',),
    pan_position=('Pan target:','Actual pan error:'),tilt_position=('Tilt target:','Actual tilt error:'),
    framing=('Framing:','Applied offset:','Magnitude:'),zoom=('Sideways / estimated frame width:','Surfer–base distance:','Zoom mode:','Zoom readback:','Zoom band:'),
    movement=('Surfer since previous packet:'),lora=('LoRa:',),coordinates=('Surfer:','Camera:'),source=('Source:',))


def validate_fields(fields):
    if not isinstance(fields,list) or not fields or any(key not in IDS for key in fields):raise ValueError('Select at least one valid subtitle field.')
    return [key for key in IDS if key in fields]


def styled(text):
    # Same card foreground colours; ASS layout supplies the translucent panel.
    color='#17324d';bold=False
    if text.startswith(('Wave ride: YES','Continuous pan: ACTIVE','Aim:')):color='#08752e';bold=True
    if text.startswith(('GoTo: ACTIVE','Pan target:','Tilt target:','Actual pan error:','Actual tilt error:')):color='#a94700';bold=True
    if text.startswith(('Framing:','Applied offset:','Magnitude:','Zoom','Sideways /')):color='#7838a5';bold=True
    if 'unavailable' in text or text.startswith(('Wave ride: STALE','Continuous pan: UNKNOWN')):color='#64748b'
    return f'<font color="{color}" face="Arial" size="28">'+('<b>' if bold else '')+escape(str(text),quote=False)+('</b>' if bold else '')+'</font>'


def srt_time(ms):
    return f'{ms//3600000:02d}:{ms//60000%60:02d}:{ms//1000%60:02d},{ms%1000:03d}'


def clip_label(name,ms):
    stem=Path(name).stem;match=re.search(r'(\d+)$',stem);clip=str(int(match[1])) if match else stem
    seconds=ms//1000
    return '['+clip+','+(f'{seconds//3600:02d}:' if seconds>=3600 else '')+f'{seconds//60%60:02d}:{seconds%60:02d}'+']'


class Index:
    def __init__(self,sessions,fields):
        self.sessions=[(s,[p['timeMs'] for p in s['points']]) for s in sessions]
        self.fields=validate_fields(fields);self.cache={}

    def render(self,epoch,video,playback):
        from vtc_map import clock
        sessions=[(s,times) for s,times in self.sessions if s['startMs'] is not None and s['startMs']<=epoch<=s['endMs']]
        reason=None;point=None
        if not sessions:reason='no log session covers this video time'
        elif len(sessions)>1:reason='overlapping log sessions; select a log override'
        else:
            s,times=sessions[0];i=bisect_right(times,epoch)-1
            if i<0:reason='no earlier accepted GPS receipt'
            else:point=s['points'][i]
        dynamic=dict(video=['Video: '+clip_label(video,playback)],clock=['Log time: '+clock(epoch)],
            age=['GPS age: '+f'{(epoch-point["timeMs"])/1000:.3f} s' if point else 'GPS age: unavailable — '+str(reason)])
        static={}
        if point:
            key=(point['source'],point['sourceLine'])
            if key not in self.cache:
                self.cache[key]={field:[text for text in point['details'] if text.startswith(prefix)] for field,prefix in PREFIXES.items()}
                self.cache[key]['source'].insert(0,'GPS #'+str(point['gps'].get('sequence','?'))+' · received '+point['timestamp'])
            static=self.cache[key]
        lines=[]
        for field in self.fields:lines.extend(dynamic.get(field) or static.get(field) or [dict(FIELDS)[field]+': unavailable — '+str(reason or 'no earlier report')])
        return '\n'.join(styled(line) for line in lines),reason


def export_video(files,video_settings,row,fields,output,progress=lambda text:None,cancel=lambda:None):
    from vtc_video import resolve
    from vtc_map import load_file
    from vtc_subtitle_layout import HEADER,AssWriter,card_events
    resolved=resolve(dict(row,playback='0'),video_settings)
    duration=resolved['durationSeconds']
    if duration is None or duration<=0:raise ValueError('Video duration is missing from MP4 metadata; cannot create timed subtitles.')
    total=round(duration*1000);start=datetime.fromisoformat(resolved['timestamp']).timestamp()*1000
    sessions=[]
    for i,path in enumerate(files):
        cancel();progress(f'Reading full log {i+1}/{len(files)}: {Path(path).name}')
        s=load_file(Path(path))
        if s['startMs'] is not None and s['startMs']<start+total and s['endMs']>=start:sessions.append(s)
    index=Index(sessions,fields);bounds=set(range(0,total,100));bounds.add(total)
    for s in sessions:
        for time in [s['startMs'],s['endMs']+1]+[p['timeMs'] for p in s['points']]:
            offset=round(time-start)
            if 0<offset<total:bounds.add(offset)
    bounds=sorted(bounds);output=Path(output);output.mkdir(parents=True,exist_ok=False)
    stem=Path(resolved['video']).stem;srt=output/(stem+'.srt');ass=output/(stem+'.ass');unavailable=0
    with srt.open('x',encoding='utf-8',newline='\n') as sw,ass.open('x',encoding='utf-8-sig',newline='\n') as aw:
        aw.write(HEADER);ass_writer=AssWriter(aw)
        for i,(a,b) in enumerate(zip(bounds,bounds[1:]),1):
            if i%100==1:cancel();progress(f'Exporting {resolved["video"]}: {a/1000:.1f} / {duration:.1f} s')
            text,reason=index.render(start+a,resolved['video'],a);unavailable+=bool(reason)
            sw.write(f'{i}\n{srt_time(a)} --> {srt_time(b)}\n'+r'{\an7}'+text+'\n\n')
            ass_writer.write(card_events(a,b,text,''))
        ass_writer.flush()
    report=dict(video=resolved,fields=index.fields,cues=len(bounds)-1,unavailableCues=unavailable,sources=[s['source'] for s in sessions],
        timing='Recording start + playback + correction. Last accepted GPS receipt at or before each cue; full logs, no map filters. 100 ms age/clock refresh plus every GPS/session boundary.',
        styling='VT Tools ASS layout: translucent top-left card. SRT colour/bold tags depend on player support. ASS boundaries round up to centiseconds (at most 9 ms).')
    report_path=output/'export-report.json';report_path.write_text(json.dumps(report,indent=2,ensure_ascii=False),encoding='utf-8')
    archive=output/(stem+'-subtitles.zip')
    with ZipFile(archive,'x',compression=ZIP_DEFLATED) as zipped:
        for path in (srt,ass,report_path):zipped.write(path,path.name)
    return report,[archive,srt,ass,report_path]
