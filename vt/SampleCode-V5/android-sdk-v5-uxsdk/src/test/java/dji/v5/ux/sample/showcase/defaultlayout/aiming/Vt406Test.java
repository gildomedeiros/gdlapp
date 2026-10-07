package dji.v5.ux.sample.showcase.defaultlayout.aiming;
import java.nio.file.*;
public final class Vt406Test {
 static int checks;static final double DEG=ComeToMeTest.DEG;
 static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
 static void near(double a,double b,String why){check(Math.abs(a-b)<.02,why+" actual="+b);}
 static WaveLinePositioning preset(double allowance,double threshold){return new WaveLinePositioning(new WaveLineGeometry(-50*DEG,0,50*DEG,0,"leftOfAToB"),"beach","diagonal","left",3,threshold,45,5,2,299,allowance);}
 static void geometry(){
  WaveLinePositioning p=preset(2,25);near(25,p.clearance(),"execution25");near(27,p.planningClearance(),"planning27");
  AngleRoutePlanner.Check c=AngleRoutePlanner.check(p,26*DEG,0,26*DEG,10*DEG,0,0,0,24*DEG,0,24*DEG);
  check(c.reason.equals("none"),"26m leg continues during execution");
  c=AngleRoutePlanner.planningCheck(p,26*DEG,0,26*DEG,10*DEG,0,0,0,24*DEG,0,24*DEG);
  check(c.reason.equals("route_planning_clearance"),"same26m leg rejected for new planning");
  c=AngleRoutePlanner.check(p,24.9*DEG,0,24.9*DEG,10*DEG,0,0,0,24*DEG,0,24*DEG);
  check(c.reason.equals("route_surfer_clearance"),"below25 execution blocks");
  AimingSession.Inputs in=ComeToMeTest.in(1000,35,5,0,0,0);double[] end={-35*DEG,5*DEG};
  AngleRoutePlanner.Plan plan=AngleRoutePlanner.plan(p,in,end,0,24*DEG,0,24*DEG);
  check(plan.reason.equals("none")&&plan.kind.equals("around"),"wider route opposite side of overlapping boundary");
  double a=in.lat,b=in.lon;boolean west=false;
  for(double[] w:plan.points){c=AngleRoutePlanner.planningCheck(p,a,b,w[0],w[1],0,0,0,24*DEG,0,24*DEG);
   check(c.reason.equals("none")&&c.clearance>=27-.001,"ALL legs including entry exit27");west|=w[1]<0;a=w[0];b=w[1];}
  check(west,"route takes available west side");
  near(end[0],plan.points[plan.points.length-1][0],"unchanged destination lat");
  near(end[1],plan.points[plan.points.length-1][1],"unchanged destination lon");
  in=ComeToMeTest.in(1000,26,0,70,70,0);
  plan=AngleRoutePlanner.recover(p,in,end,0,0,1000,0,24*DEG,0,24*DEG);
  check(plan.reason.equals("none")&&plan.escapeFirst,"outward bridge from26 to planning circle");
  double[] w=plan.points[0];c=AngleRoutePlanner.escapeCheck(p,in.lat,in.lon,w[0],w[1],0,0,0,24*DEG,0,24*DEG);
  check(c.reason.equals("none"),"bridge outward");a=w[0];b=w[1];
  for(int i=1;i<plan.points.length;i++){w=plan.points[i];c=AngleRoutePlanner.planningCheck(p,a,b,w[0],w[1],0,0,0,24*DEG,0,24*DEG);check(c.reason.equals("none"),"postescape legs planning27");a=w[0];b=w[1];}
  plan=AngleRoutePlanner.recover(p,in,new double[]{26*DEG,0},0,0,1000,0,24*DEG,0,24*DEG);
  check(plan.reason.equals("destination_inside_planning_clearance"),"saved destination inside planning circle held unchanged");
  p=preset(0,25);near(25,p.planningClearance(),"zero configurable allowance");
  p=preset(2,0);near(0,p.planningClearance(),"OFF no planning circle");
  for(double bad:new double[]{-1,51,Double.NaN}){try{preset(bad,25);throw new AssertionError("invalid allowance accepted");}catch(IllegalArgumentException expected){checks++;}}
 }
 static void config(String assets)throws Exception{
  String raw=Files.readString(Paths.get(assets,"vt_settings.json"));RetreatSettings retreat=RetreatSettings.defaults();
  near(2,VtSettingsConfig.parse(raw,retreat).planningAllowance,"default2 field");
  String old=raw.replaceAll(",?\\s*\"routePlanningAllowanceMetres\"\\s*:\\s*[-0-9.]+","");
  near(2,VtSettingsConfig.parse(old,retreat).planningAllowance,"old JSON default2");
  for(String bad:new String[]{"-1","51","null","\"2\""}){Vt40Test.fails(()->VtSettingsConfig.parse(raw.replace("\"routePlanningAllowanceMetres\": 2","\"routePlanningAllowanceMetres\": "+bad),retreat),"invalid planning allowance");checks++;}
  near(0,VtSettingsConfig.parse(raw.replace("\"routePlanningAllowanceMetres\": 2","\"routePlanningAllowanceMetres\": 0"),retreat).planningAllowance,"zero parses");
  near(1.5,VtSettingsConfig.parse(raw.replace("\"routePlanningAllowanceMetres\": 2","\"routePlanningAllowanceMetres\": 1.5"),retreat).planningAllowance,"fractional parses");
 }
 static void status(String output)throws Exception{
  ComeToMeController m=Vt404Test.planner(36,-45,45);
  double anchor=m.initialCentralLat;m.initialCentralLat=30*DEG;
  m.update_state_machine(ComeToMeTest.in(1100,14,0,40,0,0),1100,.1);
  check(m.recoveryStatus.equals("destination_central_boundary"),"recovery failure current status");
  m.initialCentralLat=anchor;
  m.update_state_machine(ComeToMeTest.in(1200,m.centralLat/DEG,m.centralLon/DEG,40,0,0),1200,.1);
  check(Math.hypot(m.forward,m.right)>0&&m.recoveryStatus.equals("none"),"continuation clears current failure");
  check(m.lastRecoveryResult.equals("destination_central_boundary")&&m.lastJourneyBlock.equals("route_central_boundary"),"history retained separately");
  check(m.failedSegment==-1&&Double.isNaN(m.failedBoundary),"old failure metrics cleared");
  Vt404Test.Port p=new Vt404Test.Port(-45);p.start();p.tick(100,0,15,40,0,0);
  FullSessionLog log=new FullSessionLog(n->Files.newOutputStream(Paths.get(output,"vt406-log.jsonl")),e->{throw new AssertionError(e);},"4.0.6-test");log.enable();
  MovementCycleLog.record(log,p.core,p.time,p.core.cycleId,p.forward,p.right);log.disable("test");
  long until=System.currentTimeMillis()+5000;while(log.busy()&&System.currentTimeMillis()<until)Thread.sleep(5);check(!log.busy(),"writer closes");
 }
 public static void main(String[] args)throws Exception{geometry();config(args[0]);status(args[1]);System.out.println("PASS: VT4.0.6 "+checks+" planning allowance/status checks");}
}
