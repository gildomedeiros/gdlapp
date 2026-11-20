from pathlib import Path
from datetime import datetime,timezone
import json
import struct
import tempfile
import unittest
from vtc_video import read_metadata,playback_seconds,resolve,catalog
from vtc_map import App


def mp4(path,start='2026-11-18T08:32:00+00:00',duration=300,version=0):
    def box(kind,data):return struct.pack('>I4s',len(data)+8,kind)+data
    created=int((datetime.fromisoformat(start)-datetime(1904,1,1,tzinfo=timezone.utc)).total_seconds()) if start else 0
    payload=bytes([version,0,0,0])+struct.pack('>IIII' if version==0 else '>QQIQ',created,created,1000,int(duration*1000))
    path.write_bytes(box(b'ftyp',b'isom0000')+box(b'mdat',b'\x00'*20)+box(b'moov',box(b'mvhd',payload)))


class VideoTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name)
        self.path=self.root/'MVI_0635.MP4';mp4(self.path)
        self.settings=dict(folder=str(self.root),clockBasis='utc',correctionSeconds=0)
        self.row=dict(video=self.path.name,playback='01:04')

    def test_metadata_versions_mdat_skip_duration_and_correction(self):
        for version in (0,1):
            mp4(self.path,version=version)
            metadata=read_metadata(self.path);self.assertEqual(metadata['duration'],300)
            r=resolve(self.row,self.settings);self.assertEqual(r['timestamp'],'2026-11-18T18:33:04.000+10:00')
            s=dict(self.settings,correctionSeconds=-4.25)
            self.assertEqual(resolve(self.row,s)['timestamp'],'2026-11-18T18:32:59.750+10:00')

    def test_camera_local_interpretation_and_manual_override(self):
        mp4(self.path,start='2026-11-18T18:32:00+00:00')
        self.assertEqual(resolve(self.row,dict(self.settings,clockBasis='brisbane'))['timestamp'],'2026-11-18T18:33:04.000+10:00')
        self.assertEqual(resolve(dict(self.row,recordingStart='2026-11-18T18:30:00'),self.settings)['timestamp'],'2026-11-18T18:31:04.000+10:00')
        mp4(self.path,start=None)
        with self.assertRaisesRegex(ValueError,'missing'):resolve(self.row,self.settings)
        self.assertEqual(resolve(dict(self.row,recordingStart='2026-11-18T18:30:00'),self.settings)['timestamp'],'2026-11-18T18:31:04.000+10:00')

    def test_playback_validation_duration_and_path_limits(self):
        self.assertEqual(playback_seconds('1:04.5'),64.5);self.assertEqual(playback_seconds('1:02:03.25'),3723.25)
        for value in ('-1','1:70','abc','1:00:61'):
            with self.assertRaises(ValueError):playback_seconds(value)
        with self.assertRaisesRegex(ValueError,'exceeds'):resolve(dict(self.row,playback='06:00'),self.settings)
        with self.assertRaises(ValueError):resolve(dict(self.row,video='../MVI_0635.MP4'),self.settings)
        self.assertEqual(catalog(str(self.root)),['MVI_0635.MP4'])

    def test_actual_map_selection_and_persistence_from_video(self):
        start=datetime.fromisoformat('2026-11-18T08:32:00+00:00').timestamp()*1000
        records=[dict(event='session_start',epochMs=start,version='1.0.27')]
        for i in range(100):records.append(dict(event='gps_accepted',epochMs=start+i*1000,telemetry=dict(sequence=i,latitude=-28,longitude=153+i*.00001)))
        (self.root/'VTC_test.jsonl').write_text('\n'.join(json.dumps(r) for r in records))
        app=App(self.root);app.catalog(dict(folder=str(self.root)))
        result=app.load(dict(file='0',selections=[dict(self.row,following=2)],video=self.settings))
        self.assertEqual([p['gps']['sequence'] for p in result['points']],[64,65,66])
        self.assertEqual([p['videoReferences'][0]['label'] for p in result['points']],['[635,01:04]','[635,01:05]','[635,01:06]'])
        self.assertTrue(result['points'][0]['details'][0].endswith('[635,01:04]'))
        restored=App(self.root);self.assertEqual(restored.config['selections'][0]['video'],'MVI_0635.MP4')
        self.assertEqual(result['videoTimes'][0]['playbackSeconds'],64)
        direct=app.load(dict(file='0',selections=[dict(at='2026-11-18T18:33:04+10:00',following=0)]))
        self.assertEqual(direct['points'][0]['videoReferences'],[])


if __name__=='__main__':unittest.main()
