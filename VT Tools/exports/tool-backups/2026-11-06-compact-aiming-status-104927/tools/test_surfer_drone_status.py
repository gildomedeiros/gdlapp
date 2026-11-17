import io
import json
import tempfile
import unittest
import xml.etree.ElementTree as ET
from contextlib import redirect_stdout
from datetime import timezone
from pathlib import Path

from surfer_drone_status import annotate_drone_status, drone_status_lines, status_record
from surfer_gps_map import load_packets, make_svg


class DroneStatusTests(unittest.TestCase):
    def row(self, event, t=1000, line=1, session=1, **fields):
        return status_record(dict(event=event, session=session, cycleId=t, **fields), t, line)

    def point(self, t=1045, line=99, session=None):
        return dict(time_ms=t, sourceLine=line, session=session)

    def tracking(self, **fields):
        return self.row('aiming_cycle', state='AIMING', reason='aiming', decision='riding_correction',
                        submitted=True, submittedYawRate=-1.6, reverseBlocked=False, reverseBlockRemainingMs=2000, **fields)

    def waiting(self, **fields):
        values=dict(line=2, phase='WAITING', reason='ride_detected', rideRemainingMs=8744,
                    currentBoundaryDistanceM=33.818, retreatBoundaryBlocked=False, retreatBoundaryStatus='inactive')
        values.update(fields)
        return self.row('movement_cycle', **values)

    def lines(self, records, p=None):
        p=p or self.point()
        annotate_drone_status([p], records)
        return drone_status_lines(p)

    def test_screenshot_ride_wait_is_distinct_from_boundary_block(self):
        lines=self.lines([self.tracking(), self.waiting()])
        self.assertEqual(lines, ['Aiming: AIMING — tracking surfer during ride · yaw -1.6°/s sent',
            'Come to Me: WAITING — ride detected · 8.7 s left', 'Boundary: no block reported · 33.8 m seaward'])
        self.assertNotIn('2.0 s left',lines[0], 'Reverse timer is not a block while reverseBlocked=false')

    def test_approach_can_be_boundary_blocked_while_retreat_is_inactive(self):
        lines=self.lines([self.tracking(), self.waiting(reason='destination_central_boundary',
            pathCheckStatus='blocked', pathBlockReason='destination_central_boundary', currentBoundaryDistanceM=0)])
        self.assertEqual(lines[1],'Come to Me: WAITING — BLOCKED: destination beyond boundary')
        self.assertEqual(lines[2],'Boundary: BLOCKED — destination beyond boundary · at line')

    def test_old_path_block_does_not_override_current_ride_or_pause(self):
        self.assertNotIn('BLOCKED','\n'.join(self.lines([self.tracking(),self.waiting(
            pathCheckStatus='blocked',pathBlockReason='destination_central_boundary')])))
        paused=self.row('aiming_cycle',state='PAUSED',reason='target_stale',decision='no_command',submitted=False)
        lines=self.lines([paused,self.waiting(paused=True,reason='destination_central_boundary',retreatBoundaryBlocked=True)])
        self.assertEqual(lines[1],'Come to Me: PAUSED — target stale')
        self.assertEqual(lines[2],'Boundary: not evaluated — aiming paused')

    def test_retreat_block_slowing_and_anchor_unavailable(self):
        for status,blocked,expected in [('blocked',True,'BLOCKED — retreat'),('slowing',False,'slowing retreat'),
                                       ('anchor_unavailable',True,'unknown — anchor unavailable')]:
            retreat=self.row('retreat_cycle',line=3,boundaryStatus=status,boundaryBlocked=blocked)
            lines=self.lines([self.tracking(),self.waiting(),retreat])
            self.assertTrue(lines[2].startswith('Boundary: '+expected),lines[2])

    def test_non_boundary_route_failure_is_not_labelled_boundary_block(self):
        lines=self.lines([self.tracking(),self.waiting(reason='route_surfer_clearance')])
        self.assertIn('BLOCKED: route too close to surfer',lines[1])
        self.assertIn('no block reported',lines[2])

    def test_cooldown_and_submitted_commands(self):
        lines=self.lines([self.tracking(),self.waiting(reason='retreat_or_cooldown',retreatActive=False,retreatCooldownRemainingMs=2000)])
        self.assertIn('retreat cooldown · 2.0 s left',lines[1])
        lines=self.lines([self.tracking(),self.waiting(phase='APPROACHING',reason='moving_saved_route_aiming_surfer',submittedForwardMps=3,submittedRightMps=4)])
        self.assertIn('move command 5.0 m/s',lines[1])
        lines=self.lines([self.tracking(),self.waiting(phase='APPROACHING',reason='moving_saved_route_aiming_surfer')])
        self.assertIn('no move command logged',lines[1])

    def test_stale_unknown_no_future_or_cross_session_claims(self):
        self.assertEqual(self.lines([]),['Aiming: unknown (no log)','Come to Me: unknown (no log)','Boundary: unknown'])
        lines=self.lines([self.tracking(),self.waiting()],self.point(2201))
        self.assertIn('log 1.2 s old',lines[0]);self.assertIn('log 1.2 s old',lines[1]);self.assertEqual(lines[2],'Boundary: unknown')
        points=[self.point(1000,1),self.point(1000,2),self.point(1000,3)]
        annotate_drone_status(points,[self.tracking(line=2),self.waiting(line=3)])
        self.assertEqual(points[0]['droneStatus'],{})
        self.assertEqual(set(points[1]['droneStatus']),{'aiming'})
        self.assertEqual(set(points[2]['droneStatus']),{'aiming','movement'})
        p=self.point(1200,session=2)
        annotate_drone_status([p],[self.tracking(),self.waiting(),self.tracking(t=1100,session=2)])
        self.assertEqual(set(p['droneStatus']),{'aiming'})
        self.assertEqual(self.lines([self.tracking(session=2)],self.point(session=1))[0],'Aiming: unknown (no log)')

    def test_stop_and_log_end_invalidate_active_state(self):
        records=[self.tracking(),self.waiting(),self.row('stop',t=1020,line=5,detail='session=1 reason=returning_home')]
        lines=self.lines(records)
        self.assertEqual(lines[0],'Aiming: STOPPING — returning home')
        self.assertEqual(lines[1],'Come to Me: unknown (no log)')
        self.assertEqual(self.lines(records+[self.row('session_end',t=1030,line=6)])[0],'Aiming: unknown (no log)')

    def test_loader_isolates_files_and_cards_include_status(self):
        root=Path(__file__).resolve().parent.parent/'exports';root.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=root) as folder, redirect_stdout(io.StringIO()):
            packet=dict(event='lora_packet',epochMs=1045,sequence=1,latitude=-27.95,longitude=153.43,accepted=True)
            control=dict(event='aiming_cycle',epochMs=1000,session=1,state='AIMING',decision='aligned_zero',submitted=True,submittedYawRate=0)
            Path(folder,'a.jsonl').write_text(json.dumps(control)+'\n'+json.dumps(packet),encoding='utf-8')
            Path(folder,'b.jsonl').write_text(json.dumps(packet),encoding='utf-8')
            points,_=load_packets(Path(folder),False,timezone.utc)
        self.assertEqual(len(points),2)
        self.assertIn('aligned',drone_status_lines(points[0])[0])
        self.assertIn('no log',drone_status_lines(points[1])[0])
        points[0].update(point='S0',dronePoint='D0',droneLatitude=-27.9502,droneLongitude=153.43)
        doc=ET.fromstring(make_svg(points[:1]))
        for kind in ('surfer-marker','drone-marker'):
            marker=next(n for n in doc.iter() if n.get('class')==kind)
            self.assertIn('Aiming: AIMING — aligned',marker.get('data-detail'))
        pair=next(n for n in doc.iter() if n.get('class')=='pair-line')
        self.assertIn('Aiming: AIMING — aligned',pair.get('data-compact'))


if __name__=='__main__': unittest.main()
