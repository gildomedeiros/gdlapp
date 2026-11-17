"""Simulation subtitles must be causal, held between receipts and styled alike."""
import json
from pathlib import Path
import re
import threading
import unittest
from unittest.mock import patch
from datetime import timezone

from surfer_subtitles import PacketIndex, SIM_FIELD_IDS, export_folder, read_cues
from surfer_yaw_sim import parameters, run_for_points
from surfer_map_app import MapApp
from surfer_subtitle_layout import card_events
import test_surfer_yaw_sim as yaw_fixture
from test_surfer_subtitles import source_srt, clock


class SimulationSubtitleTests(unittest.TestCase):
    def setUp(self):
        fixture=yaw_fixture.LogReplayTests();fixture.setUp();self.addCleanup(fixture.doCleanups)
        self.fixture=fixture;self.root=fixture.folder
        self.packets=[dict(p,time_ms=1000+i*500,accepted=True) for i,p in enumerate(fixture.points.values())]
        self.drones={str(fixture.source):[dict(time_ms=990,sourceLine=3,latitude=0,longitude=0,age=0,fix=0,timestamp=clock(990))]}
        self.videos=self.root/'videos';self.videos.mkdir()

    def index(self,params=None,packets=None,fields=None):
        index=PacketIndex(self.packets if packets is None else packets,self.drones,fields or SIM_FIELD_IDS+['gps_age','packet'])
        index.add_yaw_simulation(params,'+00:00')
        return index

    def test_each_frame_uses_latest_receipt_and_never_advances_replay(self):
        index=self.index()
        expected=run_for_points(self.fixture.points,{},'+00:00')['points']
        # Out-of-order frames and long gaps must not advance or interpolate offset.
        for time in [999,1000,2499,2500,2999,3000,5500,6500,2499]:
            text,age,_=index.render(time,clock(time),'test.MP4','00:00:00,000')
            if time<1000:
                self.assertIn('appliedOffset[SIM]: unavailable (no earlier GPS packet)',text)
                self.assertNotIn('[S00]',text);continue
            receipt=min(9,(time-1000)//500)
            self.assertEqual(age,time-(1000+receipt*500))
            for line in expected[f'S{receipt}']['cardLines']:
                self.assertIn('<font color="#7838a5" face="Arial" size="28"><b>'+line+'</b>',text)
        self.assertNotIn('yawSimulation',self.packets[0])

    def test_replay_is_full_log_not_sparse_selection_and_custom_fov_is_used(self):
        index=self.index(dict(magnitude=8,horizontalFov=90),packets=[self.packets[-1]])
        row=index.points[0]['yawSimulation']
        self.assertEqual(row['appliedOffset'],7)
        self.assertEqual(row['offsetMagnitude'],8)
        self.assertEqual(row['horizontalFov'],90)
        self.assertGreater(row['appliedFrameWidthMetres'],60)
        with patch('surfer_yaw_sim.load_observations',side_effect=AssertionError('unneeded replay')):
            self.index(fields=['gps_age'])

    def test_srt_and_ass_preserve_styles_timing_and_originals(self):
        frames=[999,1000,2499,2500,2999,3000,5500,6500]
        original=source_srt(frames)
        for stem in ('DJI_1','DJI_2'):
            (self.videos/(stem+'.MP4')).write_bytes(b'original video')
            (self.videos/(stem+'.SRT')).write_text(original,encoding='utf-8',newline='')
        before={p.name:p.read_bytes() for p in self.videos.iterdir()}
        index=self.index(dict(magnitude=8,horizontalFov=90))
        result,files=export_folder(self.videos,self.root/'out',index,timezone.utc)
        self.assertEqual(result['totals']['exported'],2)
        self.assertEqual(result['simulation']['parameters']['horizontalFov'],90)
        self.assertEqual(before,{p.name:p.read_bytes() for p in self.videos.iterdir()})
        cues,_=read_cues(self.root/'out/DJI_1.srt',timezone.utc)
        for cue,time in zip(cues,frames):
            rendered,_,_=index.render(time,clock(time),'DJI_1.MP4',cue[1])
            self.assertIn(rendered,cue[0])
        ass=(self.root/'out/DJI_1.ass').read_text(encoding='utf-8-sig')
        self.assertIn(r'{\c&Ha53878&\b1}',ass)
        displayed=' '.join(re.sub(r'\{[^}]*\}','',line.split(',',9)[9]) for line in ass.splitlines() if line.startswith('Dialogue: 2,'))
        self.assertIn('appliedOffset[SIM]:',displayed)
        self.assertIn('offsetMagnitude[SIM]:',displayed)
        self.assertIn('m sideways /',displayed)
        self.assertIn('m frame width',displayed)
        # The first positive offset belongs to GPS at 2500, the fourth cue.
        timing=[line.split(',')[1] for line in ass.splitlines() if 'appliedOffset[SIM]:' in line and '+1.00°' in line]
        self.assertEqual(timing,['0:00:00.10'])

    def test_invalid_latest_packet_is_unavailable_not_previous_simulation(self):
        records=[json.loads(line) for line in self.fixture.source.read_text().splitlines()]
        records.append(dict(event='lora_packet',epochMs=6000,sequence=10,accepted=False,latitude=None,longitude=None))
        self.fixture.source.write_text('\n'.join(json.dumps(r) for r in records),encoding='utf-8')
        bad=dict(self.packets[-1],time_ms=6000,sourceLine=len(records),sequence=10,accepted=False,latitude=None,longitude=None)
        index=self.index(packets=self.packets+[bad])
        text,age,_=index.render(6100,clock(6100),'test.MP4','00:00:00,000')
        self.assertIn('Packet: 10 | accepted: False',text)
        self.assertIn('appliedOffset[SIM]: unavailable',text)
        self.assertNotIn('appliedOffset[SIM]: +5.00',text)
        self.assertEqual(age,100)

    def test_map_settings_persist_and_export_captures_them_at_start(self):
        app=MapApp(app_dir=self.root/'app')
        snapshot=dict(id='a'*32,points=self.fixture.points,lock=threading.Lock(),output=self.root,
                      settings=dict(utc_offset='+00:00'))
        app.yaw_simulation(snapshot,dict(parameters=dict(magnitude=8,horizontalFov=90)))
        restored=MapApp(app_dir=self.root/'app')
        self.assertTrue(restored.config['yaw_simulation_configured'])
        self.assertEqual(restored.config['yaw_simulation_parameters']['magnitude'],8)
        (self.videos/'x.mp4').write_bytes(b'video');(self.videos/'x.srt').write_text(source_srt([5500]),encoding='utf-8',newline='')
        settings=dict(app.config['settings'],folder=str(self.root),video_folder=str(self.videos),output=str(self.root/'batch'),utc_offset='+00:00')
        entered=threading.Event();release=threading.Event()
        def load(*args):
            entered.set();release.wait(5)
            return self.packets,self.drones,[],timezone.utc
        with patch.object(app,'load_logs',side_effect=load):
            job=app.start_subtitles(dict(settings=settings,fields=SIM_FIELD_IDS))
            self.assertTrue(entered.wait(3))
            with app.lock:app.config['yaw_simulation_parameters']=parameters(dict(magnitude=2,horizontalFov=60))
            release.set();app.worker.join(10)
        job=app.public_job(job['id']);self.assertEqual(job['status'],'done',job)
        self.assertEqual(job['result']['simulation']['parameters']['magnitude'],8)
        text=(Path(job['result']['output'])/'x.srt').read_text(encoding='utf-8')
        self.assertIn('offsetMagnitude[SIM]: 8.00° (fixed)',text)
        self.assertIn('HFOV 90° estimate',text)

    def test_old_preferences_enable_new_fields_once(self):
        root=self.root/'preferences';root.mkdir()
        (root/'preferences.json').write_text(json.dumps(dict(subtitle_fields=['gps_age'],subtitle_fields_version=2)))
        app=MapApp(app_dir=root)
        self.assertEqual(app.config['subtitle_fields'],['gps_age']+SIM_FIELD_IDS)
        app.config['subtitle_fields']=['gps_age'];app.save_preferences()
        self.assertEqual(MapApp(app_dir=root).config['subtitle_fields'],['gps_age'])


if __name__=='__main__':unittest.main()
