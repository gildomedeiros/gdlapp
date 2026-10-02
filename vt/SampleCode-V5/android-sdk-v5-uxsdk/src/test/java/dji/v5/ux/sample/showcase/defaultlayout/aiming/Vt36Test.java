package dji.v5.ux.sample.showcase.defaultlayout.aiming;
import java.nio.file.*;

/** VT 3.6: changed behaviours exercised against production planners and session gates. */
public final class Vt36Test {
    static int checks;
    static void check(boolean b,String why) { checks++;if(!b)throw new AssertionError(why); }
    static void near(double a,double b,String why) { check(Math.abs(a-b)<1e-6,why+": "+b); }
    static ComeToMeSettings movement() { return new ComeToMeSettings(true,30,100,99,8,30000,1200000,5).withMaxMovementSpeed(3); }
    static ComeToMeController planner(double separation) {
        ComeToMeController m=new ComeToMeController();m.start(movement(),1000);
        m.update_state_machine(ComeToMeTest.in(1000,0,0,separation,0,0),1000,.1);return m;
    }
    static void approachMargins() {
        ComeToMeController m=planner(34.99);
        check(!m.approaching(),"initial approach waits inside common 5 m margin");near(35,m.approachStartThreshold(),"initial threshold");
        m=planner(35);check(!m.approaching(),"equality does not start first approach");
        m=planner(35.01);check(m.approaching(),"first approach just above 5 m starts");near(5.01,m.approachDistance,"margin not added to destination");
        m.update_state_machine(ComeToMeTest.in(1100,5.01,0,35.01,0,0),1100,.1);
        check(m.hasFilmed && !m.approaching(),"completed saved approach");near(35,m.approachStartThreshold(),"same threshold after filming");
        m.pause(true,1200);near(35,m.approachStartThreshold(),"manual reset uses same margin");
        m.update_state_machine(ComeToMeTest.in(1300,0,0,34.99,0,0),1300,.1);check(!m.approaching(),"manual reset cannot reinstate 2 m rule");
        m.replaceApproachForRetreat();near(35,m.approachStartThreshold(),"retreat replacement preserves common margin");
        m.start(movement(),1400);near(35,m.approachStartThreshold(),"Stop/Start uses common margin");
        ComeToMeSettings custom=new ComeToMeSettings(true,30,100,99,8,30000,1200000,8);
        m.start(custom,1500);near(38,m.approachStartThreshold(),"configured margin still supported before initial hold");
        m.hasFilmed=true;near(38,m.approachStartThreshold(),"configured margin identical after hold");
    }
    static void speedAndBraking() {
        ComeToMeController m=planner(60);near(3,m.forward,"distant approach immediately commands cruise");
        m=planner(40);near(2,m.forward,"short approach starts at distance-limited 2 m/s");
        // Move the aircraft along the saved route; moving surfer coordinates cannot change it.
        m.update_state_machine(ComeToMeTest.in(1100,5,0,100,0,0),1100,.1);near(1,m.forward,"remaining five metres still slows to 1 m/s");
        m.update_state_machine(ComeToMeTest.in(1200,8,0,100,0,0),1200,.1);near(.4,m.forward,"remaining two metres still slows");
        m.update_state_machine(ComeToMeTest.in(1300,9.01,0,100,0,0),1300,.1);near(0,m.forward,"one-metre completion still stops");
        check(!m.approaching(),"saved arrival releases navigation");
        m=planner(60);m.pause(false,1100);
        m.update_state_machine(ComeToMeTest.in(1200,0,0,60,0,0),1200,.001);near(3,m.forward,"permitted recovery has no software ramp");
        m=planner(60);m.pause(false,1100);
        m.update_state_machine(ComeToMeTest.in(1200,0,0,60,0,90),1200,.1);near(0,m.forward,"alignment protection still blocks translation");
        m=new ComeToMeController();ComeToMeTest.beginTestReturn(m,30,0);
        m.update_state_machine(ComeToMeTest.in(148000,30,0,160,0,0),148000,.1);near(-3,m.forward,"return immediately reaches distance-limited cruise");
        check(!m.permits(ComeToMeTest.in(148000,30,0,160,0,0),148000,-5),"return cannot use retreat's larger envelope");
        m.update_state_machine(ComeToMeTest.in(148100,8,0,160,0,0),148100,.1);near(-1,m.forward,"return preserves arrival slowdown");
        m=planner(60);check(!m.permits(ComeToMeTest.in(1000,0,0,60,0,0),1000,5),"approach cannot command retreat speed");
    }
    static AimingSessionTest.Fake retreat() {
        AimingSessionTest.Fake f=new AimingSessionTest.Fake();f.movement=movement();f.retreat=RetreatSettings.defaults();f.aiming();
        Vt35Test.step(f,0,22,20,false);return f;
    }
    static void configurableSpeed() {
        near(4,ComeToMeSettings.defaults().maxMovementSpeed,"default movement maximum");
        for(double speed:new double[]{.1,2,4,5}) {
            ComeToMeSettings c=movement().withMaxMovementSpeed(speed);
            ComeToMeController m=new ComeToMeController();m.start(c,1000);
            m.update_state_machine(ComeToMeTest.in(1000,0,0,100,0,0),1000,.1);
            near(speed,m.forward,"configured approach cruise");
            check(!m.permits(ComeToMeTest.in(1000,0,0,100,0,0),1000,speed+.01),"configured submission cap");
            m.update_state_machine(ComeToMeTest.in(1100,65,0,100,0,0),1100,.1);
            near(Math.min(speed,1),m.forward,"configured cap preserves five-metre slowdown");
            m=new ComeToMeController();
            m.start(new ComeToMeSettings(true,70,50,18,8,30000,60000).withMaxMovementSpeed(speed),1000);
            m.update_state_machine(ComeToMeTest.in(1000,0,0,60,0,0),1000,.1);
            m.update_state_machine(ComeToMeTest.in(61000,40,0,160,0,0),61000,.1);
            m.update_state_machine(ComeToMeTest.in(62000,40,0,160,0,m.returnHeading),62000,.1);
            m.update_state_machine(ComeToMeTest.in(148000,40,0,160,0,0),148000,.1);
            near(-speed,m.forward,"configured return cruise");
            near(speed+.4,TranslationSpeedAllowance.horizontalLimit(true,speed,0),"configured telemetry envelope");
        }
        for(double speed:new double[]{0,5.01,Double.NaN,Double.POSITIVE_INFINITY}) {
            boolean rejected=false;try{movement().withMaxMovementSpeed(speed);}catch(IllegalArgumentException e){rejected=true;}
            check(rejected,"invalid configured maximum rejected");
            near(.5,TranslationSpeedAllowance.horizontalLimit(true,speed,0),"invalid maximum cannot expand telemetry envelope");
        }
    }
    static void retreatDefaults() {
        RetreatSettings c=RetreatSettings.defaults();
        check(c.minimumDistance==23 && c.durationMs==3000 && c.speed==5 && c.cooldownMs==5000,"new defaults 23 m / 3 s / 5 m/s / 5 s");
        near(18,c.speed*3.6,"18 km/h conversion");
        boolean rejected=false;try{new RetreatSettings(true,23,3000,5.001,5000);}catch(IllegalArgumentException e){rejected=true;}
        check(rejected,"above 18 km/h rejected");
        AimingSessionTest.Fake f=retreat();
        check(f.core.retreat.active && Vt35Test.forward(f)==-5,"5 m/s retreat replaces active forward approach");
        check(f.core.permitsMotion(f.input,f.time,-5),"5 m/s passes session submission gate");
        check(!f.core.permitsMotion(f.input,f.time,-5.01),"out-of-range retreat rejected at submission");
        check(!f.core.permitsMotion(f.input,f.time,3),"forward motion blocked during retreat");
        long deadline=f.core.retreat.deadline;
        check(deadline-f.time==3000,"three-second timer");
        Vt35Test.until(f,deadline,0,22,true);
        check(f.core.retreat.period==2 && Vt35Test.forward(f)==-5,"three-second period repeats through NOFIX");
        Vt35Test.until(f,f.core.retreat.deadline,-5,22,true);
        check(!f.core.retreat.active && Vt35Test.forward(f)==0,"live separation finishes repeated retreat");
        check(f.core.retreat.cooldownUntil==f.time+5000,"cooldown remains five seconds");
        near(35,f.core.movement.approachStartThreshold(),"after retreat first/later margin identical");
        f=retreat();f.core.pauseImmediately("pilot_stick");f.core.tick();
        check(!f.core.retreat.active && f.core.retreat.deadline==-1,"higher-speed retreat still cancels on manual intervention");
    }
    static void velocityAllowance() {
        near(.5,TranslationSpeedAllowance.horizontalLimit(false,3,5),"stopped/start/recovery remains steady hover even after retreat");
        near(3.4,TranslationSpeedAllowance.horizontalLimit(true,3,0),"ordinary translation remains 3 m/s plus 0.4 allowance");
        near(5.4,TranslationSpeedAllowance.horizontalLimit(true,3,5),"recent 5 m/s retreat permits actual velocity plus allowance");
        near(4.4,TranslationSpeedAllowance.horizontalLimit(true,3,4),"configured retreat allowance, not always maximum");
        near(3.4,TranslationSpeedAllowance.horizontalLimit(true,3,0),"expired retreat allowance restores movement limit");
        for(double bad:new double[]{Double.NaN,Double.POSITIVE_INFINITY,5.1,-5})
            near(3.4,TranslationSpeedAllowance.horizontalLimit(true,3,bad),"invalid history cannot grant larger envelope");
    }
    static void logs(String directory) throws Exception {
        Path p=Paths.get(directory,"vt36-test.jsonl");
        FullSessionLog log=new FullSessionLog(name->Files.newOutputStream(p),e->{throw new AssertionError(e);});log.enable();
        AimingSessionTest.Fake f=retreat();
        RetreatLog.record(log,f.core,f.time,"retreat_cycle","too_close",f.core.cycleId,-5);
        MovementCycleLog.record(log,f.core,f.time,f.core.cycleId,-5);
        log.disable("test");long deadline=System.currentTimeMillis()+5000;
        while(log.busy() && System.currentTimeMillis()<deadline)Thread.sleep(5);
        check(!log.busy(),"new release log flushed");
    }
    public static void main(String[] args) throws Exception {
        approachMargins();speedAndBraking();configurableSpeed();retreatDefaults();velocityAllowance();logs(args[0]);
        System.out.println("PASS: "+checks+" VT 3.6 margin, speed, braking, retreat and envelope assertions");
    }
}
