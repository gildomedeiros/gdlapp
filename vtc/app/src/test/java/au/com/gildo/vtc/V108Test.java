package au.com.gildo.vtc;
import org.junit.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class V108Test {
    @Test public void legacyStepMigratesAwayAndAcquisitionAngleIsRetained(){
        Settings s=new Settings(Map.of("maxStep","3","gotoThreshold","4"));
        assertEquals(4,s.acquisitionDegrees(143),1e-10);assertFalse(s.values.containsKey("maxStep"));
        assertEquals(5,s.maxPanningSpeed,0);
        assertEquals(0,new Settings(Map.of("acquisitionTriggerDegrees","0")).acquisitionDegrees(143),1e-10);
    }
    @Test public void overshootWaitsForReversalThenCorrectsWithoutResettingPlanner(){
        AimPlanner p=new AimPlanner(new Settings(Map.of("panTolerance","0.15")));p.sent(1,.25,1000);
        assertEquals(0,p.step(1,-.2,1500),0);assertTrue(p.reversalBlocked(1,-.2,1500));
        assertEquals(-.2,p.step(1,-.2,3000),0);assertFalse(p.reversalBlocked(1,-.2,3000));
    }
    @Test public void nativeFineMoveAcceptsTenDegreesAndKeepsSlowFiniteMode()throws Exception {
        GtiClientTest.Wire w=new GtiClientTest.Wire();GtiClient g=new GtiClient(w);g.connect();
        GtiClient.Position before=g.position();g.move(before,10,-10,()->true);
        assertTrue(w.commands.contains(":G120\r"));assertTrue(w.commands.contains(":G221\r"));
        assertEquals(GtiClient.wrap24(before.pan+10000),w.target[0]);assertEquals(2,w.starts());
        assertThrows(java.io.IOException.class,()->g.move(before,10.1,0,()->true));
    }
    @Test public void slowCameraReadNeverOverlapsOrBlocksAnotherWorker()throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger calls=new AtomicInteger();
        ExecutorService mount=Executors.newSingleThreadExecutor();
        try(CameraPollLoop loop=new CameraPollLoop(()->{
            calls.incrementAndGet();entered.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}
        },1)){
            assertTrue(entered.await(2,TimeUnit.SECONDS));
            assertEquals("mount tick",mount.submit(()->"mount tick").get(1,TimeUnit.SECONDS));
            assertEquals(1,calls.get());
        }finally{release.countDown();mount.shutdownNow();}
    }
}
