"""Camera vs aircraft yaw, causal evidence, shared cards and subtitle exports."""
from contextlib import redirect_stdout
from datetime import timezone
import io
import json
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

from surfer_gps_map import (camera_heading_error, camera_heading_error_text,
                            load_packets, make_svg, position_detail_lines)
from surfer_subtitles import PacketIndex, FIELD_IDS
from surfer_subtitle_layout import card_events, BACKGROUND_ALPHA
from surfer_map_app import MapApp
from test_surfer_subtitles import packet, drones, clock


class CameraHeadingTests(unittest.TestCase):
    def point(self, **changes):
        p=packet(1000, latitude=-27.95161, longitude=153.42996,
                 droneLatitude=-27.951364983393578, droneLongitude=153.42984554415915,
                 droneHeadingDeg=153.2, cameraHeadingDeg=151.3, point='S1', dronePoint='D1')
        p.update(changes)
        return p

    def test_actual_frame_uses_camera_yaw_not_aircraft(self):
        p=self.point()
        self.assertAlmostEqual(camera_heading_error(p),6.27696135079,places=8)
        self.assertEqual(camera_heading_error_text(p),'Camera heading error: 6.3° left of surfer')
        p['droneHeadingDeg']=90
        self.assertAlmostEqual(camera_heading_error(p),6.27696135079,places=8)

    def test_north_wrap_direction_alignment_and_opposite(self):
        p=self.point(latitude=.001,longitude=0,droneLatitude=0,droneLongitude=0)
        for heading,error,suffix in [(359,1,'1.0° left of surfer'),(1,-1,'1.0° right of surfer'),
                                     (0,0,'0.0° — aligned with surfer'),(180,-180,'180.0° — opposite surfer')]:
            with self.subTest(heading=heading):
                p['cameraHeadingDeg']=heading
                self.assertAlmostEqual(camera_heading_error(p),error)
                self.assertEqual(camera_heading_error_text(p),'Camera heading error: '+suffix)
        self.assertIn('0.0° — aligned',camera_heading_error_text(dict(p,cameraHeadingDeg=359.99)))

    def test_missing_invalid_or_coincident_positions_are_unavailable(self):
        for key,value in [('cameraHeadingDeg',None),('cameraHeadingDeg',float('nan')),
                          ('cameraHeadingDeg',True),('latitude',None),('longitude',181),
                          ('droneLatitude',91),('droneLongitude',float('inf'))]:
            with self.subTest(key=key,value=value):
                self.assertIsNone(camera_heading_error(self.point(**{key:value})))
        self.assertEqual(camera_heading_error_text(self.point(droneLatitude=-27.95161,droneLongitude=153.42996)),
                         'Camera heading error: unavailable')

    def test_loader_camera_freshness_sessions_order_and_no_fallback(self):
        ori=dict(event='orientation_cycle',epochMs=1000,session=1,gimbalYawDeg=-208.7,
                 gimbalAttitudeFresh=True,gimbalAttitudeAgeMs=100,
                 aircraftYawDeg=153.2,aircraftAttitudeFresh=True,aircraftAttitudeAgeMs=0,
                 gimbalYawRelativeToAircraftDeg=.7)
        gps=dict(event='lora_packet',epochMs=1000,session=1,sequence=1,
                 latitude=-27.95161,longitude=153.42996,accepted=True)
        rows=[gps,ori,gps,dict(gps,epochMs=2400),dict(gps,epochMs=2401),
              dict(ori,epochMs=2500,gimbalAttitudeFresh=False),dict(gps,epochMs=2500),
              dict(ori,epochMs=2600,gimbalYawDeg=None),dict(gps,epochMs=2600),
              dict(ori,epochMs=2700),dict(gps,epochMs=2700,session=2),
              dict(event='session_end',epochMs=2700,session=1),dict(gps,epochMs=2700)]
        with tempfile.TemporaryDirectory() as folder,redirect_stdout(io.StringIO()):
            Path(folder,'a.jsonl').write_text('\n'.join(json.dumps(r) for r in rows),encoding='utf-8')
            Path(folder,'b.jsonl').write_text(json.dumps(gps),encoding='utf-8')
            points,_=load_packets(Path(folder),False,timezone.utc)
        one=[p for p in points if Path(p['sourceFile']).name=='a.jsonl']
        self.assertEqual([p['cameraHeadingDeg'] for p in one],[None,151.3,151.3,None,None,None,None,None])
        self.assertEqual(one[1]['cameraHeadingSourceLine'],2)
        self.assertEqual(one[1]['droneHeadingDeg'],153.2)
        self.assertIsNone(next(p for p in points if Path(p['sourceFile']).name=='b.jsonl')['cameraHeadingDeg'])

    def test_same_text_in_markers_hover_and_translucent_subtitle_card(self):
        p=self.point();expected='Camera heading error: 6.3° left of surfer'
        self.assertIn(expected,position_detail_lines(p))
        svg=ET.fromstring(make_svg([p]))
        for kind in ('surfer-marker','drone-marker','pair-line'):
            node=next(n for n in svg.iter() if n.get('class')==kind)
            self.assertIn(expected,node.get('data-compact' if kind=='pair-line' else 'data-detail'))
        ds=drones();ds['flight.jsonl'][0].update(latitude=p['droneLatitude'],longitude=p['droneLongitude'])
        index=PacketIndex([p,dict(p,time_ms=2000,cameraHeadingDeg=170,sequence=2)],ds,['camera_heading_error'])
        added,age,_=index.render(1999,clock(1999),'DJI_0666.MP4','00:00:00,000')
        self.assertIn('<b>'+expected+'</b>',added)
        self.assertEqual(age,999)
        self.assertNotIn('right of surfer',added)
        later,_,_=index.render(2000,clock(2000),'DJI_0666.MP4','00:00:00,033')
        self.assertIn('12.4° right of surfer',later)
        ass=card_events(0,1000,added,'Camera metadata')
        self.assertIn(BACKGROUND_ALPHA,ass)
        self.assertIn('6.3°',ass)
        self.assertIn('left',ass)
        self.assertIn(r'\b1',ass)

    def test_export_selection_migrates_once_and_deselection_persists(self):
        self.assertIn('camera_heading_error',FIELD_IDS)
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);(root/'preferences.json').write_text(json.dumps({'subtitle_fields':['heading','tilt']}),encoding='utf-8')
            app=MapApp(app_dir=root)
            self.assertEqual(app.config['subtitle_fields'],['heading','camera_heading_error','tilt'])
            app.config['subtitle_fields']=['heading','tilt'];app.save_preferences()
            self.assertEqual(MapApp(app_dir=root).config['subtitle_fields'],['heading','tilt'])


if __name__=='__main__':unittest.main()
