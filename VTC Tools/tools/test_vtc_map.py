from pathlib import Path
import json
import tempfile
import unittest
import xml.etree.ElementTree as ET
from vtc_map import load_file,select_points,kml_data,csv_data,App,distance


class MapTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name);self.path=self.root/'VTC_test.jsonl'
        self.records=[dict(event='session_start',epochMs=0,version='1.0.15'),
            dict(event='settings_snapshot',epochMs=1,settings=dict(latitude='-28',longitude='153',landmarkLat='-27.999',landmarkLon='153',height='3',targetHeight='1',gpsAge='2',hfov='10',magnitude='5')),
            dict(event='status_snapshot',epochMs=900,monoMs=900,positionSampleMonoMs=800,positionFresh=True,position=dict(headingDegrees=90,tiltDegrees=-1),state='AIMING',framing=dict(mode='AUTO',appliedOffsetDegrees=5)),
            dict(event='gps_accepted',epochMs=1000,continuity=0,telemetry=dict(sequence=1,latitude=-28,longitude=153.001,rssi=-75,snr=12)),
            dict(event='aim_cycle',epochMs=1001,targetHeadingDegrees=80,panErrorDegrees=-10,framing=dict(mode='AUTO',appliedOffsetDegrees=4)),
            dict(event='gps_accepted',epochMs=1500,continuity=0,telemetry=dict(sequence=2,latitude=-28,longitude=153.00101)),
            dict(event='gps_invalid',epochMs=1600),
            dict(event='gps_accepted',epochMs=2000,continuity=1,telemetry=dict(sequence=3,latitude=-28,longitude=153.00102)),
            dict(event='gps_accepted',epochMs=5000,continuity=1,telemetry=dict(sequence=4,latitude=-28,longitude=153.00103))]
        self.write()

    def write(self):self.path.write_text('\n'.join(json.dumps(r) for r in self.records),encoding='utf-8')

    def test_only_accepted_packets_and_no_future_context(self):
        points=load_file(self.path)['points']
        self.assertEqual(len(points),4)
        self.assertEqual(points[0]['aim'],{})
        self.assertEqual(points[1]['aim']['targetHeadingDegrees'],80)
        self.assertEqual(points[0]['positionAgeMs'],200)
        self.assertAlmostEqual(points[0]['sideways'],points[0]['distance']*.0871557427,places=5)
        self.assertIsNotNone(points[0]['frameWidth'])
        self.assertIsNone(points[-1]['position']);self.assertIsNone(points[-1]['frameWidth'])

    def test_speed_gaps_continuity_and_timestamp_union(self):
        points=load_file(self.path)['points']
        self.assertIsNotNone(points[1]['speed']);self.assertIsNone(points[2]['speed']);self.assertIsNone(points[3]['speed'])
        self.assertNotEqual(points[1]['segment'],points[2]['segment'])
        self.assertEqual([p['id'] for p in select_points(points,[dict(at='1970-01-01T00:00:01.400+00:00',following=1),dict(at='1970-01-01T00:00:01.500+00:00',following=1)])],['S0','S1','S2'])
        self.assertEqual(select_points(points,[dict(at='1970-01-01T00:00:00Z',following=1)]),[])
        self.assertEqual(select_points(points,[]),points)
        with self.assertRaises(ValueError):select_points(points,[dict(at='bad',following=1)])

    def test_exports_preserve_coordinates_references_and_ids(self):
        p=load_file(self.path)['points'][1]
        tree=ET.fromstring(kml_data([p]));ns={'k':'http://www.opengis.net/kml/2.2'}
        self.assertEqual(len(tree.findall('.//k:Placemark',ns)),3)
        self.assertIn('153.001010000000,-28.000000000000',ET.tostring(tree,encoding='unicode'))
        self.assertEqual(len(ET.fromstring(kml_data([p],False)).findall('.//k:Placemark',ns)),1)
        csv=csv_data([p]).decode('utf-8-sig');self.assertIn('S1',csv);self.assertIn('153.00101',csv)

    def test_catalog_selection_and_snapshot_isolation(self):
        app=App(self.root);catalog=app.catalog(dict(folder=str(self.root)))
        result=app.load(dict(file=catalog['files'][0]['id'],selections=[]))
        first=result['id'];self.assertEqual(len(result['points']),4)
        second=app.load(dict(file='0',selections=[dict(at='1970-01-01T00:00:01Z',following=0)]))
        self.assertEqual(len(second['points']),1)
        csv,_=app.export(dict(snapshot=first,ids=['S3'],format='csv'))
        self.assertIn(b'S3',csv)
        with self.assertRaises(ValueError):app.export(dict(snapshot=second['id'],ids=['S3'],format='csv'))
        self.assertEqual(App(self.root).config['history'],['1970-01-01T00:00:01Z'])

    def test_empty_invalid_logs_and_geo_scale(self):
        self.records=[];self.write();self.assertEqual(load_file(self.path)['points'],[])
        self.path.write_text('{broken\n'+json.dumps(dict(event='gps_accepted',epochMs=1,telemetry=dict(latitude=None,longitude=2))))
        result=load_file(self.path);self.assertEqual(len(result['warnings']),2)
        self.assertAlmostEqual(distance((0,0),(0,.001)),111.195,places=2)


if __name__=='__main__':unittest.main()
