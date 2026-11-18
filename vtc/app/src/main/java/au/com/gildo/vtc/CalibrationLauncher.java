package au.com.gildo.vtc;
import android.content.*;
import android.net.Uri;
import android.widget.Toast;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
final class CalibrationLauncher {
    private static boolean ready(Context c,Settings s){if(CurrentMap.valid(s))return true;Toast.makeText(c,"Capture or enter both camera and landmark coordinates first",Toast.LENGTH_LONG).show();return false;}
    static void maps(Context c,Settings m){
        if(!ready(c,m))return;
        Intent intent=new Intent(Intent.ACTION_VIEW,Uri.parse(CalibrationLinks.maps(m)));
        try{c.startActivity(new Intent(intent).setPackage("com.google.android.apps.maps"));}
        catch(ActivityNotFoundException e){try{c.startActivity(intent);}catch(ActivityNotFoundException absent){Toast.makeText(c,"Install Google Maps or a browser to view these positions",Toast.LENGTH_LONG).show();}}
    }
    static void earth(Context c,Settings m,boolean calibrated){
        if(!ready(c,m))return;
        try{
            File dir=new File(c.getCacheDir(),"calibration-share");if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("Cannot prepare calibration file");
            // Clean only this feature's old generated files; retain recent URI grants across launches.
            File[] old=dir.listFiles();if(old!=null)for(File f:old)if(CalibrationLinks.validFile(f.getName())&&System.currentTimeMillis()-f.lastModified()>7L*86400000)f.delete();
            File file=new File(dir,"calibration_"+System.currentTimeMillis()+"_"+UUID.randomUUID()+".kml");
            Files.write(file.toPath(),CurrentMap.kml(m,calibrated).getBytes(StandardCharsets.UTF_8));
            Uri uri=new Uri.Builder().scheme("content").authority(c.getPackageName()+".calibration").appendPath(file.getName()).build();
            Intent view=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,CalibrationShareProvider.MIME).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            view.setClipData(ClipData.newRawUri("Calibration KML",uri));
            try{c.startActivity(new Intent(view).setPackage("com.google.earth"));}
            catch(ActivityNotFoundException absent){
                Toast.makeText(c,"Google Earth could not open this KML. Choose a KML viewer, or install/update Google Earth.",Toast.LENGTH_LONG).show();
                try{c.startActivity(Intent.createChooser(view,"Open calibration KML"));}catch(ActivityNotFoundException none){Toast.makeText(c,"No KML viewer available. Install Google Earth. Calibration KML is also in Download/VTC/logs.",Toast.LENGTH_LONG).show();}
            }
        }catch(Exception e){Toast.makeText(c,"Could not open calibration KML: "+e.getMessage(),Toast.LENGTH_LONG).show();}
    }
}
