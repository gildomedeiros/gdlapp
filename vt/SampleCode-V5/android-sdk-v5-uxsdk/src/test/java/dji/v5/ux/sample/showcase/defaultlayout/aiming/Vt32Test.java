package dji.v5.ux.sample.showcase.defaultlayout.aiming;

/** VT 3.2: Exercise real planner boundaries and same-cycle session yaw handoff. */
public final class Vt32Test {
    static int checks;
    static void check(boolean ok,String why) { checks++; if(!ok) throw new AssertionError(why); }
    static void step(ComeToMeController c,long t,double n,double target) {
        c.update_state_machine(ComeToMeTest.in(t,n,0,target,0,0),t,.1);
    }
    // VT 3.2: Check the actual shared planner in both directions, including immediate speed selection from rest (VT 3.6).
    static void speedProfile() {
        for(boolean returning:new boolean[]{false,true}) {
            ComeToMeController c;
            long t;
            if(returning) { c=new ComeToMeController();ComeToMeTest.beginTestReturn(c,30,0);t=147000; }
            else { c=ComeToMeTest.start();ComeToMeTest.qualify(c);t=61000; }
            c.forward=0;
            for(int i=1;i<=140;i++) {
                t+=100;
                c.update_state_machine(ComeToMeTest.in(t,returning?30:0,0,returning?160:100,0,0),t,.1);
                check(Math.abs(Math.abs(c.forward)-3)<1e-8,"VT 3.6 both directions immediately command cruise speed and retain the cap");
            }
            for(double remaining:new double[]{15,12,10,8,5,4,3,2,1.01,.99}) {
                t+=100;
                double n=returning?3+remaining:30-remaining;
                c.update_state_machine(ComeToMeTest.in(t,n,0,returning?160:100,0,0),t,.1);
                double expected=remaining<=1?0:Math.min(3,.2*remaining);
                check(Math.abs(Math.abs(c.forward)-expected)<1e-7,"both directions preserve arrival speed at "+remaining+" m");
                check(returning?c.forward<=0:c.forward>=0,"command sign matches navigation direction");
            }
        }
    }
    public static void main(String[] args) {
        speedProfile();
        ComeToMeController c=ComeToMeTest.start();
        step(c,10999,0,100);
        check(c.approaching(),"zero wait already permits approach");
        step(c,11000,0,100);
        check(c.approaching(),"fresh GPS retains approach");
        step(c,11100,28.99,100);
        check(c.approaching() && c.forward>0,"1.01 m still moving");
        // Use the latest fix time to isolate a late aircraft update at the submission gate.
        check(!c.permits(ComeToMeTest.in(11100,29.01,0,100,0,0),11100,c.forward),"late aircraft position vetoes final sub-metre command");
        step(c,11200,29.01,100);
        check(c.phase==ComeToMeController.Phase.HOLDING && c.forward==0,"arrival within one metre enters hold");
        check(c.reason.equals("approach_arrival_tolerance") && c.noRideTimerActive(),"arrival reason and no-ride timer recorded");
        step(c,11300,28.8,100);
        check(!c.approaching(),"arrival remains latched after GPS drift");
        c=ComeToMeTest.start();c.pause(true,1100);c.pauseForGps(11000);
        check(c.qualifiedMs==0 && !c.approaching(),"outage counts but cannot launch");
        step(c,12000,0,100);check(c.approaching(),"fresh in-band fix launches qualified approach");
        c=new ComeToMeController();ComeToMeTest.beginTestReturn(c,30,0);
        step(c,147500,4.01,160);
        check(c.returning() && c.forward<0,"return continues with 1.01 m remaining");
        check(!c.permits(ComeToMeTest.in(147500,3.99,0,160,0,0),147500,c.forward),"late return telemetry blocks sub-metre command");
        step(c,148000,3.99,160);
        check(!c.returning() && c.forward==0 && c.reason.equals("return_arrival_tolerance"),"return completes relative to saved endpoint");
        check(c.returnCompletionCentralDistance>3.9,"return tolerance is not a 1 m central radius");
        c=new ComeToMeController();c.start(ComeToMeSettings.defaults(),1000);
        step(c,1000,0,400);step(c,11000,0,400);step(c,11100,248.9,400);
        check(c.forward>0,"outward motion below 249 m allowed");
        check(!c.permits(ComeToMeTest.in(11100,249.01,0,400,0,0),11100,c.forward),"late excursion telemetry vetoes outward command");
        step(c,11200,249.01,400);
        check(c.phase==ComeToMeController.Phase.HOLDING && c.reason.equals("excursion_limit") && c.forward==0,"249 m excursion hold");
        step(c,11300,248.8,400);check(!c.approaching(),"excursion hold remains latched");
        AimingSessionTest.Fake f=new AimingSessionTest.Fake();f.movement=ComeToMeSettings.defaults();f.aiming();
        f.core.movement.start(f.movement,f.time);
        for(int i=0;i<120;i++) {f.time+=100;f.input=ComeToMeTest.in(f.time,0,0,100,0,0);f.core.tick();}
        check(f.core.movement.approaching(),"session owns approach yaw");
        f.time+=100;f.input=ComeToMeTest.in(f.time,29.01,0,100,0,20);f.core.tick();
        check(!f.core.movement.approaching() && f.forwards.get(f.forwards.size()-1)==0,"completion submits zero translation immediately");
        check(!f.core.cycleDecision.equals("approach_heading") && f.sent.get(f.sent.size()-1)<0,"surfer yaw resumes on completion cycle");
        System.out.println("PASS: "+checks+" VT 3.2 boundary, GPS, excursion and yaw-handoff assertions");
    }
}
