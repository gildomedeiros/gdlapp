package com.gdl.loratester;

import android.app.*;
import android.content.*;
import android.net.*;
import android.net.wifi.WifiManager;
import java.net.*;
import android.util.Base64;
import android.os.*;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.*;

public final class WifiCaptureService extends Service {
    static volatile String status="Connect phone Wi-Fi to GDL_LORA, then tap Start LoRa Wi-Fi", file="", telemetry="Waiting for GPS readings", counters="", recent="";
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
    private volatile DatagramSocket socket;
    private WifiManager.WifiLock wifiLock;

    public IBinder onBind(Intent intent) { return null; }
    public void onCreate() {
        super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("wifi_capture","LoRa Wi-Fi logging",NotificationManager.IMPORTANCE_LOW));
    }
    private Notification notification(String text) {
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,WifiCaptureService.class).setAction("STOP"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this,"wifi_capture").setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("lora_tester").setContentText(text).setContentIntent(open).setOngoing(running)
            .addAction(new Notification.Action.Builder(null,"Stop logging",stop).build()).build();
    }
    public int onStartCommand(Intent intent,int flags,int startId) {
        if(intent!=null && "STOP".equals(intent.getAction())) { running=false; status="Stopping and saving…"; if(worker==null) stopSelf(); return START_NOT_STICKY; }
        if(worker!=null) return START_NOT_STICKY;
        running=true; active=true; failed=false;
        file=""; telemetry="Waiting for GPS readings"; counters=""; recent=""; lastReading=0;
        startForeground(5,notification("Logging automatically to Downloads/lora_tester"));
        wake=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"lora_tester:capture");
        wake.acquire();
        worker=new Thread(this::capture,"lora-wifi-reader"); worker.start();
        return START_NOT_STICKY;
    }
    private Network wifiNetwork() {
        ConnectivityManager cm=getSystemService(ConnectivityManager.class);
        for(Network n:cm.getAllNetworks()) {
            NetworkCapabilities c=cm.getNetworkCapabilities(n);
            if(c!=null && c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return n;
        }
        return null;
    }
    private void capture() {
        try {
            log=new SessionLog(this,"lora_wifi");
            file="Downloads/lora_tester/"+log.kmlName+"\nRaw backup: "+log.name;
            log.event("session_start","TTGO Wi-Fi UDP: HELLO to 192.168.4.1:5006; receive 5005. Exact datagrams saved as udp_datagram; derived readings are not extra packets. Timestamps are phone reception times. Gaps can include LoRa or Wi-Fi loss.");
            wifiLock=((WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE)).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF,"lora_tester:wifi");
            wifiLock.acquire();
            byte[] buffer=new byte[65535];
            InetAddress target=InetAddress.getByName("192.168.4.1");
            while(running) {
                Network network=wifiNetwork();
                if(network==null) {status="Connect phone Wi-Fi to GDL_LORA • log stays open";Thread.sleep(500);continue;}
                try(DatagramSocket udp=new DatagramSocket(null)) {
                    socket=udp;
                    network.bindSocket(udp);
                    udp.bind(new InetSocketAddress(5005)); udp.setSoTimeout(1000);
                    log.event("wifi_connected","Wi-Fi socket bound; waiting for TTGO at 192.168.4.1:5006");
                    sequences.boundary(); long hello=0, received=0;
                    while(running && network.equals(wifiNetwork())) {
                        long now=SystemClock.elapsedRealtime();
                        if(now-hello>=5000 || hello==0) {
                            byte[] message="HELLO".getBytes(StandardCharsets.US_ASCII);
                            udp.send(new DatagramPacket(message,message.length,target,5006)); hello=now;
                            log.event("wifi_hello_sent","192.168.4.1:5006");
                        }
                        if(received==0 || now-received>6000) status="Waiting for TTGO • connect to GDL_LORA (keep connection without internet)";
                        DatagramPacket packet=new DatagramPacket(buffer,buffer.length);
                        try {udp.receive(packet);} catch(SocketTimeoutException timeout){updateCounters();continue;}
                        chunkWall=System.currentTimeMillis();chunkMono=SystemClock.elapsedRealtimeNanos();
                        chunks++;bytes+=packet.getLength();
                        JSONObject rawRecord=log.entry("udp_datagram",chunkWall,chunkMono);
                        boolean expected=target.equals(packet.getAddress()) && packet.getPort()==5006;
                        SessionLog.put(rawRecord,"datagram",chunks);SessionLog.put(rawRecord,"byte_count",packet.getLength());
                        SessionLog.put(rawRecord,"remote_address",packet.getAddress().getHostAddress());SessionLog.put(rawRecord,"remote_port",packet.getPort());
                        SessionLog.put(rawRecord,"expected_sender",expected);
                        SessionLog.put(rawRecord,"raw_base64",Base64.encodeToString(buffer,0,packet.getLength(),Base64.NO_WRAP));
                        SessionLog.put(rawRecord,"text",new String(buffer,0,packet.getLength(),StandardCharsets.UTF_8));log.write(rawRecord);
                        if(!expected){log.event("wifi_unexpected_sender","Datagram preserved but not used as TTGO telemetry");continue;}
                        received=SystemClock.elapsedRealtime();status="TTGO connected • automatically saving";
                        // UDP boundaries are message boundaries, never join fragments across datagrams.
                        int begin=0;
                        for(int i=0;i<packet.getLength();i++) if(buffer[i]=='\n') {line(Arrays.copyOfRange(buffer,begin,i+1),"line");begin=i+1;}
                        if(begin<packet.getLength() || packet.getLength()==0) line(Arrays.copyOfRange(buffer,begin,packet.getLength()),"line");
                    }
                } catch(IOException error) {
                    if(running) {log.event("wifi_error",String.valueOf(error.getMessage()));status="Wi-Fi interrupted • reconnecting";Thread.sleep(1000);}
                } finally {socket=null;}
                log.event("wifi_disconnected","Wi-Fi socket closed");
            }
            log.event("session_stop","User stopped logging");
        } catch(Exception e) {failed=true;status="LOGGING STOPPED: "+e.getMessage();}
        finally {
            running=false;
            if(log!=null)try{log.close();}catch(Exception e){failed=true;status="LOG SAVE ERROR: "+e.getMessage();}
            if(!failed)status="Stopped • Wi-Fi log saved automatically";
            if(wifiLock!=null && wifiLock.isHeld())wifiLock.release();
            if(wake!=null && wake.isHeld())wake.release();
            active=false;stopForeground(STOP_FOREGROUND_REMOVE);
            if(failed)getSystemService(NotificationManager.class).notify(6,notification(status));
            stopSelf();
        }
    }
    private void line(byte[] original,String framing) {
        String text=new String(original,StandardCharsets.UTF_8);
        String value=text.endsWith("\n")?text.substring(0,text.length()-1):text;
        if(value.endsWith("\r")) value=value.substring(0,value.length()-1);
        JSONObject obj=log.entry("derived_reading",chunkWall==0?System.currentTimeMillis():chunkWall,chunkMono==0?SystemClock.elapsedRealtimeNanos():chunkMono);
        SessionLog.put(obj,"datagram",chunks); SessionLog.put(obj,"framing",framing); SessionLog.put(obj,"text",text);
        lines++; lastReading=SystemClock.elapsedRealtime();
        String type="malformed";
        if(framing.equals("line")) {
            if(value.equals("RX_ERROR")) { type="radio_error"; radioErrors++; }
            else if(value.startsWith("RAW,")) { type="raw_packet"; raw++; }
            else if(value.equals("GDL_LILYGO_READY")) { type="wifi_status"; }
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
    public void onDestroy() { running=false; if(socket!=null)socket.close(); super.onDestroy(); }
}
