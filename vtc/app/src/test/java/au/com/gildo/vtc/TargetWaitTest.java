package au.com.gildo.vtc;

import org.junit.Test;
import static org.junit.Assert.*;

public class TargetWaitTest {
    private String target(Settings s,Calibration cal,double lat,double lon,double offset) {
        double range=Geo.distance(s.latitude,s.longitude,lat,lon);
        return TargetWait.blocker(s,cal,range,cal.targetPan(Geo.wrap(Geo.bearing(s.latitude,s.longitude,lat,lon)+offset)),
            Geo.elevation(range,s.height,s.targetHeight)+s.tiltOffset);
    }
    @Test public void recordedLeftLimitCrossingResumesOnTheReturningGpsFix() {
        Settings s=SavedLandmarkTest.home();Calibration cal=SavedLandmarkTest.bookmark(s).saved;
        TargetWait wait=new TargetWait();
        assertEquals(TargetWait.Change.READY,wait.update(target(s,cal,-28.0788,153.40085,0)));
        String outside=target(s,cal,-28.07889,153.4008,0);
        assertTrue(outside.contains("-91.75"));
        assertEquals(TargetWait.Change.PAUSED,wait.update(outside));assertTrue(wait.waiting());
        assertEquals(TargetWait.Change.WAITING,wait.update(target(s,cal,-28.07897,153.40086,0)));
        assertEquals(TargetWait.Change.RESUMED,wait.update(target(s,cal,-28.07897,153.40087,0)));
        assertFalse(wait.waiting());assertEquals(TargetWait.Change.READY,wait.update(null));
    }
    @Test public void rangeAndTiltLimitsAlsoReleaseWhenTheTargetReturnsInside() {
        Settings s=SavedLandmarkTest.home();Calibration cal=SavedLandmarkTest.bookmark(s).saved;
        TargetWait wait=new TargetWait();
        assertEquals(TargetWait.Change.PAUSED,wait.update(TargetWait.blocker(s,cal,14,0,-10)));
        assertEquals(TargetWait.Change.RESUMED,wait.update(TargetWait.blocker(s,cal,15,0,-10)));
        assertEquals(TargetWait.Change.PAUSED,wait.update(TargetWait.blocker(s,cal,2001,0,-10)));
        assertEquals(TargetWait.Change.RESUMED,wait.update(TargetWait.blocker(s,cal,2000,0,-10)));
        assertEquals(TargetWait.Change.PAUSED,wait.update(TargetWait.blocker(s,cal,100,0,-46)));
        assertEquals(TargetWait.Change.RESUMED,wait.update(TargetWait.blocker(s,cal,100,0,-45)));
    }
    @Test public void exactSectorBoundariesAreAllowedButNonFiniteInputsCannotResume() {
        Settings s=SavedLandmarkTest.home();Calibration cal=SavedLandmarkTest.bookmark(s).saved;
        assertNull(TargetWait.blocker(s,cal,100,-90,-45));assertNull(TargetWait.blocker(s,cal,100,90,20));
        assertNotNull(TargetWait.blocker(s,cal,100,-90.001,0));assertNotNull(TargetWait.blocker(s,cal,100,0,20.001));
        assertNotNull(TargetWait.blocker(s,cal,Double.NaN,0,0));assertNotNull(TargetWait.blocker(s,cal,100,Double.NaN,0));
        assertNotNull(TargetWait.blocker(s,cal,100,0,Double.NaN));
    }
    @Test public void northWrapAndAppliedFramingOffsetAreIncludedInTheLimitCheck() {
        Settings s=SavedLandmarkTest.home();Calibration cal=SavedLandmarkTest.bookmark(s).saved;
        assertNull(TargetWait.blocker(s,cal,100,cal.targetPan(359),-5));
        assertNull(TargetWait.blocker(s,cal,100,cal.targetPan(1),-5));
        double edge=Geo.wrap(cal.bearing-89.5);
        assertNull(TargetWait.blocker(s,cal,100,cal.targetPan(edge),-5));
        assertNotNull(TargetWait.blocker(s,cal,100,cal.targetPan(Geo.wrap(edge-1)),-5));
    }
    @Test public void cancellationClearsTheResumeTransition() {
        TargetWait wait=new TargetWait();wait.update("Outside limits");wait.reset();
        assertFalse(wait.waiting());assertEquals(TargetWait.Change.READY,wait.update(null));
    }
}
