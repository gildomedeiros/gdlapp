import json
from pathlib import Path
import tempfile
import unittest
from vtc_map import App
from test_vtc_video import mp4


class AutoSessionTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name)
        for name,start in [('VTC_first.jsonl',1000),('VTC_second.jsonl',10000)]:
            records=[dict(event='session_start',epochMs=start,version='1.0.27')]+[dict(event='gps_accepted',epochMs=start+i*1000,telemetry=dict(sequence=i,latitude=-28,longitude=153)) for i in range(4)]+[dict(event='session_end',epochMs=start+4000)]
            (self.root/name).write_text('\n'.join(json.dumps(r) for r in records))
        self.app=App(self.root);self.app.catalog(dict(folder=str(self.root)))

    def test_video_alone_picks_second_session(self):
        mp4(self.root/'MVI_0635.MP4',start='1970-01-01T00:00:10+00:00')
        result=self.app.load(dict(file='auto',video=dict(folder=str(self.root)),selections=[dict(video='MVI_0635.MP4',playback='00:01',following=0)]))
        self.assertIn('VTC_second.jsonl',result['name']);self.assertEqual(result['points'][0]['timeMs'],11000)

    def test_three_ranges_cross_sessions_and_unique_ids(self):
        result=self.app.load(dict(file='auto',selections=[dict(at='1970-01-01T00:00:01Z',following=1),dict(at='1970-01-01T00:00:11Z',following=1)]))
        self.assertEqual([p['timeMs'] for p in result['points']],[1000,2000,11000,12000])
        self.assertEqual([p['id'] for p in result['points']],['S0','S1','S5','S6'])
        self.assertEqual(len({p['source'] for p in result['points']}),2)

    def test_unmatched_and_overlaps_report_instead_of_guessing(self):
        with self.assertRaisesRegex(ValueError,'No VTC session'):self.app.load(dict(file='auto',selections=[dict(at='1970-01-01T00:00:07Z')]))
        (self.root/'VTC_duplicate.jsonl').write_bytes((self.root/'VTC_first.jsonl').read_bytes());self.app.catalog(dict(folder=str(self.root)))
        with self.assertRaisesRegex(ValueError,'Overlapping'):self.app.load(dict(file='auto',selections=[dict(at='1970-01-01T00:00:02Z')]))


if __name__=='__main__':unittest.main()
