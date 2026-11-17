package au.com.gildo.vtc;
import org.junit.Test;
import static org.junit.Assert.*;
public class LoRaTelemetryTest {
    private LoRaTelemetry packet(long seq,long ms) {
        return LoRaTelemetry.parse("RX,GPS,"+seq+","+ms+",-28.1686073,153.5545667,12,0.8,-80,12,0");
    }
    @Test public void stationaryNewPacketsAreFreshButDuplicatesAreNot() {
        LoRaTelemetry.Tracker t=new LoRaTelemetry.Tracker();
        assertTrue(t.accept(packet(10,1000),1000));
        assertFalse(t.accept(packet(10,1000),2000));
        assertTrue(t.accept(packet(11,1360),2360));
        assertEquals(1,t.repeats);
    }
    @Test public void countersWrapWithoutRestart() {
        LoRaTelemetry.Tracker t=new LoRaTelemetry.Tracker();
        assertTrue(t.accept(packet(0xffffffffL,0xfffffff0L),0));
        assertTrue(t.accept(packet(0,20),100));
        assertEquals(0,t.restarts);assertEquals(0,t.gaps);
    }
    @Test public void restartNeedsThreeIncreasingLowerPackets() {
        LoRaTelemetry.Tracker t=new LoRaTelemetry.Tracker();
        t.accept(packet(100,100000),0);
        assertFalse(t.accept(packet(1,100),100));
        assertFalse(t.accept(packet(2,200),200));
        assertTrue(t.accept(packet(3,300),300));
        assertEquals(1,t.restarts);
    }
    @Test public void delayedPacketDoesNotResetBaseline() {
        LoRaTelemetry.Tracker t=new LoRaTelemetry.Tracker();
        t.accept(packet(100,100000),0);
        assertFalse(t.accept(packet(90,90000),100));
        assertTrue(t.accept(packet(101,100360),200));
        assertEquals(0,t.restarts);
    }
    @Test public void malformedNonfiniteAndNoFixPacketsRejected() {
        assertThrows(IllegalArgumentException.class,()->LoRaTelemetry.parse("RX,GPS,1,1,NaN,0,12,1,-80,12,0"));
        assertThrows(IllegalArgumentException.class,()->LoRaTelemetry.parse("RX,GPS,1,1,0,0,0,1,-80,12,0"));
        assertThrows(IllegalArgumentException.class,()->LoRaTelemetry.parse("GDL_LILYGO_READY"));
        assertThrows(IllegalArgumentException.class,()->packet(0x100000000L,1));
    }
}

