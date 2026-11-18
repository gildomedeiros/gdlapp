package au.com.gildo.vtc;
import org.junit.Test;
import java.util.Map;
import static org.junit.Assert.*;

public class FixedGotoTest {
    @Test public void landmarkReturnUsesBothCalibratedAxesAndSignsWithNoGpsOrFramingOffset()throws Exception {
        for(String panSign:new String[]{"1","-1"})for(String tiltSign:new String[]{"1","-1"}) {
            Calibration c=calibration("panSign",panSign,"tiltSign",tiltSign,"landmarkHeight","0",
                "fixedOffset","1","tiltOffset","3","rangeMin","200");
            FixedGoto route=FixedGoto.landmark(c,1000);
            assertEquals(FixedGoto.Kind.LANDMARK,route.kind);assertNull(route.source);assertEquals(0,route.offset,0);
            assertEquals(0,route.pan,0);assertEquals(c.elevation,route.tilt,0);
            assertTrue("Landmark is allowed even below the surfer minimum range",route.range<200);
            GtiClientTest.Wire w=new GtiClientTest.Wire();w.cpr=c.panCpr;
            w.pos[0]=GtiClient.wrap24(c.panRaw+(int)(20*c.panCpr/360.0*c.settings.panSign));
            w.pos[1]=GtiClient.wrap24(c.tiltRaw+(int)(5*c.tiltCpr/360.0*c.settings.tiltSign));
            GtiClient mount=new GtiClient(w);mount.connect();GtiClient.Position p=mount.position();
            double[] steps=route.nextMove(c.panOffset(p.pan),c.tiltAngle(p.tilt));
            mount.goTo(p,steps[0]*c.settings.panSign,steps[1]*c.settings.tiltSign,()->true);
            assertEquals(c.panRaw,w.target[0]);assertEquals(c.tiltRaw,w.target[1]);assertEquals(2,w.starts());
            assertArrayEquals(new double[]{0,0},route.nextMove(0,c.elevation),0);
        }
    }
    @Test public void landmarkReturnCanResumeFromAnIntermediateStopWithoutChangingItsGoal() {
        Calibration c=calibration();FixedGoto route=FixedGoto.landmark(c,1000);
        assertArrayEquals(new double[]{-45,-5},route.nextMove(120,5),1e-9);
        // Recovery stopped midway through the first leg; finish toward the same zero-pan reference.
        assertArrayEquals(new double[]{-45,-3},route.nextMove(100,3),1e-9);
        assertArrayEquals(new double[]{-10,0},route.nextMove(10,0),1e-9);
        assertEquals("LANDMARK",route.snapshot().get("kind"));assertEquals(c.panRaw,route.snapshot().get("calibratedPanRaw"));
        assertThrows(IllegalArgumentException.class,()->route.nextMove(175,0));
    }
    @Test public void combinedLandmarkRouteRequiresBothAxesStoppedAndAtTarget() {
        Calibration c=calibration();
        FixedGoto route=FixedGoto.landmark(c,1000,true);
        assertFalse(FixedGoto.landmark(c,1000).calibrateOnArrival);
        assertTrue(route.calibrateOnArrival);
        assertEquals("LANDMARK",route.snapshot().get("kind"));
        double[] atTarget=route.nextMove(0,c.elevation);
        assertFalse(route.arrived(new GtiClient.Position(c.panRaw,c.tiltRaw,true),atTarget));
        assertTrue(route.arrived(new GtiClient.Position(c.panRaw,c.tiltRaw,false),atTarget));
        assertFalse(route.arrived(new GtiClient.Position(c.panRaw,c.tiltRaw,false),route.nextMove(1,c.elevation)));
        assertFalse(route.arrived(new GtiClient.Position(c.panRaw,c.tiltRaw,false),route.nextMove(0,c.elevation+1)));
    }

    @Test public void offshoreRouteUsesPanOnlyAndRespectsLimits()throws Exception {
        Calibration c=calibration("panSign","-1","panMax","70");
        FixedGoto route=FixedGoto.offshore(c,60,c.elevation,1000);
        assertEquals("OFFSHORE",route.snapshot().get("kind"));
        assertArrayEquals(new double[]{45,0},route.nextMove(0,c.elevation),0);
        assertArrayEquals(new double[]{15,0},route.nextMove(45,c.elevation+1),0);
        assertArrayEquals(new double[]{0,0},route.nextMove(60,c.elevation),0);
        assertThrows(IllegalArgumentException.class,()->FixedGoto.offshore(c,80,c.elevation,1000));
        GtiClientTest.Wire w=new GtiClientTest.Wire();w.cpr=c.panCpr;
        w.pos[0]=GtiClient.wrap24(c.panRaw-45*c.panCpr/360);w.pos[1]=c.tiltRaw;
        GtiClient mount=new GtiClient(w);mount.connect();
        GtiClient.Position position=mount.position();
        double[] steps=route.nextMove(c.panOffset(position.pan),c.tiltAngle(position.tilt));
        mount.goTo(position,steps[0]*c.settings.panSign,steps[1]*c.settings.tiltSign,()->true);
        assertEquals(GtiClient.wrap24(c.panRaw-60*c.panCpr/360),w.target[0]);
        assertEquals(1,w.starts());
    }

    private Calibration calibration(String... changes) {
        java.util.Map<String,String> values=new java.util.HashMap<>(Map.of("latitude","0","longitude","0","height","4",
            "landmarkLat","0.001","landmarkLon","0","landmarkHeight","4","panMin","-170","panMax","170"));
        for(int i=0;i<changes.length;i+=2)values.put(changes[i],changes[i+1]);
        return new Calibration(new Settings(values),new GtiClient.Position(0x800000,0x800000,false),new int[]{2073600,2073600});
    }
    private LoRaTelemetry gps(String coordinates){return LoRaTelemetry.parse("RX,GPS,10,1000,"+coordinates+",20,0.6,-55,12,0");}
    @Test public void capturedTargetDoesNotChangeWhenSurferMovesOrGpsIsMissing() {
        Calibration cal=calibration();LoRaTelemetry latest=gps("0,0.01");
        FixedGoto route=new FixedGoto(cal,latest,1000,1100,1);
        assertEquals(91,route.pan,1e-7);assertEquals(1,route.offset,0);
        latest=gps("0,-0.01");assertEquals(-.01,latest.lon,0);
        assertEquals(.01,route.source.lon,0);assertEquals(91,route.heading,1e-7);
        latest=null;
        assertEquals(45,route.nextMove(0,0)[0],0);
        assertEquals(45,route.nextMove(45,route.tilt)[0],0);
        assertEquals(1,route.nextMove(90,route.tilt)[0],1e-7);
        assertArrayEquals(new double[]{0,0},route.nextMove(91,route.tilt),1e-7);
        assertEquals(1100,route.capturedAt);assertEquals(1000,route.sourceReceivedAt);
    }
    @Test public void longSectorCrossingKeepsTheSavedDirectionAcrossLegs() {
        FixedGoto route=new FixedGoto(calibration(),gps("0,0.01"),1000,1100,0);
        // -170 to +90 must pass through the permitted sector, not wrap backwards through -180.
        double pan=-170;
        for(int n=0;n<5;n++){double step=route.nextMove(pan,route.tilt)[0];assertEquals(45,step,0);pan+=step;}
        assertEquals(35,route.nextMove(pan,route.tilt)[0],1e-7);
    }
    @Test public void recordedHomeTargetIsOneLargeTurnWithSmallTiltCorrection() {
        Settings s=GeoAndCalibrationTest.configured("latitude","-28.07924011","longitude","153.4010217","height","4",
            "landmarkLat","-28.0791129","landmarkLon","153.4012993","landmarkHeight","4","targetHeight","0","rangeMin","15");
        Calibration c=new Calibration(s,new GtiClient.Position(8389843,8390511,false),new int[]{2073600,2073600});
        FixedGoto route=new FixedGoto(c,gps("-28.07904,153.40113"),1000,1100,0);
        double[] step=route.nextMove(0,0);
        assertEquals(-37.02894476,step[0],.00001);assertEquals(-9.21428091,step[1],.00001);
    }
    @Test public void invalidRangeSectorAndCurrentPositionAreRejected() {
        assertThrows(IllegalArgumentException.class,()->new FixedGoto(calibration("panMax","60"),gps("0,0.01"),0,0,0));
        assertThrows(IllegalArgumentException.class,()->new FixedGoto(calibration("rangeMax","500"),gps("0,0.01"),0,0,0));
        FixedGoto route=new FixedGoto(calibration(),gps("0,0.01"),0,0,0);
        assertThrows(IllegalArgumentException.class,()->route.nextMove(-175,0));
        assertThrows(IllegalArgumentException.class,()->route.nextMove(Double.NaN,0));
        assertEquals(15000,FixedGoto.timeoutMs(1,0));assertEquals(84000,FixedGoto.timeoutMs(37,9));assertEquals(100000,FixedGoto.timeoutMs(45,0));
    }
}
