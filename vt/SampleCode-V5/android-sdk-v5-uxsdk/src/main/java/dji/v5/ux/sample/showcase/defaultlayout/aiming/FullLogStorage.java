package dji.v5.ux.sample.showcase.defaultlayout.aiming;

import android.content.*;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import java.io.*;

/** CAM3 v2.5: MediaStore Downloads on Android 10+; legacy public Downloads on Android 7-9. */
public final class FullLogStorage {
    public static OutputStream open(Context context, String name) throws IOException {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            values.put(MediaStore.MediaColumns.MIME_TYPE, "application/x-ndjson");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/CAM3/");
            ContentResolver resolver = context.getContentResolver();
            Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IOException("Cannot create full log");
            try {
                OutputStream stream = resolver.openOutputStream(uri, "w");
                if (stream == null) throw new IOException("Cannot open full log");
                return stream;
            } catch (IOException | RuntimeException ex) {
                try { resolver.delete(uri, null, null); } catch (RuntimeException ignored) { }
                throw ex;
            }
        }
        File directory = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "CAM3");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Downloads/CAM3 unavailable; allow storage permission");
        return new FileOutputStream(new File(directory, name));
    }
}
