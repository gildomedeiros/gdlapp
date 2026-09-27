package dji.v5.ux.sample.showcase.defaultlayout.aiming;

/** VT 3.2: Moving-target policy, including confirmation's retained timer role. */
public final class Vt32RidePolicyTest {
    static int checks;
    static void check(boolean ok,String why) { checks++; if(!ok) throw new AssertionError(why); }
    public static void main(String[] args) {
        check(ComeToMeSettings.QUALIFY_MS==0,"qualification is disabled");
        for(boolean near:new boolean[]{false,true}) {
            ComeToMeController c=new ComeToMeController();
            c.start(ComeToMeSettings.defaults(),1000);
            c.update_state_machine(ComeToMeTest.in(1000,0,0,60,0,0),1000,.1);
            check(c.noRideTimerActive(),"initial filming hold starts no-ride timer");
            double bandLat=c.bandLat,bandLon=c.bandLon,bearing=c.bandBearing;
            boolean confirmed=false,ended=false;
            for(long t=1500;t<=47000;t+=500) {
                double target=60+Math.min(t-1000,10000)*.006;
                c.update_state_machine(ComeToMeTest.in(t,near?60:0,0,target,0,0),t,.1);
                if(c.event.equals("ride_confirmed")) {
                    confirmed=true;
                    check(!c.noRideTimerActive(),"confirmed ride still cancels no-ride countdown");
                }
                if(c.riding) check(c.forward==0 && !c.approaching(),"ride blocks approach movement");
                if(c.event.equals("ride_ended")) {
                    ended=true;
                    check(!c.returning() && c.returnReason.equals("none"),"confirmed ride end never requests return");
                    check(c.bandLat==bandLat && c.bandLon==bandLon && c.bandBearing==bearing,"ride end does not recreate the lineup band");
                    check(near ? c.phase==ComeToMeController.Phase.HOLDING : c.approaching(),"ride end permits distance-based hold or immediate approach");
                }
            }
            check(confirmed && ended,"fixture exercises real confirmed ride and end events");
        }
        ComeToMeController c=ComeToMeTest.start();
        // Cancel the saved approach via manual reset: a new approach still needs fresh GPS.
        c.pause(true,1100);
        c.update_state_machine(ComeToMeTest.in(1000,0,0,100,0,0),5000,.1);
        check(c.forward==0 && !c.approaching(),"zero qualification cannot start on stale GPS");
        c.update_state_machine(ComeToMeTest.in(5100,0,0,100,0,90),5100,.1);
        check(c.approaching() && c.forward==0,"fresh target starts alignment immediately but cannot translate misaligned");
        c.update_state_machine(ComeToMeTest.in(5200,0,0,100,0,0),5200,.1);
        check(c.forward>0,"aligned approach proceeds without ten-second dwell");
        System.out.println("PASS: "+checks+" VT 3.2 zero-qualification and ride-end policy assertions");
    }
}
