import math
import unittest
from vtc_map import aim_metres,relative_metres


class MetreAimTests(unittest.TestCase):
    def test_pan_sign_wrap_and_target_plane(self):
        p=dict(distance=100,bearing=359,settings=dict(height=3,targetHeight=1))
        angle=math.degrees(math.atan2(2,100))
        pan,tilt=aim_metres(p,359+angle,0)
        self.assertAlmostEqual(pan,2);self.assertAlmostEqual(tilt,2)
        self.assertEqual(relative_metres(pan,'right','left'),'2.00 m right of surfer')
        pan,_=aim_metres(p,359-angle,0)
        self.assertEqual(relative_metres(pan,'right','left'),'2.00 m left of surfer')

    def test_tilt_above_below_and_missing_geometry(self):
        p=dict(distance=100,bearing=0,settings=dict(height=3,targetHeight=1))
        _,v=aim_metres(p,0,math.degrees(math.atan2(-1,100)))
        self.assertEqual(relative_metres(v,'above','below'),'1.00 m above surfer')
        _,v=aim_metres(p,0,math.degrees(math.atan2(-3,100)))
        self.assertEqual(relative_metres(v,'above','below'),'1.00 m below surfer')
        self.assertEqual(aim_metres(p,None,0),(None,None))
        self.assertEqual(aim_metres(p,180,0),(None,None))


if __name__=='__main__':unittest.main()
