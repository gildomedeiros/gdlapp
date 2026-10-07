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
  check(!m.approaching()&&m.pathBlockReason.equals("destination_central_boundary"),"initial unsafe preserved-radius destination still holds");
  m.update_state_machine(ComeToMeTest.in(1200,-1,25,40,0,0),1200,.1);
  check(m.approaching()&&!m.route.escapeFirst,"first journey from beachward position calculates permitted route");
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
  port.core.stopAiming("user_stop");check(!port.core.movement.returnPending,"stop clears pending recovery");
 }
 static void replayStoppedPositions(){
  WaveLineGeometry g=new WaveLineGeometry(-28.08093,153.391,-28.08129,153.39122,"leftOfAToB");
  WaveLinePositioning p=new WaveLinePositioning(g,"wave","diagonal","left",3,19,-45,5,0,299,2);
  // All 27 distinct recovery attempts in the supplied 06:52:04 flight, journey 2.
  double[][] rows={
   {-28.080699384414707,153.3910366782554,126.30000000000001,-28.08095,153.39144,-28.080872616696404,153.3911470547214,-28.081217138788812,153.39135636642266},
   {-28.080719005830282,153.39104748916583,126.80000000000001,-28.08095,153.39144,-28.080872616696404,153.3911470547214,-28.081217138788812,153.39135636642266},
   {-28.080717534891477,153.3910475533098,126.80000000000001,-28.08095,153.39144,-28.080872616696404,153.3911470547214,-28.081217138788812,153.39135636642266},
   {-28.08071559503417,153.39104701982532,126.60000000000001,-28.08095,153.39144,-28.080872616696404,153.3911470547214,-28.081217138788812,153.39135636642266},
   {-28.080715161582685,153.39104719073998,126.60000000000001,-28.08095,153.39144,-28.080872616696404,153.3911470547214,-28.081217138788812,153.39135636642266},
   {-28.080715219495012,153.39104732178558,126.7,-28.08095,153.39144,-28.080872616696404,153.3911470547214,-28.081217138788812,153.39135636642266},
   {-28.080715513031688,153.39104760034482,126.7,-28.08095,153.39144,-28.080872616696404,153.3911470547214,-28.081217138788812,153.39135636642266},
   {-28.080716032769107,153.39104785208517,126.9,-28.08095,153.39144,-28.080872616696404,153.3911470547214,-28.081217138788812,153.39135636642266},
   {-28.080716228677254,153.3910477488295,126.60000000000001,-28.08095,153.39144,-28.080872616696404,153.3911470547214,-28.081217138788812,153.39135636642266},
   {-28.080716708904077,153.39104752697637,126.7,-28.08095,153.39144,-28.080872616696404,153.3911470547214,-28.081217138788812,153.39135636642266},
   {-28.080716849367167,153.39104648843826,126.80000000000001,-28.08095,153.39144,-28.080872616696404,153.3911470547214,-28.081217138788812,153.39135636642266},
   {-28.080717179433158,153.391045997095,126.80000000000001,-28.08095,153.39144,-28.080872616696404,153.3911470547214,-28.081217138788812,153.39135636642266},
   {-28.08071735759066,153.39104604721783,126.7,-28.08095,153.39144,-28.080872616696404,153.3911470547214,-28.081217138788812,153.39135636642266},
   {-28.080717800465752,153.39104642833362,126.80000000000001,-28.08095,153.39144,-28.080872616696404,153.3911470547214,-28.081217138788812,153.39135636642266},
   {-28.08071846333914,153.39104672040912,126.60000000000001,-28.08095,153.39144,-28.080872616696404,153.3911470547214,-28.081217138788812,153.39135636642266},
   {-28.080718477251803,153.39104683758887,126.7,-28.08095,153.39144,-28.080872616696404,153.3911470547214,-28.081217138788812,153.39135636642266},
   {-28.080718488902804,153.39104685207613,126.60000000000001,-28.08095,153.39144,-28.080872616696404,153.3911470547214,-28.081217138788812,153.39135636642266},
   {-28.08071883524593,153.3910470485668,126.7,-28.08095,153.39144,-28.080872616696404,153.3911470547214,-28.081217138788812,153.39135636642266},
   {-28.080719321949335,153.39104699925966,126.80000000000001,-28.08095,153.39144,-28.080872616696404,153.3911470547214,-28.081217138788812,153.39135636642266},
   {-28.08071955675126,153.39104695554545,126.7,-28.08095,153.39144,-28.080872616696404,153.3911470547214,-28.081217138788812,153.39135636642266},
   {-28.080719650267678,153.39104718007846,126.7,-28.08095,153.39144,-28.080872616696404,153.3911470547214,-28.081217138788812,153.39135636642266},
   {-28.08071964601849,153.3910471069625,126.7,-28.08095,153.39144,-28.080872616696404,153.3911470547214,-28.081217138788812,153.39135636642266},
   {-28.08071963025537,153.3910478115752,127.80000000000001,-28.08095,153.39144,-28.080872616696404,153.3911470547214,-28.081217138788812,153.39135636642266},
   {-28.080719547087785,153.39104801233827,130.20000000000002,-28.08095,153.39144,-28.080872616696404,153.3911470547214,-28.081217138788812,153.39135636642266},
   {-28.080719393431654,153.3910478888082,130.8,-28.08095,153.39144,-28.080872616696404,153.3911470547214,-28.081217138788812,153.39135636642266},
   {-28.08071886115227,153.39104773445874,133.0,-28.08095,153.39144,-28.080872616696404,153.3911470547214,-28.081217138788812,153.39135636642266},
   {-28.080719208591955,153.3910475597766,135.70000000000002,-28.08095,153.39144,-28.080872616696404,153.3911470547214,-28.081217138788812,153.39135636642266}
  };
  for(double[] a:rows){
   AimingSession.Inputs in=new AimingSession.Inputs(new AimingSession.Fix(a[3],a[4],0,1000),a[0],a[1],a[2],1000,null,true);
   AngleRoutePlanner.Plan route=AngleRoutePlanner.recover(p,in,new double[]{a[5],a[6]},a[3],a[4],1000,a[7],a[8],a[7],a[8]);
   check(route.reason.equals("none")&&route.points.length==1&&!route.escapeFirst,"logged recovery attempt has safe direct replacement");
   AngleRoutePlanner.Check c=AngleRoutePlanner.planningCheck(p,a[0],a[1],a[5],a[6],a[3],a[4],a[7],a[8],a[7],a[8]);
   check(c.reason.equals("none")&&c.clearance>=21-1e-6,"logged replacement complete-leg planning checks");
  }
 }
 static void log(String output)throws Exception {
  Vt407Test.Port port=new Vt407Test.Port();port.start();
  for(int i=0;i<650&&!port.core.movement.returning();i++)port.tick(-1,20,100,0,0);
  port.tick(-1,20,100,0,port.core.movement.returnHeading);
  FullSessionLog log=new FullSessionLog(name->java.nio.file.Files.newOutputStream(java.nio.file.Paths.get(output,"vt408-log.jsonl")),error->{throw new AssertionError(error);},"4.0.8-test");
  log.enable();MovementCycleLog.record(log,port.core,port.time,port.core.cycleId,port.forward,port.right);log.disable("test");
  long end=System.currentTimeMillis()+5000;while(log.busy()&&System.currentTimeMillis()<end)Thread.sleep(5);
  check(!log.busy(),"boundary recovery diagnostic closes");
 }
 public static void main(String[] args)throws Exception{boundary();logged();replayStoppedPositions();escape();arrival();returns();session();if(args.length>0)log(args[0]);System.out.println("PASS: "+checks+" VT 4.0.8 boundary, route, arrival, return and session checks");}
}
