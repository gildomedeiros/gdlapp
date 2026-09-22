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
    static void evidence() {
        RideDetector d=new RideDetector();
        // Actual 1 Hz positions repeated by a 2 Hz transport. 8 m/s is 28.8, not 57.6 km/h.
        for(long t=1000;t<=7000;t+=500) {
            feed(d,t,((t-1000)/1000)*8);
            check(!d.rejected,"duplicate packet must not double plausible movement speed");
            if(t==2000) check(d.riding && !d.confirmed,"fast detection precedes return confirmation");
        }
        check(d.confirmed && Math.abs(d.confirmationSpeed-28.8)<.01,"five-second window confirms real ride");
        for(long t=7500;t<=39000;t+=500) feed(d,t,48);
        check(!d.riding && !d.confirmed,"fresh identical coordinates complete ride-end confirmation");
        d.reset(); for(long t=1000;t<=6000;t+=500) feed(d,t,0);
        feed(d,6500,3); feed(d,7000,6);
        check(d.riding && !d.confirmed,"brief burst is a fast-only ride");
        boolean returnEvent=false;
        for(long t=7500;t<=39000;t+=500) { feed(d,t,6); returnEvent|=d.event.equals("ride_ended"); }
        check(!d.riding && !returnEvent,"brief fast detection cannot emit confirmed ride-end event");
        d.reset(); for(long t=1000;t<=7000;t+=500) feed(d,t,(t-1000)*.006);
        d.clearEvidence("stale_gps");
        check(d.riding && d.confirmed && d.slowMs==0,"pause preserves ride and cannot count as slowdown");
        feed(d,20000,36); check(d.slowMs==0,"one recovery fix is not stationary confirmation");
        for(long t=20500;t<=24000;t+=500) feed(d,t,36);
        feed(d,24500,100);
        check(d.rejected && d.riding && d.slowMs==0,"jump clears low-speed evidence without ending a ride");
        feed(d,25000,36); feed(d,25500,36);
        check(d.rejected,"jump back rejected when compared against raw jump evidence");
        d.reset(); feed(d,1000,0); feed(d,1500,0); feed(d,2000,40);
        check(d.rejected && !d.riding,"implausible jump cannot start a ride");
        d.reset(); for(long t=1000;t<=7000;t+=500) feed(d,t,(t-1000)*.006);
        feed(d,100,36);
        check(d.confirmed && Double.isNaN(d.fastSpeed) && d.slowMs==0,"sender clock reset clears evidence without ending known ride");
    }
    static void movement() {
        ComeToMeController c=ComeToMeTest.start(); ComeToMeTest.qualify(c);
        c.update_state_machine(ComeToMeTest.in(61500,30.1,0,100,0,0),61500,.1);
        check(c.noRideTimerActive(),"filming hold starts timer");
        c.update_state_machine(ComeToMeTest.in(62000,30.1,0,103,0,0),62000,.1);
        c.update_state_machine(ComeToMeTest.in(62500,30.1,0,106,0,0),62500,.1);
        check(c.riding && !c.ride.confirmed && c.noRideTimerActive(),"fast-only ride preserves existing no-ride timer");
        for(long t=63000;t<=96000;t+=500) c.update_state_machine(ComeToMeTest.in(t,30.1,0,106,0,0),t,.1);
        check(!c.returning() && !c.riding && c.noRideTimerActive(),"fast-only slowdown does not cause early return");
        // A fast-only detection near the original timeout must not suppress that timeout.
        c=new ComeToMeController(); c.start(new ComeToMeSettings(true,70,20,18,8,30000,60000),1000);
        c.update_state_machine(ComeToMeTest.in(1000,0,0,100,0,0),1000,.1); ComeToMeTest.qualify(c);
        for(long t=61500;t<=120500;t+=500) c.update_state_machine(ComeToMeTest.in(t,30.1,0,100,0,0),t,.1);
        c.update_state_machine(ComeToMeTest.in(121000,30.1,0,103,0,0),121000,.1);
        c.update_state_machine(ComeToMeTest.in(121500,30.1,0,106,0,0),121500,.1);
        check(c.riding && !c.ride.confirmed && c.returning() && c.returnReason.equals("no_ride_timeout"),
                "original timeout still expires during fast-only ride");
        c=ComeToMeTest.start(); ComeToMeTest.qualify(c);
        c.update_state_machine(ComeToMeTest.in(61500,5,0,103,0,0),61500,.1);
        c.update_state_machine(ComeToMeTest.in(62000,5,0,106,0,0),62000,.1);
        check(c.riding && !c.ride.confirmed && c.forward==0 && !c.approaching(),"fast ride stops approach before confirmation");
        c=new ComeToMeController(); c.start(new ComeToMeSettings(false,70,20,18,8,30000,900000),1000);
        for(long t=1000;t<=8000;t+=500) c.update_state_machine(ComeToMeTest.in(t,0,0,100+(t-1000)*.006,0,0),t,.1);
        check(c.riding && c.ride.confirmed && c.phase==ComeToMeController.Phase.OFF && c.forward==0,"ride observer operates with Come to me OFF");
        for(long t=8500;t<=43000;t+=500) c.update_state_machine(ComeToMeTest.in(t,0,0,142,0,0),t,.1);
        check(!c.returning() && c.forward==0,"confirmed ride end cannot move disabled planner");
        c=ComeToMeTest.start(); ComeToMeTest.qualify(c);
        for(long t=61500;t<=362000;t+=500) c.update_state_machine(ComeToMeTest.in(t,0,0,100,0,0),t,.1);
        for(long t=362500;t<=370000;t+=500) c.update_state_machine(ComeToMeTest.in(t,0,0,100+(t-362000)*.006,0,0),t,.1);
        check(c.riding && c.phase==ComeToMeController.Phase.STOPPED && c.forward==0,"ride observation cannot revive timed-out movement");
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
