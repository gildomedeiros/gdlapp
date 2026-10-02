package dji.v5.ux.sample.showcase.defaultlayout.aiming;
import java.nio.file.*;
import java.util.*;
import java.io.*;
/** VT 3.8: Real session outage/NOFIX replay and atomic strict config loading. */
public final class Vt38Test {
 static int checks;
 static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
 static AimingSessionTest.Fake start(int count) {
  AimingSessionTest.Fake f=new AimingSessionTest.Fake();
  f.movement=new ComeToMeSettings(true,30,100,99,8,30000,1200000,5);
  f.retreat=new RetreatSettings(true,23,3000,5,5000,count);f.aiming();
  Vt35Test.step(f,0,20,6,false);check(f.core.retreat.active&&f.core.retreat.gpsFresh,"good GPS starts retreat");return f;
 }
 static long events(AimingSessionTest.Fake f,String name){return f.diagnosticEvents.stream().filter(e->e.startsWith(name+" ")).count();}
 static void outage() {
  AimingSessionTest.Fake f=start(1);long first=f.core.retreat.deadline;
  Vt35Test.until(f,first-100,0,20,true);
  check(f.core.retreat.active&&f.core.retreat.startsWithoutFreshGps==0,"signal lost during good start does not count or cancel");
  Vt35Test.step(f,0,20,6,true);
  check(f.core.retreat.active&&f.core.retreat.period==2&&f.core.retreat.startsWithoutFreshGps==1,"one stale repeat permitted");
  Vt35Test.until(f,f.core.retreat.deadline,0,20,true);
  check(!f.core.retreat.active&&f.core.retreat.staleLimitBlocked,"second period ends and further repeat blocked");
  check(Vt35Test.forward(f)==0,"translation zero after allowance exhausted");
  long limit=f.time+90000;Vt35Test.until(f,limit,0,20,true);
  check(!f.core.retreat.active&&f.core.retreat.period==2&&Vt35Test.forward(f)==0,"90-second NOFIX cannot restart retreat");
  check(events(f,"retreat_start_blocked")==1,"blocked event emitted once despite repeated ticks/cooldown");
  check(events(f,"retreat_repeated")==1,"exactly one stale repeat");
  Vt35Test.step(f,0,20,6,false);
  check(f.core.retreat.active&&f.core.retreat.startsWithoutFreshGps==0,"fresh GPS resets allowance and can trigger again");
  check(events(f,"retreat_gps_allowance_reset")==1,"fresh fix restoration logged");
  check(f.core.retreat.previousStartsWithoutFreshGps==1,"reset log records previous count");
  Vt35Test.step(f,0,20,6,false);check(events(f,"retreat_gps_allowance_reset")==1,"no repeated reset logs when nothing restored");
 }
 static void stalePackets() {
  AimingSessionTest.Fake f=start(1);AimingSession.Fix old=f.input.target;
  long until=f.time+90000;
  while(f.time<until){f.time+=100;AimingSession.Inputs in=ComeToMeTest.in(f.time,0,0,20,0,6);
   f.input=new AimingSession.Inputs(old,in.lat,in.lon,in.heading,f.time,null,true);f.core.tick();}
  check(!f.core.retreat.active&&f.core.retreat.startsWithoutFreshGps==1,"ordinary packet gap bounded once fix becomes stale");
  check(events(f,"retreat_start_blocked")==1,"ordinary gap block logged once");
 }
 static void limitsAndPause() {
  for(int count:new int[]{0,2,3}) {
   AimingSessionTest.Fake f=start(count);long end=f.core.retreat.deadline+count*3000L;
   Vt35Test.until(f,end,0,20,true);
   check(!f.core.retreat.active&&f.core.retreat.startsWithoutFreshGps==count,"configured count "+count);
   check(f.core.retreat.period==count+1,"good start excluded from bad-GPS allowance");
  }
  AimingSessionTest.Fake f=start(1);Vt35Test.until(f,f.core.retreat.deadline,0,20,true);
  check(f.core.retreat.active&&f.core.retreat.startsWithoutFreshGps==1,"stale period active before protection");
  f.core.pauseImmediately("pilot_stick");f.core.tick();
  check(!f.core.retreat.active&&f.core.retreat.startsWithoutFreshGps==1,"protection cancels timer but preserves used allowance");
  Vt35Test.until(f,f.time+12000,0,20,true);
  check(!f.core.retreat.active&&f.core.retreat.startsWithoutFreshGps==1,"recovery and cooldown do not replenish");
  f=start(0);long finish=f.core.retreat.deadline;
  Vt35Test.until(f,finish+12000,0,20,false);
  check(f.core.retreat.active&&f.core.retreat.startsWithoutFreshGps==0,"fresh GPS repeats unlimited even with zero stale allowance");
  f=start(1);Vt35Test.until(f,f.core.retreat.deadline,-10,20,true);
  check(!f.core.retreat.active&&f.core.retreat.startsWithoutFreshGps==0,"sufficient distance stops without spending stale allowance");
  Vt35Test.step(f,0,20,6,true);
  check(f.core.retreat.active&&f.core.retreat.startsWithoutFreshGps==1,"new stale start during cooldown consumes allowance");
 }
 static Map<String,String> files(Path assets)throws Exception {
  Map<String,String> map=new HashMap<>();for(String n:new String[]{"vt_retreat_settings.json","vt_rotation_speeds.json","vt_gimbal_bands.json"})map.put(n,new String(Files.readAllBytes(assets.resolve(n)),java.nio.charset.StandardCharsets.UTF_8));return map;
 }
 static void config(Path assets)throws Exception {
  Map<String,String> map=files(assets);VtSessionConfig good=VtSessionConfig.load(map::get);
  check(good.retreat.minimumDistance==23&&good.retreat.durationMs==3000&&good.retreat.speed==5&&good.retreat.cooldownMs==5000&&good.retreat.maxStartsWithoutFreshGps==1,"defaults");
  String original=map.get("vt_retreat_settings.json");
  for(String[] pair:new String[][]{{"\"speedMetresPerSecond\": 5","\"speedMetresPerSecond\": 6"},{"\"maxStartsWithoutFreshGps\": 1","\"maxStartsWithoutFreshGps\": 1.5"},{"\"enabled\": true","\"enabled\": \"true\""},{"\"version\": 1","\"version\": 2"},{"\"durationSeconds\": 3","\"durationSeconds\": 0"},{"\"minimumDistanceMetres\": 23","\"minimumDistanceMetres\": -1"},{"\"comeToMeCooldownSeconds\": 5","\"comeToMeCooldownSeconds\": 61"},{"\"maxStartsWithoutFreshGps\": 1","\"maxStartsWithoutFreshGps\": -1"}}) {
   map.put("vt_retreat_settings.json",original.replace(pair[0],pair[1]));boolean failed=false;
   try{VtSessionConfig.load(map::get);}catch(IllegalArgumentException e){failed=e.getMessage().contains("vt_retreat_settings.json")&&e.getMessage().contains("Cannot start");}check(failed,"bad retreat setting blocks start");
  }
  for(String name:new String[]{"vt_retreat_settings.json","vt_rotation_speeds.json","vt_gimbal_bands.json"}) {
   map.clear();map.putAll(files(assets));map.put(name,"{invalid");boolean failed=false;
   try{VtSessionConfig.load(map::get);}catch(IllegalArgumentException e){failed=e.getMessage().contains(name);}check(failed,"invalid file named in error");
   map.remove(name);failed=false;try{VtSessionConfig.load(map::get);}catch(IllegalArgumentException e){failed=e.getMessage().contains(name);}check(failed,"missing file blocks with filename");
  }
  for(String name:new String[]{"vt_retreat_settings.json","vt_rotation_speeds.json","vt_gimbal_bands.json"}) {
   map.clear();map.putAll(files(assets));String raw=map.get(name);
   map.put(name,raw.replaceFirst("\\{","{\"duplicate\":1,\"duplicate\":2,"));
   boolean duplicate=false;try{VtSessionConfig.load(map::get);}catch(IllegalArgumentException e){duplicate=e.getMessage().contains("Duplicate field")&&e.getMessage().contains(name);}check(duplicate,"duplicate setting blocks with filename");
  }
  boolean failed=false;try{VtSessionConfig.load(name->{throw new IOException("Folder permission revoked");});}catch(IllegalArgumentException e){failed=e.getMessage().contains("permission revoked");}check(failed,"permission exception is visible");
  check(good.retreat.speed==5&&good.rotation!=null&&good.gimbal!=null,"failed load cannot mutate prior snapshot");
 }
 static void logs(String directory)throws Exception {
  AimingSessionTest.Fake f=start(1);Vt35Test.until(f,f.core.retreat.deadline+3000,0,20,true);
  Path p=Paths.get(directory,"vt38-test.jsonl");FullSessionLog log=new FullSessionLog(n->Files.newOutputStream(p),e->{throw new AssertionError(e);});log.enable();
  RetreatLog.record(log,f.core,f.time,"retreat_start_blocked","stale_gps_retreat_limit",-1,0);
  Vt35Test.step(f,0,20,6,false);
  RetreatLog.record(log,f.core,f.time,"retreat_gps_allowance_reset","fresh_valid_fix",-1,0);
  log.disable("test");long end=System.currentTimeMillis()+5000;while(log.busy()&&System.currentTimeMillis()<end)Thread.sleep(5);check(!log.busy(),"logs flushed");
 }
 public static void main(String[] args)throws Exception {outage();stalePackets();limitsAndPause();config(Paths.get(args[0]));logs(args[1]);System.out.println("PASS: "+checks+" VT 3.8 GPS allowance, strict configuration and event assertions");}
}
