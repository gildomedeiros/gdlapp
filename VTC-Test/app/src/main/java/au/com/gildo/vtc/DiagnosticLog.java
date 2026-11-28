package au.com.gildo.vtc;
import android.app.Activity;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/** Capture locally first; publish a flushed snapshot into the same Downloads/CAM3 folder as VT. */
final class DiagnosticLog implements AutoCloseable {
    private final Activity activity;
    private final File capture;
    private BufferedWriter writer;
    private final long started=SystemClock.elapsedRealtime();
    private final String name="VTC-Test_"+java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
            +"_"+UUID.randomUUID().toString().substring(0,8)+".csv";
    private Uri published;
    private File legacy;
    private volatile String failure="";
    DiagnosticLog(Activity activity) {
        this.activity=activity;
        File directory=new File(activity.getFilesDir(),"diagnostics");
        capture=new File(directory,name);
        try {
            if(!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create capture folder");
            writer=new BufferedWriter(new OutputStreamWriter(new FileOutputStream(capture),StandardCharsets.UTF_8));
            writer.write("timestamp,elapsed_ms,event,detail\n");writer.flush();
            event("SESSION","VTC-Test 0.3.4; Android="+Build.VERSION.RELEASE+"; device="+Build.MANUFACTURER+" "+Build.MODEL);
        } catch(IOException error) { failure=error.toString(); }
    }
    synchronized void event(String event,String detail) {
        if(writer==null) return;
        try {
            writer.write(LogLine.record(OffsetDateTime.now().toString(),SystemClock.elapsedRealtime()-started,event,detail));
            writer.flush();
        } catch(IOException error) { failure=error.toString(); }
    }
    String error() { return failure; }
    synchronized String publish() throws IOException {
        if(writer==null) throw new IOException("Capture not available: "+failure);
        if(!failure.isEmpty()) throw new IOException("Capture write failed: "+failure);
        event("EXPORT","Snapshot to Downloads/CAM3/"+name);
        writer.flush();
        if(Build.VERSION.SDK_INT>=29) {
            ContentResolver resolver=activity.getContentResolver();
            if(published==null) {
                ContentValues values=new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME,name);
                values.put(MediaStore.MediaColumns.MIME_TYPE,"text/csv");
                values.put(MediaStore.MediaColumns.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/CAM3/");
                published=resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);
                if(published==null) throw new IOException("Cannot create Downloads log");
            }
            try(OutputStream out=resolver.openOutputStream(published,"wt")) {
                if(out==null) throw new IOException("Cannot open Downloads log");
                copy(out);
            }
        } else {
            if(activity.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)!=android.content.pm.PackageManager.PERMISSION_GRANTED)
                throw new IOException("Allow storage permission, then tap Save diagnostic log again");
            File directory=new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),"CAM3");
            if(!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create Downloads/CAM3");
            legacy=new File(directory,name);
            try(OutputStream out=new FileOutputStream(legacy)) { copy(out); }
        }
        return "Downloads/CAM3/"+name;
    }
    private void copy(OutputStream out) throws IOException {
        try(InputStream in=new FileInputStream(capture)) {
            byte[] buffer=new byte[8192];int count;
            while((count=in.read(buffer))!=-1) out.write(buffer,0,count);
        }
    }
    public synchronized void close() {
        if(writer!=null) try { event("SESSION_END","Activity closed");writer.close();writer=null; }
        catch(IOException error) { failure=error.toString(); }
    }
}
