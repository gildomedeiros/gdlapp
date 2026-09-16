package com.gdl.loratester;

import android.app.*;
import android.content.*;
import android.hardware.usb.*;
import android.os.*;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.*;

public final class CaptureService extends Service {
    static volatile String status="Connect your CubeCell by USB OTG", file="", telemetry="Waiting for GPS readings", counters="", recent="";
    static volatile boolean active=false;
    static volatile long lastReading=0;
    private volatile boolean running;
    private Thread worker;
    private PowerManager.WakeLock wake;
    private SessionLog log;
    private final Telemetry.Framer framer=new Telemetry.Framer();
    private final Telemetry.Sequences sequences=new Telemetry.Sequences();
    private final ArrayDeque<String> tail=new ArrayDeque<>();
    private final ArrayDeque<Long> arrivals=new ArrayDeque<>();
    private long chunks,bytes,lines,gps,invalid,raw,radioErrors,chunkWall,chunkMono;
    private boolean failed;

    public IBinder onBind(Intent intent) { return null; }
    public void onCreate() {
        super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("capture","LoRa logging",NotificationManager.IMPORTANCE_LOW));
    }
    private Notification notification(String text) {
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,CaptureService.class).setAction("STOP"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this,"capture").setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("lora_tester").setContentText(text).setContentIntent(open).setOngoing(running)
            .addAction(new Notification.Action.Builder(null,"Stop logging",stop).build()).build();
    }
    public int onStartCommand(Intent intent,int flags,int startId) {
        if(intent!=null && "STOP".equals(intent.getAction())) { running=false; status="Stopping and saving…"; if(worker==null) stopSelf(); return START_NOT_STICKY; }
        if(worker!=null) return START_NOT_STICKY;
        running=true; active=true; failed=false;
        file=""; telemetry="Waiting for GPS readings"; counters=""; recent=""; lastReading=0;
        startForeground(1,notification("Logging automatically to Downloads/lora_tester"));
        wake=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"lora_tester:capture");
        wake.acquire();
        worker=new Thread(this::capture,"lora-usb-reader"); worker.start();
        return START_NOT_STICKY;
    }
    private UsbDevice find(UsbManager manager) {
        for(UsbDevice d:manager.getDeviceList().values()) if(Cp2102.supports(d)) return d;
        return null;
    }
    private void capture() {
        try {
            log=new SessionLog(this); file="Downloads/lora_tester/"+log.kmlName+"\nRaw backup: "+log.name;
            log.event("session_start","CP2102 115200 8N1; usb_chunk records preserve exact bytes; line records are derived views. All times are phone reception times, not radio arrival times.");
            UsbManager manager=(UsbManager)getSystemService(USB_SERVICE);
            byte[] buffer=new byte[4096];
            while(running) {
                UsbDevice device=find(manager);
                if(device==null || !manager.hasPermission(device)) {
                    status=device==null?"Waiting for USB receiver • log stays open":"Open app to allow USB access";
                    Thread.sleep(500); continue;
                }
                try(Cp2102 port=new Cp2102(manager,device)) {
                    log.event("usb_connected",device.getDeviceName());
                    sequences.boundary(); status="Connected • automatically saving";
                    while(running) {
                        if(!manager.getDeviceList().containsKey(device.getDeviceName())) throw new IOException("USB receiver detached");
                        int count=port.read(buffer);
                        if(count==0) { updateCounters(); continue; }
                        chunkWall=System.currentTimeMillis(); chunkMono=SystemClock.elapsedRealtimeNanos();
                        chunks++; bytes+=count;
                        log.raw(buffer,count,chunks,chunkWall,chunkMono);
                        log.sync();
                        framer.feed(buffer,count,this::line);
                        log.sync(); updateCounters();
                    }
                } catch(IOException usbError) {
                    // Distinguish storage failure from a transport failure before reconnecting.
                    log.event("usb_error",String.valueOf(usbError.getMessage()));
                    status="USB interrupted • reconnecting: "+usbError.getMessage();
                    framer.finish(this::line); log.sync(); sequences.boundary();
                    if(running) Thread.sleep(1000);
                }
                log.event("usb_disconnected","Receiver closed");
            }
            framer.finish(this::line);
            log.event("session_stop","User stopped logging");
        } catch(Exception e) {
            failed=true; status="LOGGING STOPPED: "+e.getMessage();
        } finally {
            running=false;
            if(log!=null) try { log.close(); } catch(Exception e) { failed=true; status="LOG SAVE ERROR: "+e.getMessage(); }
            if(!failed) status="Stopped • log saved automatically";
            active=false;
            if(wake!=null && wake.isHeld()) wake.release();
            stopForeground(STOP_FOREGROUND_REMOVE);
            if(failed) getSystemService(NotificationManager.class).notify(2,notification(status));
            stopSelf();
        }
    }
    private void line(byte[] original,String framing) {
        String text=new String(original,StandardCharsets.UTF_8);
        String value=text.endsWith("\n")?text.substring(0,text.length()-1):text;
        if(value.endsWith("\r")) value=value.substring(0,value.length()-1);
        JSONObject obj=log.entry("derived_reading",chunkWall==0?System.currentTimeMillis():chunkWall,chunkMono==0?SystemClock.elapsedRealtimeNanos():chunkMono);
        SessionLog.put(obj,"ending_chunk",chunks); SessionLog.put(obj,"framing",framing); SessionLog.put(obj,"text",text);
        lines++; lastReading=SystemClock.elapsedRealtime();
        String type="malformed";
        if(framing.equals("line")) {
            if(value.equals("RX_ERROR")) { type="radio_error"; radioErrors++; }
            else if(value.startsWith("RAW,")) { type="raw_packet"; raw++; }
            else if(value.equals("GDL LORA CLIENT READY") || value.equals("Listening on 917 MHz")) { type="receiver_status"; sequences.boundary(); }
            else try {
                Telemetry t=Telemetry.parse(value); type="gps"; gps++;
                String sequenceState=sequences.accept(t);
                SessionLog.put(obj,"sequence_state",sequenceState);
                SessionLog.put(obj,"sequence",t.sequence); SessionLog.put(obj,"sender_ms",t.senderMs);
                SessionLog.put(obj,"latitude",t.lat); SessionLog.put(obj,"longitude",t.lon);
                SessionLog.put(obj,"satellites",t.sats); SessionLog.put(obj,"hdop",t.hdop);
                SessionLog.put(obj,"rssi",t.rssi); SessionLog.put(obj,"snr",t.snr);
                SessionLog.put(obj,"receiver_missed",t.receiverMissed);
                telemetry=String.format(Locale.US,"%.6f, %.6f\nRSSI  %d dBm     SNR  %.1f dB\nSatellites  %d     HDOP  %.2f\nSequence  %d     Sender ms  %d\n%s",t.lat,t.lon,t.rssi,t.snr,t.sats,t.hdop,t.sequence,t.senderMs,sequenceState.replace('_',' '));
            } catch(Exception e) { SessionLog.put(obj,"parse_error",e.getMessage()); }
        }
        if(type.equals("malformed")) invalid++;
        if(type.equals("gps") || type.equals("raw_packet")) arrivals.addLast(lastReading);
        SessionLog.put(obj,"classification",type);
        try { log.write(obj); } catch(IOException e) { throw new IllegalStateException("Cannot save reading",e); }
        tail.addLast(value.length()>250?value.substring(0,250)+"…":value);
        while(tail.size()>14) tail.removeFirst(); recent=String.join("\n",tail);
        updateCounters();
    }
    private void updateCounters() {
        long now=SystemClock.elapsedRealtime();
        while(!arrivals.isEmpty() && arrivals.peekFirst()<now-10000) arrivals.removeFirst();
        counters=String.format(Locale.US,"GPS %d   Raw %d   Bad/partial %d\nRadio errors %d   Lines %d   Bytes %d\nSequence gaps %d   Repeats %d   Older %d\nPossible restarts/wraps/old packets %d\nReceived packets / last 10 seconds: %d",gps,raw,invalid,radioErrors,lines,bytes,sequences.gaps,sequences.repeats,sequences.backwards,sequences.resets,arrivals.size());
    }
    public void onDestroy() { running=false; super.onDestroy(); }
}
