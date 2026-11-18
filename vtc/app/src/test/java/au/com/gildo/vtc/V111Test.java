package au.com.gildo.vtc;
import org.junit.Test;
import java.io.IOException;
import java.util.*;
import static org.junit.Assert.*;

public class V111Test {
    static class Wire extends V110Test.SlewWire {
        String ratio="20";
        @Override public String exchange(String command)throws IOException {
            if(command.startsWith(":g")){commands.add(command);return "="+ratio+"\r";}
            return super.exchange(command);
        }
    }
    @Test public void settingAcceptsFiveAndPreservesDefaultAndSavedValues(){
        assertEquals(5,new Settings(Map.of("maxPanningSpeed","5")).maxPanningSpeed,0);
        assertEquals(5,new Settings(Map.of()).maxPanningSpeed,0);
        assertEquals(.2,new Settings(Map.of("maxPanningSpeed","0.2")).maxPanningSpeed,0);
        assertThrows(IllegalArgumentException.class,()->new Settings(Map.of("maxPanningSpeed","5.001")));
    }
    @Test public void highSpeedUsesReportedRatioAndHighMode()throws Exception {
        Wire w=new Wire();GtiClient g=new GtiClient(w);g.connect();g.rates(2,0,()->true);
        assertTrue(w.sent(":g1"));assertTrue(w.sent(":G130"));
        assertTrue(w.commands.contains(":I1"+GtiClient.encode24(16000)+"\r"));
        assertEquals(1,w.starts());assertFalse(w.sent(":L"));assertFalse(w.sent(":S"));
    }
    @Test public void unchangedHighSpeedKeepsMovingWithoutStopOrRestart()throws Exception {
        Wire w=new Wire();GtiClient g=new GtiClient(w);g.connect();g.rates(2,0,()->true);
        w.commands.clear();g.rates(2,0,()->true);
        for(String prefix:List.of(":g",":G",":I",":J",":L"))assertFalse(w.sent(prefix));
        assertTrue(w.moving[0]);
    }
    @Test public void changingHighSpeedStopsBeforeWritingPeriod()throws Exception {
        Wire w=new Wire();GtiClient g=new GtiClient(w);g.connect();g.rates(2,0,()->true);
        w.commands.clear();g.rates(1,0,()->true);
        int stop=w.commands.indexOf(":L1\r"),mode=w.commands.indexOf(":G130\r"),period=w.commands.indexOf(":I1"+GtiClient.encode24(32000)+"\r"),start=w.commands.indexOf(":J1\r");
        assertTrue(stop>=0&&stop<mode&&mode<period&&period<start);
    }
    @Test public void highLowTransitionsStopOnlyAffectedAxis()throws Exception {
        Wire w=new Wire();GtiClient g=new GtiClient(w);g.connect();g.rates(.4,.2,()->true);
        w.commands.clear();g.rates(1,.2,()->true);assertTrue(w.sent(":L1"));assertFalse(w.sent(":L2"));assertTrue(w.sent(":G130"));
        w.commands.clear();g.rates(.4,.2,()->true);assertTrue(w.sent(":L1"));assertTrue(w.sent(":G110"));assertFalse(w.sent(":L2"));
    }
    @Test public void fiveSettingIsClampedToDocumentedMountCommandRange()throws Exception {
        Wire w=new Wire();GtiClient g=new GtiClient(w);g.connect();g.rates(5,-5,()->true);
        int ticks=GtiClient.stepPeriod(1000000,360000,GtiClient.MAX_SLEW_SPEED/32);
        assertTrue(w.commands.contains(":I1"+GtiClient.encode24(ticks)+"\r"));
        assertTrue(w.commands.contains(":I2"+GtiClient.encode24(ticks)+"\r"));assertTrue(w.sent(":G231"));
        assertEquals(-GtiClient.MAX_SLEW_SPEED,GtiClient.effectiveRate(-5),0);
        assertTrue(1000000*360.0*32/(360000*ticks)<=GtiClient.MAX_SLEW_SPEED);
    }
    @Test public void invalidHighRatioFailsWithoutStartingAndStopsBothAxes()throws Exception {
        for(String invalid:List.of("00","","200000","XX")){
            Wire w=new Wire();w.ratio=invalid;GtiClient g=new GtiClient(w);g.connect();
            assertThrows(IOException.class,()->g.rates(2,0,()->true));assertFalse(w.sent(":J"));assertTrue(w.sent(":L1"));assertTrue(w.sent(":L2"));
        }
    }
    @Test public void highSpeedRatioReplyHasTwoHexDigits(){
        assertTrue(GtiReplyReader.matches(":g1\r","=20\r"));
        assertFalse(GtiReplyReader.matches(":g1\r","=200000\r"));assertFalse(GtiReplyReader.matches(":g1\r","=\r"));
    }
}
