import json
from datetime import timezone
from pathlib import Path
import tempfile
import unittest

from surfer_map_landmarks import load_map_landmarks
from surfer_gps_map import make_svg


class LandmarkTests(unittest.TestCase):
    def setUp(self):
        root=Path(__file__).resolve().parent.parent/'exports'
        root.mkdir(exist_ok=True)
        self.folder = tempfile.TemporaryDirectory(dir=root)
        self.addCleanup(self.folder.cleanup)
        self.source = Path(self.folder.name) / 'flight.jsonl'

    def config(self, t=500, session=1, name='Test wave'):
        return dict(event='vt40_configuration', epochMs=t, nextSession=session,
                    effectiveSettingsJson=json.dumps(dict(waveLineId='chosen')),
                    effectiveWaveLinesJson=json.dumps(dict(waveLines=[
                        dict(id='unrelated', name='Far away', pointA=dict(latitude=1, longitude=1), pointB=dict(latitude=2, longitude=2)),
                        dict(id='chosen', name=name, pointA=dict(latitude=-27.95, longitude=153.43),
                             pointB=dict(latitude=-27.951, longitude=153.43))])))

    def movement(self, t=1000, session=1, **kwargs):
        return dict(event='movement_cycle', epochMs=t, session=session, waveLineId='chosen', seawardBearingDeg=90,
                    retreatBoundaryLatitude=-27.9505, retreatBoundaryLongitude=153.4301, **kwargs)

    def sample(self, t=1500, line=100, session=1, name='S0', source=None):
        return dict(point=name, sourceFile=str(source or self.source), time_ms=t, sourceLine=line, session=session)

    def load(self, rows, samples=None):
        self.source.write_text('\n'.join(json.dumps(r) for r in rows), encoding='utf-8')
        return load_map_landmarks(samples or [self.sample()], timezone.utc)

    def test_selected_logged_profile_boundary_and_original_start(self):
        refs = self.load([self.config(), self.movement(), self.movement(1400, centralLatitude=-27.96, centralLongitude=153.44)])
        self.assertEqual({r['kind'] for r in refs}, {'wave', 'boundary', 'start'})
        wave = next(r for r in refs if r['kind']=='wave')
        self.assertEqual(wave['pointA']['latitude'], -27.95)
        self.assertNotIn('Far away', json.dumps(refs))
        start = next(r for r in refs if r['kind']=='start')
        boundary = next(r for r in refs if r['kind']=='boundary')
        self.assertEqual(start['latitude'], -27.9505)
        self.assertEqual(start['time_ms'], 1000)
        self.assertEqual(boundary['bearingDegrees'], 0)
        self.assertEqual(boundary['longitude'], start['longitude'])

    def test_no_future_state_and_equal_timestamp_line_order(self):
        rows = [self.config(), self.movement(1500)]
        self.assertEqual(self.load(rows, [self.sample(1500, 1)]), [])
        self.assertEqual(len(self.load(rows, [self.sample(1500, 3)])), 3)
        self.assertEqual(self.load(rows, [self.sample(1400)]), [])

    def test_sessions_and_files_do_not_share_references(self):
        rows = [self.config(), self.movement(), dict(event='session_end', epochMs=1600),
                dict(event='session_start', epochMs=1700), self.movement(1800, session=2)]
        refs = self.load(rows, [self.sample(1500), self.sample(1900, session=2, name='S1')])
        self.assertEqual(next(r for r in refs if r['kind']=='wave')['pairs'], ['S0'])
        self.assertEqual(len([r for r in refs if r['kind']=='start']), 2)
        self.assertEqual(self.load(rows, [self.sample(1900, session=1)]), [])
        second = Path(self.folder.name)/'other.jsonl'
        second.write_text(json.dumps(self.movement()), encoding='utf-8')
        refs = load_map_landmarks([self.sample(1500,source=second)], timezone.utc)
        self.assertNotIn('wave', {r['kind'] for r in refs})

    def test_missing_or_invalid_fields_do_not_invent_geometry(self):
        bad = self.movement()
        bad['retreatBoundaryLatitude'] = True
        bad['seawardBearingDeg'] = None
        config = self.config()
        config['effectiveWaveLinesJson'] = 'not json'
        self.assertEqual(self.load([config,bad]), [])

    def test_multiple_samples_share_one_reference_without_relabeling_ids(self):
        refs = self.load([self.config(),self.movement()], [self.sample(name='S10'),self.sample(t=1550,name='S20')])
        self.assertEqual(len(refs),3)
        self.assertTrue(all(r['pairs']==['S10','S20'] for r in refs))

    def test_svg_uses_the_same_projection_for_markers_and_references(self):
        import xml.etree.ElementTree as ET
        refs = self.load([self.config(),self.movement()])
        points = [dict(point='S0',dronePoint='D0',latitude=-27.9505,longitude=153.4301,
                       droneLatitude=-27.9505,droneLongitude=153.4301,sequence=1,accepted=True,
                       receivedTimestamp='1970-01-01T00:00:01.500+00:00')]
        root = ET.fromstring(make_svg(points,landmarks=refs))
        start = next(n for n in root.iter() if n.get('class')=='map-landmark start-point')
        anchor = next(n for n in start if n.get('class')=='landmark-anchor')
        surfer = next(n for n in root.iter() if n.get('class')=='surfer-marker')
        self.assertEqual(anchor.get('data-x'),surfer.get('data-x'))
        self.assertEqual(anchor.get('data-y'),surfer.get('data-y'))
        self.assertIsNotNone(next(n for n in root.iter() if n.get('id')=='map-grid-pattern'))


if __name__ == '__main__':
    unittest.main()
