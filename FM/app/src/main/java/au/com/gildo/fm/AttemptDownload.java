package au.com.gildo.fm;

import android.content.*;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

final class AttemptDownload {
    static final String DIRECTORY=Environment.DIRECTORY_DOWNLOADS+"/FM/logs";
    private final ContentResolver resolver;
    private final Uri pending;
    private final File legacy;
    private final String directory;

    private AttemptDownload(ContentResolver resolver,Uri pending,File legacy,String directory) {
        this.resolver=resolver;
        this.pending=pending;
        this.legacy=legacy;
        this.directory=directory;
    }

    static AttemptDownload create(Context context,String filename) throws IOException {
        return create(context,filename,DIRECTORY,"application/x-ndjson");
    }

    static AttemptDownload create(Context context,String filename,String directory,String mimeType) throws IOException {
        if(Build.VERSION.SDK_INT<29) {
            File folder=new File(Environment.getExternalStorageDirectory(),directory);
            if(!folder.isDirectory()&&!folder.mkdirs()) throw new IOException("Cannot create "+directory);
            return new AttemptDownload(null,null,new File(folder,filename),directory);
        }
        ContentResolver resolver=context.getContentResolver();
        ContentValues values=new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME,filename);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH,directory+"/");
        values.put(MediaStore.MediaColumns.MIME_TYPE,mimeType);
        values.put(MediaStore.MediaColumns.IS_PENDING,1);
        Uri pending=resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),values);
        if(pending==null) throw new IOException("Cannot create "+filename+" in "+directory);
        return new AttemptDownload(resolver,pending,null,directory);
    }

    void publish(File source) throws IOException {
        if(legacy!=null) {
            File staging=File.createTempFile("FM-attempt-",".tmp",legacy.getParentFile());
            try {
                Files.copy(source.toPath(),staging.toPath(),StandardCopyOption.REPLACE_EXISTING);
                Files.move(staging.toPath(),legacy.toPath(),StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(staging.toPath()); }
            return;
        }
        try(InputStream in=new FileInputStream(source); OutputStream out=resolver.openOutputStream(pending,"wt")) {
            if(out==null) throw new IOException("Cannot write file in "+directory);
            byte[] buffer=new byte[8192];
            for(int n;(n=in.read(buffer))!=-1;) out.write(buffer,0,n);
        }
        ContentValues values=new ContentValues();
        values.put(MediaStore.MediaColumns.IS_PENDING,0);
        if(resolver.update(pending,values,null,null)!=1) throw new IOException("Cannot publish file in "+directory);
    }

    void append(byte[] tail) throws IOException {
        if(legacy!=null) {
            Files.write(legacy.toPath(),tail,java.nio.file.StandardOpenOption.APPEND);
            return;
        }
        try(OutputStream out=resolver.openOutputStream(pending,"wa")) {
            if(out==null) throw new IOException("Cannot append publication timing in "+directory);
            out.write(tail);
        }
    }

    void abandon() {
        if(pending!=null) {
            try { resolver.delete(pending,null,null); }
            catch(RuntimeException ignored) { }
        }
    }
}
