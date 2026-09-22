package dji.v5.ux.sample.showcase.defaultlayout.aiming;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** CAM3 v2.7: Exercise the production cycle adapter and async JSONL writer, no Android required. */
public final class AimingCycleLogTest {
    static void check(boolean v,String why) { if(!v) throw new AssertionError(why); }
    public static void main(String[] args) throws Exception {
        Path output=Paths.get(args[0],"aiming-cycle-test.jsonl");
        FullSessionLog log=new FullSessionLog(name -> Files.newOutputStream(output),error -> { throw new AssertionError(error); });
        log.enable();
        AimingSessionTest.Fake f=new AimingSessionTest.Fake(); f.aiming();
        AimingSession.Fix fix=new AimingSession.Fix(.0001,.0001,Double.NaN,f.time,1234);
        AimingSession.Inputs used=new AimingSession.Inputs(fix,0,0,0,f.time,null,true);
        f.input=used; f.core.tick();
        // Pass a newer/different observation: adapter must keep the exact decision snapshot.
        AimingSession.Inputs other=f.data(f.time,f.time+1,null,true);
        AimingCycleLog.record(log,f.core,other,f.time,false,f.core.cycleId,f.sent.get(f.sent.size()-1));
        f.time+=100; f.input=new AimingSession.Inputs(new AimingSession.Fix(0,0,Double.NaN,f.time,1235),0,0,0,f.time,null,true);
        f.core.tick(); AimingCycleLog.record(log,f.core,f.input,f.time,false,f.core.cycleId,0);
        f.time+=100; f.input=new AimingSession.Inputs(fix,0,0,0,f.time,"pilot_stick",true);
        f.core.tick(); AimingCycleLog.record(log,f.core,f.input,f.time,false,-1,7);
        // Wait for open to finish before requesting close; FullSessionLog has an asynchronous boundary.
        long deadline=System.currentTimeMillis()+5000;
        while(log.busy() && System.currentTimeMillis()<deadline) Thread.sleep(5);
        log.disable("test");
        while(log.busy() && System.currentTimeMillis()<deadline) Thread.sleep(5);
        check(!log.busy(),"log closed");
        String text=new String(Files.readAllBytes(output),StandardCharsets.UTF_8);
        check(text.contains("\"targetSequence\":1234"),"sequence recorded");
        check(text.contains("\"targetLatitude\":1.0E-4"),"exact input coordinates recorded");
        check(text.contains("\"permittedDirection\":\"left\""),"retained direction recorded");
        check(text.contains("\"reverseBlocked\":true"),"blocked opposite command recorded");
        check(text.contains("\"decision\":\"coincident_zero\""),"coincident decision recorded");
        check(text.contains("\"submitted\":false,\"submittedYawRate\":null"),"no historical command fabricated");
        check(text.contains("\"state\":\"PAUSED\""),"pause snapshot recorded");
        System.out.println("PASS: production per-cycle JSONL contains exact inputs, sequence, lock, route, pause and submission status");
    }
}
