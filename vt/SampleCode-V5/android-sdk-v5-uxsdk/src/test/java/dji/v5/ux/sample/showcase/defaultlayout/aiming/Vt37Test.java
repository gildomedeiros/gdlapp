package dji.v5.ux.sample.showcase.defaultlayout.aiming;
import java.nio.file.*;
import java.util.*;
import java.io.*;
/** VT 3.7: Production JSON, ramp/reversal, session selection, migration and log evidence. */
public final class Vt37Test {
 static int checks;
 static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
 static void near(double a,double b,String why){check(Math.abs(a-b)<1e-6,why+": "+b);}
 static void curves(String raw) {
  RotationSpeedCurve c=RotationSpeedConfig.parse(raw);
  near(6,c.speed(6,true),"six degrees interpolates to six");
  near(3.25,c.speed(4,true),"unchanged three-degree point joins doubled five-degree point");
  near(1.5,c.speed(3,true),"ride three-degree point unchanged");
  near(30,c.speed(180,true),"hold last point");
  for(double e=-180;e<=180;e+=.25) {
   near(RotationSpeedCurve.desired(e,false,15,null),RotationSpeedCurve.desired(e,false,15,c),"normal default matches old curve");
   check(Math.abs(RotationSpeedCurve.desired(e,true,15,c))<=15,"configured cap");
  }
  near(30,RotationSpeedCurve.desired(30,true,30,c),"configured 30 cap exposes full ride curve");
  near(0,YawAimingMath.calculateYawRate(3,8,.1,15,8,c),"normal tolerance immediate zero");
  near(.8,new SurferYawController().calculate(30,0,.1,true,1000,30,8,c),"ride acceleration unchanged");
  near(7.2,new SurferYawController().calculate(5,8,.1,true,1000,30,8,c),"ride slowdown ramp unchanged");
  SurferYawController yaw=new SurferYawController();yaw.calculate(10,0,.1,true,1000,30,8,c);
  near(0,yaw.calculate(-10,.8,.1,true,1100,30,8,c),"reversal blocked");
  near(0,yaw.calculate(-10,0,.1,true,2999,30,8,c),"block lasts two seconds");
  near(-.8,yaw.calculate(-10,0,.1,true,3000,30,8,c),"reversal eligible at two seconds");
  near(-.8,YawAimingMath.calculateYawRate(-10,0,.1,15,8,c),"navigation has no reversal block");
  double[][] normal={{0,0},{3,0},{10,8}},ride={{0,0},{10,10}};
  RotationSpeedCurve immutable=new RotationSpeedCurve(normal,ride);ride[1][1]=25;
  near(10,immutable.speed(10,true),"snapshot copies arrays");
  for(String bad:new String[]{"{}",raw+" true",raw.replace("\"version\": 1","\"version\": 2"),
    raw.replace("\"headingErrorDeg\": 3","\"headingErrorDeg\": 0"),
    raw.replace("\"speedDegPerSec\": 30","\"speedDegPerSec\": 31"),
    raw.replace("\"speedDegPerSec\": 1.5","\"speedDegPerSec\": -1"),
    raw.replace("\"speedDegPerSec\": 1.5","\"speedDegPerSec\": \"1.5\""),
    raw.replace("\"headingErrorDeg\": 33","\"headingErrorDeg\": 181"),
    new String(new char[65537]).replace('\0',' ')}) {
   boolean rejected=false;try{RotationSpeedConfig.parse(bad);}catch(Exception e){rejected=true;}check(rejected,"malformed curve rejected");
  }
 }
 static void migration()throws Exception {
  Map<String,String> data=new HashMap<>();int[] created={0};
  ConfigFileMigration.Files files=new ConfigFileMigration.Files(){
   public boolean exists(String n){return data.containsKey(n);}
   public String read(String n){return data.get(n);}
   public void create(String n,String s){created[0]++;data.put(n,s);}
  };
  check(ConfigFileMigration.ensure(files,"gimbal",()->"custom old bytes"),"missing gimbal copied");
  check(data.get("gimbal").equals("custom old bytes"),"custom bytes preserved");
  check(!ConfigFileMigration.ensure(files,"gimbal",()->{throw new IOException("source inaccessible");}),"existing destination bypasses source");
  data.put("rotation","existing custom rotation");
  check(!ConfigFileMigration.ensure(files,"rotation",()->"defaults"),"existing rotation preserved");
  check(created[0]==1&&data.get("rotation").equals("existing custom rotation"),"no overwrite on retry");
  boolean failed=false;try{ConfigFileMigration.ensure(files,"new",()->{throw new IOException("unreadable legacy");});}catch(IOException e){failed=true;}
  check(failed&&!data.containsKey("new"),"unreadable legacy cannot seed replacement");
  ConfigFileMigration.Files broken=new ConfigFileMigration.Files(){
   public boolean exists(String n){return false;}public String read(String n){return "truncated";}public void create(String n,String s){}
  };
  failed=false;try{ConfigFileMigration.ensure(broken,"gimbal",()->"custom old bytes");}catch(IOException e){failed=true;}
  check(failed,"failed verification does not accept migration");
 }
 static void sessions(String raw,String directory)throws Exception {
  RotationSpeedCurve c=RotationSpeedConfig.parse(raw);
  AimingSessionTest.Fake f=Vt35Test.start(20);f.core.rotationCurve=c;
  Vt35Test.step(f,0,20,6,false);
  check(f.core.retreat.active,"retreat remains active with JSON");
  near(-1.5,f.core.surferYaw.desiredRate,"normal retreat uses normal curve");
  // Real 6 m/s GPS sequence triggers production ride detection during retreat.
  f=Vt35Test.start(15,18);f.core.rotationCurve=c;
  f.core.movement.ride.clearEvidence("test_motion_origin");
  for(int i=1;i<=15;i++)Vt35Test.step(f,0,15+i*.6,6,false);
  check(f.core.movement.riding,"live ride active");near(-6,f.core.surferYaw.desiredRate,"ride retreat selects doubled curve");
  Vt35Test.step(f,0,20,6,true);near(-6,f.core.surferYaw.desiredRate,"NOFIX retains curve and target");
  Path path=Paths.get(directory,"vt37-test.jsonl");
  FullSessionLog log=new FullSessionLog(n->Files.newOutputStream(path),e->{throw new AssertionError(e);});log.enable();
  log.record("rotation_speed_config","fileContents",raw,"source","shared_folder");
  AimingCycleLog.record(log,f.core,f.input,f.time,false,f.core.cycleId,f.sent.get(f.sent.size()-1));
  log.disable("test");long end=System.currentTimeMillis()+5000;while(log.busy()&&System.currentTimeMillis()<end)Thread.sleep(5);
  check(!log.busy(),"log flush");
  f.core.pauseImmediately("pilot_stick");f.core.tick();check(!f.core.retreat.active,"protection still cancels retreat");
 }
 public static void main(String[] args)throws Exception {
  String raw=new String(Files.readAllBytes(Paths.get(args[0])),java.nio.charset.StandardCharsets.UTF_8);
  SharedConfigStorage.raw=null;
  check(RotationSpeedStorage.load(null).curve==null,"missing JSON keeps original formula");
  SharedConfigStorage.raw=raw;
  RotationSpeedStorage.Loaded loaded=RotationSpeedStorage.load(null);
  check(loaded.curve!=null&&loaded.source.equals("shared_folder"),"valid rotation file selected");
  SharedConfigStorage.raw="{invalid";
  check(RotationSpeedStorage.load(null).curve==null,"invalid JSON uses original formula");
  near(6,loaded.curve.speed(6,true),"loaded snapshot unaffected by disk edits");
  SharedConfigStorage.error="permission revoked";
  check(RotationSpeedStorage.load(null).curve==null,"permission failure uses original formula");
  SharedConfigStorage.error=null;SharedConfigStorage.raw=null;
  curves(raw);migration();sessions(raw,args[1]);System.out.println("PASS: "+checks+" VT 3.7 rotation, migration and session assertions");
 }
}
