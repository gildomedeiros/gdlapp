import json
import math
from pathlib import Path
import tempfile
import unittest
from vtc_map import load_file,csv_data,App
from vtc_telemetry import optical_zoom,mode


class TelemetryTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name)
        self.path=self.root/'VTC_modern.jsonl'
        self.settings=dict(latitude='0',longitude='0',landmarkLat='0',landmarkLon='.001',height='3',targetHeight='1',gpsAge='2',
            aimingUnits='metres',magnitudeMetres='2',panToleranceMetres='1',tiltToleranceMetres='.5',acquisitionTriggerMetres='3',
            zoomMode='bands',wideFrameFactor='1.648',desiredFrameWidthMetres='40',zoomMapping=json.dumps([dict(zoomX=1,ccapiValue=0),dict(zoomX=8,ccapiValue=100)]))
        self.records=[dict(event='session_start',epochMs=0,version='1.0.25'),dict(event='settings_snapshot',epochMs=1,settings=self.settings),
            dict(event='settings_public_saved',epochMs=2,readbackVerified=True),
            dict(event='camera_zoom_response',epochMs=100,zoom=100),
            dict(event='zoom_decision',epochMs=110,decision=dict(zoomMode='bands',requestedOpticalZoom=8,requestedCcapi=100,selectedBandIndex=2,bandMinDistanceMetres=60,bandMaxDistanceMetres=200,bandZoomX=8,bufferMetres=5,multiplier=1,action='Send zoom')),
            dict(event='gps_accepted',epochMs=200,telemetry=dict(sequence=1,latitude=0,longitude=.001)),
            dict(event='camera_zoom_command',epochMs=201,requestedCcapi=50),
            dict(event='camera_zoom_ack',epochMs=210,requestedCcapi=50),
            dict(event='gps_accepted',epochMs=220,telemetry=dict(sequence=2,latitude=0,longitude=.002)),
            dict(event='camera_zoom_confirmed',epochMs=230,readbackCcapi=50),
            dict(event='camera_zoom_response',epochMs=240,zoom=50),
            dict(event='framing_observation',epochMs=250,framing=dict(appliedOffsetDegrees=1,mode='AUTO')),
            dict(event='gps_accepted',epochMs=260,telemetry=dict(sequence=3,latitude=0,longitude=.002)),
            dict(event='camera_zoom_error',epochMs=270,exception='timeout'),
            dict(event='gps_accepted',epochMs=280,telemetry=dict(sequence=4,latitude=0,longitude=.002)),
            dict(event='camera_zoom_response',epochMs=290,zoom=50),
            dict(event='gps_accepted',epochMs=6000,telemetry=dict(sequence=5,latitude=0,longitude=.002))]
        self.path.write_text('\n'.join(json.dumps(r) for r in self.records),encoding='utf-8')

    def test_mapping_shared_values_strings_and_no_default_assumption(self):
        mapping=[dict(zoomX=1,ccapiValue=0),dict(zoomX=3.2,ccapiValue=60),dict(zoomX=3.3,ccapiValue=60),dict(zoomX=65,ccapiValue=200)]
        self.assertAlmostEqual(optical_zoom(mapping,60),math.sqrt(3.2*3.3))
        self.assertEqual(optical_zoom(json.dumps(mapping),60),optical_zoom(mapping,60))
        self.assertIsNone(optical_zoom(None,60));self.assertIsNone(optical_zoom([dict(zoomX=1,ccapiValue=0)],0))
        self.assertEqual(mode(dict(autoFit='1')),'auto-fit');self.assertEqual(mode(dict(autoZoom='1')),'bands');self.assertEqual(mode(dict(zoomMode='off',autoFit='1')),'off')

    def test_metre_conversion_changes_with_range_and_no_hfov_required(self):
        p,q=load_file(self.path)['points'][:2]
        self.assertEqual(p['magnitudeMetres'],2)
        self.assertAlmostEqual(p['magnitudeDegrees'],math.degrees(math.atan2(2,p['distance'])))
        self.assertLess(q['magnitudeDegrees'],p['magnitudeDegrees'])
        self.assertIsNone(p['frameWidth']);self.assertIsNotNone(p['zoomFrameWidth'])
        self.assertAlmostEqual(p['zoomFrameWidth'],p['distance']*1.648/8)
        self.assertIn('Magnitude: 2.00 m', '\n'.join(p['details']))
        self.assertEqual(p['settingsSaveStatus'],'Saved and verified')

    def test_no_future_zoom_or_command_as_readback_and_staleness(self):
        points=load_file(self.path)['points']
        self.assertEqual(points[0]['zoomRequestedCcapi'],None)
        self.assertEqual(points[1]['zoomRequestedCcapi'],50)
        self.assertEqual(points[1]['zoomReadbackCcapi'],100)
        self.assertEqual(points[1]['opticalZoom'],8)
        self.assertEqual(points[2]['zoomConfirmedCcapi'],50)
        self.assertAlmostEqual(points[2]['opticalZoom'],math.sqrt(8))
        self.assertAlmostEqual(points[2]['appliedOffsetMetres'],points[2]['distance']*math.tan(math.radians(1)))
        self.assertIsNone(points[3]['opticalZoom']);self.assertFalse(points[3]['zoomReadbackFresh'])
        self.assertIsNone(points[4]['zoomFrameWidth']);self.assertEqual(points[4]['zoomReadbackAgeMs'],5710)
        self.assertIsNotNone(points[4]['fovWidthMetres'])
        self.assertFalse(points[4]['fovFresh'])

    def test_diagnostics_include_post_gps_events_and_exports_have_new_fields(self):
        self.records.append(dict(event='camera_zoom_command_error',epochMs=7000,exception='late timeout'))
        self.path.write_text('\n'.join(json.dumps(r) for r in self.records),encoding='utf-8')
        data=load_file(self.path)
        self.assertEqual(data['diagnostics'][-1]['event'],'camera_zoom_command_error')
        csv=csv_data(data['points']).decode('utf-8-sig');self.assertIn('zoomFrameWidth',csv);self.assertIn('magnitudeMetres',csv)
        app=App(self.root);app.catalog(dict(folder=str(self.root)));r=app.load(dict(file='0',selections=[]))
        exported,kind=app.export(dict(snapshot=r['id'],format='events'))
        self.assertEqual(kind,'json');self.assertIn(b'late timeout',exported)


if __name__=='__main__':unittest.main()
