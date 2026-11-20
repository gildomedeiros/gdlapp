import json
from pathlib import Path
import tempfile
import time
import unittest
from vtc_map import App,load_file
from vtc_subtitles import Index,IDS,export_video
from test_vtc_video import mp4


class SubtitleTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name)
        self.video=self.root/'MVI_0635.MP4';mp4(self.video,start='1970-01-01T00:00:00+00:00',duration=4)
        self.log=self.root/'VTC_test.jsonl'
        records=[dict(event='session_start',epochMs=0,version='1.0.40'),
            dict(event='framing_observation',epochMs=500,framing=dict(rideActive=False,qualifyingSpeedKmh=5)),
            dict(event='gps_accepted',epochMs=1000,telemetry=dict(sequence=1,latitude=-28,longitude=153)),
            dict(event='framing_observation',epochMs=1500,framing=dict(rideActive=True,qualifyingSpeedKmh=15)),
            dict(event='gps_accepted',epochMs=2000,telemetry=dict(sequence=2,latitude=-28,longitude=153.0001)),
            dict(event='session_end',epochMs=4000)]
        self.log.write_text('\n'.join(json.dumps(r) for r in records),encoding='utf-8')
        self.settings=dict(folder=str(self.root),clockBasis='utc',correctionSeconds=0)

    def test_index_is_last_received_and_holds_ride_until_next_gps(self):
        session=load_file(self.log);index=Index([session],['ride','age','source'])
        text,reason=index.render(999,self.video.name,999);self.assertIsNotNone(reason)
        self.assertIn('unavailable',text)
        text,_=index.render(1999,self.video.name,1999);self.assertIn('Wave ride: NO',text);self.assertIn('0.999 s',text);self.assertNotIn('Wave ride: YES',text)
        text,_=index.render(2000,self.video.name,2000);self.assertIn('Wave ride: YES',text);self.assertIn('#08752e',text)
        text,reason=Index([session,session],IDS).render(2000,self.video.name,2000);self.assertIn('overlapping',reason)

    def test_export_reuses_ass_layout_preserves_source_and_exact_gps_boundaries(self):
        before=self.video.read_bytes();report,files=export_video([self.log],self.settings,dict(video=self.video.name),IDS,self.root/'output')
        self.assertEqual(self.video.read_bytes(),before)
        self.assertEqual({p.suffix for p in files},{'.zip','.srt','.ass','.json'})
        text=(self.root/'output/MVI_0635.srt').read_text(encoding='utf-8');ass=(self.root/'output/MVI_0635.ass').read_text(encoding='utf-8-sig')
        self.assertIn('00:00:02,000 -->',text);self.assertIn('Wave ride: YES',text)
        self.assertIn('PlayResX: 1920',ass);self.assertIn(r'\1a&H80&',ass);self.assertIn('Wave',ass)
        self.assertEqual(report['video']['correctionSeconds'],0)
        with self.assertRaises(FileExistsError):export_video([self.log],self.settings,dict(video=self.video.name),IDS,self.root/'output')

    def test_background_job_downloads_and_no_map_required(self):
        app=App(self.root);app.catalog(dict(folder=str(self.root)))
        job=app.start_subtitles(dict(video=self.settings,row=dict(video=self.video.name),fields=['ride','age'],file='auto'))
        deadline=time.monotonic()+10
        while app.subtitle_jobs[job['id']]['status']=='running' and time.monotonic()<deadline:time.sleep(.02)
        result=app.subtitle_jobs[job['id']]
        self.assertEqual(result['status'],'done',result)
        self.assertEqual(len(result['exports']),4);self.assertEqual(App(self.root).config['subtitleFields'],['age','ride'])


if __name__=='__main__':unittest.main()
