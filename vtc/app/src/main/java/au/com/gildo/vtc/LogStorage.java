package au.com.gildo.vtc;

import android.content.*;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import java.io.*;
import java.nio.channels.FileChannel;

/** Same public Downloads mechanism as VT, with a dedicated VTC folder. */
final class LogStorage implements AutomaticLogWriter.Destination {
    static boolean migratePreviousLogs(Context context,SessionLog log) {
        try {
            if(Build.VERSION.SDK_INT>=29){
                ContentResolver resolver=context.getContentResolver();
                try(android.database.Cursor c=resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.MediaColumns._ID,MediaStore.MediaColumns.DISPLAY_NAME},
                    MediaStore.MediaColumns.RELATIVE_PATH+"=?",new String[]{Environment.DIRECTORY_DOWNLOADS+"/VTC/"},null)){
                    while(c!=null&&c.moveToNext()){
                        String name=c.getString(1);if(!isSessionLog(name))continue;
                        Uri uri=ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI,c.getLong(0));
                        ContentValues values=new ContentValues();values.put(MediaStore.MediaColumns.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/VTC/logs/");
                        if(resolver.update(uri,values,null,null)!=1)throw new IOException("Could not relocate "+name);
                        log.record("log_relocated","name",name,"destination","Download/VTC/logs");
                    }
                }
            }else{
                File root=new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),"VTC");
                File dest=new File(root,"logs");if(!dest.isDirectory()&&!dest.mkdirs())throw new IOException("Cannot create logs directory");
                File[] files=root.listFiles();if(files!=null)for(File old:files)if(old.isFile()&&isSessionLog(old.getName())){
                    File target=new File(dest,old.getName());if(target.exists()||!old.renameTo(target))throw new IOException("Could not relocate "+old.getName());
                    log.record("log_relocated","name",old.getName(),"destination","Download/VTC/logs");
                }
            }
            return true;
        }catch(Exception e){log.error("log_relocation_retry",e);return false;}
    }
    private static boolean isSessionLog(String name){return name!=null&&name.startsWith("VTC_")&&(name.endsWith(".jsonl")||name.endsWith(".kml"));}
    private final Context context;
    private final String name;
    private Uri uri;
    LogStorage(Context context,String name){this.context=context.getApplicationContext();this.name=name;}
    public OutputStream open()throws IOException {
        if(Build.VERSION.SDK_INT>=29) {
            ContentResolver resolver=context.getContentResolver();
            if(uri==null) {
                ContentValues v=new ContentValues();v.put(MediaStore.MediaColumns.DISPLAY_NAME,name);
                v.put(MediaStore.MediaColumns.MIME_TYPE,name.endsWith(".kml")?"application/vnd.google-earth.kml+xml":"application/x-ndjson");
                v.put(MediaStore.MediaColumns.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/VTC/logs/");
                // Visible while recording, as in VT; closing/exporting is never required.
                uri=resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,v);
                if(uri==null)throw new IOException("Cannot create Download/VTC/logs log");
            }
            OutputStream out=resolver.openOutputStream(uri,"wt");
            if(out==null)throw new IOException("Cannot open Download/VTC/logs log");
            return out;
        }
        File dir=new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),"VTC/logs");
        if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("Allow storage permission for automatic Download/VTC/logs logs");
        return new FileOutputStream(new File(dir,name),false);
    }
    FileChannel openChannel()throws IOException {
        // open() creates/reuses this session's Downloads entry and truncates it for recovery.
        if(Build.VERSION.SDK_INT>=29) {
            try(OutputStream ignored=open()) { }
            android.os.ParcelFileDescriptor fd=context.getContentResolver().openFileDescriptor(uri,"rw");
            if(fd==null)throw new IOException("Cannot open Download/VTC/logs KML");
            return new android.os.ParcelFileDescriptor.AutoCloseOutputStream(fd).getChannel();
        }
        return ((FileOutputStream)open()).getChannel();
    }
}

