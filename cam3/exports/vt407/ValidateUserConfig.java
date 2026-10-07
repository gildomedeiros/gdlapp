import java.nio.file.*;
import dji.v5.ux.sample.showcase.defaultlayout.aiming.*;
public final class ValidateUserConfig {
 public static void main(String[] args)throws Exception {
  Path folder=Paths.get(args[0]);
  VtSessionConfig.Reader reader=name->Files.readString(folder.resolve(name));
  VtSessionConfig base=VtSessionConfig.load(reader);
  VtSettingsConfig movement=VtSettingsConfig.parse(reader.read("vt_settings.json"),base.retreat);
  WaveLineLibrary library=WaveLineLibrary.parse(reader.read("vt_wave_lines.json"));
  System.out.println("PASS: all five files pass VT 4.0.7 production parsers");
  System.out.println("Filming="+movement.movement.filmingDistance+" m; retreat="+base.retreat.minimumDistance+" m; angle="+movement.angle+" degrees; planning allowance="+movement.planningAllowance+" m; return stand-off="+movement.movement.returnBoundaryStandOffMetres+" m");
  try {VtSessionConfig.load40(reader);System.out.println("PASS: selected Wave line is ready for Start");}
  catch(VtSessionConfig.WaveLineSetupRequired expected){
   if(!library.profiles.isEmpty()||!movement.waveLineId.isEmpty())throw expected;
   System.out.println("SETUP_REQUIRED: capture/save/select a Wave line before Start");
  }
 }
}
