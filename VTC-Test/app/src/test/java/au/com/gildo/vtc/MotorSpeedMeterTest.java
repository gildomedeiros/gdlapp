package au.com.gildo.vtc;
import org.junit.Test;
import static org.junit.Assert.*;
public class MotorSpeedMeterTest {
    @Test public void measuresHalfDegreePerSecond() {
        MotorSpeedMeter meter=new MotorSpeedMeter();int[] cpr={2073600,2073600};
        meter.sample(new GtiClient.Position(0x800000,0x800000,true),cpr,1_000_000_000L);
        String value=meter.sample(new GtiClient.Position(0x800000+2880,0x800000,true),cpr,2_000_000_000L);
        assertTrue(value.contains("pan 0.500°/s"));assertTrue(value.contains("tilt 0.000°/s"));
    }
    @Test public void retainsLastMovingSampleWhileStopped() {
        MotorSpeedMeter meter=new MotorSpeedMeter();int[] cpr={2073600,2073600};
        meter.sample(new GtiClient.Position(0,0,true),cpr,1_000_000_000L);
        meter.sample(new GtiClient.Position(2880,0,true),cpr,2_000_000_000L);
        String value=meter.sample(new GtiClient.Position(2880,0,false),cpr,3_000_000_000L);
        assertTrue(value.contains("Last non-zero interval"));assertTrue(value.contains("pan 0.500°/s"));
    }
    @Test public void rejectsStaleTimeGapAndCanReset() {
        MotorSpeedMeter meter=new MotorSpeedMeter();int[] cpr={2073600,2073600};
        GtiClient.Position p=new GtiClient.Position(0,0,false);
        meter.sample(p,cpr,1_000_000_000L);
        assertTrue(meter.sample(p,cpr,9_000_000_000L).contains("waiting"));
        meter.reset();assertTrue(meter.sample(p,cpr,10_000_000_000L).contains("waiting"));
    }
}

