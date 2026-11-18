package au.com.gildo.vtc;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import java.util.*;
import java.util.concurrent.*;

/** Owns the complete runtime. Activities only attach/detach views, never close tracking. */
public final class VtcService extends Service {
    private static final String CHANNEL="vtc_tracking", STOP="au.com.gildo.vtc.STOP";
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ScheduledExecutorService storage=Executors.newSingleThreadScheduledExecutor(r->new Thread(r,"vtc-settings"));
    private final LocalBinder binder=new LocalBinder();
    private final Set<VtcController.Listener> listeners=new HashSet<>();
    SessionLog log;
    VtcController controller;
    PublicSettingsStore setup;
    CameraZoomReader camera;
    private VtcController.View latest;
    private PowerManager.WakeLock wake;
    private String notificationText="";
    private volatile String loadError="";
    private android.net.wifi.WifiManager.WifiLock wifiLock;
    private boolean destroyed;
    private final java.util.concurrent.atomic.AtomicLong savesQueued=new java.util.concurrent.atomic.AtomicLong();
    private volatile long savesCompleted;
    private boolean logsMigrated;
    private Thread.UncaughtExceptionHandler previousHandler,fatalHandler;
    public final class LocalBinder extends Binder {VtcService service(){return VtcService.this;}}
    @Override public void onCreate(){
        super.onCreate();
        NotificationManager nm=getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL,"VTC mount control",NotificationManager.IMPORTANCE_LOW));
        if(Build.VERSION.SDK_INT>=29)startForeground(108,notification("Starting VTC"),ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        else startForeground(108,notification("Starting VTC"));
        wake=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"VTC:mount-control");wake.setReferenceCounted(false);
        android.net.wifi.WifiManager wifi=(android.net.wifi.WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);
        if(wifi!=null){wifiLock=wifi.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF,"VTC:device-wifi");wifiLock.setReferenceCounted(false);}
        log=new SessionLog(this);setup=new PublicSettingsStore(this,"vtc_setup","setup.json",log);
        Settings settings;boolean loadedOk=true;
        try{
            Map<String,String> loaded=setup.load();
            if(!loaded.isEmpty()&&!loaded.containsKey("schemaVersion")){
                try{new PublicSettingsStore(this,"vtc_setup_pre_117","setup-before-1.0.17.json",log).save(loaded);}
                catch(Exception backup){log.error("settings_legacy_backup_failed",backup);}
                log.record("settings_units_migration","referenceDistanceMetres",143,"from","degrees","to","metres");
            }
            settings=new Settings(loaded);
        }catch(Exception e){loadedOk=false;settings=new Settings(Collections.emptyMap());loadError="Settings could not be loaded: "+e.getMessage();log.error("settings_load_failed",e);}
        controller=new VtcController(this,settings,log,v->main.post(()->update(v)));
        previousHandler=Thread.getDefaultUncaughtExceptionHandler();
        fatalHandler=(thread,failure)->{try{controller.fatalFailure(failure);}finally{if(previousHandler!=null)previousHandler.uncaughtException(thread,failure);}};
        Thread.setDefaultUncaughtExceptionHandler(fatalHandler);
        camera=new CameraZoomReader(controller::settings,controller::cameraNetwork,controller::zoomInput,log);
        log.settings("service_start",settings);
        final Settings migrated=settings;
        if(loadedOk)storage.execute(()->{try{setup.save(migrated.values);}catch(Exception e){log.error("settings_migration_save_failed",e);}});
        log.event("service_session","New service session; calibration must be validated before aiming");
        storage.scheduleWithFixedDelay(()->{setup.retry();controller.retryLandmarkStorage();
            if(!logsMigrated)logsMigrated=LogStorage.migratePreviousLogs(this,log);
        },5,10,TimeUnit.SECONDS);
    }
    String setupStatus(){return savesCompleted<savesQueued.get()?"Saving setup.json…":loadError.isEmpty()?setup.status():loadError;}
    void saveSettings(Settings settings)throws Exception {
        String previousManual=controller.settings().values.get("manualZoomX");
        controller.configure(settings);
        if(!previousManual.equals(settings.values.get("manualZoomX")))camera.manualObservation("field_changed");
        final long revision=savesQueued.incrementAndGet();
        storage.execute(()->{try{setup.save(settings.values);loadError="";}catch(Exception e){loadError="Settings save failed: "+e.getMessage();log.error("settings_save_failed",e);}finally{savesCompleted=revision;}});
    }
    void useSettingsFolder(android.net.Uri tree,int flags){
        int grants=Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        if((flags&grants)!=grants)throw new IllegalArgumentException("Allow both read and write access to the Settings folder");
        String id=android.provider.DocumentsContract.getTreeDocumentId(tree);
        if(!id.endsWith(":Download/VTC/Settings")&&!id.endsWith(":Downloads/VTC/Settings"))throw new IllegalArgumentException("Select Download/VTC/Settings, not the Downloads root");
        getContentResolver().takePersistableUriPermission(tree,grants);
        if(!getSharedPreferences(AndroidSettingsFiles.FOLDER_PREFS,0).edit().putString("uri",tree.toString()).commit())throw new IllegalStateException("Cannot retain folder access");
        final long revision=savesQueued.incrementAndGet();
        // Retry known pending edits only. Granting access must never write fallback
        // defaults over an existing file that could not be loaded at startup.
        storage.execute(()->{try{setup.retry();controller.retryLandmarkStorage();log.event("settings_folder_access",tree.toString());}catch(Exception e){loadError=e.toString();log.error("settings_folder_save_failed",e);}finally{savesCompleted=revision;}});
    }
    void exit(Runnable finished){controller.exit(ok->main.post(()->{
        if(!ok)android.widget.Toast.makeText(this,"VTC closed. Mount stop NOT confirmed; check mount power.",android.widget.Toast.LENGTH_LONG).show();
        finished.run();stopSelf();
    }));}
    void attach(VtcController.Listener listener){listeners.add(listener);if(latest!=null)listener.update(latest);}
    void detach(VtcController.Listener listener){listeners.remove(listener);}
    @android.annotation.SuppressLint("WakelockTimeout") // Held only for a connected device session; released on disconnect or service destruction.
    private void update(VtcController.View v){
        if(destroyed)return;latest=v;
        // Keep CPU awake throughout a connected session, including waiting for fresh GPS.
        boolean needed=v.connected||v.busy||v.tracking||controller.loraRequested();
        if(needed&&!wake.isHeld())wake.acquire();else if(!needed&&wake.isHeld())wake.release();
        if(wifiLock!=null){if(needed&&!wifiLock.isHeld())wifiLock.acquire();else if(!needed&&wifiLock.isHeld())wifiLock.release();}
        String text=v.tracking?"Aiming active · "+v.state:v.connected?"Connected · "+v.state:v.state;
        if(!text.equals(notificationText)){notificationText=text;getSystemService(NotificationManager.class).notify(108,notification(text));}
        for(VtcController.Listener listener:new ArrayList<>(listeners))listener.update(v);
    }
    private Notification notification(String text){
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,VtcService.class).setAction(STOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_vtc).setContentTitle("VTC").setContentText(text)
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(new Notification.Action.Builder(null,"Stop aiming",stop).build()).build();
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent!=null&&STOP.equals(intent.getAction()))controller.stop("Notification Stop");
        // Do not silently resume after process death with an unverified mount reference.
        return START_NOT_STICKY;
    }
    @Override public IBinder onBind(Intent intent){return binder;}
    @Override public void onTaskRemoved(Intent rootIntent){log.event("task_removed","Requesting confirmed Stop before exit");exit(()->{});}
    @Override public void onDestroy(){
        if(Thread.getDefaultUncaughtExceptionHandler()==fatalHandler)Thread.setDefaultUncaughtExceptionHandler(previousHandler);
        destroyed=true;listeners.clear();storage.shutdown();if(camera!=null)camera.close();if(controller!=null)controller.close();
        if(wifiLock!=null&&wifiLock.isHeld())wifiLock.release();
        if(wake!=null&&wake.isHeld())wake.release();main.removeCallbacksAndMessages(null);super.onDestroy();
    }
}
