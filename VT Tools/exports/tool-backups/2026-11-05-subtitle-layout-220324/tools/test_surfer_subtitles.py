"""Causal GPS matching, styling, preservation, batch export and API integration."""
from datetime import datetime, timezone
import copy
import json
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from zipfile import ZipFile

from surfer_subtitles import FIELD_IDS, PacketIndex, export_folder, read_cues, style_card_line, validate_fields
from surfer_map_app import Cancelled, MapApp, create_server


def clock(ms):
    return datetime.fromtimestamp(ms / 1000, timezone.utc).isoformat(timespec='milliseconds')


def packet(ms, sequence=1, **updates):
    p = dict(time_ms=ms, sequence=sequence, receivedTimestamp=clock(ms), sourceFile='flight.jsonl', sourceLine=sequence,
             latitude=-27.95, longitude=153.43 + sequence * .00001, accepted=True, gimbalTiltDeg=-32.9,
             gimbalTilting=True, gimbalTargetPitchDeg=-35, heightAboveTakeoffM=10.4, droneHeadingDeg=41.5,
             gimbalReportedTimestamp=clock(ms-10), retreatActive=False, waveRiding=True, minimumDistanceMetres=26,
             filmingSeparationMetres=31, rssi=-80, snr=12, gimbalActiveBand=2,
             gimbalBandConfig=dict(bufferMetres=5, bands=[dict(maxDistanceMetres=20, pitchDegrees=-40),
                                   dict(maxDistanceMetres=33, pitchDegrees=-33), dict(maxDistanceMetres=None, pitchDegrees=-30)]))
    p.update(updates)
    return p


def drones(source='flight.jsonl'):
    return {source: [dict(time_ms=0, sourceLine=0, latitude=-27.9502, longitude=153.43,
                        timestamp=clock(0), age=0, fix=0)]}


def source_srt(times):
    # Different recording clock and playback intervals ensure no fixed-fps guess.
    return '\r\n\r\n'.join(f'{i+1}\r\n00:00:00,{i*33:03d} --> 00:00:00,{(i+1)*33:03d}\r\n'
                             f'<font size="28">SrtCnt : {i+1}, DiffTime : 33ms\r\n{clock(t)}\r\n'
                             '[iso : 100] [latitude: -27.95]</font>\r\n[VT TELEMETRY BEGIN]\r\noriginal saved note'
                             for i, t in enumerate(times)) + '\r\n\r\n'


class SubtitleTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name)
        self.video=self.root/'videos';self.video.mkdir()

    def test_latest_received_not_nearest_and_age_across_gaps(self):
        rows=[packet(1000),packet(3000,2)]
        index=PacketIndex(rows,drones(),['packet','gps_age','direction'])
        for time, expected in [(999,None),(1000,0),(2999,0),(3000,1),(9000,1)]:
            self.assertEqual(index.lookup(time)[0],expected)
        text,age,_=index.render(2999,clock(2999),'test.mp4','00:00:00,000')
        self.assertEqual(age,1999);self.assertIn('Packet: 1',text);self.assertIn('GPS age: 1.999 s',text)
        self.assertIn('#c40000',text);self.assertNotIn('toward next',text)
        self.assertIn('GPS movement direction: unchanged',text)
        text,age,_=index.render(3000,clock(3000),'test.mp4','00:00:00,033')
        self.assertEqual(age,0);self.assertIn('Packet: 2',text);self.assertIn('previous → current',text)
        self.assertNotIn('point',rows[0])

    def test_same_time_uses_last_source_line_and_conflicts_are_unavailable(self):
        same=PacketIndex([packet(1000),packet(1000,2)],drones(),['packet'])
        self.assertEqual(same.points[same.lookup(1000)[0]]['sequence'],2)
        conflict=PacketIndex([packet(1000),packet(1000,2,sourceFile='other.jsonl')],drones(),['gps_age'])
        text,age,reason=conflict.render(1001,clock(1001),'x.mp4','00:00:00,000')
        self.assertIsNone(age);self.assertIn('conflicting logs',reason)

    def test_invalid_or_rejected_latest_packet_is_not_silently_skipped(self):
        rows=[packet(1000),packet(2000,2,latitude=None,accepted=False),packet(3000,3)]
        index=PacketIndex(rows,drones(),FIELD_IDS)
        text,age,_=index.render(2500,clock(2500),'x.mp4','00:00:00,000')
        self.assertIn('Packet: 2 | accepted: False',text);self.assertIn('invalid or unavailable',text)
        self.assertEqual(age,500)
        text,_,_=index.render(3000,clock(3000),'x.mp4','00:00:00,033')
        self.assertIn('Surfer since previous packet: unknown',text)

    def test_card_highlights_and_field_selection(self):
        self.assertIn('<font color="#a94700"><b>[29.8][31] m</b>',style_card_line('GPS 1 | [29.8][31] m horizontal'))
        self.assertIn('<font color="#08752e"><b>Retreat active: YES',style_card_line('Retreat active: YES [26] m'))
        self.assertIn('<font color="#8226a0"><b>Ride detected: YES',style_card_line('Ride detected: YES'))
        line=style_card_line('Gimbal tilt: -32.9° — TILTING → -35° (command active)')
        self.assertIn('<font color="#a94700"><b>Gimbal tilt:',line)
        self.assertIn('<font color="#08752e"><b>TILTING',line)
        index=PacketIndex([packet(1000),packet(4000,2)],drones(),['bands','received'])
        text,_,_=index.render(4000,clock(4000),'x.mp4','00:00:00,000')
        self.assertIn('<font color="#08752e"><b> 20–33 m: -33° (selected)',text)
        self.assertIn('<font color="#c40000"><b>Received:',text)
        self.assertNotIn('GPS age:',text);self.assertNotIn('Packet:',text)
        self.assertIn('&lt;test&gt;',style_card_line('<test>'))
        for bad in ([],None,['unknown'],['packet',None],'packet'):
            with self.assertRaises(ValueError):validate_fields(bad)

    def test_all_mp4s_preserve_originals_and_batch_continues_after_missing_or_bad_srt(self):
        for name in ['DJI_1.MP4','DJI_2.mp4','DJI_3.Mp4','DJI_4.mp4']:
            (self.video/name).write_bytes(b'untouched video')
        original=source_srt([999,1000,2501,3000,6500])
        for name in ['DJI_1.SRT','DJI_2.srt']:
            (self.video/name).write_bytes(original.encode('utf-8-sig'))
        (self.video/'DJI_4.SRT').write_text('malformed',encoding='utf-8')
        before={p.name:p.read_bytes() for p in self.video.iterdir()}
        index=PacketIndex([packet(1000),packet(3000,2)],drones(),FIELD_IDS)
        report,files=export_folder(self.video,self.root/'batch',index,timezone.utc)
        self.assertEqual(report['totals'],dict(videos=4,exported=2,skipped=2,frames=10,unavailable=2,stale=4))
        self.assertEqual(before,{p.name:p.read_bytes() for p in self.video.iterdir()})
        out=(self.root/'batch/DJI_1_VT.srt').read_text(encoding='utf-8')
        original_cues,_=read_cues(self.video/'DJI_1.SRT',timezone.utc)
        for (block,_,_,_) in original_cues:self.assertIn(block+'\n',out)
        self.assertIn('GPS age: 1.501 s',out);self.assertIn('GPS age: 3.500 s',out)
        self.assertIn('GPS age: unavailable (no earlier GPS packet)',out)
        self.assertEqual(len(read_cues(self.root/'batch/DJI_1_VT.srt',timezone.utc)[0]),5)
        with ZipFile(files[0]) as zipped:
            self.assertEqual(set(zipped.namelist()),{'DJI_1_VT.srt','DJI_2_VT.srt','export-report.json'})
        with self.assertRaises(FileExistsError):export_folder(self.video,self.root/'batch',index,timezone.utc)
        export_folder(self.video,self.root/'second-batch',index,timezone.utc)

    def test_missing_clock_or_bad_timing_cannot_be_silently_guessed(self):
        source=self.root/'x.SRT'
        for body in ['1\n00:00:00,000 --> 00:00:00,033\nNo clock\n',
                     source_srt([1000]).replace('00:00:00,033','00:00:00,000')]:
            source.write_text(body,encoding='utf-8')
            with self.assertRaises(ValueError):read_cues(source,timezone.utc)

    def test_recording_clock_steps_are_reported_and_each_frame_keeps_its_clock(self):
        (self.video/'x.mp4').write_bytes(b'video')
        (self.video/'x.srt').write_text(source_srt([1000,1170,1000,2000]),encoding='utf-8',newline='')
        index=PacketIndex([packet(1000),packet(1100,2)],drones(),['gps_age','packet'])
        report,_=export_folder(self.video,self.root/'batch',index,timezone.utc)
        self.assertEqual(report['totals']['exported'],1)
        self.assertIn('largest 170 ms',report['files'][0]['warning'])
        blocks=(self.root/'batch/x_VT.srt').read_text(encoding='utf-8').strip().split('\n\n')
        self.assertIn('Packet: 2',blocks[1]);self.assertIn('GPS age: 0.070 s',blocks[1])
        self.assertIn('Packet: 1',blocks[2]);self.assertIn('GPS age: 0.000 s',blocks[2])

    def test_cancel_removes_partial_subtitle(self):
        (self.video/'x.mp4').write_bytes(b'video');(self.video/'x.srt').write_text(source_srt([1000]),encoding='utf-8',newline='')
        checks=0
        def cancel():
            nonlocal checks
            checks+=1
            if checks==3:raise Cancelled()
        with self.assertRaises(Cancelled):
            export_folder(self.video,self.root/'batch',PacketIndex([packet(1000)],drones(),['gps_age']),timezone.utc,check_cancel=cancel)
        self.assertFalse(list((self.root/'batch').glob('*.part')))
        self.assertFalse(list((self.root/'batch').glob('*.srt')))

    def test_app_export_uses_full_log_and_keeps_map_and_preferences(self):
        (self.video/'x.mp4').write_bytes(b'video');(self.video/'x.srt').write_text(source_srt([2000]),encoding='utf-8',newline='')
        log=self.root/'flight.jsonl';log.write_text('',encoding='utf-8')
        rows=[packet(1000,sourceFile=str(log)),packet(2000,2,sourceFile=str(log))]
        app=MapApp(app_dir=self.root/'app')
        app.current={'mapUrl':'unchanged','id':'old'}
        settings=dict(app.config['settings'],folder=str(self.root),video_folder=str(self.video),output=str(self.root/'exports'),utc_offset='+00:00')
        with patch('surfer_gps_map.load_packets',return_value=(rows,drones(str(log)))):
            job=app.start_subtitles(dict(settings=settings,fields=['packet','gps_age']))
            app.worker.join(10)
        job=app.public_job(job['id'])
        self.assertEqual(job['status'],'done',job)
        self.assertEqual(app.current,{'mapUrl':'unchanged','id':'old'})
        self.assertEqual(app.state()['subtitleResult'],job['result'])
        self.assertIn('Packet: 2',(Path(job['result']['output'])/'x_VT.srt').read_text(encoding='utf-8'))
        self.assertNotIn('point',rows[0])
        self.assertEqual(MapApp(app_dir=self.root/'app').config['subtitle_fields'],['gps_age','packet'])
        server=create_server(app)
        thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
        try:
            url='http://'+server.address
            with urlopen(url+job['result']['exports'][0]['url']) as response:
                self.assertEqual(response.headers['Content-Type'],'application/zip')
                self.assertTrue(response.read().startswith(b'PK'))
            for path in ['/api/subtitles/'+job['id']+'/files/999','/api/subtitles/'+job['id']+'/files/../../preferences.json']:
                with self.assertRaises(HTTPError) as error:urlopen(url+path)
                self.assertEqual(error.exception.code,404)
            request=Request(url+'/api/subtitles',data=json.dumps(dict(settings=settings,fields=['packet'])).encode(),headers={'Content-Type':'application/json'})
            with self.assertRaises(HTTPError) as error:urlopen(request)
            self.assertEqual(error.exception.code,403)
        finally:
            server.shutdown();server.server_close();thread.join()

    def test_export_and_map_jobs_cannot_overlap(self):
        app=MapApp(app_dir=self.root/'app')
        settings=dict(app.config['settings'],folder=str(self.root),video_folder=str(self.video))
        entered=threading.Event();release=threading.Event()
        def delayed(*args):
            entered.set();release.wait(3)
            raise Cancelled()
        with patch.object(app,'load_logs',side_effect=delayed):
            job=app.start_subtitles(dict(settings=settings,fields=['packet']))
            self.assertTrue(entered.wait(2))
            try:
                with self.assertRaisesRegex(ValueError,'still running'):app.start_subtitles(dict(settings=settings,fields=['packet']))
                with self.assertRaisesRegex(ValueError,'still running'):app.start(dict(mode='timestamp',settings=settings,selection=dict(at='00:00:01')))
                app.cancel(job['id'])
            finally:release.set();app.worker.join(5)
        self.assertEqual(app.public_job(job['id'])['status'],'cancelled')


if __name__=='__main__':unittest.main()
