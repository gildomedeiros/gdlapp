import unittest
from vtc_activity import Activity,lines


class ActivityTests(unittest.TestCase):
    def setUp(self):self.a=Activity()
    def card(self,time):return '\n'.join(lines(dict(activity=self.a.at(time),settings=dict(gpsAge='2',gpsGapTimeout='3'))))

    def test_ride_flag_reason_speed_and_no_future_state(self):
        self.a.observe('framing_observation',1000,dict(framing=dict(mode='RIDE',rideActive=False,startConfirmations=1,qualifyingSpeedKmh=9,reason='Confirming start')))
        before=self.a.at(1000)
        self.a.observe('framing_observation',1500,dict(framing=dict(mode='RIDE',rideActive=True,startConfirmations=2,qualifyingSpeedKmh=10,reason='Ride')))
        self.assertFalse(before['ride']['rideActive'])
        self.assertIn('Wave ride: YES · qualifying speed 10.0 km/h',self.card(1600))
        self.assertIn('STALE — last YES',self.card(6000))
        self.a.observe('aim_stopped',6100,{})
        self.assertNotIn('Wave ride: YES',self.card(6200))

    def test_goto_activation_completion_and_continuous_motion(self):
        self.a.observe('continuous_rates',900,dict(panDegreesPerSecond=2))
        self.a.observe('goto_captured',1000,dict(target=dict(kind='SURFER',source=dict(sequence=42)),acquisitionTriggerMetres=3))
        self.assertIn('GoTo: ACTIVE · SURFER',self.card(1100))
        self.assertIn('Continuous pan: INACTIVE',self.card(1100))
        self.a.observe('goto_completed',1200,{})
        self.a.observe('continuous_decision',1300,dict(panRateDegreesPerSecond=-.2,highSpeedPan=False))
        self.assertIn('GoTo: COMPLETED',self.card(1400))
        self.assertIn('Continuous pan: ACTIVE · left / anticlockwise',self.card(1400))
        self.assertEqual(self.a.at(1400)['activation']['sequence'],42)
        self.assertIn('UNKNOWN / stale',self.card(9000))

    def test_hold_and_reversal_zero_do_not_claim_panning(self):
        self.a.observe('continuous_decision',1000,dict(panRateDegreesPerSecond=0,holdPan=True))
        self.assertIn('Continuous pan: HOLD',self.card(1100))
        self.a.observe('continuous_decision',1200,dict(panRateDegreesPerSecond=0,holdPan=False,panReversalBlocked=True))
        self.assertIn('Continuous pan: WAITING — reversal blocked',self.card(1300))
        self.a.observe('stop_unconfirmed',1400,{})
        self.assertIn('UNKNOWN / stale motor report',self.card(1500))


if __name__=='__main__':unittest.main()
