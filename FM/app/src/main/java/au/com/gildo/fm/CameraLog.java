package au.com.gildo.fm;

import android.content.Context;
import android.util.Log;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Writes Canon events on the camera worker with the same time bases as FM tracking logs. */
final class CameraLog {
    private final Context context;
    private AttemptDownload download;
    private boolean unavailable;

    CameraLog(Context context) { this.context=context.getApplicationContext(); }

    void record(String event,Object... fields) {
        if(unavailable) return;
        Map<String,Object> row=new LinkedHashMap<>();
        row.put("event",event);
        row.put("elapsedMs",GoTo.now());
        row.put("wallTimeMs",System.currentTimeMillis());
        for(int i=0;i<fields.length;i+=2) row.put((String)fields[i],fields[i+1]);
        byte[] line=(LogJson.encode(row)+"\n").getBytes(StandardCharsets.UTF_8);
        try {
            if(download==null) {
                download=AttemptDownload.create(context,"FM-Canon-"+UUID.randomUUID().toString().substring(0,8)+".jsonl");
                File first=File.createTempFile("fm-camera-",".jsonl",context.getCacheDir());
                try {
                    java.nio.file.Files.write(first.toPath(),line);
                    download.publish(first);
                } catch(IOException|RuntimeException failure) {
                    download.abandon();
                    throw failure;
                } finally { first.delete(); }
            } else download.append(line);
        } catch(IOException|RuntimeException failure) {
            unavailable=true;
            Log.w("FM","Canon log unavailable",failure);
        }
    }

    void error(String event,Throwable failure,Object... fields) {
        Object[] details=java.util.Arrays.copyOf(fields,fields.length+2);
        details[fields.length]="reason";
        details[fields.length+1]=failure.toString();
        record(event,details);
    }
}
