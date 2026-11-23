package au.com.gildo.fm;

import org.junit.Test;
import static org.junit.Assert.*;

public class LandmarkCalibrationTest {
    @Test public void derivesTrueBearingAndElevationFromTheLandmark() {
        GoTo.Point pivot=new GoTo.Point(0,0,10);
        GoTo.Point landmark=new GoTo.Point(0,0.001,0);
        LandmarkCalibration calibration=LandmarkCalibration.calculate(pivot,landmark);
        assertEquals(90,calibration.bearing(),1e-9);
        assertEquals(-5.139,calibration.elevation(),0.001);
    }

    @Test public void blockedGotoIncludesTheActualLastCalibrationFailure() {
        String cause="GTi axis 1 is uninitialized";
        String blocked=LandmarkCalibration.requiredMessage(cause);
        assertTrue(blocked.contains(cause));
        assertTrue(blocked.contains("Calibrate here"));
        assertFalse(LandmarkCalibration.requiredMessage("").contains("Last calibration error"));
    }

    @Test(expected=IllegalArgumentException.class)
    public void rejectsMountPositionAsLandmark() {
        LandmarkCalibration.calculate(new GoTo.Point(0,0,2),new GoTo.Point(0,0,0));
    }

    @Test public void savedReferenceIsTiedToTheCalibratedSetup() {
        assertEquals(FmService.KEYS.length,FmService.DEFAULTS.length);
        String[] calibrated=FmService.DEFAULTS.clone();
        MountCoordinates.apply("0,0",calibrated);
        calibrated[LandmarkCalibration.PIVOT_ELEVATION]="10";
        MountCoordinates.apply("0,0.001",calibrated,LandmarkCalibration.LANDMARK_LAT,
            LandmarkCalibration.LANDMARK_LON,LandmarkCalibration.COORDINATES);
        calibrated[LandmarkCalibration.LANDMARK_HEIGHT]="0";
        new LandmarkCalibration(90,-5.139).saveReference(calibrated,new GoTo.Encoders(0x800000,0x800012));
        assertTrue(LandmarkCalibration.hasReference(calibrated));

        String[] edited=calibrated.clone();
        edited[LandmarkCalibration.LANDMARK_LON]="0.00100";
        assertFalse(LandmarkCalibration.clearStaleReference(calibrated,edited));
        edited[LandmarkCalibration.LANDMARK_HEIGHT]="1";
        assertFalse(LandmarkCalibration.hasReference(edited));
        assertTrue(LandmarkCalibration.clearStaleReference(calibrated,edited));
        assertEquals("",edited[LandmarkCalibration.PAN_COUNT]);
        assertEquals("1",edited[LandmarkCalibration.LANDMARK_HEIGHT]);
    }
}
