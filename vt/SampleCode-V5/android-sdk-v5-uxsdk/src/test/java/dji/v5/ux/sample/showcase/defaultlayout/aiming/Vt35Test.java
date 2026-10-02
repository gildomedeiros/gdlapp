package dji.v5.ux.sample.showcase.defaultlayout.aiming;
import java.nio.file.*;

/** Production session integration: independent fixed retreat, retained GPS and all existing gates. */
public final class Vt35Test {
    static int checks;
    static void check(boolean value,String why) { checks++;if(!value)throw new AssertionError(why); }
    static AimingSessionTest.Fake start(double distance) { return start(distance,99); }
    static AimingSessionTest.Fake start(double distance,double rideThreshold) {
        AimingSessionTest.Fake f=new AimingSessionTest.Fake();
        f.movement=new ComeToMeSettings(true,10,100,rideThreshold,8,30000,1200000,5);
        f.retreat=new RetreatSettings(true,25,10000,3,5000);f.aiming();step(f,0,distance,20,false);return f;
    }
    static void step(AimingSessionTest.Fake f,double drone,double target,double heading,boolean nofix) {
        f.time+=100;
        AimingSession.Inputs in=ComeToMeTest.in(f.time,drone,0,target,0,heading);
        f.input=nofix ? new AimingSession.Inputs(null,in.lat,in.lon,in.heading,in.aircraftTime,null,true) : in;
        f.core.tick();
    }
    static void until(AimingSessionTest.Fake f,long end,double drone,double target,boolean nofix) {
        while(f.time<end)step(f,drone,target,20,nofix);
    }
    static double forward(AimingSessionTest.Fake f) { return f.forwards.get(f.forwards.size()-1); }
    static boolean event(AimingSessionTest.Fake f,String name) {
        return f.diagnosticEvents.stream().anyMatch(e->e.startsWith(name+" "));
    }
    static void timers() {
        AimingSessionTest.Fake f=start(20);
        check(f.core.retreat.active && forward(f)==-3,"configured 3 m/s immediately, independent of approach settings");
        check(!f.core.movement.approaching() && event(f,"retreat_approach_replaced"),"active approach replaced and logged");
        check(f.core.cycleDecision.equals("surfer_correction") && f.sent.get(f.sent.size()-1)<0,"surfer yaw remains active while reversing");
        long deadline=f.core.retreat.deadline;
        until(f,deadline-100,0,30,false);
        check(f.core.retreat.active && forward(f)==-3,"crossing threshold does not shorten timed retreat");
        until(f,deadline,0,20,false);
        check(f.core.retreat.active && f.core.retreat.period==2 && forward(f)==-3,"still close repeats seamlessly at expiry");
        check(event(f,"retreat_repeated"),"repeat transition logged");
        deadline=f.core.retreat.deadline;
        until(f,deadline,0,30,false);
        check(!f.core.retreat.active && forward(f)==0 && f.core.retreat.cooling(f.time),"far enough finishes, neutral and cooldown");
        long cooldown=f.core.retreat.cooldownUntil;
        until(f,cooldown-100,0,30,false);
        check(!f.core.movement.approaching() && forward(f)==0,"approach blocked for entire cooldown");
        step(f,0,30,20,false);
        check(f.core.movement.approaching() && event(f,"retreat_cooldown_ended"),"approach eligible after exactly five seconds");
        f=start(20);until(f,f.core.retreat.deadline,0,30,false);
        step(f,0,20,20,false);
        check(f.core.retreat.active && forward(f)==-3,"backward flight has no cooldown");
        long oldDeadline=f.core.retreat.deadline;until(f,oldDeadline,0,30,false);
        check(f.core.retreat.cooldownUntil==f.time+5000,"same cooldown starts again after last retreat");
        f=start(30);step(f,0,25.01,0,false);
        check(!f.core.retreat.active,"distance above threshold does not trigger");
        f.core.retreat.start(new RetreatSettings(true,YawAimingMath.distance(f.input.lat,f.input.lon,f.input.target.lat,f.input.target.lon),10000,3,5000));
        check(!f.core.retreat.close(f.input),"exact threshold equality does not trigger");
    }
    static void retainedGps() {
        AimingSessionTest.Fake f=start(20);long first=f.core.retreat.deadline;
        until(f,first+11000,0,20,true);
        check(f.core.state()==AimingSession.State.AIMING && f.core.retreat.period==3,"NOFIX retains coordinates and repeats indefinitely with fresh aircraft");
        check(f.core.cycleRetainedTarget && forward(f)==-3,"retained target logged and backward command retained");
        check(f.core.retainedRetreatTarget(f.core.controlInputs(f.input,f.time),f.time),"gimbal may use retained target during NOFIX retreat");
        check(f.core.permitsMotion(f.input,f.time,-3),"final SDK gate also accepts retained NOFIX position");
        step(f,-10,20,60,true);
        check(f.core.cycleAngle< -59 && f.core.cycleAngle> -61,"aiming uses live heading and retained target");
        until(f,f.core.retreat.deadline,-10,20,true);
        check(!f.core.retreat.active && f.core.retreat.distance>29.9,"live aircraft position ends retreat despite NOFIX");
        check(f.core.retainedRetreatTarget(f.core.controlInputs(f.input,f.time),f.time),"retained gimbal aiming continues during cooldown");
        f=start(30);step(f,10,30,0,true);
        check(f.core.retreat.active,"NOFIX can trigger from last-known coordinate and live aircraft position");
        f=start(30);AimingSession.Fix old=f.input.target;
        for(int i=0;i<40;i++) {f.time+=100;f.input=new AimingSession.Inputs(old,0,0,0,f.time,null,true);f.core.tick();}
        f.time+=100;f.input=new AimingSession.Inputs(old,10.0/6371000*180/Math.PI,0,0,f.time,null,true);f.core.tick();
        check(f.core.retreat.active,"ordinary stale packet gap can trigger retreat");
        f=start(100);step(f,0,100,0,true);
        check(f.core.state()==AimingSession.State.PAUSED && !f.core.retreat.active,"NOFIX outside retreat retains legacy pause");
    }
    static void protections() {
        AimingSessionTest.Fake f=start(20);
        AimingSession.Inputs good=f.input;
        f.input=new AimingSession.Inputs(good.target,good.lat,good.lon,good.heading,f.time-1600,null,true);
        check(!f.core.permitsMotion(f.input,f.time,-3),"stale aircraft veto at SDK gate");
        int commands=f.sent.size();f.core.tick();
        check(f.core.state()==AimingSession.State.PAUSED && !f.core.retreat.active && f.core.retreat.deadline==-1,"telemetry pause cancels timer");
        check(!f.core.cycleRetainedTarget,"aircraft failure does not mislabel fresh surfer GPS as retained");
        check(event(f,"retreat_cancelled") && f.sent.size()==commands,"cancellation logged; stale telemetry cannot authorize even a neutral submission");
        f=start(20);long cancelledDeadline=f.core.retreat.deadline;
        f.core.pauseImmediately("pilot_stick");f.core.tick();
        check(!f.core.retreat.active && f.sent.get(f.sent.size()-1)==0,"manual interruption stops retreat");
        until(f,f.time+2200,0,20,false);
        check(f.core.state()==AimingSession.State.AIMING && f.core.retreat.active,"existing two-second recovery then fresh retreat");
        check(f.core.retreat.deadline>cancelledDeadline && f.core.retreat.deadline-f.core.retreat.periodStartedAt==10000,"recovery creates full timer, never remaining seconds");
        for(String cause:new String[]{"rth","landing","connection","hover","aircraft_gps"}) {
            f=start(20);good=f.input;
            f.input=new AimingSession.Inputs(good.target,good.lat,good.lon,good.heading,f.time,cause,true);f.core.tick();
            check(!f.core.retreat.active && f.core.retreat.deadline==-1,"protection cancels retreat: "+cause);
        }
        f=start(20);f.lost=true;f.core.tick();
        check(!f.core.retreat.active && f.core.state()!=AimingSession.State.AIMING,"control loss stops retreat");
        f=start(20);f.core.cancelImmediately();f.core.tick();
        check(!f.core.retreat.active,"explicit cancellation stops retreat");
        f=start(20);final AimingSessionTest.Fake race=f;int[] reads={0};
        f.onRead=()->{if(++reads[0]==2){AimingSession.Inputs i=race.input;race.input=new AimingSession.Inputs(i.target,i.lat,i.lon,i.heading,i.aircraftTime,"pilot_stick",true);}};
        f.core.tick();f.onRead=null;
        check(!f.core.retreat.active && f.sent.get(f.sent.size()-1)==0,"last-moment input failure cancels precomputed retreat");
        f=start(20);step(f,249.1,260,0,false);
        check(!f.core.retreat.active && forward(f)==0,"existing excursion boundary stops retreat");
        f=start(20);good=f.input;
        AimingSession.Inputs bad=new AimingSession.Inputs(new AimingSession.Fix(Double.NaN,0,0,f.time),0,0,0,f.time,null,true);
        check(!f.core.permitsMotion(bad,f.time,-3),"invalid current GPS is not replaced with retained target");
    }
    static void rideAndReturn() {
        AimingSessionTest.Fake f=start(15,18);
        // Discard Fake.aiming's far-away setup fix; measure a real continuous 6 m/s ride.
        f.core.movement.ride.clearEvidence("test_motion_origin");
        for(int i=1;i<=15;i++)step(f,0,15+i*.6,0,false);
        check(f.core.movement.riding && f.core.retreat.active && forward(f)==-3,"ride detection remains active and does not block retreat");
        f=start(100);
        ComeToMeTest.beginTestReturn(f.core.movement,30,0);
        check(!f.core.retreat.eligible(f.core.movement),"existing saved return keeps navigation ownership");
        f.core.movement.phase=ComeToMeController.Phase.STOPPED;
        check(!f.core.retreat.eligible(f.core.movement),"movement timeout latch is respected");
        f.core.movement.cancel();
        check(!f.core.retreat.eligible(f.core.movement),"disabled/cancelled movement cannot retreat");
    }
    static void settings() {
        RetreatSettings c=RetreatSettings.defaults();
        check(c.minimumDistance==23 && c.durationMs==3000 && c.speed==5 && c.cooldownMs==5000,"agreed defaults");
        for(double bad:new double[]{Double.NaN,Double.POSITIVE_INFINITY,0,-1,5.01}) {
            boolean rejected=false;try {new RetreatSettings(true,25,10000,bad,5000);}catch(IllegalArgumentException e){rejected=true;}
            check(rejected,"invalid retreat speed rejected");
        }
        AimingSessionTest.Fake f=start(20);
        f.core.retreat.start(new RetreatSettings(true,22,2000,1.2,700));step(f,0,20,0,false);
        check(forward(f)==-1.2 && f.core.retreat.deadline==f.time+2000,"custom fixed speed and timer");
        until(f,f.core.retreat.deadline,0,23,false);
        check(f.core.retreat.cooldownUntil==f.time+700,"custom cooldown");
        f.throwDiagnostics=true;step(f,0,20,0,false);
        check(f.core.retreat.active && forward(f)==-1.2,"logger failure cannot change retreat");
    }
    static void logs(String directory) throws Exception {
        Path path=Paths.get(directory,"retreat-test.jsonl");
        FullSessionLog log=new FullSessionLog(name->Files.newOutputStream(path),error->{throw new AssertionError(error);});log.enable();
        AimingSessionTest.Fake f=start(20);
        RetreatLog.record(log,f.core,f.time,"retreat_started","too_close",f.core.cycleId,forward(f));
        step(f,0,20,0,true);
        RetreatLog.record(log,f.core,f.time,"retreat_cycle","too_close",-1,0);
        log.disable("test");long deadline=System.currentTimeMillis()+5000;
        while(log.busy() && System.currentTimeMillis()<deadline)Thread.sleep(5);
        check(!log.busy(),"retreat log flushed");
    }
    public static void main(String[] args) throws Exception {
        timers();retainedGps();protections();rideAndReturn();settings();logs(args[0]);
        System.out.println("PASS: "+checks+" VT 3.5 retreat integration assertions");
    }
}
