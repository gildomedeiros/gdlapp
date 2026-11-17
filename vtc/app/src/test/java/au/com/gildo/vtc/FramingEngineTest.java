package au.com.gildo.vtc;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class FramingEngineTest {
    private FramingEngine engine(String... changes) {
        Map<String,String> m=new HashMap<>();m.put("framing","AUTO");m.put("offsetRate","0.3");
        for(int i=0;i<changes.length;i+=2)m.put(changes[i],changes[i+1]);
        return new FramingEngine(new Settings(m));
    }
    private void sample(FramingEngine f,long t,double b){f.observe(t+1,t+1,t,b);}
    private void right(FramingEngine f){sample(f,0,359.4);sample(f,750,359.7);sample(f,1500,0);}
    @Test public void northCrossingConfirmsRightAndRampsOnlyAfterDecision() {
        FramingEngine f=engine();right(f);
        assertEquals(1,f.direction);assertEquals(1,f.desired,1e-10);
        assertEquals(.6,f.net,1e-8);assertEquals(0,f.applied,0);
        assertEquals(.15,f.tick(2000),1e-8);
    }
    @Test public void smoothLeftTrend() {
        FramingEngine f=engine();sample(f,0,.6);sample(f,750,.3);sample(f,1500,0);
        assertEquals(-1,f.direction);assertEquals(-1,f.desired,1e-10);
        assertEquals(-.15,f.tick(2000),1e-8);
    }
    @Test public void oneJumpAndRepeatedCoordinatesDoNotConfirmDirection() {
        FramingEngine f=engine();sample(f,0,0);sample(f,750,3);sample(f,1500,3);
        assertEquals(1,f.supporting);assertEquals(0,f.desired,1e-10);
        sample(f,2250,3);sample(f,3000,3);assertEquals(0,f.desired,1e-10);
    }
    @Test public void reusedPacketDoesNotInventObservationsOrRefreshAge() {
        FramingEngine f=engine();f.observe(1,1,0,0);f.observe(2,2,750,.3);
        f.observe(2,2,1500,.6);assertEquals(0,f.desired,1e-10);
        f.tick(3000);assertTrue(f.reason.contains("unavailable"));
    }
    @Test public void alternatingJitterIsNeitherQuietNorTrend() {
        FramingEngine f=engine();right(f);
        sample(f,2250,2);sample(f,3000,0);sample(f,3750,2);sample(f,4500,0);
        assertEquals(1,f.desired,1e-10);assertEquals(0,f.quietSeconds(4500),0);
        assertTrue(f.consistency<.8);
    }
    @Test public void oppositeTrendDoesNotNeedQuietTimeout() {
        FramingEngine f=engine();right(f);f.tick(2000);
        sample(f,2250,359.6);sample(f,3000,359.2);
        assertEquals(-1,f.desired,1e-10);assertTrue(f.applied>0);
        double previous=f.applied;f.tick(3500);
        assertEquals(previous-.15,f.applied,1e-8);
    }
    @Test public void quietMustBeContinuousAndThenSmoothlyRecentres() {
        FramingEngine f=engine();right(f);
        for(long t=2250;t<=6000;t+=750)sample(f,t,0);
        assertEquals(0,f.desired,1e-10);assertEquals(0,f.direction);
        assertTrue(f.applied>0);
        double previous=f.applied;f.tick(6500);
        assertEquals(previous-.15,f.applied,1e-8);
    }
    @Test public void missingGpsFreezesOffsetAndBreaksQuietEvidence() {
        FramingEngine f=engine();right(f);f.tick(2000);
        double before=f.applied;f.unavailable(2200);f.tick(9000);
        assertEquals(before,f.applied,0);assertEquals(1,f.desired,1e-10);
        sample(f,10000,0);
        assertEquals(before,f.applied,0);assertEquals(0,f.quietSeconds(10000),0);
        assertTrue(f.reason.contains("Building"));
    }
    @Test public void stalePacketGapCannotBuildDirection() {
        FramingEngine f=engine();sample(f,0,0);sample(f,750,.3);sample(f,4000,.6);
        assertEquals(0,f.desired,1e-10);assertTrue(f.reason.contains("Building"));
    }
    @Test public void sessionResetClearsDirectionAndOffset() {
        FramingEngine f=engine();right(f);f.tick(2000);f.reset();
        assertEquals(0,f.direction);assertEquals(0,f.desired,1e-10);assertEquals(0,f.applied,0);
    }
    @Test public void legacyModeSelectionsMigrateToAutomatic() {
        FramingEngine f=engine("framing","FIXED","fixedOffset","-1");
        right(f);assertEquals(1,f.desired,1e-10);
        f=engine("framing","CENTRED");right(f);assertEquals(1,f.desired,1e-10);assertEquals(0,f.applied,0);
    }
    @Test public void longControlStallDoesNotCatchUpOffsetInstantly() {
        FramingEngine f=engine("gpsAge","5");right(f);
        assertEquals(.15,f.tick(3500),1e-8);
    }
}

