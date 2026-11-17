package au.com.gildo.vtc;
import org.junit.Test;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

public class V110Test {
    static class SlewWire extends GtiClientTest.Wire {
        @Override public String exchange(String c)throws IOException {
            if(c.startsWith(":b")){commands.add(c);return "="+GtiClient.encode24(1000000)+"\r";}
            return super.exchange(c);
        }
    }
    @Test public void sameDirectionChangesOnlyPeriodWithoutStopsOrDestinations()throws Exception {
        SlewWire w=new SlewWire();GtiClient g=new GtiClient(w);g.connect();g.begin();
        g.rates(.5,0,()->true);assertTrue(w.sent(":G110"));assertEquals(1,w.starts());
        w.commands.clear();g.rates(.25,0,()->true);
        assertTrue(w.sent(":I1"));assertFalse(w.sent(":G"));assertFalse(w.sent(":J"));assertFalse(w.sent(":L"));assertFalse(w.sent(":S"));assertTrue(w.moving[0]);
        w.commands.clear();g.rates(.25,0,()->true);assertFalse(w.sent(":I"));assertFalse(w.sent(":L"));
    }
    @Test public void zeroOnTiltDoesNotStopPanning()throws Exception {
        SlewWire w=new SlewWire();GtiClient g=new GtiClient(w);g.connect();g.rates(.3,.1,()->true);
        w.commands.clear();g.rates(.3,0,()->true);assertTrue(w.moving[0]);assertFalse(w.moving[1]);
        assertFalse(w.sent(":L1"));assertTrue(w.sent(":L2"));
    }
    @Test public void reversalStopsBeforeChangingDirection()throws Exception {
        SlewWire w=new SlewWire();GtiClient g=new GtiClient(w);g.connect();g.rates(.3,0,()->true);
        w.commands.clear();g.rates(-.3,0,()->true);
        assertTrue(w.commands.indexOf(":L1\r")<w.commands.indexOf(":G111\r"));assertTrue(w.moving[0]);
    }
    @Test public void stopBlocksQueuedStartAfterPeriodAcknowledgement()throws Exception {
        AtomicReference<GtiClient> ref=new AtomicReference<>();
        SlewWire w=new SlewWire(){@Override public String exchange(String c)throws IOException {
            String result=super.exchange(c);if(c.startsWith(":I"))ref.get().cancel();return result;
        }};
        GtiClient g=new GtiClient(w);ref.set(g);g.connect();
        assertThrows(IOException.class,()->g.rates(.3,0,()->true));
        assertFalse(w.sent(":J"));assertTrue(w.sent(":L1"));assertTrue(w.sent(":L2"));
    }
    @Test public void lostRateAcknowledgementStopsBothAxes()throws Exception {
        SlewWire w=new SlewWire();GtiClient g=new GtiClient(w);g.connect();g.rates(.3,.1,()->true);
        w.fail=":I1";
        assertThrows(IOException.class,()->g.rates(.2,.1,()->true));
        assertFalse(w.moving[0]);assertFalse(w.moving[1]);assertFalse(g.continuous());
    }
    @Test public void rejectedStopStillAttemptsOtherAxisAndNeverClaimsSuccess()throws Exception {
        SlewWire w=new SlewWire();GtiClient g=new GtiClient(w);g.connect();g.rates(.3,.1,()->true);w.fail=":L1";
        IOException failure=assertThrows(IOException.class,g::stop);
        assertTrue(failure.getMessage().contains("unconfirmed"));assertTrue(w.moving[0]);assertFalse(w.moving[1]);
        assertEquals(3,w.commands.stream().filter(c->c.equals(":L2\r")).count());
    }
    @Test public void timerConversionHonoursMaximumAndRejectsImpossibleRates()throws Exception {
        assertEquals(2000,GtiClient.stepPeriod(1000000,360000,.5));
        int period=GtiClient.stepPeriod(1000000,360001,.3);
        assertTrue(1000000*360.0/(360001*period)<=.3);
        assertThrows(IOException.class,()->GtiClient.stepPeriod(1000000,360000,.6));
        assertThrows(IOException.class,()->GtiClient.stepPeriod(0,360000,.3));
    }
    @Test public void slowsDownStopsAndUsesRestartHysteresis(){
        ContinuousPlanner p=new ContinuousPlanner();
        assertEquals(.5,p.rate(1,3,.15,.5),0);
        assertEquals(.15,p.rate(1,.3,.15,.5),1e-9);
        assertEquals(0,p.rate(1,.15,.15,.5),0);
        assertEquals(0,p.rate(1,.2,.15,.5),0);
        assertEquals(.12,p.rate(1,.24,.15,.5),1e-9);
        assertEquals(0,p.rate(2,.11,.1,.5),0);
    }
    @Test public void lowerConfiguredCapIsRespected(){
        assertEquals(-.1,new ContinuousPlanner().rate(1,-2,.15,.1),0);
        assertThrows(IllegalArgumentException.class,()->new Settings(Map.of("maxPanningSpeed","0.6")));
        assertEquals(.05,new Settings(Map.of("maxPanningSpeed","0.05")).maxPanningSpeed,0);
    }
    @Test public void gpsGapDeadlineIsFromPacketNotLossDetection(){
        assertTrue(ContinuousPlanner.gapAllowed(2999,0,3));assertFalse(ContinuousPlanner.gapAllowed(3000,0,3));
        assertFalse(ContinuousPlanner.gapAllowed(1000,-1,3));assertFalse(ContinuousPlanner.gapAllowed(99,100,3));
        assertTrue(ContinuousPlanner.gapAllowed(3100,2900,3));
    }
    @Test public void movementLimitsReduceRateInDirectionOfTravel(){
        assertEquals(.5,ContinuousPlanner.limitRate(.5,0,-90,90),0);
        assertTrue(ContinuousPlanner.limitRate(.5,89,-90,90)<.1);
        assertEquals(0,ContinuousPlanner.limitRate(.5,90,-90,90),0);
        assertEquals(-.5,ContinuousPlanner.limitRate(-.5,90,-90,90),0);
    }
    @Test public void watchdogLatchesAndRequiresExplicitReset(){
        MovementWatchdog w=new MovementWatchdog(0);
        assertFalse(w.check(6000,false));assertFalse(w.check(4999,true));assertTrue(w.check(5000,true));
        assertFalse(w.check(6000,true));w.healthy(7000);assertFalse(w.check(13000,true));
        w.reset(14000);assertTrue(w.check(19000,true));
    }
    @Test public void independentWatchdogCanStopWhileControlWorkerIsStalled()throws Exception {
        SlewWire wire=new SlewWire();GtiClient g=new GtiClient(wire);g.connect();g.rates(.5,.2,()->true);
        ExecutorService control=Executors.newSingleThreadExecutor();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        try {control.submit(()->{entered.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});
            assertTrue(entered.await(1,TimeUnit.SECONDS));MovementWatchdog watchdog=new MovementWatchdog(0);
            assertTrue(watchdog.check(5000,true));g.cancel();g.stop();
            assertFalse(wire.moving[0]);assertFalse(wire.moving[1]);
        }finally{release.countDown();control.shutdownNow();}
    }
    @Test public void oldSetupMigratesWithoutRetainingObsoleteControls(){
        Settings s=new Settings(Map.of("gotoThreshold","4","maxStep","3","waitForGoto","0","framing","FIXED","fixedOffset","-1"));
        assertEquals(4,s.acquisitionTriggerDegrees,0);
        for(String k:List.of("gotoThreshold","maxStep","waitForGoto","framing","fixedOffset"))assertFalse(s.values.containsKey(k));
        assertEquals(5,new Settings(Map.of("gotoThreshold","4","acquisitionTriggerDegrees","5")).acquisitionTriggerDegrees,0);
    }
    @Test public void zeroMagnitudeAlwaysProducesZeroOffset(){
        FramingEngine f=new FramingEngine(new Settings(Map.of("magnitude","0")));
        f.observe(1,1,0,0);f.observe(2,2,750,.5);f.observe(3,3,1500,1);f.tick(1800);
        assertEquals(1,f.direction);assertEquals(0,f.desired,0);assertEquals(0,f.applied,0);
    }
    @Test public void clockRoundTripsPhoneTimezoneAndDst()throws Exception {
        long epoch=1794873600000L;
        for(String id:List.of("Australia/Brisbane","Australia/Sydney","America/New_York")){
            String value=CameraClock.format(epoch,TimeZone.getTimeZone(id));assertEquals(epoch,CameraClock.parse(value));
        }
        assertTrue(CameraClock.format(epoch,TimeZone.getTimeZone("Australia/Brisbane")).endsWith("+1000"));
    }
    @Test public void timerQueriesAndRateAcknowledgementsHaveStrictReplyShapes(){
        assertTrue(GtiReplyReader.matches(":b1\r","=40420F\r"));assertFalse(GtiReplyReader.matches(":b1\r","=\r"));
        assertTrue(GtiReplyReader.matches(":I1000001\r","=\r"));assertFalse(GtiReplyReader.matches(":I1000001\r","=000000\r"));
    }
}
