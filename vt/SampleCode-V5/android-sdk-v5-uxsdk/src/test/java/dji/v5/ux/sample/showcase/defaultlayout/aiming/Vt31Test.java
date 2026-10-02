package dji.v5.ux.sample.showcase.defaultlayout.aiming;

/** VT 3.1: Regression scenarios run production state machines, without an aircraft. */
public final class Vt31Test {
    static int checks;
    static void check(boolean b,String m) { checks++; if(!b) throw new AssertionError(m); }
    static void step(ComeToMeController c,long t,double n,double e) {
        c.update_state_machine(ComeToMeTest.in(t,0,0,n,e,0),t,.1);
    }
    static void holdFix(AimingSessionTest.Fake f,AimingSession.Fix fix,double heading,String problem) {
        f.time+=100;
        f.input=new AimingSession.Inputs(fix,0,0,heading,f.time,problem,true);
        f.core.tick();
    }
    public static void main(String[] args) {
        check(ComeToMeSettings.QUALIFY_MS==0,"VT 3.2 zero qualification");
        check(ComeToMeSettings.defaults().lineupWidth==50,"50 m default band");
        check(ComeToMeSettings.defaults().reapproachMargin==5,"VT 3.6 shared 5 m default margin");
        boolean rejected=false;
        try { new ComeToMeSettings(true,70,50,18,8,30000,900000,Double.NaN); }
        catch(IllegalArgumentException e) { rejected=true; }
        check(rejected,"invalid margin rejected");
        qualification(); margin(); yaw(); recovery(); savedNavigation();
        System.out.println("PASS: "+checks+" VT 3.1 qualification, margin, retained yaw and recovery assertions");
    }
    static void qualification() {
        ComeToMeController c=ComeToMeTest.start();
        check(c.approaching() && c.qualifiedMs==0,"zero wait permits initial approach");
        c.pause(true,1100);c.pauseForGps(15000);
        check(!c.approaching() && c.forward==0,"zero wait never bypasses stale GPS after manual reset");
        step(c,16000,100,0);
        check(c.approaching(),"fresh GPS permits recaptured approach without dwell");
        step(c,17000,100,26);
        check(c.qualifiedMs==0 && c.event.equals("band_recreated") && c.approaching(),"band exit keeps zero wait and existing plan");
    }
    static void margin() {
        ComeToMeController c=new ComeToMeController();
        c.start(ComeToMeSettings.defaults(),1000);
        for(long t=1000;t<=21000;t+=500) step(c,t,80,0);
        check(c.approaching() && Math.abs(c.approachDistance-10)<.001,"VT 3.6 first approach respects the same 5 m margin");
        c.update_state_machine(ComeToMeTest.in(21500,10,0,80,0,0),21500,.1);
        check(c.hasFilmed && c.phase==ComeToMeController.Phase.HOLDING,"arrival latches subsequent-approach margin");
        for(long t=22000;t<=24000;t+=500) c.update_state_machine(ComeToMeTest.in(t,10,0,80+(t-22000)/500,0,0),t,.1);
        check(!c.approaching(),"inside 75 m threshold keeps yaw with surfer");
        c.update_state_machine(ComeToMeTest.in(29500,10,0,85,0,0),29500,.1);
        check(!c.approaching(),"exact margin boundary does not approach");
        long timer=c.inactiveMs;
        c.update_state_machine(ComeToMeTest.in(30500,10,0,86,0,0),30500,.1);
        check(c.approaching() && Math.abs(c.approachDistance-6)<.001,"beyond margin plans to 70 m, not 75 m");
        check(c.inactiveMs>timer && c.noRideTimerActive(),"re-approach preserves no-ride deadline");
        c.pause(true,30600);
        check(!c.hasFilmed,"manual reposition begins a new first approach");
        c=new ComeToMeController();c.start(new ComeToMeSettings(true,70,50,18,8,30000,900000,25),1000);
        for(long t=1000;t<=21000;t+=500) step(c,t,60,0);
        check(c.hasFilmed && c.approachStartThreshold()==95,"custom margin applied after initial hold");
    }
    static void yaw() {
        AimingSessionTest.Fake f=new AimingSessionTest.Fake();f.aiming();
        AimingSession.Fix saved=f.input.target;
        for(int i=0;i<40;i++) holdFix(f,saved,90,null);
        check(f.core.state()==AimingSession.State.AIMING && f.core.cycleRetainedTarget,"active yaw survives aged known target");
        check(f.sent.get(f.sent.size()-1)<0,"retained target still produces correction");
        check(f.forwards.get(f.forwards.size()-1)==0,"retained target cannot translate");
        holdFix(f,saved,0,null);
        check(f.sent.get(f.sent.size()-1)==0,"aligned retained target stops rotation");
        holdFix(f,saved,90,"pilot_stick");
        check(f.core.state()==AimingSession.State.PAUSED,"pilot veto outranks retained yaw");
        f.advance(100);check(f.core.state()==AimingSession.State.PAUSED,"manual recovery still waits");
        for(int i=0;i<20;i++) f.advance(100);
        check(f.core.state()==AimingSession.State.AIMING,"manual recovery completes at two seconds");
        saved=f.input.target;
        for(int i=0;i<40;i++) holdFix(f,saved,90,null);
        holdFix(f,saved,90,"telemetry");
        check(f.core.state()==AimingSession.State.PAUSED,"invalid telemetry still pauses");
        f=new AimingSessionTest.Fake();f.aiming();saved=f.input.target;
        for(int i=0;i<40;i++) holdFix(f,saved,90,null);
        f.core.cancelImmediately();holdFix(f,saved,90,null);
        check(f.core.state()!=AimingSession.State.AIMING,"cancel still wins with old target");
    }
    static void recovery() {
        AimingSessionTest.Fake f=new AimingSessionTest.Fake();f.movement=ComeToMeSettings.defaults();f.aiming();
        for(int i=0;i<210;i++) { f.time+=100;f.input=ComeToMeTest.in(f.time,0,0,100,0,0);f.core.tick(); }
        check(f.core.movement.approaching(),"navigation fixture approaches");
        AimingSession.Fix saved=f.input.target;
        for(int i=0;i<31;i++) holdFix(f,saved,0,null);
        check(f.core.state()==AimingSession.State.AIMING && f.core.movement.approaching(),"old GPS does not pause saved navigation");
        check(f.forwards.get(f.forwards.size()-1)>0,"forward command survives surfer GPS outage");
        holdFix(f,saved,90,null);
        check(f.core.state()==AimingSession.State.AIMING && f.sent.get(f.sent.size()-1)<0,"saved approach yaw also continues through old GPS");
        long credit=f.core.movement.qualifiedMs;
        f.advance(100);
        check(f.core.state()==AimingSession.State.AIMING,"fresh fix keeps navigation active");
        check(f.core.movement.qualifiedMs==credit,"navigation GPS pause preserves credit");
        f.core.pauseImmediately("pilot_stick");f.core.tick();
        f.core.pauseImmediately("stale_gps");f.core.tick();
        f.advance(100);
        check(f.core.state()==AimingSession.State.PAUSED,"GPS cannot erase mixed manual pause dwell");
    }
    static void savedNavigation() {
        ComeToMeController c=ComeToMeTest.start(); c.pause(true,1100);
        c.pauseForGps(25000);
        check(c.qualifiedMs==ComeToMeSettings.QUALIFY_MS && !c.approaching(),"qualified outage waits for fresh position");
        check(c.gpsPaused,"GPS pause remains explicit");
        step(c,26000,100,26);
        check(c.qualifiedMs==0 && c.approaching(),"fresh fix after reset can approach with zero qualification");
        c=ComeToMeTest.start(); ComeToMeTest.qualify(c);
        AimingSession.Inputs old=ComeToMeTest.in(61000,30,0,100,0,0);
        AimingSession.Inputs arrived=new AimingSession.Inputs(old.target,old.lat,old.lon,0,65000,null,true);
        c.update_state_machine(arrived,65000,.1,false);
        check(c.phase==ComeToMeController.Phase.HOLDING,"saved approach completes using live aircraft position and old surfer fix");
        // Existing no-ride deadline creates a return; then keep the same surfer fix throughout it.
        c.update_state_machine(ComeToMeTest.in(966000,30,0,100,0,180),966000,.1);
        check(c.returning(),"return fixture started");
        AimingSession.Inputs ret=ComeToMeTest.in(966000,30,0,100,0,c.returnHeading);
        AimingSession.Inputs stale=new AimingSession.Inputs(ret.target,ret.lat,ret.lon,c.returnHeading,970000,null,true);
        c.update_state_machine(stale,970000,.1,false);
        check(c.forward<0 && c.permits(stale,970000,c.forward),"saved return sends backward movement with old surfer GPS");
        AimingSession.Inputs bad=new AimingSession.Inputs(ret.target,ret.lat,ret.lon,c.returnHeading,970000,"telemetry",true);
        check(!c.permits(bad,970000,c.forward),"aircraft telemetry failure still vetoes saved return");
        AimingSession.Inputs home=new AimingSession.Inputs(ret.target,0,0,c.returnHeading,970100,null,true);
        c.update_state_machine(home,970100,.1,false);
        check(!c.returning() && c.forward==0,"saved return completes without new surfer packet");
    }

}
