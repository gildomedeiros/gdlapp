package dji.v5.ux.sample.showcase.defaultlayout.aiming;
public final class Vt408Test {
 static int checks;static final double DEG=ComeToMeTest.DEG;
 static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
 static WaveLinePositioning p(double threshold){return new WaveLinePositioning(Vt40Test.shore(),"wave","diagonal","left",3,threshold,45,5,0,299,2);}
 static void boundary(){
  check(AngleRoutePlanner.boundaryLegAllowed(-50,10),"deep beachward recovery allowed");
  check(AngleRoutePlanner.boundaryLegAllowed(0,10),"on boundary to sea");
  check(AngleRoutePlanner.boundaryLegAllowed(0,0),"parallel on boundary");
  check(!AngleRoutePlanner.boundaryLegAllowed(10,-1),"sea to beach rejected");
  check(!AngleRoutePlanner.boundaryLegAllowed(-1,-.1),"beachward waypoint rejected");
  check(!AngleRoutePlanner.boundaryLegAllowed(-1,-2),"worsening rejected");
  check(!AngleRoutePlanner.boundaryLegAllowed(Double.NaN,10),"invalid rejected");
  WaveLinePositioning p=p(19);
  AngleRoutePlanner.Check c=AngleRoutePlanner.planningCheck(p,-DEG,0,10*DEG,0,60*DEG,0,0,0,0,0);
  check(c.reason.equals("none"),"safe first recovery leg");
  c=AngleRoutePlanner.planningCheck(p,-DEG,0,50*DEG,0,25*DEG,0,0,0,0,0);
  check(c.reason.equals("route_planning_clearance"),"safe endpoints do not permit crossing S0");
  c=AngleRoutePlanner.check(p,-DEG,0,301*DEG,0,60*DEG,0,0,0,0,0);
  check(c.reason.equals("route_excursion_limit"),"boundary recovery does not bypass excursion");
  for(int i=1;i<=30;i++)for(int j=0;j<=30;j++) {
   double a=-i,b=j;
   check(AngleRoutePlanner.boundaryLegAllowed(a,b),"valid recovery endpoints");
   for(int k=1;k<=5;k++)check(a+(b-a)*k/5.0>=a,"recovery never worsens boundary");
  }
 }
 static void logged(){
  WaveLineGeometry g=new WaveLineGeometry(-28.08093,153.391,-28.08129,153.39122,"leftOfAToB");
  WaveLinePositioning p=new WaveLinePositioning(g,"wave","diagonal","left",3,19,-45,5,0,299,2);
  double sl=-28.08095,so=153.39144,al=-28.081217138788812,ao=153.39135636642266;
  double[] end={-28.080872616696404,153.3911470547214};
  AimingSession.Inputs in=new AimingSession.Inputs(new AimingSession.Fix(sl,so,0,1000),-28.080699384414707,153.3910366782554,0,1000,null,true);
  AngleRoutePlanner.Plan plan=AngleRoutePlanner.recover(p,in,end,sl,so,1000,al,ao,al,ao);
  check(plan.reason.equals("none")&&plan.points.length==1&&!plan.escapeFirst,"actual D1 to P3 direct recovery");
  AngleRoutePlanner.Check c=AngleRoutePlanner.planningCheck(p,in.lat,in.lon,end[0],end[1],sl,so,al,ao,al,ao);
  check(Math.abs(c.clearance-30)<.02&&c.boundary<0,"logged clearance and beachward origin");
  check(plan.points[0][0]==end[0]&&plan.points[0][1]==end[1],"P3 frozen");
  plan=AngleRoutePlanner.recover(p,in,new double[]{al-.001,ao},sl,so,1000,al,ao,al,ao);
  check(plan.reason.equals("destination_central_boundary"),"initial unsafe destination still rejected");
 }
 static void escape(){
  WaveLinePositioning p=p(19);AimingSession.Inputs in=ComeToMeTest.in(1000,-1,15,0,0,0);
  double[] end={30*DEG,30*DEG};
  AngleRoutePlanner.Plan route=AngleRoutePlanner.recover(p,in,end,0,0,1000,0,0,0,0);
  check(route.reason.equals("none")&&route.escapeFirst,"combined beachward and inside circle escape");
  double a=in.lat,b=in.lon;
  for(int i=0;i<route.points.length;i++) {
   double[] w=route.points[i];
   check(p.boundaryDistance(w[0],w[1],0,0)>=-1e-6,"all escape waypoints sea-side");
   AngleRoutePlanner.Check c=i==0?AngleRoutePlanner.escapeCheck(p,a,b,w[0],w[1],0,0,0,0,0,0):AngleRoutePlanner.planningCheck(p,a,b,w[0],w[1],0,0,0,0,0,0);
   check(c.reason.equals("none"),"escape and continuation checked");a=w[0];b=w[1];
  }
  check(!AngleRoutePlanner.escapeCheck(p,-DEG,15*DEG,10*DEG,0,0,0,0,0,0,0).reason.equals("none"),"sea-directed inward S0 escape rejected");
  ComeToMeController m=new ComeToMeController();m.start(Vt404Test.settings(),1000);m.positioning=p;
  // Original central remains zero; actual aircraft has drifted after capture.
  m.update_state_machine(ComeToMeTest.in(1000,0,0,40,0,0),1000,.1);
  m.replaceApproachForRetreat();
  m.update_state_machine(ComeToMeTest.in(1100,-1,15,0,0,0),1100,.1);
  check(m.approaching()&&m.route.escapeFirst,"initial calculation also supports escape");
 }
 static void arrival(){
  ComeToMeController m=Vt404Test.planner(36,45,45);
  m.route.points=new double[][]{{(m.initialCentralLat/DEG+.1)*DEG,m.centralLon}};
  m.approachTargetLat=m.route.points[0][0];m.approachTargetLon=m.route.points[0][1];
  AimingSession.Inputs in=ComeToMeTest.in(1100,m.initialCentralLat/DEG-.28,m.centralLon/DEG,70,0,0);
  m.update_state_machine(in,1100,.1);
  check(m.approaching()&&Math.hypot(m.forward,m.right)>0,"sub-metre recovery does not falsely arrive");
  check(m.permits(in,1100,m.forward,m.right),"final guard allows sub-metre recovery");
  check(!m.permits(ComeToMeTest.in(1100,in.lat/DEG,in.lon/DEG,70,0,90),1100,m.forward,m.right),"changed heading veto");
  long fix=m.destinationFixTime;double s=m.routeSurferLat;
  m.update_state_machine(ComeToMeTest.in(1200,m.route.points[0][0]/DEG,m.route.points[0][1]/DEG,70,0,0),1200,.1);
  check(m.phase==ComeToMeController.Phase.HOLDING,"arrival only after sea-side reached");
  check(m.destinationFixTime==fix&&m.routeSurferLat==s,"arrival preserves snapshot diagnostics");
 }
 static void returns(){
  for(String mode:new String[]{"front","sideways","diagonal"})for(double n:new double[]{-.02,-1,-50}) {
   ComeToMeController m=Vt407Test.start("front",10);m.positioning=Vt407Test.preset(mode);Vt407Test.trigger(m,n,20);
   check(m.returning(),"beachward return starts "+mode);
   check(Math.abs(m.returnTargetLat/DEG-10)<.02&&Math.abs(m.returnTargetLon/DEG-20)<.02,"nearest stand-off target for beachward start");
   double lat=m.returnTargetLat,lon=m.returnTargetLon,heading=m.returnHeading;
   AimingSession.Inputs in=ComeToMeTest.in(61100,n,20,100,0,heading);
   m.update_state_machine(in,61100,.1);
   check(m.forward!=0||m.right!=0,"return recovery command");check(m.permits(in,61100,m.forward,m.right),"return final submission");
   check(!m.permits(ComeToMeTest.in(61100,n,20,100,0,heading+90),61100,m.forward,m.right),"return late heading veto");
   m.update_state_machine(ComeToMeTest.in(61200,lat/DEG,lon/DEG,100,0,heading),61200,.1);
   check(!m.returning(),"return completes at fixed permitted target");
  }
  ComeToMeController m=Vt407Test.start("front",10);Vt407Test.trigger(m,40,20);
  double lat=m.returnTargetLat,lon=m.returnTargetLon,heading=m.returnHeading;
  AimingSession.Inputs in=ComeToMeTest.in(61100,-1,20,100,0,heading);m.update_state_machine(in,61100,.1);
  check(m.returning()&&m.permits(in,61100,m.forward,m.right),"mid-return beachward deviation recovers");
  check(m.returnTargetLat==lat&&m.returnTargetLon==lon,"mid-return target fixed");
  m=Vt407Test.start("front",10);Vt407Test.trigger(m,-1,299);
  check(!m.returning()&&m.reason.equals("return_target_excursion_limit"),"return cannot bypass target excursion");
 }
 static void session(){
  Vt407Test.Port port=new Vt407Test.Port();port.start();
  for(int i=0;i<650&&!port.core.movement.returning();i++)port.tick(-1,20,100,0,0);
  check(port.core.movement.returning(),"session due return does not starve beachward recovery");
  double h=port.core.movement.returnHeading;port.tick(-1,20,100,0,h);
  check(Math.hypot(port.forward,port.right)>0&&port.core.permitsMotion(port.in,port.time,port.forward,port.right),"session recovery submitted");
  check(!port.core.permitsMotion(ComeToMeTest.in(port.time,-1,20,5,20,h),port.time,port.forward,port.right),"current close surfer vetoes return");
  port.tick(-1,20,5,20,180);
  check(port.core.movement.returnPending&&!port.core.movement.returning(),"retreat preempts recovery return");
  port.core.stopAiming();check(!port.core.movement.returnPending,"stop clears pending recovery");
 }
 public static void main(String[] args){boundary();logged();escape();arrival();returns();session();System.out.println("PASS: "+checks+" VT 4.0.8 boundary, route, arrival, return and session checks");}
}
