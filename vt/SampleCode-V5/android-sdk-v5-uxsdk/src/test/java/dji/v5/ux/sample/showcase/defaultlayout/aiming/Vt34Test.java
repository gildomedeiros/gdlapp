package dji.v5.ux.sample.showcase.defaultlayout.aiming;
import java.nio.file.*;
public final class Vt34Test {
 static int count;
 static void ok(boolean b,String m){count++;if(!b)throw new AssertionError(m);}
 static void step(GimbalBandPolicy p,GimbalBandConfig c,double d,int band,double pitch){p.update(0,d,true,c,-90,0);ok(p.band==band-1,"band at "+d);ok(p.target==pitch,"pitch at "+d);}
 static void bad(String json){try{GimbalBandConfig.parse(json);throw new AssertionError("accepted invalid: "+json);}catch(RuntimeException expected){count++;}}
 public static void main(String[] args)throws Exception {
  String raw=new String(Files.readAllBytes(Paths.get(args[0])),java.nio.charset.StandardCharsets.UTF_8);
  GimbalBandConfig c=GimbalBandConfig.parse(raw);ok(c.size()==5 && c.bufferMetres==5,"bundled defaults");
  GimbalBandPolicy p=new GimbalBandPolicy();
  step(p,c,15,1,-35);step(p,c,20,1,-35);step(p,c,25,1,-35);step(p,c,25.01,2,-25);
  step(p,c,20,2,-25);step(p,c,19.99,1,-35);
  step(p,c,35,2,-25);step(p,c,35.01,3,-19);step(p,c,30,3,-19);step(p,c,29.99,2,-25);
  step(p,c,45,3,-19);step(p,c,45.01,4,-16);step(p,c,40,4,-16);step(p,c,39.99,3,-19);
  step(p,c,55,4,-16);step(p,c,55.01,5,-12);step(p,c,50,5,-12);step(p,c,49.99,4,-16);
  step(p,c,500,5,-12);step(p,c,10,1,-35);ok(p.previousBand==4,"skip all bands inward");
  p.update(0,60,false,c,-90,0);ok(p.band==0,"stale GPS preserves band");
  p.update(Double.NaN,60,true,c,-90,0);ok(p.band==0,"stale attitude preserves band");
  p.update(0,-1,true,c,-90,0);ok(p.band==0,"negative distance rejected");
  p.update(0,Double.NaN,true,c,-90,0);ok(p.band==0,"invalid distance rejected");
  for(double d:new double[]{0,20,20.1,30,30.1,40,40.1,50,50.1,1000}) {
   p.reset();p.update(0,d,true,c,-90,0);int expected=d<=20?0:d<=30?1:d<=40?2:d<=50?3:4;ok(p.band==expected,"startup "+d);
  }
  p.reset();ok(!p.update(-30,10,true,c,-30,0) && p.band==0 && p.target==-30,"hardware clamp and already reached");
  for(String invalid:new String[]{"{}","{","null",raw+" true",raw.replace("5,","-1,"),raw.replace("20,","30,"),raw.replace("-35","-95"),raw.replace("null","60"),raw.replace("-35","\"-35\""),raw.replace("\"bufferMetres\"","bufferMetres")})bad(invalid);
  GimbalBandConfig custom=GimbalBandConfig.parse("{\"bufferMetres\":2,\"bands\":[{\"maxDistanceMetres\":10,\"pitchDegrees\":-40},{\"maxDistanceMetres\":null,\"pitchDegrees\":-10}]}");
  p.reset();step(p,custom,5,1,-40);step(p,custom,12,1,-40);step(p,custom,12.1,2,-10);step(p,custom,9.9,1,-40);
  System.out.println("PASS: "+count+" VT 3.4 config and band checks");
 }
}
