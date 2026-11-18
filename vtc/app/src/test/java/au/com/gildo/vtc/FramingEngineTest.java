package au.com.gildo.vtc;

import org.junit.Test;
import java.util.Map;
import static org.junit.Assert.*;

public class FramingEngineTest {
    private static final OffshoreHeading NORTH = new OffshoreHeading(0);
    private static final double DEG_PER_METRE=1/111194.9266;
    private static FramingEngine engine(String magnitude) {
        return new FramingEngine(new Settings(Map.of("offshoreHeadingDegrees","0","magnitudeMetres",magnitude)));
    }
    private static void fix(FramingEngine engine,long time,double east,double north,double bearing) {
        engine.observe(time,time,north*DEG_PER_METRE,east*DEG_PER_METRE,bearing);
    }

    @Test public void projectionCountsAlongshoreAndShorewardAndIgnoresSeaward() {
        assertEquals(0,NORTH.qualifyingDistance(0,0,10*DEG_PER_METRE,0),.01);
        assertEquals(10,NORTH.qualifyingDistance(0,0,-10*DEG_PER_METRE,0),.01);
        assertEquals(6,NORTH.qualifyingDistance(0,0,8*DEG_PER_METRE,6*DEG_PER_METRE),.01);
        assertEquals(10,NORTH.qualifyingDistance(0,0,-8*DEG_PER_METRE,-6*DEG_PER_METRE),.01);
        OffshoreHeading east=new OffshoreHeading(90);
        assertEquals(0,east.qualifyingDistance(0,0,0,10*DEG_PER_METRE),.01);
        assertEquals(6,east.qualifyingDistance(0,0,-6*DEG_PER_METRE,8*DEG_PER_METRE),.01);
    }

    @Test public void twoFreshEstimatesStartFramingAndSlowdownEndsIt() {
        FramingEngine f=engine("2");
        f.distance(100);
        fix(f,0,0,0,359);
        fix(f,1000,4,0,0);
        assertFalse(f.riding);assertEquals(0,f.applied,0);
        fix(f,2000,8,0,1);
        assertTrue(f.riding);assertEquals(1,f.direction);
        assertEquals(AimUnits.degrees(2,100),f.applied,1e-9);
        fix(f,3000,8,0,1);
        assertTrue(f.riding);assertEquals(0,f.applied,0);
        fix(f,4000,8,0,1);
        assertTrue(f.riding);
        fix(f,5000,8,0,1);
        assertFalse(f.riding);
    }

    @Test public void duplicateFixesAndSeawardTravelCannotStartARide() {
        FramingEngine f=engine("2");
        fix(f,0,0,0,0);fix(f,1000,0,5,0);fix(f,2000,0,10,0);
        assertFalse(f.riding);assertEquals(0,f.speedKmh,.01);
        fix(f,3000,4,10,1);fix(f,3000,4,10,1);
        assertFalse(f.riding);assertEquals(1,f.startCount);
        fix(f,4000,8,10,2);
        assertTrue(f.riding);
        assertEquals(0,f.tick(7000),0);assertFalse(f.riding);
    }

    @Test public void clockwiseTurnsCounterclockwiseImmediatelyAndZeroMagnitudeDisablesLead() {
        for(String magnitude:new String[]{"2","0"}) {
            FramingEngine f=engine(magnitude);
            fix(f,0,0,0,0);fix(f,1000,4,0,1);fix(f,2000,8,0,2);
            assertTrue(f.riding);assertEquals(1,f.direction);
            fix(f,3000,4,0,1);
            assertEquals(-1,f.direction);
            assertEquals(-AimUnits.degrees(Double.parseDouble(magnitude),143),f.applied,1e-9);
        }
    }

    @Test public void rideNeedsAnOffshoreHeadingAndCanUseCustomConfirmationCount() {
        FramingEngine noLine=new FramingEngine(new Settings(Map.of()));
        fix(noLine,0,0,0,0);fix(noLine,1000,6,0,1);fix(noLine,2000,12,0,2);
        assertFalse(noLine.riding);
        FramingEngine custom=new FramingEngine(new Settings(Map.of("offshoreHeadingDegrees","0","rideStartConfirmations","1")));
        fix(custom,0,0,0,0);fix(custom,1000,6,0,0);
        assertTrue(custom.riding);assertEquals(0,custom.applied,0);
    }
}
