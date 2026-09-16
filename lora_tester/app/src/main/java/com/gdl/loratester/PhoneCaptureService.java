package com.gdl.loratester;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.location.*;
import android.os.*;
import org.json.*;
import java.util.Locale;

public final class PhoneCaptureService extends Service {
    static volatile boolean active;
    static volatile String status="Phone GPS ready", telemetry="Waiting for phone GPS", files="";
    static volatile long lastReading;
    private HandlerThread thread;
    private Handler handler;
    private LocationManager locations;
    private SessionLog log;
    private PowerManager.WakeLock wake;
    private boolean closing;
    private long samples,lastFixNanos=-1,lastCallbackNs=-1,satelliteNs=-1,startedNs;
    private int satellitesUsed,satellitesVisible;
    private double meanCn0=Double.NaN;
    private final NmeaFix nmea=new NmeaFix();

    public IBinder onBind(Intent intent){return null;}
    public void onCreate(){
        super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("phone_capture","Phone GPS logging",NotificationManager.IMPORTANCE_LOW));
    }
    private Notification notification(String text,boolean ongoing){
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop=PendingIntent.getService(this,3,new Intent(this,PhoneCaptureService.class).setAction("STOP"),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,"phone_capture").setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("lora_tester • phone GPS").setContentText(text).setContentIntent(open).setOngoing(ongoing)
            .addAction(new Notification.Action.Builder(null,"Stop phone",stop).build()).build();
    }
    public int onStartCommand(Intent intent,int flags,int startId){
        if(intent!=null && "STOP".equals(intent.getAction())){
            status="Stopping phone and saving…";
            if(handler!=null) handler.post(()->finish(null)); else stopSelf();
            return START_NOT_STICKY;
        }
        if(thread!=null) return START_NOT_STICKY;
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){status="Precise location permission is required";stopSelf();return START_NOT_STICKY;}
        active=true;status="Starting phone GPS…";files="";telemetry="Waiting for a fresh phone GPS fix";lastReading=0;
        startForeground(3,notification("Phone KML and raw log save automatically",true));
        wake=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"lora_tester:phone");
        wake.setReferenceCounted(false);wake.acquire(600_000);
        thread=new HandlerThread("phone-gps-logger");thread.start();handler=new Handler(thread.getLooper());
        handler.post(this::begin);return START_NOT_STICKY;
    }
    @android.annotation.SuppressLint("MissingPermission")
    private void begin(){
        try{
            log=new SessionLog(this,"phone");files="Downloads/lora_tester/"+log.kmlName+"\nRaw backup: "+log.name;
            log.event("session_start","Phone GPS_PROVIDER, requested 1-second updates and zero minimum distance. sequence is a local callback counter. RSSI/SNR and sender_ms are unavailable, not zero. HDOP comes only from fresh valid NMEA. GNSS C/N0 is satellite signal quality, not LoRa SNR. Location accuracy is Android's reported estimate, not measured error.");
            locations=(LocationManager)getSystemService(LOCATION_SERVICE);
            if(!locations.getAllProviders().contains(LocationManager.GPS_PROVIDER)) throw new IllegalStateException("This phone has no GPS provider");
            boolean gnss=locations.registerGnssStatusCallback(gnssCallback,handler);
            boolean sentences=locations.addNmeaListener(nmeaListener,handler);
            log.event("phone_capabilities","GNSS status callbacks: "+gnss+"; NMEA callbacks: "+sentences+". Availability and message fields depend on this phone.");
            locations.requestLocationUpdates(LocationManager.GPS_PROVIDER,500L,0f,listener,thread.getLooper());
            startedNs=SystemClock.elapsedRealtimeNanos();
            status=locations.isProviderEnabled(LocationManager.GPS_PROVIDER)?"Phone GPS running • waiting for fix":"Enable phone Location to receive GPS";
            handler.post(watchdog);handler.postDelayed(renewWake,300_000);
        }catch(Exception e){finish(e);}
    }
    private final Runnable renewWake=new Runnable(){public void run(){if(!closing){wake.acquire(600_000);handler.postDelayed(this,300_000);}}};
    private final Runnable watchdog=new Runnable(){public void run(){
        if(closing)return;
        try{
            long now=SystemClock.elapsedRealtimeNanos();
            if(lastCallbackNs<0 || now-lastCallbackNs>3_000_000_000L){
                JSONObject obj=log.entry("phone_no_fix",System.currentTimeMillis(),now);
                SessionLog.put(obj,"classification","phone_no_fix");
                SessionLog.put(obj,"detail","No new GPS callback; no coordinate invented or repeated");
                SessionLog.put(obj,"seconds_waiting",(now-(lastCallbackNs<0?startedNs:lastCallbackNs))/1e9);
                log.write(obj);status="Phone GPS • waiting for a fresh fix";
            }
            handler.postDelayed(this,1000);
        }catch(Exception e){finish(e);}
    }};
    private final LocationListener listener=new LocationListener(){
        public void onLocationChanged(Location location){if(!closing)recordLocation(location);}
        public void onProviderEnabled(String provider){event("provider_enabled",provider);}
        public void onProviderDisabled(String provider){satelliteNs=-1;nmea.reset();event("provider_disabled",provider);status="Phone Location disabled • waiting";}
        @SuppressWarnings("deprecation") public void onStatusChanged(String provider,int state,Bundle extras){event("provider_status",provider+": "+state);}
    };
    private final OnNmeaMessageListener nmeaListener=(message,timestamp)->{
        if(closing)return;
        try{
            long now=SystemClock.elapsedRealtimeNanos();nmea.accept(message,now);
            JSONObject obj=log.entry("phone_nmea",System.currentTimeMillis(),now);
            SessionLog.put(obj,"nmea_timestamp_ms",timestamp);SessionLog.put(obj,"text",message);log.write(obj);
        }catch(Exception e){finish(e);}
    };
    private final GnssStatus.Callback gnssCallback=new GnssStatus.Callback(){
        public void onStarted(){event("gnss_started","Satellite receiver started");}
        public void onStopped(){satelliteNs=-1;nmea.reset();event("gnss_stopped","Satellite receiver stopped");}
        public void onFirstFix(int ttff){event("gnss_first_fix","Time to first fix: "+ttff+" ms");}
        public void onSatelliteStatusChanged(GnssStatus gnss){
            if(closing)return;
            try{
                satellitesVisible=gnss.getSatelliteCount();satellitesUsed=0;double sum=0;
                satelliteNs=SystemClock.elapsedRealtimeNanos();JSONArray satellites=new JSONArray();
                for(int i=0;i<satellitesVisible;i++){
                    boolean used=gnss.usedInFix(i);if(used){satellitesUsed++;sum+=gnss.getCn0DbHz(i);}
                    JSONObject item=new JSONObject();SessionLog.put(item,"svid",gnss.getSvid(i));SessionLog.put(item,"constellation",gnss.getConstellationType(i));
                    SessionLog.put(item,"used_in_fix",used);SessionLog.put(item,"cn0_db_hz",gnss.getCn0DbHz(i));
                    SessionLog.put(item,"azimuth_degrees",gnss.getAzimuthDegrees(i));SessionLog.put(item,"elevation_degrees",gnss.getElevationDegrees(i));satellites.put(item);
                }
                meanCn0=satellitesUsed>0?sum/satellitesUsed:Double.NaN;
                JSONObject obj=log.entry("phone_gnss_status",System.currentTimeMillis(),satelliteNs);
                SessionLog.put(obj,"satellites",satellitesUsed);SessionLog.put(obj,"satellites_visible",satellitesVisible);SessionLog.put(obj,"satellite_details",satellites);log.write(obj);
            }catch(Exception e){finish(e);}
        }
    };
    private void recordLocation(Location location){
        try{
            long now=SystemClock.elapsedRealtimeNanos(),wall=System.currentTimeMillis();
            long fixNs=location.getElapsedRealtimeNanos();double ageMs=(now-fixNs)/1e6;
            boolean repeated=fixNs==lastFixNanos,older=lastFixNanos>=0 && fixNs<lastFixNanos;
            lastFixNanos=fixNs;lastCallbackNs=now;lastReading=SystemClock.elapsedRealtime();samples++;
            boolean valid=Double.isFinite(location.getLatitude()) && Double.isFinite(location.getLongitude()) && Math.abs(location.getLatitude())<=90 && Math.abs(location.getLongitude())<=180;
            JSONObject obj=log.entry("phone_reading",wall,now);
            SessionLog.put(obj,"location_raw",location.toString());
            SessionLog.put(obj,"classification",valid?"phone_gps":"phone_invalid");
            SessionLog.put(obj,"sequence",samples);SessionLog.put(obj,"sequence_meaning","local phone callback counter");
            SessionLog.put(obj,"latitude",Double.isFinite(location.getLatitude())?location.getLatitude():JSONObject.NULL);
            SessionLog.put(obj,"longitude",Double.isFinite(location.getLongitude())?location.getLongitude():JSONObject.NULL);
            SessionLog.put(obj,"fix_timestamp_ms",location.getTime());SessionLog.put(obj,"fix_elapsed_realtime_ns",fixNs);
            SessionLog.put(obj,"fix_age_ms",ageMs);SessionLog.put(obj,"stale_fix",ageMs>3000 || ageMs<0);
            SessionLog.put(obj,"repeated_fix",repeated);SessionLog.put(obj,"older_fix",older);SessionLog.put(obj,"provider",location.getProvider());
            SessionLog.put(obj,"accuracy_m",location.hasAccuracy()?location.getAccuracy():JSONObject.NULL);
            SessionLog.put(obj,"altitude_m",location.hasAltitude()?location.getAltitude():JSONObject.NULL);
            SessionLog.put(obj,"vertical_accuracy_m",location.hasVerticalAccuracy()?location.getVerticalAccuracyMeters():JSONObject.NULL);
            SessionLog.put(obj,"speed_mps",location.hasSpeed()?location.getSpeed():JSONObject.NULL);
            SessionLog.put(obj,"speed_accuracy_mps",location.hasSpeedAccuracy()?location.getSpeedAccuracyMetersPerSecond():JSONObject.NULL);
            SessionLog.put(obj,"bearing_degrees",location.hasBearing()?location.getBearing():JSONObject.NULL);
            SessionLog.put(obj,"bearing_accuracy_degrees",location.hasBearingAccuracy()?location.getBearingAccuracyDegrees():JSONObject.NULL);
            SessionLog.put(obj,"mock_location",location.isFromMockProvider());
            boolean freshSat=satelliteNs>=0 && now-satelliteNs<=3_000_000_000L;
            Double hdop=nmea.current(now);
            SessionLog.put(obj,"satellites",freshSat?satellitesUsed:JSONObject.NULL);
            SessionLog.put(obj,"satellites_visible",freshSat?satellitesVisible:JSONObject.NULL);
            SessionLog.put(obj,"satellite_status_age_ms",satelliteNs>=0?(now-satelliteNs)/1e6:JSONObject.NULL);
            SessionLog.put(obj,"gnss_cn0_mean_db_hz",freshSat && Double.isFinite(meanCn0)?meanCn0:JSONObject.NULL);
            SessionLog.put(obj,"hdop",hdop!=null?hdop:JSONObject.NULL);
            SessionLog.put(obj,"hdop_age_ms",hdop!=null?(now-nmea.receivedNs)/1e6:JSONObject.NULL);
            for(String field:new String[]{"rssi","snr","sender_ms","receiver_missed"})SessionLog.put(obj,field,JSONObject.NULL);
            log.write(obj);
            status="Phone GPS • automatically saving";
            telemetry=String.format(Locale.US,"%.6f, %.6f\nAccuracy: %s m    Satellites: %s\nHDOP: %s    Fix age: %.0f ms\nReadings: %d%s",location.getLatitude(),location.getLongitude(),location.hasAccuracy()?String.format(Locale.US,"%.1f",location.getAccuracy()):"N/A",freshSat?String.valueOf(satellitesUsed):"N/A",hdop!=null?String.format(Locale.US,"%.2f",hdop):"N/A",ageMs,samples,repeated?" • repeated fix":older?" • older fix":ageMs>3000?" • stale fix":"");
        }catch(Exception e){finish(e);}
    }
    private void event(String kind,String detail){if(!closing && log!=null)try{log.event(kind,detail);}catch(Exception e){finish(e);}}
    private void finish(Exception failure){
        if(closing)return;closing=true;
        handler.removeCallbacksAndMessages(null);
        if(locations!=null){
            try{locations.removeUpdates(listener);}catch(Exception ignored){}
            try{locations.unregisterGnssStatusCallback(gnssCallback);}catch(Exception ignored){}
            try{locations.removeNmeaListener(nmeaListener);}catch(Exception ignored){}
        }
        if(log!=null){
            try{log.event(failure==null?"session_stop":"phone_error",failure==null?"Phone logging stopped":String.valueOf(failure.getMessage()));}catch(Exception e){if(failure==null)failure=e;}
            try{log.close();}catch(Exception e){if(failure==null)failure=e;}
        }
        if(wake!=null && wake.isHeld())wake.release();
        status=failure==null?"Phone stopped • KML and raw log saved":"PHONE LOGGING STOPPED: "+failure.getMessage();
        active=false;stopForeground(STOP_FOREGROUND_REMOVE);
        if(failure!=null)getSystemService(NotificationManager.class).notify(4,notification(status,false));
        stopSelf();thread.quitSafely();
    }
    public void onDestroy(){if(handler!=null)handler.post(()->finish(null));super.onDestroy();}
}
