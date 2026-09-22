package dji.v5.ux.sample.showcase.defaultlayout.aiming;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;

/** Exercise the actual async writer and production movement adapter with active and paused cycles. */
public final class MovementLogTest {
    public static void main(String[] args) throws Exception {
        Path output=Paths.get(args[0],"movement-cycle-test.jsonl");
        FullSessionLog log=new FullSessionLog(name->Files.newOutputStream(output),error->{throw new AssertionError(error);});
        log.enable();
        AimingSessionTest.Fake f=new AimingSessionTest.Fake();
        f.movement=ComeToMeSettings.defaults(); f.aiming();
        for(int i=0;i<610;i++) { f.time+=100; f.input=ComeToMeTest.in(f.time,0,0,100,0,0); f.core.tick(); }
        MovementCycleLog.record(log,f.core,f.time,f.core.cycleId,f.forwards.get(f.forwards.size()-1));
        f.core.pauseImmediately("stale_gps"); f.core.tick();
        MovementCycleLog.record(log,f.core,f.time,-1,0);
        long deadline=System.currentTimeMillis()+5000;
        while(log.busy() && System.currentTimeMillis()<deadline) Thread.sleep(5);
        log.disable("test");
        while(log.busy() && System.currentTimeMillis()<deadline) Thread.sleep(5);
        if(log.busy()) throw new AssertionError("writer close timeout");
        String text=new String(Files.readAllBytes(output),StandardCharsets.UTF_8);
        if(!text.contains("\"submittedForwardMps\":null") || !text.contains("\"filmingDistanceM\":70.0")
                || !text.contains("\"version\":\"3.0\"")) throw new AssertionError("movement log metadata");
        Path returnOutput=Paths.get(args[0],"return-cycle-test.jsonl");
        FullSessionLog returnLog=new FullSessionLog(name->Files.newOutputStream(returnOutput),error->{throw new AssertionError(error);});
        returnLog.enable();
        ComeToMeTest.beginTestReturn(f.core.movement,30,0);
        MovementCycleLog.record(returnLog,f.core,47000,-1,0);
        f.core.movement.update_state_machine(ComeToMeTest.in(47500,2,10,160,0,0),47500,.1);
        MovementCycleLog.record(returnLog,f.core,47500,-1,0);
        returnLog.disable("test");
        deadline=System.currentTimeMillis()+5000;
        while(returnLog.busy() && System.currentTimeMillis()<deadline) Thread.sleep(5);
        if(returnLog.busy()) throw new AssertionError("return log close timeout");
        System.out.println("PASS: real movement JSONL contains settings, command evidence and paused non-submission");
    }
}
