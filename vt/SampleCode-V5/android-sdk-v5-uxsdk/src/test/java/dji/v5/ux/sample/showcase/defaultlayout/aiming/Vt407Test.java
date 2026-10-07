package dji.v5.ux.sample.showcase.defaultlayout.aiming;
import java.nio.file.*;
public final class Vt407Test {
 static int checks;static final double DEG=ComeToMeTest.DEG;
 static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
 static void near(double a,double b,String why){check(Math.abs(a-b)<.02,why+" actual="+b);}
 static WaveLinePositioning preset(String mode){return new WaveLinePositioning(new WaveLineGeometry(0,-50*DEG,0,50*DEG,"leftOfAToB"),"wave","front".equals(mode)?"front":mode,"left",3,18,45,5,0,299,2);}
 static ComeToMeSettings settings(double standOff){return new ComeToMeSettings(true,28,50,99,.1,1000,60000,5,90000,-25,-10,15,8,4,300,standOff);}
 static ComeToMeController start(String mode,double standOff){
  ComeToMeController m=new ComeToMeController();m.start(settings(standOff),1000);m.positioning=preset(mode);
  m.update_state_machine(ComeToMeTest.in(1000,0,0,28,0,0),1000,.1);
  check(m.phase==ComeToMeController.Phase.HOLDING,"initial hold starts no-ride timer");return m;
 }
 static void trigger(ComeToMeController m,double n,double e){m.update_state_machine(ComeToMeTest.in(61000,n,e,100,0,0),61000,.1);}
 static void returns(){
  for(String mode:new String[]{"front","sideways","diagonal"}){
   // Sideways/diagonal fixtures use Front for the first hold, then switch preset.
   ComeToMeController m=start("front",10);m.positioning=preset(mode);trigger(m,40,20);
   check(m.returning()&&m.forward==0&&m.right==0,"neutral return transition "+mode);
   near(10,m.returnTargetLat/DEG,"target ten sea-side "+mode);near(5,m.returnTargetLon/DEG,"target on original return line");
   near(10,m.positioning.boundaryDistance(m.returnTargetLat,m.returnTargetLon,m.initialCentralLat,m.initialCentralLon),"boundary stand-off");
   double lat=m.returnTargetLat,lon=m.returnTargetLon,heading=m.returnHeading;
   AimingSession.Inputs in=ComeToMeTest.in(61100,40,22,100,0,heading);
   m.update_state_machine(in,61100,.1);check(m.returnAligned&&Math.hypot(m.forward,m.right)>0,"return movement");
   check(m.permits(in,61100,m.forward,m.right),"body velocity permitted");
   near(lat,m.returnTargetLat,"GPS deviation preserves target lat");near(lon,m.returnTargetLon,"GPS deviation preserves target lon");
   check(Math.abs(m.right)>.01,"lateral correction to fixed target");
   check(!m.permits(ComeToMeTest.in(61100,lat/DEG,lon/DEG,100,0,heading),61100,m.forward,m.right),"late arrival vetoes old command");
   m.pause(false,61200);m.update_state_machine(ComeToMeTest.in(61300,40,22,100,0,heading),61300,.1);
   near(lat,m.returnTargetLat,"pause preserves target");
   m.update_state_machine(ComeToMeTest.in(61400,lat/DEG,lon/DEG,100,0,heading),61400,.1);
   check(!m.returning()&&m.forward==0&&m.right==0,"arrival releases translation");
   near(0,m.initialCentralLat,"original anchor unchanged");
  }
  for(double n:new double[]{0,1,9.9,10}){
   ComeToMeController m=start("front",10);trigger(m,n,50);
   check(!m.returning()&&m.reason.equals("return_already_in_restart_zone"),"skip zone at "+n);
   check(m.forward==0&&m.right==0&&!m.returnPending,"skip neutral");
   check(!m.noRideTimerActive(),"skip consumes no-ride reset");
  }
  ComeToMeController m=start("front",10);trigger(m,-.02,0);
  check(m.returning(),"VT408 beachward start recovers to fixed stand-off");
  m.update_state_machine(ComeToMeTest.in(61100,1,0,100,0,m.returnHeading),61100,.1);
  check(m.returning()&&Math.hypot(m.forward,m.right)>0,"recovery keeps saved stand-off target");
  m=start("front",15);trigger(m,40,20);near(15,m.returnTargetLat/DEG,"configurable15m");near(7.5,m.returnTargetLon/DEG,"15m return intersection");
  m=start("front",10);trigger(m,40,0);m.update_state_machine(ComeToMeTest.in(61100,-1,0,100,0,0),61100,.1);
  check(m.returning()&&Math.hypot(m.forward,m.right)>0,"VT408 overshoot recovers rather than falsely completing");
  check(m.permits(ComeToMeTest.in(61100,-1,0,100,0,0),61100,m.forward,m.right),"sea-side recovery submission permitted");
 }
 static void priority(){
  ComeToMeController m=start("front",10);trigger(m,40,0);
  RetreatController r=new RetreatController((event,why)->{});r.start(new RetreatSettings(true,18,1000,3,1000));
  AimingSession.Inputs in=ComeToMeTest.in(61100,40,0,50,0,0);
  check(r.eligible(m)&&r.blocksApproach(m,in,61100),"retreat eligible during return");
  m.update_state_machine(in,61100,.1,true,r.blocksApproach(m,in,61100));r.update(m,in,61100);
  check(!m.returning()&&m.returnPending&&r.active,"return interrupted and pending");
  check(r.command(m,in)<0,"retreat owns movement");
  in=ComeToMeTest.in(62100,35,0,100,0,0);m.update_state_machine(in,62100,.1,true,r.blocksApproach(m,in,62100));r.update(m,in,62100);
  check(!r.active&&r.cooling(62100)&&m.returnPending,"cooldown retains pending reset");
  in=ComeToMeTest.in(63200,35,0,100,0,0);m.update_state_machine(in,63200,.1,true,r.blocksApproach(m,in,63200));r.update(m,in,63200);
  check(m.returning()&&!m.returnPending,"resume after cooldown");near(10,m.returnTargetLat/DEG,"resumed return same boundary offset");
  m.replaceApproachForRetreat();m.update_state_machine(ComeToMeTest.in(361001,35,0,100,0,0),361001,.1,true,true);
  check(m.phase==ComeToMeController.Phase.STOPPED,"retreat does not renew return deadline");
  m=start("front",10);trigger(m,40,0);m.replaceApproachForRetreat();m.pause(true,61200);
  check(!m.returnPending&&m.captureRequired&&Double.isNaN(m.returnTargetLat),"manual intervention clears pending reset");
 }
 static void config(String assets)throws Exception{
  String raw=Files.readString(Paths.get(assets,"vt_settings.json"));
  near(10,VtSettingsConfig.parse(raw,RetreatSettings.defaults()).movement.returnBoundaryStandOffMetres,"default config10");
  String absent=raw.replaceAll(",?\\s*\"returnBoundaryStandOffMetres\"\\s*:\\s*[-0-9.]+","");
  near(10,VtSettingsConfig.parse(absent,RetreatSettings.defaults()).movement.returnBoundaryStandOffMetres,"optional10");
  for(String bad:new String[]{"0","101","null","\"10\""}){
   Vt40Test.fails(()->VtSettingsConfig.parse(raw.replace("\"returnBoundaryStandOffMetres\": 10","\"returnBoundaryStandOffMetres\": "+bad),RetreatSettings.defaults()),"bad stand-off");checks++;
  }
  near(15,settings(15).withMaxMovementSpeed(3).returnBoundaryStandOffMetres,"speed copy preserves stand-off");
  Vt40Test.fails(()->VtSettingsConfig.parse(raw.replace("waveLineId","shorelineId"),RetreatSettings.defaults()),"old key rejected");checks++;
  WaveLineLibrary.parse(Files.readString(Paths.get(assets,"vt_wave_lines.json")));checks++;
  Vt40Test.fails(()->WaveLineLibrary.parse("{\"version\":1,\"shorelines\":[]}"),"old library rejected");checks++;
  check(!Files.exists(Paths.get(assets,"vt_shorelines.json")),"old asset absent");
 }
 static final class Port implements AimingSession.Port {
  long time=10000;AimingSession.Inputs in=ComeToMeTest.in(time,0,0,28,0,0);
  AimingSession.Authority a=new AimingSession.Authority(AimingSession.Owner.RC,false,false);
  AimingSession.Completion enable;double forward,right,yaw;final AimingSession core=new AimingSession(this);
  public long now(){return time;}public AimingSession.Inputs inputs(){return in;}public AimingSession.Authority authority(){return a;}
  public void enable(AimingSession.Completion c){enable=c;}public void disable(AimingSession.Completion c){c.complete(true);}public void advanced(){}
  public void sendYaw(double v){yaw=v;forward=right=0;}public void sendMotion(double y,double f,double r){yaw=y;forward=f;right=r;}
  public RetreatSettings retreatSettings(){return new RetreatSettings(true,18,1000,3,1000);}
  public ComeToMeSettings movementSettings(){return settings(10);}public WaveLinePositioning positioningSettings(){return preset("front");}
  void tick(double dn,double de,double sn,double se,double heading){time+=100;in=ComeToMeTest.in(time,dn,de,sn,se,heading);core.tick();}
  void start(){core.startAiming();enable.complete(true);a=new AimingSession.Authority(AimingSession.Owner.MSDK,true,false);core.tick();a=new AimingSession.Authority(AimingSession.Owner.MSDK,true,true);core.tick();tick(0,0,28,0,0);}
 }
 static void session(String output)throws Exception {
  Port p=new Port();p.start();
  for(int i=0;i<650&&!p.core.movement.returning();i++)p.tick(40,20,100,0,0);
  check(p.core.movement.returning(),"session no-ride return starts");
  double heading=p.core.movement.returnHeading;p.tick(40,22,100,0,heading);
  check(Math.hypot(p.forward,p.right)>0&&Math.abs(p.right)>.01,"session submits both-axis fixed-target correction");
  check(p.core.permitsMotion(p.in,p.time,p.forward,p.right),"session final submission guard permits return");
  AimingSession.Inputs late=ComeToMeTest.in(p.time,40,22,50,22,heading);
  check(!p.core.permitsMotion(late,p.time,p.forward,p.right),"late close surfer vetoes return command");
  p.tick(40,22,50,22,0);
  check(p.core.retreat.active&&p.core.movement.returnPending&&!p.core.movement.returning(),"session retreat preempts return");
  near(-3,p.forward,"actual submitted retreat");near(0,p.right,"retreat sole translation owner");
  FullSessionLog log=new FullSessionLog(name->Files.newOutputStream(Paths.get(output,"vt407-log.jsonl")),error->{throw new AssertionError(error);},"4.0.7-test");
  log.enable();MovementCycleLog.record(log,p.core,p.time,p.core.cycleId,p.forward,p.right);log.disable("test");
  long deadline=System.currentTimeMillis()+5000;while(log.busy()&&System.currentTimeMillis()<deadline)Thread.sleep(5);
  check(!log.busy(),"return diagnostic log closes");
  for(int i=0;i<25&&!p.core.movement.returning();i++)p.tick(35,22,100,0,0);
  check(p.core.movement.returning(),"session resumes pending return after retreat cooldown");
  double n=p.core.movement.returnTargetLat/DEG,e=p.core.movement.returnTargetLon/DEG;
  p.tick(n,e,100,0,p.core.movement.returnHeading);
  check(!p.core.movement.returning()&&p.forward==0&&p.right==0,"session completion neutral");
  p.tick(n,e,100,0,0);check(p.core.movement.approaching(),"ordinary positioning restarts after reset");
 }
 public static void main(String[] args)throws Exception{returns();priority();config(args[0]);session(args[1]);System.out.println("PASS: VT4.0.7 "+checks+" soft return, retreat and fresh Wave line checks");}
}
