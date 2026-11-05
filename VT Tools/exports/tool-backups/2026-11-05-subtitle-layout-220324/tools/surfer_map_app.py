"""Persistent, loopback-only map application with selection-scoped capture APIs."""
from argparse import Namespace
from collections import OrderedDict
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import math
import mimetypes
from pathlib import Path
import re
import threading
from urllib.parse import urlsplit
import uuid
from surfer_subtitles import FIELDS, FIELD_IDS, validate_fields


APP_DIR = Path(__file__).resolve().parent.parent / 'exports' / 'surfer-map-app'
MODES = ('rides', 'timestamp', 'pitch', 'framing')


class Cancelled(Exception):
    pass


def defaults(app_dir):
    return dict(settings=dict(folder='', video_folder='E:/DCIM/100MEDIA', output=str(app_dir),
                recursive=False, utc_offset='+10:00', gap_red_ms=1000, ffmpeg=''), mode='rides', subtitle_fields=list(FIELD_IDS),
        presets=dict(
            rides=dict(scope='first', number_of_rides=3, ride_numbers='', before=15, after=15, interval=0, max_samples='', auto_fov=False,
                       filter_pitch=False,pitch_min=-40,pitch_max=-34,filter_distance=False,distance_min=25,distance_max=40),
            timestamp=dict(at='', next=35, auto_fov=False),
            pitch=dict(pitch_min=-40, pitch_max=-34, distance_min=25, distance_max=40,
                       before=125, after=125, interval=1, max_samples=10, auto_fov=False),
            framing=dict(sample_bands='0 20 30 40', sample_count=3, before=5, after=20,
                         interval=5, sample_max_checks=0)))


def number(value, name, minimum=0, integer=False):
    try:
        n=float(value)
    except (TypeError, ValueError):
        raise ValueError(f'{name} must be a number.') from None
    if isinstance(value, bool) or not math.isfinite(n) or n<minimum or (integer and n!=int(n)):
        raise ValueError(f'{name} must be a finite {"whole " if integer else ""}number of at least {minimum}.')
    return int(n) if integer else n


def normalize_settings(settings):
    if not isinstance(settings, dict):raise ValueError('Invalid settings.')
    s={}
    for key in ('folder','video_folder','output','utc_offset','ffmpeg'):
        value=settings.get(key, '')
        if not isinstance(value,str):raise ValueError(f'{key} must be text.')
        s[key]=value.strip()
    if not s['folder']:raise ValueError('Enter the log folder first.')
    if not s['output']:raise ValueError('Enter an output folder.')
    s['recursive']=settings.get('recursive') is True
    s['gap_red_ms']=number(settings.get('gap_red_ms',1000),'Gap highlight')
    from telemetry_around import offset_timezone
    offset_timezone(s['utc_offset'])
    return s


def normalize_request(request):
    """Accept data only, never shell fragments or arbitrary Python arguments."""
    if not isinstance(request, dict):raise ValueError('Expected a selection object.')
    mode=request.get('mode')
    if mode not in MODES:raise ValueError('Choose rides, timestamp, pitch/distance or framing samples.')
    s=normalize_settings(request.get('settings', {}))
    selection=request.get('selection', {})
    if not isinstance(selection, dict):raise ValueError('Invalid settings.')
    q=dict(auto_fov=selection.get('auto_fov') is True)
    if mode=='timestamp':
        at=selection.get('at','')
        if not isinstance(at,str) or not at.strip():raise ValueError('Enter a timestamp.')
        q.update(at=at.strip(), next=number(selection.get('next',35),'Following packets',integer=True))
    else:
        for field in ('before','after','interval'):
            q[field]=number(selection.get(field),field.capitalize())
        if mode in ('rides','pitch'):
            maximum=selection.get('max_samples')
            q['max_samples']=None if maximum in (None,'') else number(maximum,'Maximum samples',1,True)
        if mode=='rides':
            scope=selection.get('scope','all')
            if scope not in ('all','first','specific'):raise ValueError('Invalid ride selection.')
            q['scope']=scope
            if scope=='first':q['number_of_rides']=number(selection.get('number_of_rides'),'Number of rides',1,True)
            if scope=='specific':
                raw=selection.get('ride_numbers','')
                if not isinstance(raw,str):raise ValueError('Enter ride numbers separated by commas.')
                q['ride_numbers']=sorted({number(v,'Ride number',1,True) for v in re.split(r'[,\s]+',raw.strip()) if v})
                if not q['ride_numbers']:raise ValueError('Enter at least one ride number.')
            for kind in ('pitch','distance'):
                enabled=selection.get('filter_'+kind) is True
                q['filter_'+kind]=enabled
                if enabled:
                    for edge in ('min','max'):
                        field=kind+'_'+edge
                        q[field]=number(selection.get(field),field.replace('_',' '),-180 if kind=='pitch' else 0)
                    if q[kind+'_min']>q[kind+'_max']:raise ValueError(kind.capitalize()+' minimum must not exceed maximum.')
        elif mode=='pitch':
            for field in ('pitch_min','pitch_max','distance_min','distance_max'):
                q[field]=number(selection.get(field),field.replace('_',' '),-180 if field.startswith('pitch') else 0)
            if q['pitch_min']>q['pitch_max'] or q['distance_min']>q['distance_max']:
                raise ValueError('Range minimum must not exceed maximum.')
            if q['max_samples'] is None:q['max_samples']=50
        else:
            raw=selection.get('sample_bands','')
            if not isinstance(raw,str):raise ValueError('Enter distance edges separated by spaces.')
            edges=[number(v,'Distance edge') for v in re.split(r'[,\s]+',raw.strip()) if v]
            if len(edges)<2 or any(b<=a for a,b in zip(edges,edges[1:])):raise ValueError('Distance edges must increase, for example: 0 20 30 40.')
            q.update(sample_bands=edges, sample_count=number(selection.get('sample_count',3),'Samples per combination',1,True),
                     sample_max_checks=number(selection.get('sample_max_checks',0),'Maximum checks',integer=True))
    return dict(mode=mode,settings=s,selection=q)


class MapApp:
    def __init__(self, args=None, app_dir=APP_DIR):
        self.app_dir=Path(app_dir)
        self.preferences=self.app_dir/'preferences.json'
        self.config=defaults(self.app_dir)
        try:
            saved=json.loads(self.preferences.read_text(encoding='utf-8'))
            self.config['settings'].update({k:v for k,v in saved.get('settings',{}).items() if k in self.config['settings']})
            for mode in MODES:self.config['presets'][mode].update(saved.get('presets',{}).get(mode,{}))
            if saved.get('mode') in MODES:self.config['mode']=saved['mode']
            if 'subtitle_fields' in saved:self.config['subtitle_fields']=validate_fields(saved['subtitle_fields'])
        except (OSError,ValueError,TypeError,AttributeError):
            pass
        self.initial_selection=False
        if args is not None:self.seed_cli(args)
        self.lock=threading.RLock()
        self.jobs=OrderedDict()
        self.snapshots=OrderedDict()
        self.current=None
        self.active=None
        self.log_cache=None
        self.video_cache=None
        self.log_info=None
        self.cache_reads=0
        self.worker=None
        self.subtitle_exports=OrderedDict()

    def seed_cli(self,args):
        supplied=getattr(args,'provided_options',set())
        for key in self.config['settings']:
            if '--'+key.replace('_','-') in supplied:
                self.config['settings'][key]=getattr(args,key)
        mode='framing' if args.get_fov_samples else 'rides' if args.all_rides or args.number_of_rides is not None else 'pitch' if args.pitch_range else 'timestamp' if args.at else self.config['mode']
        self.config['mode']=mode
        q=self.config['presets'][mode]
        self.initial_selection=bool(args.folder and (args.get_fov_samples or args.all_rides or args.number_of_rides is not None or args.pitch_range or args.at))
        if not self.initial_selection:return
        q['auto_fov']=args.auto_fov
        if mode=='timestamp':q.update(at=args.at,next=args.next)
        else:
            q.update(before=args.sample_before,after=args.sample_after,interval=args.sample_interval)
            if mode in ('rides','pitch'):q['max_samples']=args.max_samples
            if mode=='rides':
                q.update(scope='first' if args.number_of_rides else 'all',number_of_rides=args.number_of_rides or 3)
                for kind in ('pitch','distance'):
                    limits=getattr(args,kind+'_range')
                    q['filter_'+kind]=limits is not None
                    if limits:q.update({kind+'_min':min(limits),kind+'_max':max(limits)})
            elif mode=='pitch':
                q.update(pitch_min=min(args.pitch_range),pitch_max=max(args.pitch_range))
                q.update(distance_min=min(args.distance_range) if args.distance_range else 0,
                         distance_max=max(args.distance_range) if args.distance_range else 100000)
            else:q.update(sample_count=args.sample_count,sample_bands=' '.join(map(str,args.sample_bands)),sample_max_checks=args.sample_max_checks)

    def state(self):
        with self.lock:
            return dict(config=self.config,current=self.current,job=self.public_job(self.active),logs=self.log_info,
                        subtitleFields=[dict(id=key,label=label) for key,label in FIELDS],
                        subtitleResult=next(reversed(self.subtitle_exports.values()))['result'] if self.subtitle_exports else None)

    def public_job(self,ident):
        job=self.jobs.get(ident)
        return {k:v for k,v in job.items() if k!='cancel'} if job else None

    def progress(self,ident,message):
        with self.lock:self.jobs[ident]['message']=message

    def check_cancel(self,ident):
        if self.jobs[ident]['cancel'].is_set():raise Cancelled()

    def start(self,request):
        request=normalize_request(request)
        with self.lock:
            if self.active and self.jobs[self.active]['status']=='running':
                raise ValueError('A job is still running. Cancel it or wait for it to finish.')
            ident=uuid.uuid4().hex
            self.jobs[ident]=dict(id=ident,status='running',message='Preparing selection…',cancel=threading.Event())
            self.active=ident
            self.worker=threading.Thread(target=self.run,args=(ident,request),daemon=True)
            self.worker.start()
            return self.public_job(ident)

    def start_subtitles(self,request):
        if not isinstance(request,dict):raise ValueError('Expected an export object.')
        settings=normalize_settings(request.get('settings',{}))
        fields=validate_fields(request.get('fields'))
        if not Path(settings['video_folder']).is_dir() or not settings['video_folder']:
            raise ValueError('Enter an existing video / SRT folder.')
        with self.lock:
            if self.active and self.jobs[self.active]['status']=='running':
                raise ValueError('A job is still running. Cancel it or wait for it to finish.')
            ident=uuid.uuid4().hex
            self.jobs[ident]=dict(id=ident,kind='subtitles',status='running',message='Preparing subtitle export…',cancel=threading.Event())
            self.active=ident
            self.worker=threading.Thread(target=self.run_subtitles,args=(ident,settings,fields),daemon=True)
            self.worker.start()
            return self.public_job(ident)

    def run_subtitles(self,ident,settings,fields):
        output=Path(settings['output']).expanduser().resolve()/'subtitles'/ident
        try:
            from surfer_subtitles import PacketIndex,export_folder
            packets,drones,_,tz=self.load_logs(settings,ident)
            self.check_cancel(ident)
            self.progress(ident,'Preparing all received GPS packets for subtitles…')
            index=PacketIndex(packets,drones,fields,settings['gap_red_ms'])
            report,files=export_folder(Path(settings['video_folder']).expanduser().resolve(),output,index,tz,
                progress=lambda message:self.progress(ident,message),check_cancel=lambda:self.check_cancel(ident))
            self.check_cancel(ident)
            result=dict(output=str(output),totals=report['totals'],issues=[row for row in report['files'] if row['status']!='exported'],
                        warnings=[dict(video=row['video'],message=row['warning']) for row in report['files'] if row.get('warning')],
                        exports=[dict(name=p.name,url=f'/api/subtitles/{ident}/files/{i}') for i,p in enumerate(files)])
            with self.lock:
                self.config['settings']=dict(settings)
                self.config['subtitle_fields']=fields
                self.save_preferences()
                self.subtitle_exports[ident]=dict(files=files,result=result)
                while len(self.subtitle_exports)>12:self.subtitle_exports.popitem(last=False)
                totals=report['totals']
                self.jobs[ident].update(status='done',result=result,
                    message=f'Subtitles ready: {totals["exported"]}/{totals["videos"]} videos exported; {totals["skipped"]} skipped. Current map kept.')
                while len(self.jobs)>20:self.jobs.popitem(last=False)
        except Cancelled:
            with self.lock:self.jobs[ident].update(status='cancelled',message=f'Export cancelled. Completed files, if any, remain in {output}. Current map kept.')
        except Exception as error:
            with self.lock:self.jobs[ident].update(status='error',message=str(error) or type(error).__name__)

    def save_preferences(self):
        self.app_dir.mkdir(parents=True,exist_ok=True)
        temp=self.preferences.with_suffix('.tmp')
        temp.write_text(json.dumps(self.config,indent=2),encoding='utf-8')
        temp.replace(self.preferences)

    def cancel(self,ident):
        with self.lock:
            if ident not in self.jobs:raise ValueError('Unknown selection job.')
            if self.jobs[ident]['status']=='running':
                self.jobs[ident]['cancel'].set()
                self.jobs[ident]['message']='Cancelling after the current stage/frame…'
            return self.public_job(ident)

    def load_logs(self,s,ident):
        from surfer_gps_map import load_packets,select_packets,pair_drones,drone_next_bearing
        from telemetry_around import offset_timezone
        folder=Path(s['folder']).expanduser().resolve()
        if not folder.is_dir():raise ValueError('Log folder does not exist: '+str(folder))
        files=sorted(folder.rglob('*.jsonl') if s['recursive'] else folder.glob('*.jsonl'))
        if not files:raise ValueError('No .jsonl files found in the log folder.')
        key=(str(folder),s['utc_offset'],s['recursive'],tuple((str(p),p.stat().st_size,p.stat().st_mtime_ns) for p in files))
        tz=offset_timezone(s['utc_offset'])
        if self.log_cache and self.log_cache[0]==key:
            self.progress(ident,'Using loaded logs…')
            return (*self.log_cache[1:],tz)
        self.progress(ident,f'Loading {len(files)} log files…')
        packets,drones=load_packets(folder,s['recursive'],tz)
        self.check_cancel(ident)
        valid=[dict(p) for p in packets if all(isinstance(p.get(k),(int,float)) and not isinstance(p[k],bool)
               and math.isfinite(p[k]) and abs(p[k])<=limit for k,limit in [('latitude',90),('longitude',180)])]
        if not valid:raise ValueError('No valid received surfer positions found.')
        self.progress(ident,'Pairing real surfer and drone positions…')
        _,prepared=select_packets(valid,valid[0]['receivedTimestamp'],len(valid)-1,tz)
        pair_drones(prepared,drones)
        for i,p in enumerate(prepared):p['droneNextBearingDeg']=drone_next_bearing(prepared,i)
        rides=sorted({(start,p['sourceFile']) for p in packets for start in p.get('waveRideStartTimesMs',[])})
        with self.lock:
            self.log_info=dict(folder=str(folder),packets=len(packets),first=packets[0]['receivedTimestamp'],last=packets[-1]['receivedTimestamp'],
                rides=[dict(number=i,timestamp=datetime.fromtimestamp(t/1000,tz).isoformat(timespec='milliseconds'),source=Path(source).name) for i,(t,source) in enumerate(rides,1)])
        self.log_cache=(key,packets,drones,prepared)
        self.cache_reads+=1
        return packets,drones,prepared,tz

    def video_timeline(self,s,tz):
        from telemetry_around import load_video_timeline
        if not s['video_folder']:return ([],[])
        folder=Path(s['video_folder']).expanduser()
        # SRTs only; loading a map never decodes videos unless detection was requested.
        files=sorted(folder.glob('*.SRT'))+sorted(folder.glob('*.srt')) if folder.is_dir() else []
        key=(str(folder.resolve()),s['utc_offset'],tuple(sorted({(str(p),p.stat().st_size,p.stat().st_mtime_ns) for p in files})))
        if not self.video_cache or self.video_cache[0]!=key:self.video_cache=(key,load_video_timeline(folder,tz))
        return self.video_cache[1]

    def remember(self,request):
        q=dict(request['selection'])
        for field in ('ride_numbers','sample_bands'):
            if isinstance(q.get(field),list):q[field]=' '.join(str(v) for v in q[field])
        with self.lock:
            self.config['mode']=request['mode']
            self.config['settings']=dict(request['settings'])
            self.config['presets'][request['mode']].update(q)
            self.save_preferences()

    def run(self,ident,request):
        try:
            from surfer_gps_map import select_packets,pair_drones,add_video_playback,write_outputs
            from surfer_pitch_samples import run_log_samples,annotate_ride_windows
            s,q,mode=request['settings'],request['selection'],request['mode']
            packets,drones,prepared,tz=self.load_logs(s,ident)
            self.check_cancel(ident)
            self.progress(ident,'Selecting positions from the logs…')
            timeline=self.video_timeline(s,tz)
            output=Path(s['output']).expanduser().resolve()/'selections'/ident
            output.mkdir(parents=True,exist_ok=True)
            cache_output=Path(s['output']).expanduser().resolve()
            a=Namespace(output=str(output),video_folder=s['video_folder'],ffmpeg=s['ffmpeg'] or None,
                        gap_red_ms=s['gap_red_ms'],number_of_rides=q.get('number_of_rides'),ride_numbers=q.get('ride_numbers'),
                        sample_before=q.get('before',0),sample_after=q.get('after',0),sample_interval=q.get('interval',0),
                        max_samples=q.get('max_samples'),pitch_range=[q['pitch_min'],q['pitch_max']] if mode=='pitch' or q.get('filter_pitch') else None,
                        distance_range=[q['distance_min'],q['distance_max']] if mode=='pitch' or q.get('filter_distance') else None)
            if mode in ('rides','pitch'):
                points,_,description=run_log_samples(packets,drones,a,tz,all_rides=mode=='rides',prepared_points=prepared,timeline_data=timeline)
                target=points[0]['time_ms'] if points else None
            elif mode=='timestamp':
                target,points=select_packets([dict(p) for p in packets],q['at'],q['next'],tz)
                pair_drones(points,drones)
                add_video_playback(points,s['video_folder'],tz,timeline)
                description=f'Timestamp {q["at"]}; starting packet plus {q["next"]} following packets. {len(points)} actual positions.'
            else:
                from surfer_fov_samples import run_samples
                a.sample_count=q['sample_count'];a.sample_bands=q['sample_bands'];a.sample_seed=0;a.sample_max_checks=q['sample_max_checks']
                points,_=run_samples(packets,drones,a,tz,prepared_points=prepared,timeline_data=timeline,
                    progress=lambda message:self.progress(ident,message),check_cancel=lambda:self.check_cancel(ident),cache_output=cache_output)
                rides=sorted({(t,p['sourceFile']) for p in prepared for t in p.get('waveRideStartTimesMs',[])})
                annotate_ride_windows(points,rides,q['before'],q['after'],tz)
                target=points[0]['time_ms'] if points else None
                description=f'Framing samples: {len(points)} of {3*(len(q["sample_bands"])-1)*q["sample_count"]} requested; missing combinations are allowed.'
            self.check_cancel(ident)
            self.remember(request)
            if not points:
                with self.lock:self.jobs[ident].update(status='empty',message='No matching positions. The current map has been kept.')
                return
            if q.get('auto_fov') and mode!='framing':
                from surfer_map_capture import precompute_positions
                precompute_positions(points,output,s['video_folder'],s['ffmpeg'] or None,
                    progress=lambda message:self.progress(ident,message),check_cancel=lambda:self.check_cancel(ident),cache_output=cache_output)
            self.check_cancel(ident)
            self.progress(ident,f'Drawing {len(points)} positions…')
            write_outputs(points,target,output,tz,s['gap_red_ms'],selection_info=description,initial_ride='first' if mode=='rides' else 'all')
            self.check_cancel(ident)
            snapshot=dict(id=ident,points={p['point']:p for p in points},output=output,cache_output=cache_output,
                          settings=s,images={},lock=threading.Lock())
            result=dict(id=ident,mapUrl=f'/maps/{ident}/',count=len(points),mode=mode,description=description,output=str(output),
                        videoMapped=sum(p.get('videoFrameTimestamp') is not None for p in points),videoWarnings=timeline[1],
                        exports=[dict(name=name,url=f'/maps/{ident}/export/{name}') for name in
                            ('surfer-gps.html','surfer-gps.csv','surfer-gps.svg','surfer-gps.kml','fov-samples.html','fov-samples.xlsx') if (output/name).is_file()])
            if mode=='timestamp':result['label']=f'{q["at"]} · next {q["next"]} packets'
            elif mode=='pitch':result['label']=f'Pitch {q["pitch_min"]:g} to {q["pitch_max"]:g}° AND distance {q["distance_min"]:g}–{q["distance_max"]:g} m · {q["before"]:g}s before / {q["after"]:g}s after rides'
            elif mode=='framing':result['label']=description
            else:
                ride_label='All rides' if q['scope']=='all' else f'First {q["number_of_rides"]} rides' if q['scope']=='first' else 'Rides '+', '.join(map(str,q['ride_numbers']))
                result['label']=f'{ride_label} · {q["before"]:g}s before / {q["after"]:g}s after · '+(f'{q["interval"]:g}s granularity' if q['interval'] else 'every packet')
            with self.lock:
                self.check_cancel(ident)
                self.snapshots[ident]=snapshot
                while len(self.snapshots)>12:self.snapshots.popitem(last=False)
                self.current=result
                self.jobs[ident].update(status='done',message=f'{len(points)} positions ready.',result=result)
                while len(self.jobs)>20:self.jobs.popitem(last=False)
        except Cancelled:
            with self.lock:self.jobs[ident].update(status='cancelled',message='Cancelled. The current map has been kept.')
        except Exception as error:
            with self.lock:self.jobs[ident].update(status='error',message=str(error) or type(error).__name__)

    def snapshot(self,ident):
        with self.lock:return self.snapshots.get(ident)

    def fov_results(self,snapshot):
        identities={(p.get('sourceFile'),p.get('receivedTimestamp'),p.get('videoFrameTimestamp')) for p in snapshot['points'].values()}
        results=[]
        for path in (snapshot['cache_output']/'person-positions').glob('*.json'):
            try:
                result=json.loads(path.read_text(encoding='utf-8'))
                if (result.get('sourceFile'),result.get('gpsTimestamp'),result.get('frameTimestamp')) in identities:results.append(result)
            except (ValueError,OSError):pass
        return results

    def video_frame(self,snapshot,request):
        from surfer_video_frames import capture_video_frame
        from telemetry_selected_frames import ffmpeg_path
        point=snapshot['points'].get(request.get('point'))
        if point is None:raise ValueError('Unknown surfer position in this map.')
        s=snapshot['settings']
        with snapshot['lock']:
            dest,result=capture_video_frame(point,request,s['video_folder'],snapshot['output'],ffmpeg_path(s['ffmpeg'] or None))
            url=f'/maps/{snapshot["id"]}/frames/{dest.name}'
            snapshot['images'][url]=dest
            return dict(result,url=url)

    def wave_results(self,snapshot):
        from surfer_wave_orientation import wave_results
        return wave_results(snapshot['points'].values(),snapshot['cache_output'])

    def wave(self,snapshot,request):
        from surfer_wave_orientation import analyze_wave, ignore_wave
        from telemetry_selected_frames import ffmpeg_path
        point=snapshot['points'].get(request.get('point'))
        if point is None:raise ValueError('Unknown surfer position in this map.')
        s=snapshot['settings']
        with snapshot['lock']:
            if request.get('action') == 'ignore':
                return ignore_wave(point,snapshot['cache_output'])
            return analyze_wave(point,request,s['video_folder'],snapshot['output'],snapshot['cache_output'],ffmpeg_path(s['ffmpeg'] or None))

    def capture(self,snapshot,request,detect=False):
        from surfer_map_capture import capture_frame, capture_variant
        from telemetry_selected_frames import ffmpeg_path
        point=snapshot['points'].get(request.get('point'))
        if point is None:raise ValueError('Unknown surfer position in this map.')
        s=snapshot['settings']
        with snapshot['lock']:
            if detect:
                saved=next((r for r in self.fov_results(snapshot) if r.get('sourceFile')==point.get('sourceFile') and r.get('gpsTimestamp')==point.get('receivedTimestamp') and r.get('frameTimestamp')==point.get('videoFrameTimestamp')),None)
                if saved:return saved
            if detect:
                dest=capture_frame(point,s['video_folder'],snapshot['output'],ffmpeg_path(s['ffmpeg'] or None),s['gap_red_ms'],annotate=False)
                from surfer_fov import FovEstimator
                estimator=FovEstimator(Path(__file__).with_name('models')/'yolo11n-seg.pt')
                return estimator.classify_person(dest,point,snapshot['cache_output'])
            variant=request.get('variant','telemetry')
            dest=capture_variant(point,s['video_folder'],snapshot['output'],ffmpeg_path(s['ffmpeg'] or None),s['gap_red_ms'],variant)
            suffix='' if variant=='telemetry' else '-'+variant
            url=f'/maps/{snapshot["id"]}/frames/{point["point"]}{suffix}.png'
            snapshot['images'][url]=dest
            return dict(url=url,filename=dest.name,variant=variant,timestamp=point['videoFrameTimestamp'],saved=str(dest))


def create_server(app,port=0):
    class Handler(BaseHTTPRequestHandler):
        def reply(self,status,data,kind='application/json; charset=utf-8',download=None):
            if not isinstance(data,bytes):data=json.dumps(data,ensure_ascii=False).encode('utf-8')
            self.send_response(status)
            self.send_header('Content-Type',kind)
            self.send_header('Content-Length',str(len(data)))
            self.send_header('Cache-Control','no-store')
            self.send_header('X-Content-Type-Options','nosniff')
            self.send_header('X-Frame-Options','SAMEORIGIN')
            if download:self.send_header('Content-Disposition',f'attachment; filename="{download}"')
            self.end_headers()
            try:self.wfile.write(data)
            except (BrokenPipeError,ConnectionResetError):pass

        def allowed(self,post=False):
            origin='http://'+self.server.address
            return self.headers.get('Host')==self.server.address and (self.headers.get('Origin')==origin if post else self.headers.get('Origin') in (None,origin))

        def do_GET(self):
            if not self.allowed():return self.reply(403,dict(error='Local origin required.'))
            path=urlsplit(self.path).path
            if path=='/':return self.reply(200,Path(__file__).with_suffix('.html').read_bytes(),'text/html; charset=utf-8')
            if path=='/api/state':return self.reply(200,app.state())
            export_match=re.fullmatch(r'/api/subtitles/([a-f0-9]{32})/files/(\d{1,6})',path)
            if export_match:
                with app.lock:
                    batch=app.subtitle_exports.get(export_match[1])
                    files=batch['files'] if batch else []
                    index=int(export_match[2])
                    file=files[index] if index<len(files) else None
                if file and file.is_file():
                    from urllib.parse import quote
                    kind='application/zip' if file.suffix=='.zip' else 'application/json; charset=utf-8' if file.suffix=='.json' else 'application/x-subrip; charset=utf-8'
                    return self.reply(200,file.read_bytes(),kind,download=quote(file.name,safe='._-'))
                return self.reply(404,dict(error='Subtitle download not found.'))
            if path.startswith('/api/jobs/'):
                with app.lock:job=app.public_job(path.rsplit('/',1)[1])
                return self.reply(200 if job else 404,job or dict(error='Unknown job.'))
            match=re.fullmatch(r'/maps/([a-f0-9]{32})/(.*)',path)
            if match:
                snapshot=app.snapshot(match[1])
                if not snapshot:return self.reply(410,dict(error='This map has expired. Generate the selection again.'))
                suffix=match[2]
                if not suffix:return self.reply(200,(snapshot['output']/'surfer-gps.html').read_bytes(),'text/html; charset=utf-8')
                if suffix=='fov-results':return self.reply(200,app.fov_results(snapshot))
                if suffix=='wave-results':return self.reply(200,app.wave_results(snapshot))
                if path in snapshot['images']:return self.reply(200,snapshot['images'][path].read_bytes(),'image/png')
                if suffix.startswith('export/'):
                    name=suffix[len('export/'):]
                    allowed={'surfer-gps.html','surfer-gps.csv','surfer-gps.svg','surfer-gps.kml','fov-samples.html','fov-samples.xlsx'}
                    # Contact sheets can load only their generated PNGs and workbook.
                    frame=bool(re.fullmatch(r'sample-frames/sample-\d+-S\d+\.png',name))
                    file=snapshot['output']/name
                    if (name in allowed or frame) and file.is_file():
                        kind=mimetypes.guess_type(name)[0] or 'application/octet-stream'
                        return self.reply(200,file.read_bytes(),kind,download=None if name.endswith(('.html','.png')) else Path(name).name)
            return self.reply(404,dict(error='Not found.'))

        def do_POST(self):
            if not self.allowed(post=True):return self.reply(403,dict(error='Local origin required.'))
            try:
                length=int(self.headers.get('Content-Length','0'))
                if not 0<length<=32768:raise ValueError('Invalid request size.')
                request=json.loads(self.rfile.read(length))
                if not isinstance(request,dict):raise ValueError('Expected an object.')
                path=urlsplit(self.path).path
                if path=='/api/select':return self.reply(202,app.start(request))
                if path=='/api/subtitles':return self.reply(202,app.start_subtitles(request))
                if path=='/api/cancel':return self.reply(200,app.cancel(request.get('id')))
                match=re.fullmatch(r'/maps/([a-f0-9]{32})/(capture|fov|video-frame|wave)',path)
                if match:
                    snapshot=app.snapshot(match[1])
                    if not snapshot:return self.reply(410,dict(error='This map has expired. Generate the selection again.'))
                    if match[2]=='wave':return self.reply(200,app.wave(snapshot,request))
                    if match[2]=='video-frame':return self.reply(200,app.video_frame(snapshot,request))
                    return self.reply(200,app.capture(snapshot,request,detect=match[2]=='fov'))
                return self.reply(404,dict(error='Not found.'))
            except (ValueError,TypeError,OSError,RuntimeError,ImportError) as error:
                return self.reply(400,dict(error=str(error)))
            except Exception as error:
                return self.reply(500,dict(error=str(error) or type(error).__name__))

    server=ThreadingHTTPServer(('127.0.0.1',port),Handler)
    server.address=f'127.0.0.1:{server.server_port}'
    server.timeout=.25
    return server


def serve_app(args=None):
    app=MapApp(args)
    server=create_server(app)
    print(f'Interactive map app: http://{server.address}/',flush=True)
    print('Choose folders and selections on the map. Keep this command running; Ctrl+C stops the server.',flush=True)
    if app.initial_selection:
        mode=app.config['mode']
        app.start(dict(mode=mode,settings=app.config['settings'],selection=app.config['presets'][mode]))
    try:
        while True:server.handle_request()
    except KeyboardInterrupt:
        if app.active:app.cancel(app.active)
        print('\nMap server stopped.',flush=True)
    finally:server.server_close()
