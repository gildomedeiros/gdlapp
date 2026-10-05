package dji.v5.ux.sample.showcase.defaultlayout.aiming;
import java.nio.file.*;
import java.util.*;
public final class Vt405Test {
    static int checks;static final double DEG=ComeToMeTest.DEG;
    static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    static void near(double a,double b,String why){check(Math.abs(a-b)<.02,why+" actual="+b);}
    static void path(ShorelinePositioning p,AngleRoutePlanner.Plan plan,AimingSession.Inputs in,double sl,double so,double al,double ao){
        check(plan.reason.equals("none"),"permitted replacement "+plan.reason);double a=in.lat,b=in.lon;
        for(int i=0;i<plan.points.length;i++){
            double[] w=plan.points[i];AngleRoutePlanner.Check c=plan.escapeFirst&&i==0?
                AngleRoutePlanner.escapeCheck(p,a,b,w[0],w[1],sl,so,al,ao,al,ao):
                AngleRoutePlanner.check(p,a,b,w[0],w[1],sl,so,al,ao,al,ao);
            check(c.reason.equals("none"),"replacement leg "+i+" safe "+c.reason);a=w[0];b=w[1];
        }
    }
    static void recovery(){
        ShorelinePositioning p=Vt404Test.preset(45,25);near(25,p.clearance(),"legacy extra2 ignored");
        // Sketch: east boundary overlaps circle; drone and target remain west. Sea points west.
        ShorelineGeometry g=new ShorelineGeometry(-50*DEG,0,50*DEG,0,"leftOfAToB");
        p=new ShorelinePositioning(g,"beach","diagonal","left",3,25,45,5,2,299);
        AimingSession.Inputs in=ComeToMeTest.in(1000,35,5,0,0,0);double[] end={-35*DEG,5*DEG};
        AngleRoutePlanner.Plan plan=AngleRoutePlanner.recover(p,in,end,0,0,1000,0,24*DEG,0,24*DEG);
        path(p,plan,in,0,0,0,24*DEG);check(plan.kind.equals("around"),"overlap routes around circle");
        for(double[] w:plan.points)check(w[1]/DEG<=24.001,"no boundary crossing");
        near(end[0],plan.points[plan.points.length-1][0],"saved endpoint lat");
        near(end[1],plan.points[plan.points.length-1][1],"saved endpoint lon");
        // Inside the circle: escape first, then safe full continuation.
        in=ComeToMeTest.in(1000,20,0,100,100,0);
        plan=AngleRoutePlanner.recover(p,in,end,0,0,1000,0,24*DEG,0,24*DEG);
        path(p,plan,in,0,0,0,24*DEG);check(plan.escapeFirst,"outward escape marked");
        check(!AngleRoutePlanner.escapeCheck(p,20*DEG,0,19*DEG,0,0,0,0,24*DEG,0,24*DEG).reason.equals("none"),"inward escape rejected");
        plan=AngleRoutePlanner.recover(p,in,new double[]{0,30*DEG},0,0,1000,0,24*DEG,0,24*DEG);
        check(plan.reason.equals("destination_central_boundary"),"unsafe saved destination not moved");
        // Retreat OFF keeps geometric around routing without adding a retreat circle.
        p=new ShorelinePositioning(g,"beach","diagonal","left",3,0,45,5,2,299);
        near(0,p.clearance(),"OFF clearance remains disabled");
        in=ComeToMeTest.in(1000,35,5,0,0,0);plan=AngleRoutePlanner.recover(p,in,end,0,0,1000,0,24*DEG,0,24*DEG);
        check(plan.reason.equals("none")&&!plan.escapeFirst,"OFF geometric route remains permitted without escape circle");
    }
    static void controller(){
        ComeToMeController repaired=Vt404Test.planner(36,-45,45);
        repaired.update_state_machine(ComeToMeTest.in(1100,70,0,40,0,0),1100,.1);
        check(repaired.routeStatus.equals("replanned")&&!repaired.route.escapeFirst,"outside circle blocked connector replans around");
        path(repaired.positioning,repaired.route,ComeToMeTest.in(1100,70,0,40,0,0),repaired.routeSurferLat,repaired.routeSurferLon,repaired.initialCentralLat,repaired.initialCentralLon);
        ComeToMeController m=Vt404Test.planner(36,-45,45);List<ComeToMeController.JourneyOutcome> outcomes=new ArrayList<>();m.outcomeListener=outcomes::add;
        double sl=m.routeSurferLat,so=m.routeSurferLon,el=m.approachTargetLat,eo=m.approachTargetLon;long fix=m.destinationFixTime;
        // Current surfer moves; repair must still use captured (40,0), not current (60,0).
        m.update_state_machine(ComeToMeTest.in(1100,23,0,60,0,0),1100,.1);
        check(m.routeStatus.equals("replanned")&&m.route.escapeFirst,"blocked leg replans escape");
        check(m.forward==0&&m.right==0,"repair cycle neutral");
        check(sl==m.routeSurferLat&&so==m.routeSurferLon&&el==m.approachTargetLat&&eo==m.approachTargetLon&&fix==m.destinationFixTime,"all snapshot fields frozen");
        m.update_state_machine(ComeToMeTest.in(1200,23,0,60,0,0),1200,.1);
        check(Math.hypot(m.forward,m.right)>0,"escape next tick moves");
        check(m.permits(ComeToMeTest.in(1200,23,0,60,0,0),1200,m.forward,m.right),"submission accepts monotonic escape");
        m.update_state_machine(ComeToMeTest.in(301000,23,0,60,0,0),301000,.1);
        check(m.phase==ComeToMeController.Phase.STOPPED,"original300s timeout despite recovery");
        check(outcomes.size()==1&&outcomes.get(0).outcome.equals("timed_out"),"one timeout outcome");
        m.cancel("user_stop");check(outcomes.size()==1,"no duplicate outcome");
        m=Vt404Test.planner(36,-45,45);outcomes.clear();m.outcomeListener=outcomes::add;m.replaceApproachForRetreat();
        check(m.route==null&&outcomes.size()==1&&outcomes.get(0).cause.equals("retreat"),"retreat cancels journey and logs once");
        m=Vt404Test.planner(36,-45,45);outcomes.clear();m.outcomeListener=outcomes::add;m.pause(true,1100);
        check(outcomes.size()==1&&outcomes.get(0).cause.equals("pilot_intervention"),"pilot cancellation outcome");
        m=Vt404Test.planner(36,-45,45);outcomes.clear();m.outcomeListener=outcomes::add;m.cancel("returning_home");
        check(outcomes.size()==1&&outcomes.get(0).cause.equals("returning_home"),"stop cause retained");
        m=Vt404Test.planner(36,-45,45);outcomes.clear();m.outcomeListener=outcomes::add;
        for(double[] w:m.route.points)m.update_state_machine(ComeToMeTest.in(1100,w[0]/DEG,w[1]/DEG,40,0,0),1100,.1);
        check(outcomes.size()==1&&outcomes.get(0).outcome.equals("arrived"),"one arrival outcome");
        m=Vt404Test.planner(36,-45,45);m.update_state_machine(ComeToMeTest.in(1100,14,0,40,0,0),1100,.1);
        check(m.routeStatus.equals("blocked")&&m.forward==0&&m.right==0,"no permitted boundary recovery holds");
        long count=m.recoveryAttempts;m.update_state_machine(ComeToMeTest.in(1200,14,0,40,0,0),1200,.1);
        check(m.recoveryAttempts==count,"failed replan throttled");
    }
    static void logs(String output)throws Exception{
        Vt404Test.Port p=new Vt404Test.Port(-45);p.start();p.tick(100,0,15,40,0,0);
        Path file=Paths.get(output,"vt405-log.jsonl");FullSessionLog log=new FullSessionLog(n->Files.newOutputStream(file),e->{throw new AssertionError(e);},"4.0.5-test");log.enable();
        p.core.movement.projectedSeparation=99;p.core.movement.alignmentError=88;
        MovementCycleLog.record(log,p.core,p.time,p.core.cycleId,p.forward,p.right);
        p.core.movement.update_state_machine(ComeToMeTest.in(p.time+100,0,15,40,0,0),p.time+100,.1,true,true);
        check(Double.isNaN(p.core.movement.projectedSeparation),"retreat gate clears projected separation");
        log.disable("test");long deadline=System.currentTimeMillis()+5000;while(log.busy()&&System.currentTimeMillis()<deadline)Thread.sleep(5);
        check(!log.busy(),"log closes");String text=Files.readString(file);
        check(text.contains("\"version\":\"4.0.5-test\"")&&text.contains("\"projectedSeparationM\":null"),"runtime metadata and diagonal null");
    }
    public static void main(String[] args)throws Exception{recovery();controller();logs(args[0]);System.out.println("PASS: VT4.0.5 "+checks+" recovery/outcome/log checks");}
}
