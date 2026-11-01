"""Seaward filtering uses logged orientation and original GPS arrow direction."""
from contextlib import redirect_stdout
from datetime import datetime, timezone
import io
import json
from pathlib import Path
import tempfile
import unittest

from surfer_gps_map import load_packets, select_packets
from surfer_map_filters import map_filter_data, seaward_motion


class SeawardTests(unittest.TestCase):
    def test_logged_orientation_respects_source_line_and_session_boundaries(self):
        def packet(t, **extra):
            return dict(event='lora_packet', epochMs=t, sequence=t,
                        latitude=-27.95, longitude=153.43, accepted=True, **extra)

        def cycle(t, bearing, session=1):
            return dict(event='movement_cycle', epochMs=t, session=session, seawardBearingDeg=bearing)

        records=[packet(500), cycle(1000,90), packet(1500), packet(2000),
                 cycle(2000,270), packet(2100), cycle(2200,None), packet(2300),
                 cycle(2400,90), dict(event='session_end',epochMs=2500,session=1),
                 packet(2600), dict(event='session_start',epochMs=2700,session=2),
                 packet(2800), cycle(2900,True,2), packet(3000),
                 cycle(3100,45,2), packet(3200,session=1), packet(3300,session=2)]
        root=Path(__file__).resolve().parent.parent/'exports'
        root.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=root) as folder, redirect_stdout(io.StringIO()):
            path=Path(folder)/'flight.jsonl'
            path.write_text('\n'.join(json.dumps(r) for r in records),encoding='utf-8')
            other=Path(folder)/'other.jsonl'
            other.write_text(json.dumps(packet(1500)),encoding='utf-8')
            points,_=load_packets(Path(folder),False,timezone.utc)
        flight=[p for p in points if Path(p['sourceFile']).name=='flight.jsonl']
        self.assertEqual([p['seawardBearingDeg'] for p in flight],
                         [None,90,90,270,None,None,None,None,None,45])
        self.assertIsNone(next(p for p in points if Path(p['sourceFile']).name=='other.jsonl')['seawardBearingDeg'])

    def test_direction_projection_wraparound_sideways_and_unknown(self):
        for bearing,sea,expected in [(350,10,True),(170,350,False),(90,90,True),
                                     (270,90,False),(0,90,False),(180,90,False),
                                     (90.000001,0,False),(89,90,True),
                                     (None,90,None),(90,None,None),(float('nan'),90,None),
                                     (True,90,None),(90,float('inf'),None)]:
            with self.subTest(bearing=bearing,sea=sea):
                self.assertIs(seaward_motion(dict(surferNextBearingDeg=bearing,seawardBearingDeg=sea)),expected)

    def test_selection_uses_next_original_receipt_even_outside_selected_window(self):
        points=[]
        for i,lon in enumerate([153.43,153.4301,153.4299,153.4299]):
            t=10000+i*1000
            points.append(dict(time_ms=t,receivedTimestamp=datetime.fromtimestamp(t/1000,timezone.utc).isoformat(),
                               sourceFile='flight',sourceLine=i+1,latitude=-27.95,longitude=lon,
                               accepted=True,sequence=i,seawardBearingDeg=90))
        _,selected=select_packets(points,'1970-01-01 00:00:10',0,timezone.utc)
        self.assertEqual(len(selected),1)
        self.assertIs(map_filter_data(selected)['points'][0]['seaward'],True)
        _,all_points=select_packets(points,'1970-01-01 00:00:10',3,timezone.utc)
        rows=map_filter_data(all_points)['points']
        self.assertEqual([p['seaward'] for p in rows],[True,False,None,None])
        # Subsampling must not replace S0's eastward original neighbour with S2.
        self.assertIs(map_filter_data([all_points[0],all_points[2]])['points'][0]['seaward'],True)


if __name__=='__main__':
    unittest.main()
