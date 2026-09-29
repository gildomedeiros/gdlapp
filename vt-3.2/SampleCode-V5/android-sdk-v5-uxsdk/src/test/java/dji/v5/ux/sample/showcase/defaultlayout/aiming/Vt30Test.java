package dji.v5.ux.sample.showcase.defaultlayout.aiming;

/** Behavioral regressions: competing control owners, false fast rides and GPS timing. */
public final class Vt30Test {
    static int checks;
    static void check(boolean ok,String why) { checks++; if(!ok) throw new AssertionError(why); }
    static AimingSession.Fix fix(long t,double n) {
        return new AimingSession.Fix(n*ComeToMeTest.DEG,0,0,t,t,t);
    }
    static void feed(RideDetector d,long t,double n) { d.observe(fix(t,n),ComeToMeSettings.defaults()); }
    public static void main(String[] args) {
        yaw(); evidence(); movement(); session();
        System.out.println("PASS: "+checks+" VT 3.0 behavioral assertions");
    }
    static void yaw() {
        SurferYawController y=new SurferYawController();
        check(y.calculate(2,0,.1,false,1000)==0,"normal tolerance retained");
        check(y.calculate(1,0,.1,true,1100)>0,"riding corrects inside tolerance");
        check(y.desiredRate==.5,"full 1 degree used during riding");
        check(y.calculate(-1,.4,.1,true,1200)==0 && y.blocked,"overshoot stops instead of reversing");
        check(y.calculate(-1,0,.1,true,3000)==0 && y.lastPermittedAt==1100,"zero output must not erase direction; blocked requests do not extend timer");
        check(y.calculate(-1,0,.1,true,3100)<0 && !y.blocked,"opposite correction permitted at two seconds");
        check(y.calculate(0,-.4,.1,true,3200)==0 && y.lastPermittedAt==3100,"exact alignment stops without refreshing direction");
        y.reset(); y.calculate(10,0,.1,false,1000);
        check(y.calculate(-10,.4,.1,false,1100)==0 && y.blocked,"reverse block also applies outside rides");
        y.calculate(10,0,.1,false,1500);
        check(y.calculate(-10,0,.1,false,3400)==0,"resumed original correction refreshes cooldown");
        check(y.calculate(-10,0,.1,false,3500)<0,"refreshed cooldown expires");
        check(YawAimingMath.shortestHeadingError(1,359)==2,"north crossing uses short right error");
        check(YawAimingMath.shortestHeadingError(359,1)==-2,"north crossing uses short left error");
        y.reset(); double rate=0;
        for(int i=0;i<30;i++) { double next=y.calculate(170,rate,.1,true,1000+i*100);
            check(next<=8 && next-rate<=.400001,"normal cap and acceleration apply at every distance"); rate=next; }
        check(rate==8,"maximum remains 8 deg/s without Nearby");
    }
    // VT 3.3 replaces speed-based confirmation/exit tests with Vt33Test.
    static void evidence() {
        RideDetector d=new RideDetector();
        feed(d,1000,0);feed(d,1500,0);feed(d,2000,40);
        check(d.rejected && !d.riding,"implausible jump cannot start a ride");
        d.reset();feed(d,1000,0);feed(d,1500,0);feed(d,2000,8);
        check(d.riding && d.confirmed,"plausible short-window speed starts accepted ride");
    }
    static void movement() {
        ComeToMeController c=ComeToMeTest.start();ComeToMeTest.qualify(c);
        for(long t=61500;t<=362000;t+=500) c.update_state_machine(ComeToMeTest.in(t,0,0,100,0,0),t,.1);
        for(long t=362500;t<=370000;t+=500) c.update_state_machine(ComeToMeTest.in(t,0,0,100+(t-362000)*.006,0,0),t,.1);
        check(c.riding && c.phase==ComeToMeController.Phase.STOPPED && c.forward==0,"ride cannot revive movement timeout");
    }
    static void session() {
        AimingSessionTest.Fake f=new AimingSessionTest.Fake(); f.aiming(); f.core.surferYaw.reset();
        f.time+=100; f.input=ComeToMeTest.in(f.time,0,0,100,0,-10); f.core.tick();
        f.time+=100; f.input=ComeToMeTest.in(f.time,0,0,100,0,10); f.core.tick();
        check(f.core.surferYaw.blocked && f.sent.get(f.sent.size()-1)==0,"session sends zero for a blocked reversal");
        f.core.pauseImmediately("pilot_stick"); f.core.tick();
        check(f.core.surferYaw.permittedDirection==0,"pilot pause clears direction commitment");
        f=new AimingSessionTest.Fake(); f.aiming();
        long start=f.time;
        for(int i=1;i<=65;i++) { f.time+=100; f.input=ComeToMeTest.in(f.time,0,0,100+(f.time-start)*.006,0,-1); f.core.tick(); }
        check(f.core.movement.riding && f.sent.get(f.sent.size()-1)>0,"riding subdegree correction works with movement disabled");
        f.core.stopAiming("test"); check(f.core.surferYaw.permittedDirection==0 && !f.core.movement.riding,"stop clears both controllers");
        AimingSession.Inputs input=new AimingSession.Inputs(fix(1000,100),0,0,0,1000,null,true,0,.5);
        check(input.steadyHover(),"central capture uses the approved 0.5 vertical boundary");
    }
}
