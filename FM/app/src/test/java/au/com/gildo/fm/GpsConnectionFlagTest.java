package au.com.gildo.fm;

import org.junit.Test;
import static org.junit.Assert.*;

public class GpsConnectionFlagTest {
    @Test public void receiverTrafficAndGpsFreshnessAreIndependent() {
        assertEquals(ConnectionFlag.failed("No receiver data"),ConnectionFlag.gps(0,-1,-1,-1,2000,false));
        assertEquals(ConnectionFlag.pending("Receiver OK · No fresh fix"),ConnectionFlag.gps(0,0,-1,-1,2000,false));
        assertEquals(ConnectionFlag.good("GPS OK · Fix #42"),ConnectionFlag.gps(2000,0,0,42,2000,false));
        assertEquals(ConnectionFlag.pending("Receiver OK · No fresh fix · 2 s ago"),ConnectionFlag.gps(2500,2500,0,42,2000,false));
        assertEquals(ConnectionFlag.failed("No receiver data"),ConnectionFlag.gps(12501,2500,0,42,20000,false));
        assertEquals(ConnectionFlag.good("Simulator · Fix #43"),ConnectionFlag.gps(200,-1,199,43,2000,true));
    }
}
