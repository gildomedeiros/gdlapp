package dji.v5.ux.sample.showcase.defaultlayout.aiming;

/** Deterministic movement scenarios; no Android runtime or aircraft connection. */
public final class ComeToMeTest {
    static int checks;
    static void check(boolean ok,String text) { checks++; if(!ok) throw new AssertionError(text); }
    static final double DEG=180/Math.PI/6371000;
    static AimingSession.Inputs in(long t,double droneN,double droneE,double targetN,double targetE,double heading) {
        return new AimingSession.Inputs(new AimingSession.Fix(targetN*DEG,targetE*DEG,0,t,t,t),
                droneN*DEG,droneE*DEG,heading,t,null,true);
    }
    static ComeToMeController start() {
        ComeToMeController c=new ComeToMeController();
        c.start(ComeToMeSettings.defaults(),1000);
        c.update_state_machine(in(1000,0,0,100,0,0),1000,.1);
        return c;
    }
    static void qualify(ComeToMeController c) {
        for(long t=1500;t<=61000;t+=500) c.update_state_machine(in(t,0,0,100,0,0),t,.1);
    }
    public static void main(String[] args) {
        ComeToMeController c=start();
        // Isolate band-exit handling: the 11 m fixture step is 39.6 km/h over 1 s.
        // A separate test below verifies that a fast ride DOES interrupt approach.
        c.start(new ComeToMeSettings(true,70,20,50,8,30000,900000),1000);
        c.update_state_machine(in(1000,0,0,100,0,0),1000,.1);
        check(c.forward==0 && c.phase==ComeToMeController.Phase.WAITING,"Start never immediately translates");
        qualify(c);
        check(c.forward>0 && c.forward<=ComeToMeSettings.MAX_SPEED,"60 seconds of fresh inside-band fixes permits forward movement");
        check(c.centralLat==0 && c.centralLon==0,"saved central unchanged");
        c.update_state_machine(in(61500,0,0,100,0,4),61500,.1);
        check(c.forward>0,"aligned approach keeps moving while heading is corrected");
        c.update_state_machine(in(62000,0,0,100,0,2),62000,.1);
        check(c.forward>0,"resume inside 3 degrees without repeating qualification");
        check(c.permits(in(62000,0,0,100,0,4),62000,c.forward),"fixed approach is not repeatedly gated by small heading variation");
        c.update_state_machine(in(62500,0,0,100,11,0),62500,.1);
        check(c.forward>0 && c.phase==ComeToMeController.Phase.APPROACHING,"sideways exit cannot interrupt a fixed approach");
        check(c.qualifiedMs==0 && Math.abs(c.bandLon-11*DEG)<1e-9,"new band recentred and timer reset");

        c=start(); qualify(c);
        c.update_state_machine(in(61500,30.1,0,100,0,0),61500,.1);
        check(c.phase==ComeToMeController.Phase.HOLDING && c.forward==0,"stop when projected planned travel is completed");
        c.update_state_machine(in(62000,29,0,80,0,0),62000,.1);
        check(c.forward==0 && c.phase==ComeToMeController.Phase.HOLDING,"closer surfer never causes retreat");
        c.update_state_machine(in(62500,29,0,105,0,0),62500,.1);
        check(c.forward==0,"holding does not chase distance noise");

        c=start(); qualify(c);
        c.pause(false,62000);
        c.update_state_machine(in(62500,0,0,100,0,0),62500,.1);
        // A safety pause preserves the fixed plan; session recovery handles fresh-input dwell.
        check(c.forward>0 && c.approachHeading==0,"recovered approach realigns and resumes its saved plan");

        c=start();
        AimingSession.Inputs same=in(1000,0,0,100,0,0);
        c.update_state_machine(same,62000,.1);
        check(c.forward==0 && c.qualifiedMs==0,"reused fix cannot complete qualification");

        c=start(); qualify(c);
        c.pause(true,62000);
        c.update_state_machine(in(64500,10,0,100,0,0),64500,.1);
        check(Math.abs(c.centralLat-10*DEG)<1e-9 && c.forward==0,"pilot recovery recaptures central and qualification");
        c.cancel();
        check(c.phase==ComeToMeController.Phase.OFF && Double.isNaN(c.centralLat),"Stop invalidates central");

        c=start(); qualify(c);
        for(long t=61500;t<=362000;t+=500) c.update_state_machine(in(t,0,0,100,0,0),t,.1);
        check(c.phase==ComeToMeController.Phase.STOPPED && c.forward==0,"approach timeout latches");
        c.update_state_machine(in(363000,0,0,100,20,0),363000,.1);
        check(c.phase==ComeToMeController.Phase.STOPPED,"band changes cannot restart timed-out movement");
        c=start(); qualify(c); c.pause(false,400000);
        check(c.phase==ComeToMeController.Phase.STOPPED,"paused time counts toward attempt deadline");

        c=start();
        // Simulate a valid northward ride at 21.6 km/h, without leaving the sideways band.
        for(long t=1500;t<=11000;t+=500) c.update_state_machine(in(t,30,0,100+(t-1000)*.006,0,0),t,.1);
        check(c.riding && c.forward==0,"18 km/h threshold detects ride and holds location");
        for(long t=11500;t<=47000;t+=500) c.update_state_machine(in(t,30,0,160,0,0),t,.1);
        check(c.phase==ComeToMeController.Phase.RETURNING && c.forward<0,"30 seconds below 8 triggers backward return");
        check(Math.abs(c.returnHeading)<.01,"nose points away from central for backward navigation");
        c.update_state_machine(in(47500,30,0,160,0,90),47500,.1);
        check(c.forward<0 && c.returning(),"aligned return continues while saved-heading correction runs");
        c.update_state_machine(in(48000,2,0,160,0,0),48000,.1);
        check(!c.returning() && c.forward==0,"central arrival completes inside 3m");
        c.update_state_machine(in(48500,4,0,160,0,0),48500,.1);
        check(!c.returning() && c.forward==0,"arrival stays complete after GPS drift outside 3m");

        c=start();
        for(long t=1500;t<=11000;t+=500) c.update_state_machine(in(t,30,0,100+(t-1000)*.006,0,0),t,.1);
        c.pause(false,12000);
        check(c.riding && c.slowMs==0,"missing GPS does not end a known ride");

        c=start(); qualify(c);
        for(long t=61500;t<=961500;t+=500) c.update_state_machine(in(t,30,0,100,0,0),t,.1);
        check(c.returning() && c.returnReason.equals("no_ride_timeout") && c.forward==0,"inactivity starts return with neutral translation");
        c.update_state_machine(in(961600,30,0,100,0,0),961600,.1);
        check(c.forward<0,"return begins backward after neutral transition");
        long generation=c.centralGeneration;
        c.update_state_machine(in(962000,30,0,100,25,0),962000,.1);
        check(c.returning() && c.centralGeneration==generation,"band exit cannot interrupt return or change anchor");
        for(long t=962500;t<=1262000;t+=500) c.update_state_machine(in(t,30,0,100,25,0),t,.1);
        check(c.phase==ComeToMeController.Phase.STOPPED,"return timeout latches independently of approach");

        c=new ComeToMeController(); c.start(ComeToMeSettings.defaults(),1000);
        c.update_state_machine(in(1000,0,0,400,0,0),1000,.1);
        for(long t=1500;t<=61000;t+=500) c.update_state_machine(in(t,0,0,400,0,0),t,.1);
        c.update_state_machine(in(61500,200.1,0,400,0,0),61500,.1);
        check(c.forward==0 && c.reason.equals("excursion_limit"),"excursion cap prevents further approach");

        c=new ComeToMeController();
        c.start(new ComeToMeSettings(false,70,20,18,8,30000,900000),1000);
        c.update_state_machine(in(1000,0,0,100,0,0),1000,.1);
        check(c.phase==ComeToMeController.Phase.OFF && c.forward==0,"disabled planner preserves yaw-only behavior");
        try { new ComeToMeSettings(true,Double.NaN,20,18,8,30000,900000); throw new AssertionError("NaN accepted"); }
        catch(IllegalArgumentException expected) { checks++; }
        try { new ComeToMeSettings(true,70,20,8,18,30000,900000); throw new AssertionError("inverted ride thresholds"); }
        catch(IllegalArgumentException expected) { checks++; }

        // Verify actual AimingSession integration, not just the pure planner.
        AimingSessionTest.Fake f=new AimingSessionTest.Fake();
        f.movement=ComeToMeSettings.defaults(); f.aiming();
        for(int i=0;i<610;i++) { f.time+=100; f.input=in(f.time,0,0,100,0,0); f.core.tick(); }
        check(f.forwards.get(f.forwards.size()-1)>0,"session submits combined forward+yaw after qualification");
        f.core.pauseImmediately("pilot_stick"); f.core.tick();
        check(f.core.state()==AimingSession.State.PAUSED && f.core.movement.forward==0,"urgent pilot event clears planned translation");
        f.core.stopAiming("user_stop");
        check(f.core.movement.phase==ComeToMeController.Phase.OFF,"session Stop cancels movement");

        f=new AimingSessionTest.Fake(); f.movement=ComeToMeSettings.defaults(); f.aiming();
        for(int i=0;i<610;i++) { f.time+=100; f.input=in(f.time,0,0,100,0,0); f.core.tick(); }
        final AimingSessionTest.Fake active=f; final int[] reads={0}; int before=f.forwards.size();
        f.onRead=() -> { if(++reads[0]==2) active.core.cancelImmediately(); };
        f.time+=100; f.core.tick();
        check(f.forwards.subList(before,f.forwards.size()).stream().allMatch(v->v==0),"late cancellation prevents forward command");
        regressions();
        fixedReturnRegressions();
        speedRegressions();
        System.out.println("PASS: "+checks+" VT 2.9 movement and session assertions");
    }
    static void regressions() {
        // One-minute inactivity setting must not conflict with initial lineup qualification.
        ComeToMeController c=new ComeToMeController();
        c.start(new ComeToMeSettings(true,70,20,18,8,30000,60000),1000);
        c.update_state_machine(in(1000,0,0,100,0,20),1000,.1);
        for(long t=1500;t<=61000;t+=500) c.update_state_machine(in(t,0,0,100,0,20),t,.1);
        check(c.approaching() && c.forward==0 && !c.noRideTimerActive(),"initial alignment waits without running no-ride timer");
        c.update_state_machine(in(61500,0,0,100,0,0),61500,.1);
        check(c.forward>0,"initial alignment permits travel");
        double heading=c.approachHeading, planned=c.approachDistance;
        c.update_state_machine(in(62000,10,15,100,20,4),62000,.1);
        check(c.approaching() && c.forward>0 && c.approachHeading==heading && c.approachDistance==planned,
                "sideways target jump cannot change plan or stop aligned approach");
        check(Math.abs(c.approachProgress-10)<.01,"lateral drone travel does not count as forward progress");
        c.update_state_machine(in(62500,29,15,100,20,0),62500,.1);
        check(c.approaching(),"29 of 30 metres does not complete approach");
        c.update_state_machine(in(63000,30.1,15,100,20,0),63000,.1);
        check(c.phase==ComeToMeController.Phase.HOLDING && c.noRideTimerActive(),"first completed approach starts countdown");
        c.update_state_machine(in(63500,30.1,15,100,40,0),63500,.1);
        check(c.phase==ComeToMeController.Phase.WAITING && c.noRideTimerActive(),"holding band exit retains countdown");
        for(long t=64000;t<=123000;t+=500) c.update_state_machine(in(t,30.1,15,100,40,0),t,.1);
        check(c.returning() && c.returnReason.equals("no_ride_timeout") && !c.noRideTimerActive(),
                "countdown expires even while waiting for a new lineup");

        // A second approach and another arrival must not reset the original countdown.
        c=new ComeToMeController();
        c.start(new ComeToMeSettings(true,70,20,18,8,30000,120000),1000);
        c.update_state_machine(in(1000,0,0,100,0,0),1000,.1); qualify(c);
        c.update_state_machine(in(61500,30.1,0,100,0,0),61500,.1);
        c.update_state_machine(in(62000,30.1,0,200,20,0),62000,.1);
        for(long t=62500;t<=122000;t+=500) c.update_state_machine(in(t,30.1,0,200,20,0),t,.1);
        check(c.approaching() && c.inactiveMs==60500,"requalified approach keeps original elapsed time");
        double n=30.1+(c.approachDistance+.1)*Math.cos(Math.toRadians(c.approachHeading));
        double e=(c.approachDistance+.1)*Math.sin(Math.toRadians(c.approachHeading));
        c.update_state_machine(in(122500,n,e,200,20,c.approachHeading),122500,.1);
        check(c.phase==ComeToMeController.Phase.HOLDING && c.inactiveMs==61000,"second arrival does not reset timer");
        for(long t=123000;t<=181500;t+=500) c.update_state_machine(in(t,n,e,200,20,0),t,.1);
        check(c.returning(),"timeout measured from first arrival");

        // Implausible jumps must not start rides or count as evidence that a ride ended.
        c=start(); qualify(c);
        c.update_state_machine(in(61500,0,0,146,0,0),61500,.1);
        check(c.speedJumpRejected && !c.riding && Double.isNaN(c.speedKmh),"46m jump in half a second rejected");
        for(long t=62000;t<=66500;t+=500) c.update_state_machine(in(t,0,0,146,0,0),t,.1);
        check(c.speedKmh==0 && !c.ride.confirmed,"fresh stationary evidence recovers without confirming a ride");
        c.update_state_machine(in(67000,0,0,146,0,0),67000,.1);
        check(c.speedKmh==0 && !c.riding,"stationary evidence recovers after five seconds");
        for(long t=67500;t<=73000;t+=500) c.update_state_machine(in(t,0,0,146+(t-67000)*.006,0,0),t,.1);
        check(c.riding && c.forward==0 && !c.approaching() && Double.isNaN(c.approachHeading),
                "genuine 21.6km/h ride interrupts approach and discards plan");
        c.update_state_machine(in(73500,0,0,300,0,0),73500,.1);
        check(c.riding && c.speedJumpRejected && c.slowMs==0,"jump cannot end a known ride");
        for(double boundary:new double[]{10,200}) new ComeToMeSettings(true,boundary,20,18,8,30000,900000);
        try { new ComeToMeSettings(true,9.99,20,18,8,30000,900000); throw new AssertionError("minimum not enforced"); }
        catch(IllegalArgumentException expected) { checks++; }

        // Return rotation must accelerate despite an opposing surfer-bearing command.
        AimingSessionTest.Fake f=new AimingSessionTest.Fake();
        f.movement=ComeToMeSettings.defaults(); f.aiming();
        f.core.movement.phase=ComeToMeController.Phase.RETURNING;
        f.core.movement.returnStartLat=30*DEG; f.core.movement.returnStartLon=0;
        f.core.movement.returnBearing=180; f.core.movement.returnHeading=0;
        f.core.movement.returnDistance=27; f.core.movement.returnProgress=0;
        for(int i=0;i<25;i++) {
            f.time+=100; f.input=in(f.time,30,0,-100,0,90); f.core.tick();
        }
        check(f.sent.get(f.sent.size()-1)<-7,"return yaw accelerates without opposing surfer calculation");
        check(f.forwards.get(f.forwards.size()-1)==0,"backward motion waits for return alignment");
        f.time+=100; f.input=in(f.time,30,0,-100,0,0); f.core.tick();
        check(f.forwards.get(f.forwards.size()-1)<0,"aligned return sends backward command");
        f.time+=100; f.input=in(f.time,2,0,-100,0,0); f.core.tick();
        check(!f.core.movement.returning() && !f.core.cycleDecision.equals("return_alignment"),"surfer aiming resumes on central arrival");
    }

    /** Exercise the real ride-end trigger, not a manually injected return phase. */
    static void beginTestReturn(ComeToMeController c,double north,double east) {
        c.start(ComeToMeSettings.defaults(),1000);
        c.update_state_machine(in(1000,0,0,100,0,0),1000,.1);
        for(long t=1500;t<=11000;t+=500)
            c.update_state_machine(in(t,north,east,100+(t-1000)*.006,0,0),t,.1);
        for(long t=11500;t<=47000;t+=500)
            c.update_state_machine(in(t,north,east,160,0,0),t,.1);
    }

    static void fixedReturnRegressions() {
        ComeToMeController c=new ComeToMeController(); beginTestReturn(c,30,0);
        check(c.returning() && c.returnAligned && c.forward<0,"real ride-end starts aligned backward return");
        check(Math.abs(c.returnDistance-27)<.01,"30m return plans 27m travel with 3m allowance");
        double saved=c.returnHeading; long generation=c.centralGeneration;
        c.update_state_machine(in(47500,30,10,160,0,4),47500,.1);
        check(c.returnHeading==saved && Math.abs(c.returnProgress)<.01 && c.forward<0,
                "sideways drift neither changes heading nor advances return progress");
        check(c.permits(in(47500,30,10,160,0,4),47500,c.forward),"final submission check does not reintroduce 3-degree gate");
        c.update_state_machine(in(48000,32,10,160,0,4),48000,.1);
        check(c.returnProgress< -1.9,"moving away from central produces negative progress");
        c.pause(false,48100);
        c.update_state_machine(in(48500,20,10,160,0,4),48500,.1);
        check(c.forward==0 && !c.returnAligned && c.returnHeading==saved && Math.abs(c.returnProgress-10)<.01,
                "safety pause preserves origin and heading but requires re-alignment");
        c.update_state_machine(in(49000,20,10,160,0,0),49000,.1);
        check(c.forward<0 && c.returnAligned,"realigned return resumes original travel");
        check(!c.permits(in(49000,2,10,160,0,0),49000,c.forward),"late telemetry reaching planned travel suppresses backward command");
        c.update_state_machine(in(49500,2,10,160,0,0),49500,.1);
        check(!c.returning() && c.forward==0 && c.reason.equals("return_travel_completed"),"projected completion latches even with lateral miss");
        check(c.returnCompletionCentralDistance>10 && c.returnCompletionCentralDistance<10.3,
                "completion records GPS miss outside 3m circle");
        c.update_state_machine(in(50000,5,10,160,0,0),50000,.1);
        check(!c.returning() && c.forward==0 && c.returnCompletionCentralDistance>10,
                "GPS drift cannot restart completed return or overwrite completion evidence");
        // Next ride must calculate from this new position to the original central coordinates.
        for(long t=50500;t<=60000;t+=500)
            c.update_state_machine(in(t,5,10,160+(t-50000)*.006,0,0),t,.1);
        for(long t=60500;t<=96000;t+=500)
            c.update_state_machine(in(t,5,10,220,0,0),t,.1);
        check(c.returning() && c.centralGeneration==generation && c.centralLat==0 && c.centralLon==0,
                "next return retains original central");
        check(Math.abs(c.returnStartLat-5*DEG)<1e-12 && Math.abs(c.returnStartLon-10*DEG)<1e-12
                && Math.abs(c.returnDistance-(Math.sqrt(125)-3))<.01 && c.returnHeading!=saved,
                "next return captures fresh origin, bearing and distance rather than repeating old plan");
        c.pause(true,96500);
        check(Double.isNaN(c.returnHeading) && !c.returnAligned,"manual intervention discards return plan");
        c=new ComeToMeController(); beginTestReturn(c,2,0);
        check(!c.returning() && c.forward==0 && c.returnDistance==0
                && Math.abs(c.returnCompletionCentralDistance-2)<.01,"already within 3m requires no travel");
        c=new ComeToMeController(); beginTestReturn(c,30,0);
        c.pause(false,400000);
        check(c.phase==ComeToMeController.Phase.STOPPED && c.forward==0,"paused fixed return retains five-minute deadline");
        c.cancel();
        check(Double.isNaN(c.returnDistance) && Double.isNaN(c.returnCompletionCentralDistance),"Stop clears return snapshot");
    }

    static void speedRegressions() {
        ComeToMeController c=start(); qualify(c);
        double last=c.forward;
        for(long t=61100;t<=66000;t+=100) {
            c.update_state_machine(in(t,0,0,100,0,0),t,.1);
            check(c.forward<=1.0 && c.forward-last<=.025001,"approach retains acceleration bound and 1m/s cap");
            last=c.forward;
        }
        check(Math.abs(c.forward-1.0)<1e-9,"approach reaches doubled 1m/s speed");
        c.update_state_machine(in(66500,29,0,100,0,0),66500,.1);
        check(c.forward>0 && c.forward<=.200001,"approach still slows in final five metres");
        c=new ComeToMeController(); beginTestReturn(c,30,0);
        for(long t=47100;t<=52000;t+=100) c.update_state_machine(in(t,30,0,160,0,0),t,.1);
        check(Math.abs(c.forward+1.0)<1e-9,"return reaches doubled 1m/s backward speed");
        c.update_state_machine(in(52500,4,0,160,0,0),52500,.1);
        check(c.forward<0 && Math.abs(c.forward)<=.200001,"return still slows in final five metres");
    }

}
