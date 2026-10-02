package dji.v5.ux.sample.showcase.defaultlayout.aiming;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.*;
public final class GimbalBandStorageTest {
 static void ok(boolean b){if(!b)throw new AssertionError("storage check");}
 public static void main(String[] args)throws Exception {
  final Path asset=Paths.get(args[0]),dir=Files.createTempDirectory(Paths.get(args[1]),"band-storage-");
  android.content.Context context=new android.content.Context(){
   public File getExternalFilesDir(String type){return dir.toFile();}
   public android.content.res.AssetManager getAssets(){return new android.content.res.AssetManager(){public InputStream open(String name)throws IOException{return Files.newInputStream(asset);}};}
  };
  GimbalBandStorage.Loaded first=GimbalBandStorage.load(context);
  Path file=dir.resolve(GimbalBandStorage.NAME);
  ok(Files.exists(file)&&first.source.equals("bundled_seeded_file"));
  String custom=first.effective.replace("-35","-40");Files.write(file,custom.getBytes(StandardCharsets.UTF_8));
  GimbalBandStorage.Loaded changed=GimbalBandStorage.load(context);
  ok(changed.source.equals("external_file")&&changed.config.pitch(0)==-40&&changed.raw.equals(custom));
  ok(first.config.pitch(0)==-35); // Existing session snapshot remains immutable.
  Files.write(file,"{invalid".getBytes(StandardCharsets.UTF_8));
  GimbalBandStorage.Loaded bad=GimbalBandStorage.load(context);
  ok(bad.source.equals("bundled_fallback")&&bad.config.pitch(0)==-35&&!bad.error.equals("none")&&bad.raw.equals("{invalid"));
  ok(new String(Files.readAllBytes(file),StandardCharsets.UTF_8).equals("{invalid"));
  Files.write(file,new byte[65537]);ok(GimbalBandStorage.load(context).source.equals("bundled_fallback"));
  Path logfile=dir.resolve("config-log.jsonl");
  FullSessionLog log=new FullSessionLog(name->Files.newOutputStream(logfile),error->{throw new AssertionError(error);});
  log.enable();log.record("gimbal_band_config","fileContents",changed.raw,"effectiveJson",changed.effective,"configPath",changed.path);log.disable("test");
  long deadline=System.currentTimeMillis()+5000;while(log.busy()&&System.currentTimeMillis()<deadline)Thread.sleep(5);ok(!log.busy());
  String event=Files.readAllLines(logfile).stream().filter(x->x.contains("gimbal_band_config")).findFirst().get();
  com.google.gson.JsonObject row=com.google.gson.JsonParser.parseString(event).getAsJsonObject();
  ok(row.get("fileContents").getAsString().equals(custom)&&row.get("effectiveJson").getAsString().equals(custom));
  Files.write(file,custom.getBytes(StandardCharsets.UTF_8));
  SharedConfigStorage.raw=first.effective;
  ok(GimbalBandStorage.load(context).source.equals("shared_folder"));
  ok(GimbalBandStorage.load(context).config.pitch(0)==-35);
  SharedConfigStorage.raw="{invalid";
  GimbalBandStorage.Loaded sharedBad=GimbalBandStorage.load(context);
  ok(sharedBad.config.pitch(0)==-40&&sharedBad.source.startsWith("legacy_fallback")&&!sharedBad.error.equals("none"));
  SharedConfigStorage.error="permission revoked";SharedConfigStorage.raw=null;
  ok(GimbalBandStorage.load(context).config.pitch(0)==-40);
  ok(new String(Files.readAllBytes(file),StandardCharsets.UTF_8).equals(custom));
  SharedConfigStorage.error=null;
  System.out.println("PASS: override seeding, immutable session config, reload, fallback, oversized input and full JSON log round-trip");
 }
}
