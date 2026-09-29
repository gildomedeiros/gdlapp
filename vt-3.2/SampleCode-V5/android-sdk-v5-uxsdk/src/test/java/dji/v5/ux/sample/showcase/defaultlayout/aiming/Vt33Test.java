package dji.v5.ux.sample.showcase.defaultlayout.aiming;
/** Offline policy regression tests; no DJI stubs are executed. */
public final class Vt33Test {
 static int checks;
 static void check(boolean v,String s) {checks++;if(!v)throw new AssertionError(s);}
 static ComeToMeSettings config(long duration) {return new ComeToMeSettings(true,70,50,18,.1,1000,60000,15,duration,-35);}
 static AimingSession.Fix fix(long t,double n) {return new AimingSession.Fix(n*ComeToMeTest.DEG,0,0,t,t,t);}
 public static void main(String[] args) {
  RideDetector d=new RideDetector();ComeToMeSettings c=config(90000);
  d.observe(fix(1000,0),c);d.observe(fix(2000,6),c,2100);
  check(d.riding && d.confirmed && d.expiresAt==92100,"timer starts at detection, not sample timestamp");
  long end=d.expiresAt;
  for(long t=2500;t<90000;t+=500) {d.observe(fix(t,t%2==0?500:0),c);check(d.expiresAt==end && d.riding,"packets cannot extend/end ride");}
  d.clearEvidence("stale_gps");check(!d.advance(end-1) && d.riding,"gap preserves deadline");
  check(d.advance(end) && !d.riding && d.event.equals("ride_expired"),"deadline expires with no packet");
  d.observe(fix(end-100,600),c);check(!d.riding,"old packet cannot rearm");
  d.observe(fix(end+100,600),c);check(!d.riding,"first new packet cannot rearm alone");
  d.observe(fix(end+1100,606),c);check(d.riding,"new post-expiry evidence can start next ride");
  d.reset();check(!d.riding && d.expiresAt==-1,"session reset clears timer");
  d.observe(fix(1000,0),config(2000));d.observe(fix(2000,6),config(2000));
  check(d.expiresAt==4000,"configured duration used");
  ComeToMeController m=new ComeToMeController();m.start(config(2000),1000);
  m.update_state_machine(ComeToMeTest.in(1000,0,0,60,0,0),1000,.1);
  check(m.noRideTimerActive(),"holding starts no-ride timer");
  m.update_state_machine(ComeToMeTest.in(2000,0,0,66,0,0),2000,.1);
  check(m.riding && !m.noRideTimerActive(),"initial ride detection cancels timeout");
  m.pauseForGps(4000);check(!m.riding && !m.returning() && !m.approaching(),"stale expiry clears ride without starting navigation");
  m.update_state_machine(ComeToMeTest.in(4100,0,0,100,0,0),4100,.1);
  check(m.approaching(),"fresh fix permits approach after expiry");
  GimbalPitchPolicy p=new GimbalPitchPolicy();
  check(!p.update(0,40,false,-35,-6,-90,20) && !Double.isFinite(p.target),"stale startup waits");
  check(!p.update(Double.NaN,40,true,-35,-6,-90,20),"unknown pitch blocks startup");
  check(!p.update(0,40,true,-35,-6,Double.NaN,20),"unknown limits block startup");
  check(p.update(0,40,true,-35,-6,-90,20) && p.target==-6 && p.reason.equals("startup_long_range"),"startup uses configured long pitch");
  check(!p.update(-15,40,true,-35,-6,-90,20),"manual wheel not continuously overridden");
  check(!p.update(-6,29,false,-35,-6,-90,20),"stale GPS cannot tilt");
  check(!p.update(-6,30,true,-35,-6,-90,20),"exact 30m does not enter");
  check(p.update(-6,29,true,-35,-6,-90,20) && p.target==-35,"close target");
  check(!p.update(-35,35,true,-35,-6,-90,20) && p.close,"exact 35m holds");
  check(!p.update(-35,36,false,-35,-6,-90,20) && p.close,"stale cannot switch long");
  check(p.update(-35,36,true,-35,-6,-90,20) && p.target==-6,"restores configured long, not manual startup");
  p.reset();check(p.update(0,20,true,-35,-6,-90,20) && p.target==-6,"starts long even already close");
  check(p.update(-6,20,true,-35,-6,-90,20) && p.target==-35,"then applies close range");
  p.reset();check(!p.update(-6,40,true,-35,-6,-90,20) && p.target==-6,"already aligned startup needs no command");
  p.reset();check(p.update(0,40,true,-90,-90,-80,20) && p.target==-80,"long clamped to hardware");
  check(!p.update(-80,20,true,-90,-90,-80,20) && p.close && p.target==-80,"close clamped too");
  p.reset();check(p.update(-24,40,true,0,0,-90,20) && p.target==0,"zero means horizon");
  check(ComeToMeSettings.defaults().closeRangePitchDeg==-35 && ComeToMeSettings.defaults().longRangePitchDeg==-6,"defaults");
  for(double angle:new double[]{-90,0,-35}) {
   ComeToMeSettings angles=new ComeToMeSettings(true,70,50,18,.1,1000,60000,15,90000,angle,angle);
   check(angles.closeRangePitchDeg==angle && angles.longRangePitchDeg==angle,"valid angles");
  }
  for(double angle:new double[]{-91,1,Double.NaN,Double.POSITIVE_INFINITY}) {
   boolean rejected=false;try {new ComeToMeSettings(true,70,50,18,.1,1000,60000,15,90000,angle);} catch(IllegalArgumentException expected) {rejected=true;}
   check(rejected,"invalid close angle rejected");
   rejected=false;try {new ComeToMeSettings(true,70,50,18,.1,1000,60000,15,90000,-35,angle);} catch(IllegalArgumentException expected) {rejected=true;}
   check(rejected,"invalid long angle rejected");
  }
  check(RecordingGate.problem(false,1000,1100).equals("recording_off"),"recording off blocks Start");
  check(RecordingGate.problem(null,1000,1100).equals("recording_unknown"),"unknown recording blocks Start");
  check(RecordingGate.problem(true,1000,3100).equals("recording_unknown"),"stale recording blocks Start");
  check(RecordingGate.problem(true,1000,1100)==null,"fresh recording allows gate");
  AimingSessionTest.Fake f=new AimingSessionTest.Fake();
  f.recordingProblem="recording_off";f.core.startAiming();
  check(!f.core.canStart() && f.enables==0,"actual Start cannot bypass recording gate");
  f.recordingProblem=null;f.aiming();f.recordingProblem="recording_off";f.advance(100);
  check(f.core.state()==AimingSession.State.AIMING,"recording stopping mid-run is not a flight veto");
  f.core.movement.ride.riding=true;f.core.movement.riding=true;f.core.movement.ride.expiresAt=f.time+1000;
  f.core.pauseImmediately("pilot_stick");f.core.tick();
  check(!f.core.movement.riding,"manual reposition still clears ride");
  System.out.println("PASS: "+checks+" VT 3.3 fixed ride timer, pitch hysteresis and recording gate assertions");
 }
}
