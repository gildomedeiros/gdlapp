import io
import json
import tempfile
import unittest
from contextlib import redirect_stdout
from datetime import timezone
from pathlib import Path

from surfer_gps_map import load_packets, mark_drone_heading


class DroneHeadingTests(unittest.TestCase):
    def heading(self, rows, time=1045, line=99, session=1, source='a'):
        point=dict(time_ms=time, sourceLine=line, session=session, sourceFile=source)
        mark_drone_heading([point], {'a':rows}, timezone.utc)
        return point

    def test_compass_normalization_and_source_evidence(self):
        for raw, expected in [(-90,270),(0,0),(180,180),(450,90)]:
            point=self.heading([(1000,3,1,raw,True,100)])
            self.assertEqual(point['droneHeadingDeg'],expected)
            self.assertEqual(point['droneHeadingSourceLine'],3)
            self.assertEqual(point['droneHeadingTimestamp'],'1970-01-01T00:00:01.000+00:00')

    def test_only_prior_readings_in_same_file_and_session(self):
        rows=[(1100,4,1,90,True,0),(1000,2,1,0,True,0),(1000,5,1,180,True,0)]
        self.assertEqual(self.heading(rows,time=1000,line=3)['droneHeadingDeg'],0)
        self.assertEqual(self.heading(rows,time=1000,line=5)['droneHeadingDeg'],180)
        self.assertIsNone(self.heading(rows,time=999)['droneHeadingDeg'])
        self.assertIsNone(self.heading(rows,session=2)['droneHeadingDeg'])
        self.assertIsNone(self.heading(rows,source='b')['droneHeadingDeg'])

    def test_freshness_includes_sensor_age_and_receipt_delay(self):
        rows=[(1000,2,1,25,True,500)]
        self.assertEqual(self.heading(rows,time=2000)['droneHeadingDeg'],25)
        self.assertIsNone(self.heading(rows,time=2001)['droneHeadingDeg'])
        for heading,fresh,age in [(None,True,0),(True,True,0),(float('nan'),True,0),
                                   (25,False,0),(25,None,0),(25,True,None),
                                   (25,True,-1),(25,True,True),(25,True,float('inf'))]:
            with self.subTest(heading=heading,fresh=fresh,age=age):
                # An invalid latest record must not revive the older valid heading.
                self.assertIsNone(self.heading([(900,1,1,90,True,0),
                                                (1000,2,1,heading,fresh,age)])['droneHeadingDeg'])

    def test_loader_reads_aircraft_heading_and_clears_at_session_boundaries(self):
        root=Path(__file__).resolve().parent.parent/'exports'
        root.mkdir(exist_ok=True)
        orientation=dict(event='orientation_cycle',epochMs=1000,session=1,
                         aircraftYawDeg=-90,gimbalYawDeg=42,
                         aircraftAttitudeFresh=True,aircraftAttitudeAgeMs=10)
        packet=dict(event='lora_packet',epochMs=1000,session=1,
                    sequence=1,latitude=-27.95,longitude=153.43,accepted=True)
        rows=[packet,orientation,packet,dict(event='session_end',epochMs=1000,session=1),
              packet,orientation,dict(event='session_start',epochMs=1000,session=1),packet]
        with tempfile.TemporaryDirectory(dir=root) as folder,redirect_stdout(io.StringIO()):
            Path(folder,'a.jsonl').write_text('\n'.join(json.dumps(r) for r in rows),encoding='utf-8')
            Path(folder,'b.jsonl').write_text(json.dumps(packet),encoding='utf-8')
            points,_=load_packets(Path(folder),False,timezone.utc)
        self.assertEqual([p['droneHeadingDeg'] for p in points],[None,270,None,None,None])


if __name__=='__main__':
    unittest.main()
