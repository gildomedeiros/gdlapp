package com.gdl.loratester;

import android.content.*;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.util.Base64;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

final class SessionLog implements AutoCloseable {
    final String name, kmlName;
    private final KmlLog kml;
    private final String source;
    private final FileOutputStream stream;
    private final ParcelFileDescriptor descriptor;
    private long record=0;
    SessionLog(Context context) throws IOException {
        this(context,"lora");
    }
    SessionLog(Context context,String source) throws IOException {
        this.source=source;
        String stem="lora_tester_"+source+"_"+DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss_SSS").format(LocalDateTime.now())+"_"+UUID.randomUUID().toString().substring(0,8);
        name=stem+".jsonl";
        ContentValues values=new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME,name);
        values.put(MediaStore.MediaColumns.MIME_TYPE,"application/x-ndjson");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH,"Download/lora_tester/");
        Uri uri=context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);
        if(uri==null) throw new IOException("Cannot create Downloads/lora_tester log");
        descriptor=context.getContentResolver().openFileDescriptor(uri,"w");
        if(descriptor==null) throw new IOException("Cannot open session log");
        stream=new ParcelFileDescriptor.AutoCloseOutputStream(descriptor);
        try { kml=new KmlLog(context,stem,source);kmlName=kml.name; }
        catch(Exception e) { stream.close();throw e; }
    }
    JSONObject entry(String kind,long wall,long monotonic) {
        JSONObject obj=new JSONObject();
        put(obj,"record",++record); put(obj,"kind",kind);
        put(obj,"source",source);
        put(obj,"phone_timestamp",Instant.ofEpochMilli(wall).toString());
        put(obj,"phone_epoch_ms",wall); put(obj,"elapsed_realtime_ns",monotonic);
        return obj;
    }
    static void put(JSONObject object,String key,Object value) {
        if(value instanceof Double && !Double.isFinite((Double)value))value=value.toString();
        if(value instanceof Float && !Float.isFinite((Float)value))value=value.toString();
        try { object.put(key,value); } catch(Exception e) { throw new IllegalArgumentException(e); }
    }
    void write(JSONObject obj) throws IOException {
        try {
            stream.write((obj.toString()+"\n").getBytes(StandardCharsets.UTF_8));
            stream.flush();stream.getFD().sync();
            kml.write(obj);
        }
        catch(IOException e) { throw new IllegalStateException("Log write failed; capture stopped",e); }
    }
    void event(String kind,String detail) throws IOException {
        JSONObject obj=entry(kind,System.currentTimeMillis(),SystemClock.elapsedRealtimeNanos());
        put(obj,"detail",detail); write(obj); sync();
    }
    void raw(byte[] bytes,int size,long chunk,long wall,long mono) throws IOException {
        JSONObject obj=entry("usb_chunk",wall,mono);
        put(obj,"chunk",chunk); put(obj,"byte_count",size);
        put(obj,"raw_base64",Base64.encodeToString(bytes,0,size,Base64.NO_WRAP));
        put(obj,"text",new String(bytes,0,size,StandardCharsets.UTF_8)); write(obj);
    }
    void sync() throws IOException {
        try { stream.flush(); stream.getFD().sync(); }
        catch(IOException e) { throw new IllegalStateException("Log storage failed; capture stopped",e); }
    }
    public void close() throws IOException {
        try { sync(); } finally { try { stream.close(); } finally { kml.close(); } }
    }
}
