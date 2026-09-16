package com.gdl.loratester;

import android.content.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.util.*;
import org.json.JSONObject;

final class KmlLog implements AutoCloseable {
    final String name;
    private final FileOutputStream stream;
    private final FileChannel channel;
    private final KmlDocument document;
    private long end;
    KmlLog(Context context,String stem,String source) throws IOException {
        name=stem+".kml"; document=new KmlDocument(source);
        ContentValues values=new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME,name);
        values.put(MediaStore.MediaColumns.MIME_TYPE,"application/vnd.google-earth.kml+xml");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH,"Download/lora_tester/");
        Uri uri=context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);
        if(uri==null) throw new IOException("Cannot create KML file");
        ParcelFileDescriptor fd=context.getContentResolver().openFileDescriptor(uri,"rw");
        if(fd==null) throw new IOException("Cannot open KML file");
        stream=new ParcelFileDescriptor.AutoCloseOutputStream(fd); channel=stream.getChannel();
        try {
            byte[] header=document.header(stem).getBytes(StandardCharsets.UTF_8);
            stream.write(header);end=header.length;finishDocument();
        } catch(Exception e) { stream.close(); throw e; }
    }
    void write(JSONObject object) throws IOException {
        Map<String,Object> values=new LinkedHashMap<>();
        Iterator<String> keys=object.keys();
        while(keys.hasNext()){String key=keys.next();values.put(key,object.opt(key));}
        String fragment=document.record(values);
        if(fragment.isEmpty()) return;
        channel.position(end);
        byte[] bytes=fragment.getBytes(StandardCharsets.UTF_8);
        stream.write(bytes);end+=bytes.length;finishDocument();
    }
    private void finishDocument() throws IOException {
        // Keep a complete closing footer after each record, even before Stop is tapped.
        stream.write(KmlDocument.FOOTER.getBytes(StandardCharsets.UTF_8));
        channel.truncate(channel.position());stream.flush();stream.getFD().sync();
    }
    public void close() throws IOException { stream.close(); }
}
