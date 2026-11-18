package au.com.gildo.vtc;
import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
/** Persistent pending spool; retries public storage without blocking mount control. */
final class CalibrationFiles implements AutoCloseable {
    private final Context context;private final SessionLog log;private final File pending;
    private final Map<String,LogStorage> destinations=new HashMap<>();
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(r->new Thread(r,"vtc-calibration-kml"));
    CalibrationFiles(Context context,SessionLog log){this.context=context;this.log=log;pending=new File(context.getFilesDir(),"calibration-kml-pending");worker.scheduleWithFixedDelay(this::retry,1,5,TimeUnit.SECONDS);}
    void save(SavedLandmark mark){worker.execute(()->{try{
        if(!pending.isDirectory()&&!pending.mkdirs())throw new IOException("Cannot create calibration KML recovery directory");
        String name="VTC_calibration_"+mark.savedAtEpochMs+"_"+UUID.randomUUID().toString().substring(0,8)+".kml";
        File file=new File(pending,name),temp=new File(pending,name+".tmp");
        Files.write(temp.toPath(),CalibrationMapData.kml(mark).getBytes(StandardCharsets.UTF_8));
        Files.move(temp.toPath(),file.toPath());
        log.record("calibration_kml_queued","name",name,"snapshot",mark.snapshot());retry();
    }catch(Exception e){log.error("calibration_kml_save_failed",e);}});}
    private void retry(){File[] files=pending.listFiles((d,n)->n.endsWith(".kml"));if(files==null)return;for(File file:files){try{
        LogStorage dest=destinations.computeIfAbsent(file.getName(),n->new LogStorage(context,n));
        try(OutputStream out=dest.open()){Files.copy(file.toPath(),out);}
        Files.delete(file.toPath());destinations.remove(file.getName());log.record("calibration_kml_saved","path","Download/VTC/logs/"+file.getName());
    }catch(Exception e){log.error("calibration_kml_retry",e,"name",file.getName());}}}
    public void close(){worker.shutdown();}
}
