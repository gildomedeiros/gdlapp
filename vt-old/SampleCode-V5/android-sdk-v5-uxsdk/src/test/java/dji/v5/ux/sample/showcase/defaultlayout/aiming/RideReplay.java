package dji.v5.ux.sample.showcase.defaultlayout.aiming;
import java.io.*;
/** Read-only replay adapter: tab-separated snapshots from existing full logs, no flight APIs. */
public final class RideReplay {
    public static void main(String[] args) throws Exception {
        RideDetector d=new RideDetector(); ComeToMeSettings config=ComeToMeSettings.defaults(); String label="";
        try(BufferedReader reader=new BufferedReader(new FileReader(args[0]))) {
            String line;
            while((line=reader.readLine())!=null) {
                String[] f=line.split("\t");
                if(f[0].equals("session")) {
                    label=f[1]; d.reset(); config=new ComeToMeSettings(false,70,20,Double.parseDouble(f[2]),Double.parseDouble(f[3]),Long.parseLong(f[4]),900000);
                } else if(f[0].equals("pause")) {
                    if(f[1].equals("pilot_stick")) d.reset(); else d.clearEvidence("pause");
                } else {
                    d.observe(new AimingSession.Fix(Double.parseDouble(f[3]),Double.parseDouble(f[4]),Double.NaN,
                            Long.parseLong(f[1]),0,Long.parseLong(f[2])),config);
                    if(!d.event.equals("none")) System.out.println(label+"\t"+f[5]+"\t"+d.event+"\t"+d.fastSpeed+"\t"+d.confirmationSpeed);
                }
            }
        }
    }
}
