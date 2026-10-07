from pathlib import Path
root=Path('C:/Users/gildo/.codex/worktrees/vt/gdlapp/vt')
p=root/'SampleCode-V5/android-sdk-v5-uxsdk/src/test/java/dji/v5/ux/sample/showcase/defaultlayout/aiming/Vt407Test.java'
s=p.read_text(encoding='utf-8')
insert=''' static final class Port implements AimingSession.Port {
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
'''
assert ' public static void main' in s
s=s.replace(' public static void main',insert+' public static void main').replace('priority();config(args[0]);','priority();config(args[0]);session(args[1]);')
p.write_text(s,encoding='utf-8')
p=root/'tools/test-aiming.ps1';s=p.read_text(encoding='utf-8')
s=s.replace("'dji.v5.ux.sample.showcase.defaultlayout.aiming.Vt407Test' (Join-Path $projectRoot 'SampleCode-V5/android-sdk-v5-uxsdk/src/main/assets')", "'dji.v5.ux.sample.showcase.defaultlayout.aiming.Vt407Test' (Join-Path $projectRoot 'SampleCode-V5/android-sdk-v5-uxsdk/src/main/assets') $output")
s+='''
$v407=@(Get-Content (Join-Path $output 'vt407-log.jsonl') | ForEach-Object { $_ | ConvertFrom-Json })
$row407=@($v407 | Where-Object event -eq 'movement_cycle')[0]
if($row407.returnBoundaryStandOffMetres -ne 10 -or $row407.returnPending -ne $true -or $row407.translationPurpose -ne 'retreat' -or $row407.waveLineId -ne 'wave' -or $null -eq $row407.returnTargetLatitude -or $null -eq $row407.returnBoundaryDistanceM -or $row407.submittedForwardMps -ne -3){throw 'VT407 independent return log validation failed'}
Write-Output 'PASS: VT407 independent JSON parser verifies fixed return target, stand-off, pending reset and retreat submission'
'''
p.write_text(s,encoding='utf-8')
print('Added production session return/retreat/submission tests and independent log verification')
