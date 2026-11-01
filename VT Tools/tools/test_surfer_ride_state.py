"""Ride detector state at GPS receipt, distinct from padded ride-selection windows."""
from contextlib import redirect_stdout
from datetime import datetime, timezone
import io
import json
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

from surfer_gps_map import load_packets, mark_ride_active, make_svg, select_packets


class RideStateTests(unittest.TestCase):
    def test_state_uses_same_source_and_prior_line_and_session(self):
        states={'a':[(2000,20,True,1),(1000,10,False,1),(2000,30,False,1),
                     (3000,40,None,1),(4000,50,True,2)]}
        cases=[
            (900,1,'a',1,None), (1500,15,'a',1,False),
            (2000,19,'a',1,False), (2000,20,'a',1,True),
            (2000,25,'a',1,True), (2000,31,'a',1,False),
            (3100,41,'a',1,None), (4100,51,'a',1,None),
            (4100,51,'a',2,True), (4100,51,'b',2,None)]
        packets=[dict(time_ms=t,sourceLine=line,sourceFile=source,session=session)
                 for t,line,source,session,expected in cases]
        mark_ride_active(packets,states,timezone.utc)
        self.assertEqual([p['waveRiding'] for p in packets],[row[-1] for row in cases])
        self.assertEqual(packets[4]['rideStateSourceLine'],20)
        self.assertEqual(packets[4]['rideStateTimestamp'],'1970-01-01T00:00:02.000+00:00')
        self.assertIsNone(packets[-1]['rideStateTimestamp'])

    def test_loader_reads_riding_bool_and_resets_at_session_boundaries(self):
        def packet(t):
            return dict(event='lora_packet',epochMs=t,sequence=t,latitude=-27.95,
                        longitude=153.43,accepted=True)
        records=[
            packet(500),
            dict(event='movement_cycle',epochMs=1000,session=1,riding=False),
            packet(1500),
            packet(2000),
            dict(event='movement_cycle',epochMs=2000,session=1,riding=True,rideEvent='ride_started'),
            packet(2100),
            dict(event='movement_cycle',epochMs=2200,session=1,riding=False),
            packet(2300),
            dict(event='movement_cycle',epochMs=2400,session=1,riding=True),
            dict(event='session_end',epochMs=2500,session=1),
            packet(2600),
            dict(event='session_start',epochMs=2700,session=2),
            packet(2800),
            dict(event='movement_cycle',epochMs=2900,session=2,riding='true'),
            packet(3000)]
        root=Path(__file__).resolve().parent.parent/'exports'
        root.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=root) as folder, redirect_stdout(io.StringIO()):
            source=Path(folder)/'flight.jsonl'
            source.write_text('\n'.join(json.dumps(row) for row in records),encoding='utf-8')
            points,_=load_packets(Path(folder),False,timezone.utc)
        self.assertEqual([p['waveRiding'] for p in points],[None,False,False,True,False,None,None,None])
        # The padded sampling window does not turn pre-start packets into riding points.
        self.assertTrue(all(p['waveRideStartTimesMs']==[2000] for p in points))

    def test_svg_preserves_mixed_shared_positions_and_explicit_unknown(self):
        packets=[]
        for i,ride in enumerate([False,True,True,None]):
            t=10000+i*1000
            packets.append(dict(time_ms=t,sourceFile='fixture',sourceLine=i+1,
                receivedTimestamp=datetime.fromtimestamp(t/1000,timezone.utc).isoformat(),
                latitude=-27.95+(0 if i<2 else i*.00001),longitude=153.43,
                sequence=i,accepted=True,waveRiding=ride))
        _,points=select_packets(packets,'1970-01-01 00:00:10',3,timezone.utc)
        doc=ET.fromstring(make_svg(points))
        markers=[n for n in doc.iter() if n.get('class')=='surfer-marker']
        by_ids={n.get('data-pairs'):n for n in markers}
        def ring(node):
            return next((n for n in node if n.get('class')=='ride-ring'),None)
        self.assertEqual(ring(by_ids['S0 S1']).get('data-ride-state'),'mixed')
        self.assertEqual(ring(by_ids['S0 S1']).get('stroke-dasharray'),'4 3')
        self.assertEqual(ring(by_ids['S2']).get('data-ride-state'),'all')
        self.assertIsNone(ring(by_ids['S3']))
        self.assertIn('Ride detected: NO',by_ids['S0 S1'].get('data-detail'))
        self.assertIn('Ride detected: YES',by_ids['S0 S1'].get('data-detail'))
        self.assertIn('Ride detected: unknown',by_ids['S3'].get('data-detail'))


if __name__=='__main__':
    unittest.main()

