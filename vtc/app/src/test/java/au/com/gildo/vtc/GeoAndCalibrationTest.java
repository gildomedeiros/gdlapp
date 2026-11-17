package au.com.gildo.vtc;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class GeoAndCalibrationTest {
    static Settings configured(String... changes) {
        Map<String,String> m=new HashMap<>();
        m.put("height","40");m.put("landmarkLat","-28.168607320532757");
        m.put("landmarkLon","153.55456670415674");m.put("landmarkHeight","0");
        for(int i=0;i<changes.length;i+=2)m.put(changes[i],changes[i+1]);
        return new Settings(m);
    }
    @Test public void knownPointDangerToGroyneGeometry() {
        Settings s=configured("hfov","6.48");
        double range=Geo.distance(s.latitude,s.longitude,s.landmarkLat,s.landmarkLon);
        assertEquals(529.7,range,.5);
        assertTrue(Geo.bearing(s.latitude,s.longitude,s.landmarkLat,s.landmarkLon)>120);
        assertTrue(Geo.bearing(s.latitude,s.longitude,s.landmarkLat,s.landmarkLon)<140);
        assertEquals(-4.21,Geo.elevation(range,40,1),.02);
        assertEquals(60,2*range*Math.tan(Math.toRadians(s.hfov/2)),.1);
    }
    @Test public void bearingsUnwrapAcrossNorthBothWays() {
        assertEquals(2,Geo.delta(1,359),0);assertEquals(-2,Geo.delta(359,1),0);
        assertEquals(359,Geo.wrap(-1),0);assertEquals(1,Geo.wrap(721),0);
    }
    @Test public void landmarkMapsBothMotorDirectionsAndCounterWrap() {
        Settings s=configured("panSign","-1","tiltSign","-1");
        Calibration c=new Calibration(s,new GtiClient.Position(0xfffff0,10000,false),new int[]{360000,360000});
        assertEquals(0,c.panOffset(0xfffff0),1e-8);
        assertEquals(c.bearing,c.heading(0xfffff0),1e-8);
        assertEquals(-1,c.panOffset(GtiClient.wrap24(0xfffff0+1000)),1e-8);
        assertEquals(c.elevation+2,c.tiltAngle(8000),1e-8);
        assertEquals(1,c.targetPan(Geo.wrap(c.bearing+1)),1e-8);
    }
    @Test public void sectorDoesNotPermitFullTurnToOtherSide() {
        Calibration c=new Calibration(configured(),new GtiClient.Position(10000,10000,false),new int[]{360000,360000});
        assertFalse(c.allowed(100,-3));assertFalse(c.allowed(-100,-3));
        assertFalse(c.allowed(0,30));assertFalse(c.allowed(Double.NaN,0));assertTrue(c.allowed(0,-3));
    }
    @Test public void tiltChangesWithRangeAndHeight() {
        assertEquals(-45,Geo.elevation(100,101,1),1e-8);
        assertEquals(0,Geo.elevation(100,1,1),1e-8);
        assertTrue(Geo.elevation(500,40,1)>Geo.elevation(100,40,1));
    }
    @Test public void invalidSetupCannotArm() {
        assertThrows(IllegalArgumentException.class,()->new Settings(Map.of("magnitude","NaN")));
        assertThrows(IllegalArgumentException.class,()->new Settings(Map.of("panSign","0")));
        assertEquals(5,new Settings(Map.of("framing","AUTO","magnitude","5")).magnitudeDegrees(143),1e-10);
        assertThrows(IllegalArgumentException.class,()->new Settings(Map.of("height","")).calibrationReady());
        assertThrows(IllegalArgumentException.class,()->configured("landmarkLat","-28.165513983264137","landmarkLon","153.55045722684628").calibrationReady());
        assertThrows(IllegalArgumentException.class,()->configured("mountHost","192.168.4.1").connectionReady());
        assertThrows(IllegalArgumentException.class,()->new Settings(Map.of("unsupported","1")));
        assertFalse(Settings.ipv4("999.1.1.1"));assertFalse(Settings.ipv4("192.168.1.2:11880"));
        configured("mountHost","192.168.4.2").connectionReady();
    }
}

