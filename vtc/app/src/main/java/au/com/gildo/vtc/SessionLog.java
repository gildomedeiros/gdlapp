package au.com.gildo.vtc;

import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import java.io.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Always-on, structured full diagnostics. No save/export step. */
final class SessionLog implements AutoCloseable {
    static final String VERSION="1.0.10";
    private final AutomaticLogWriter writer;
    private final AutomaticKmlWriter kml;
    private long record;
    private boolean open=true;
    final String name="VTC_"+VERSION+"_"+LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"))+"_"+UUID.randomUUID().toString().substring(0,8)+".jsonl";
    final String kmlName=name.replace(".jsonl",".kml");
    SessionLog(Context context) {
        File spool=new File(new File(context.getFilesDir(),"logs"),name);
        writer=new AutomaticLogWriter(spool,new LogStorage(context,name));
        LogStorage kmlStorage=new LogStorage(context,kmlName);
        kml=new AutomaticKmlWriter(new File(spool.getParentFile(),kmlName),kmlStorage::openChannel,kmlName);
        record("session_start","version",VERSION,"format",2,"manufacturer",Build.MANUFACTURER,"model",Build.MODEL,
            "androidVersion",Build.VERSION.RELEASE,"androidApi",Build.VERSION.SDK_INT,"timezone",ZoneId.systemDefault().toString(),
            "publicPath","Download/VTC/logs/"+name,"privateRecoveryPath",spool.getAbsolutePath(),"kmlPath","Download/VTC/logs/"+kmlName);
    }
    synchronized void record(String type,Object... fields) {
        if(!open)return;
        Map<String,Object> row=new LinkedHashMap<>();
        row.put("record",++record);row.put("timestamp",OffsetDateTime.now().toString());
        row.put("epochMs",System.currentTimeMillis());row.put("monoMs",SystemClock.elapsedRealtime());
        row.put("thread",Thread.currentThread().getName());row.put("event",type);
        for(int i=0;i+1<fields.length;i+=2)row.put(String.valueOf(fields[i]),fields[i+1]);
        writer.append(LogJson.encode(row));
        kml.append(row);
    }
    void event(String type,String detail){record(type,"detail",detail);}
    void error(String type,Throwable error,Object... fields) {
        StringWriter text=new StringWriter();error.printStackTrace(new PrintWriter(text));
        Object[] all=Arrays.copyOf(fields,fields.length+4);
        all[fields.length]="exception";all[fields.length+1]=error.toString();
        all[fields.length+2]="stackTrace";all[fields.length+3]=text.toString();
        record(type,all);
    }
    void settings(String reason,Settings settings){record("settings_snapshot","reason",reason,"settings",settings.values);}
    String status() {
        String path="Download/VTC/logs/"+name;
        String failure=writer.error();
        String status=!failure.isEmpty()?"Log storage error: "+failure+"\nDestination: "+path:
            writer.publicReady()?"Logging automatically to "+path:"Opening automatic log: "+path;
        String kmlStatus=!kml.error().isEmpty()?"KML storage error: "+kml.error():
            kml.publicReady()?"KML updating automatically":"Opening automatic KML";
        return status+"\nRecords written "+writer.written()+" · dropped "+writer.dropped()
            +"\n"+kmlStatus+"\nDownload/VTC/logs/"+kmlName+"\nKML events "+kml.written()+" · dropped "+kml.dropped();
    }
    public synchronized void close() {
        if(!open)return;
        record("session_end","reason","Control service ended","recordsDropped",writer.dropped(),"kmlEventsDropped",kml.dropped());
        open=false;writer.close();kml.close();
    }
}

