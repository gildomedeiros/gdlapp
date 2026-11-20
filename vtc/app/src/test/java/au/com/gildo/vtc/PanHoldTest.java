package au.com.gildo.vtc;

import org.junit.Test;
import java.util.Map;
import static org.junit.Assert.*;

public class PanHoldTest {
    @Test public void packet5075KeepsClockwiseGoToAndMirrorKeepsAnticlockwiseGoTo() {
        double before=217.76717956655372,rider=218.45419770995346;
        double firstCamera=218.17062786656652,secondCamera=219.59475981101093;
        assertEquals("PR3",PanHold.rule(before,rider,firstCamera));
        String pr4=PanHold.rule(before,rider,secondCamera);
        assertEquals("PR4",pr4);
        assertTrue(PanHold.continues(pr4,1));
        assertFalse(PanHold.blocks(pr4,1));
        assertTrue(PanHold.blocks(pr4,-1));
        String pr2=PanHold.rule(Geo.wrap(-before),Geo.wrap(-rider),Geo.wrap(-secondCamera));
        assertEquals("PR2",pr2);
        assertTrue(PanHold.continues(pr2,-1));
        assertFalse(PanHold.blocks(pr2,-1));
        assertTrue(PanHold.blocks(pr2,1));
        assertFalse(PanHold.continues(pr4,0));
        assertFalse(PanHold.continues(pr2,0));
    }

    @Test public void validatesTheFourClockPathsAtDifferentCameraHeadings() {
        for (double heading : new double[]{0, 19, 181, 350}) {
            assertTrue(PanHold.movingTowardCentreline(Geo.wrap(heading - 30), heading, heading));
            assertTrue(PanHold.movingTowardCentreline(Geo.wrap(heading + 30), heading, heading));
            assertFalse(PanHold.movingTowardCentreline(Geo.wrap(heading + 30), Geo.wrap(heading + 60), heading));
            assertFalse(PanHold.movingTowardCentreline(Geo.wrap(heading - 30), Geo.wrap(heading - 60), heading));
        }
    }

    @Test public void worksAcrossNorthAndResumesNormalRulesAfterPassingCentreline() {
        assertTrue(PanHold.movingTowardCentreline(355, 1, 5));
        assertTrue(PanHold.movingTowardCentreline(5, 359, 355));
        assertFalse(PanHold.movingTowardCentreline(350, 2, 0));
        assertFalse(PanHold.movingTowardCentreline(10, 358, 0));
        assertFalse(PanHold.movingTowardCentreline(350, 350, 0));
        assertFalse(PanHold.movingTowardCentreline(0, 180, 0));
    }

    @Test public void holdsAboveAcquisitionThresholdWithNoOffshoreHeading() {
        Settings settings = new Settings(Map.of("latitude","0","longitude","0","acquisitionTriggerMetres","5"));
        GpsFeed.Fix previous = fix(1, 1000, 1, 350, null);
        double bearing = Geo.wrap(-AimUnits.degrees(6, 100));
        GpsFeed.Fix latest = fix(2, 1500, 1, bearing, previous);
        AimPlanner planner = new AimPlanner(settings);
        planner.distance(100);
        assertTrue(planner.shouldGoTo(Geo.delta(bearing,0),0,1500));
        assertTrue(PanHold.applies(latest, settings, 1500, 0, bearing));
    }

    @Test public void usesTwoFreshAcceptedFixesFromTheSameContinuity() {
        Settings settings = new Settings(Map.of("latitude","0","longitude","0","gpsAge","2"));
        GpsFeed.Fix previous = fix(1, 1000, 1, 350, null);
        GpsFeed.Fix latest = fix(2, 2900, 1, 355, previous);
        assertTrue(PanHold.applies(latest, settings, 2900, 0, 355));
        assertFalse(PanHold.applies(latest, settings, 3001, 0, 355));
        GpsFeed.Fix afterRestart = fix(2, 1500, 2, 355, previous);
        assertFalse(PanHold.applies(afterRestart, settings, 1500, 0, 355));
        assertFalse(PanHold.applies(previous, settings, 1500, 0, 350));
    }

    private static GpsFeed.Fix fix(int sequence, long received, long epoch, double bearing, GpsFeed.Fix previous) {
        double[] point = KmlDocument.destination(0, 0, bearing, 100);
        LoRaTelemetry packet = LoRaTelemetry.parse("RX,GPS," + sequence + "," + received + ","
            + point[0] + "," + point[1] + ",20,0.6,-55,12,0");
        return new GpsFeed.Fix(packet, received, epoch, previous);
    }
}
