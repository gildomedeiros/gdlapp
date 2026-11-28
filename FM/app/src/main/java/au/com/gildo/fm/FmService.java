package au.com.gildo.fm;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.net.*;
import android.os.*;
import org.json.*;
import java.io.*;
import java.math.BigDecimal;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

public final class FmService extends Service {
    static final int HS_GAP_WARNING=22, TILT_DISABLE=23, LIVE_TILT_SPEED=24, LIVE_GPS_HOLD=25,
        LIVE_GPS_STATIONARY_MS=26, LIVE_PAN_LEAD=27, SIMULATOR_SHOT_WIDTH=28, LIVE_MIN_RANGE=37, MANUAL_CAMERA=38,
        LIVE_PAN_SPEED_MULTIPLIER=39;
    static final String[] KEYS={"GPS IP","GPS port","Listen port","GPS max age (ms)",
        "GTi IP","GTi port","Canon IP","Canon port","Mount latitude","Mount longitude",
        "Pivot elevation (m)","Target elevation (m)","Reference bearing","Reference elevation",
        "Reference pan count","Reference tilt count","Pan sign","Tilt sign",
        "Landmark latitude","Landmark longitude","Landmark elevation (m)","Calibration basis","HS gap warning (m)",
        "tilt-disable","Live slow tilt speed (× sidereal)","Live GPS small-step hold (fixes)",
        "Live GPS stationary confirmation (ms)","Live pan lead (m)","Simulator horizontal shot width (m)",
        "Canon clip limit (minutes)","Canon zoom mode","Canon frame width (m)","Canon wide frame factor",
        "Canon zoom bands (JSON)","Canon zoom mapping (JSON)","Canon zoom scales","Canon zoom scale interval (s)","Minimum surfer-to-mount distance (m)","Manual camera",
        "Live pan speed multiplier"};
    static final String[] DEFAULTS={"192.168.4.1","5006","5005","2000","192.168.4.4","11880",
        "192.168.4.3","8080","","","","","","","","","1","-1","","","","","30",
        "false",Integer.toString(LiveTracking.DEFAULT_TILT_SPEED),
        Integer.toString(LiveGpsFilter.DEFAULT_HOLD_FIXES),
        Integer.toString(LiveGpsFilter.DEFAULT_STATIONARY_CONFIRMATION_MS),
        Integer.toString(LiveGpsFilter.DEFAULT_PAN_LEAD_M),Integer.toString(LiveFraming.DEFAULT_SHOT_WIDTH_M),
        "20","bands","40","1.648",CameraSettings.DEFAULT_BANDS,ZoomMapping.DEFAULT,"[1.0]","10","5","false","1.0"};

    private static final String CHANNEL="fm_connected";
    static final String REPLAY_URI="replayCsvUri";
    private static final int NOTIFICATION=101;
    private static final String STOP="au.com.gildo.fm.STOP";
    private static final String LAST_CALIBRATION_ERROR="lastCalibrationError";
    private static final String REPLAY_STOP_COLUMN="wait for moto full stop";
    private static final String REPLAY_STOP_MOTOR_COLUMN="stop motor";
    private static final String HS_SPEED_UP_COLUMN="speed up";
    private static final long REPLAY_STOP_TIMEOUT_MS=30_000,REPLAY_STOP_POLL_MS=100;
    private static final String REPORT_DIRECTORY=Environment.DIRECTORY_DOWNLOADS+"/FM/reports";
    static final String HS_SPEED_SHORTFALL="Speed shortfall · ";
    private static final String REPLAY_MOTION_PENDING="Total distance displaced · —\nAverage speed · —";
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService surferFiles=Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService settingsWatcher=Executors.newSingleThreadScheduledExecutor();
    private final Object settingsIo=new Object();
    private final LandmarkCalibration.Session calibrationSession=new LandmarkCalibration.Session();
    private volatile long settingsRevision;
    private volatile boolean settingsLoaded;
    private volatile SettingsFile.LastLoad lastSettingsLoad;
    private volatile String settingsFileStatus="";
    long settingsRevision() { return settingsRevision; }
    String settingsFileStatus() { return settingsFileStatus; }
    String settingsVersionLabel() {
        if(!settingsLoaded) return "Loading…";
        SettingsFile.LastLoad load=lastSettingsLoad;
        if(load==null) return "File not loaded";
        String version=load.labelAt(SystemClock.elapsedRealtime());
        return settingsFileStatus.startsWith("Settings file error")?version+" (reload failed)":version;
    }

    private void recordSettingsLoad(SettingsFile.Snapshot snapshot,byte[] contents) {
        lastSettingsLoad=new SettingsFile.LastLoad(snapshot.displayVersion(),SystemClock.elapsedRealtime(),contents);
        main.post(this::publish);
    }
    private final Handler main=new Handler(Looper.getMainLooper());
    private final List<ReplayRowReport> replayReports=new ArrayList<>();
    private final Connection binder=new Connection();
    private SharedPreferences prefs;
    private ConnectivityManager network;
    private GdlWifi gdl;
    private boolean waitingForGdl;
    private volatile boolean discoverGdlWhenIdle;
    private DeviceDiscovery.Link deviceLink(String host) throws IOException {
        return DeviceDiscovery.selectLink(network,gdl.route(),host);
    }

    void connectGdl(String security,String password) {
        if(!ready()) return;
        waitingForGdl=true;
        begin("Connecting to GDL Wi-Fi");
        gdl.request(security,password);
        publish();
    }

    void gdlPermissionDenied() { gdl.fail("Permission denied"); }

    private void gdlChanged(boolean available) {
        if(destroyed || stopWhenIdle) return;
        boolean waiting=waitingForGdl;
        waitingForGdl=false;
        if(waiting) busy=false;
        if(available) discoverGdlWhenIdle=true;
        else {
            discoverGdlWhenIdle=false;
            if(gdl.requested()) gtiExchangeFailed=true;
            if(waiting) resultText="GDL Wi-Fi · "+gdl.flag().label();
        }
        discoverGdlIfReady();
        publish();
    }

    private void discoverGdlIfReady() {
        if(!discoverGdlWhenIdle || !ready()) return;
        discoverGdlWhenIdle=false;
        begin("GDL connected · Discovering GTi");
        worker.execute(()->{ resetGps(); finish(connectMount(true)); });
    }
    private volatile CameraZoomReader camera;
    private volatile CameraSettings cameraSettings;
    private volatile long cameraSession=-1;
    private volatile CameraPosition cameraPosition;

    private record CameraPosition(GoTo.Point mount,GoTo.Point surfer,long freshAt,double maxAgeMs) {}
    private PowerManager.WakeLock wake;
    private Listener listener;
    private boolean foreground,busy,linkingSettings;
    private volatile boolean stopWhenIdle;
    private MountConnection lastMovingMount;
    private String gpsText="GPS waiting for receiver", resultText="Starting FM";
    private String liveGpsRuleText="";
    private String replayMotionText=REPLAY_MOTION_PENDING;
    private Long replayDurationMs,replayFirstStartMs;
    private HsReport hsReport;
    private boolean hsCanCancel;
    private volatile boolean hsCancelled;
    private LiveGpsSource liveGps;
    private boolean calibrating;
    private volatile LandmarkGpsCapture landmarkCapture;
    private volatile String captureProgress="";
    boolean capturingLandmark() { return landmarkCapture!=null; }
    String captureProgress() { return captureProgress; }
    void cancelLandmarkCapture() {
        LandmarkGpsCapture capture=landmarkCapture;
        if(capture!=null) capture.cancel();
    }
    private volatile long liveStopRequested=-1;
    private volatile boolean liveStopConfirmed;
    private final Queue<GpsPadEvent> gpsPadEvents=new ConcurrentLinkedQueue<>();
    private Trace gpsPadLog;
    private final LiveGpsSource idleSimulatorGps=new LiveGpsSource(0);
    private LiveGpsFilter idleSimulatorFilter;
    private volatile GpsPadSimulator gpsPad;
    private GpsPadSimulator workerGpsPad;
    private GpsPadSimulator.Playback workerPlayback;
    private List<GoTo.Point> preparedSurferFixes;
    private String preparedFixFile=SurferFixFile.LOCATION;
    private boolean preparingSurferFixes;
    private String surferFileStatus="Save selected modes or load the existing file before Apply.";
    private DatagramSocket gps;
    private InetSocketAddress receiver;
    private DeviceDiscovery.Link gpsLink;
    private LoRaTelemetry.Tracker tracker=new LoRaTelemetry.Tracker();
    private record MapGps(GoTo.Point point,long at,String simulatorId,long packet) {}
    private record MapPan(LiveTrackingKml.CameraHeading heading,String state) {}
    private volatile MapGps mapGps;
    private volatile MapPan mapPan=new MapPan(null,"Awaiting encoder");
    private LoRaTelemetry latest;
    private Long latestGpsTap;
    private SurferModes.Mode latestGpsMode;
    private volatile long lastReceiverMs=-1;
    private long received, lastHello=-5000;
    private volatile String landmarkRangeBlock="";
    String landmarkRangeBlock() { return landmarkRangeBlock; }
    private volatile String[] settings;
    private String workerReferenceIssue;
    private volatile MountConnection gtiConnection;
    private volatile boolean destroyed;
    private long lastGpsUi;
    record Saved(GoTo.Point point,long sequence,long captured,String attempt) {}
    private record GpsSnapshot(LoRaTelemetry fix,long receivedAt,long ageMs) {}
    private record LiveGpsPacket(String line,LoRaTelemetry fix,long receivedAt,String rejection,
                                 String simulatorId,Long lastTap,boolean firstPlaybackFix,SurferModes.Mode mode) {
        boolean simulated() { return simulatorId!=null; }
    }
    private record GpsPadEvent(String name,long at,String simulatorId,Object[] fields) {
        void write(Trace trace) {
            if(trace!=null) trace.axisEvent(0,name,at,fields);
        }
    }
    record ReplayRowReport(int viewpoint,String callTime,Long sincePreviousCallMs,String stopMotor,long csvDelayMs,Long actualWaitMs,
                           Long callToRequiredStartsMs,Long stopCheckWaitMs,Long rowToBothStoppedMs,String details) {}
    record HsRow(int viewpoint,boolean observed,boolean distanceGapOverLimit,String[] cells,String details) {}
    record HsReport(String summary,List<HsRow> rows,Double gapWarningM) {}
    private record Viewpoint(GoTo.Point point,long delayMs,boolean waitForStop,boolean stopMotor,HighSpeedReplay.SpeedUp speedUp,int row) {}
    private record ReplayStart(GoTo.Reference reference,long measuredStartMs) {}
    private record StopCheck(long elapsedMs,long completedAtMs,String failure) {
        boolean stopped() { return failure==null; }
        String report() {
            return stopped() ? "Both motors reported stopped after "+elapsedMs+" ms." :
                "Motor stop check failed after "+elapsedMs+" ms. "+failure;
        }
    }
    private record HsTiming(GoTo.PanTiming timing,String issue) {
        GoTo.PanTiming require(String axis) throws IOException {
            if(timing==null) throw new IOException("HS "+axis+" setup unavailable. Tap Connect GTi. "+issue);
            return timing;
        }
    }

    private record MountConnection(InetSocketAddress endpoint,DeviceDiscovery.Link link,GoTo.MountSetup setup,
                                   HsTiming pan,HsTiming tilt) {
        GoTo client(GoTo.Trace trace) { return new GoTo(link::udp,endpoint,trace); }
        GoTo.PanTiming requireHsTiming() throws IOException { return pan.require("pan"); }
    }

    interface Listener { void changed(); }
    final class Connection extends Binder { FmService service() { return FmService.this; } }
    @Override public IBinder onBind(Intent intent) { return binder; }

    @Override public void onCreate() {
        super.onCreate();
        prefs=getSharedPreferences("fm",MODE_PRIVATE);
        network=getSystemService(ConnectivityManager.class);
        gdl=new GdlWifi(network,main,this::gdlChanged);
        wake=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"FM:ConnectedDevices");
        NotificationChannel channel=new NotificationChannel(CHANNEL,"FM connection",NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if(intent!=null && STOP.equals(intent.getAction())) {
            requestStopFm();
            return START_NOT_STICKY;
        }
        if(stopWhenIdle || destroyed) return START_NOT_STICKY;
        if(!foreground) {
            if(Build.VERSION.SDK_INT>=29)
                startForeground(NOTIFICATION,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            else startForeground(NOTIFICATION,notification());
            foreground=true;
            wake.acquire();
            begin("Loading FM settings");
            worker.execute(this::startMonitoring);
        }
        return START_NOT_STICKY;
    }

    private void startMonitoring() {
        String[] values=SettingsFile.cached(prefs);
        String settingsStatus,settingsLocation=null;
        prefs.edit().remove(LAST_CALIBRATION_ERROR).apply();
        try {
            SettingsFile.Loaded stored=SettingsFile.read(this,prefs);
            if(stored!=null) {
                SettingsFile.Snapshot snapshot=stored.snapshot();
                values=snapshot.values();
                byte[] contents=stored.contents();
                if(calibrationSession.reconcile(values)) contents=SettingsFile.write(this,prefs,snapshot);
                recordSettingsLoad(snapshot,contents);
                settingsLocation=stored.location();
                settingsStatus="FM active · Settings loaded from "+settingsLocation;
                if(!stored.warning().isEmpty()) settingsStatus+="\n"+stored.warning();
                if(!SettingsFile.LOCATION.equals(settingsLocation))
                    settingsStatus+="\nUse Link FM settings folder to load "+SettingsFile.LOCATION+".";
            } else {
                calibrationSession.reconcile(values);
                SettingsFile.Snapshot created=new SettingsFile.Snapshot(values,"");
                recordSettingsLoad(created,SettingsFile.write(this,prefs,created));
                settingsLocation=SettingsFile.LOCATION;
                settingsStatus="FM active · Settings created at "+settingsLocation;
            }
        } catch(Exception e) { settingsStatus="Settings file unavailable: "+e.getMessage()+" · Using cached settings"; }
        calibrationSession.reconcile(values);
        SettingsFile.cache(prefs,values);
        apply(values);
        configureCamera(values);
        settingsLoaded=true;
        main.post(this::publish);
        settingsWatcher.scheduleWithFixedDelay(this::checkSettingsFile,1,2,TimeUnit.SECONDS);

        // Publishing a startup record makes the public folder available before any attempt.
        String startup=settingsStatus+"\n"+publishStartupLog(settingsStatus,settingsLocation,values);
        if(!motionAllowed()) return;
        updateResult("Finding GTi on local Wi-Fi");
        String mountStatus=connectMount(true);
        if(destroyed) return;
        worker.scheduleWithFixedDelay(this::readGps,0,75,TimeUnit.MILLISECONDS);
        finish(mountStatus+"\n"+startup);
    }

    private String publishStartupLog(String settingsStatus,String settingsLocation,String[] values) {
        String id=UUID.randomUUID().toString().substring(0,12);
        try {
            Trace log=new Trace(this,"FM-session-"+id+".jsonl",id);
            try(log) {
                log.event("service_started","wallTimeMs",System.currentTimeMillis(),
                    "build",BuildConfig.VERSION_NAME,"settingsStatus",settingsStatus,"logsDirectory",AttemptDownload.DIRECTORY,
                    "settingsLocation",settingsLocation,"canonZoomScales",values[CameraSettings.SCALES],
                    "manualCamera",Boolean.parseBoolean(values[MANUAL_CAMERA]),"mountCoordinates",MountCoordinates.format(values));
            }
            if(log.downloadError!=null)
                return "Could not create "+AttemptDownload.DIRECTORY+": "+log.downloadError;
            return "Logs: "+AttemptDownload.DIRECTORY;
        } catch(IOException e) {
            return "Could not save the FM startup log: "+e.getMessage();
        }
    }

    private Notification notification() {
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,FmService.class).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        String summary=stopWhenIdle?resultText():busy?resultText:"Receiving GPS · Tap to open FM";
        return new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("FM is running").setContentText(summary.split("\n",2)[0])
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(new Notification.Action.Builder(null,"Stop FM",stop).build()).build();
    }
    private void refreshNotification() {
        if(foreground) getSystemService(NotificationManager.class).notify(NOTIFICATION,notification());
    }
    boolean stopping() { return stopWhenIdle; }
    private boolean motionAllowed() { return !destroyed && !stopWhenIdle; }

    void requestStopFm() {
        if(stopWhenIdle || destroyed) return;
        stopWhenIdle=true;
        boolean tracking=liveGps!=null;
        if(tracking) stopLiveTracking();
        hsCancelled=true;
        cancelLandmarkCapture();
        if(camera!=null) camera.cancelOneShotZoom();
        resultText="Waiting for full stop";
        publish();
        refreshNotification();
        worker.execute(()->finishShutdown(tracking));
    }

    private GoTo shutdownClient(MountConnection mount,GoTo.Trace trace) {
        // Resolve the Wi-Fi route for each retry so reconnecting can confirm a lost Stop reply.
        return new GoTo(()->{
            try { return mount.link().udp(); }
            catch(IOException unavailable) { return deviceLink(mount.endpoint().getHostString()).udp(); }
        },mount.endpoint(),trace,
            LiveTracking.RETAINED_REPLY_PORTS);
    }

    private void finishShutdown(boolean tracking) {
        MountConnection mount=lastMovingMount!=null?lastMovingMount:gtiConnection;
        if(mount!=null && !(tracking && liveStopConfirmed)) {
            String id=UUID.randomUUID().toString().substring(0,12);
            Trace log=null;
            try { log=new Trace(this,"FM-stop-"+id+".jsonl",id); }
            catch(IOException error) { android.util.Log.w("FM","Stop log unavailable",error); }
            try {
                GoTo.Trace trace=log==null?(name,fields)->android.util.Log.d("FM",name):log;
                if(!LiveTracking.awaitFullStop(shutdownClient(mount,trace),()->!destroyed)) return;
            } catch(InterruptedException e) {
                Thread.currentThread().interrupt();
                if(!destroyed) worker.execute(()->finishShutdown(false));
                return;
            } finally {
                if(log!=null) try {log.close();} catch(IOException e){android.util.Log.w("FM","Stop log",e);}
            }
        }
        main.post(()->{
            if(destroyed) return;
            Runnable stopped=()->{
                resultText=mount==null?"FM stopped · GTi disconnected":"FM stopped · Both motors stopped";
                stopMonitoring();
            };
            CameraZoomReader reader=camera;
            if(reader==null) stopped.run();
            else reader.close(()->main.post(stopped));
        });
    }

    private void stopMonitoring() {
        foreground=false;
        destroyed=true;
        gtiConnection=null;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
        publish();
    }
    @Override public void onDestroy() {
        destroyed=true;
        gdl.release();
        settingsWatcher.shutdown(); // Finish any accepted file save.
        cancelLandmarkCapture();
        cameraSession=-1;
        CameraZoomReader closingCamera=camera;
        if(closingCamera!=null) closingCamera.close();
        gtiConnection=null;
        stopGpsSimulator();
        worker.execute(()->{
            drainGpsPadEvents(null);
            resetGps();
        });
        worker.shutdown();
        surferFiles.shutdown();
        if(wake.isHeld()) wake.release();
        listener=null;
        super.onDestroy();
    }
    void attach(Listener next) { listener=next; publish(); }
    void detach(Listener previous) { if(listener==previous) listener=null; }
    private void publish() { if(listener!=null) listener.changed(); }
    String gpsText() { return gpsText; }
    String liveGpsRuleText() { return liveGpsRuleText; }
    private volatile boolean gtiExchangeFailed;

    ConnectionFlag connectionFlag(ConnectionFlag.Channel channel) {
        if(channel==ConnectionFlag.Channel.GDL) return gdl.flag();
        if(channel==ConnectionFlag.Channel.CALIBRATION) {
            String why=calibrationSession.reason();
            return why==null?ConnectionFlag.good("Calibrated"):ConnectionFlag.failed("Required · "+why);
        }
        String[] saved=settings;
        if(saved==null) return ConnectionFlag.pending("Starting");
        if(channel==ConnectionFlag.Channel.GPS) {
            GpsPadSimulator simulator=gpsPad;
            MapGps fix=mapGps;
            if(fix!=null && !Objects.equals(fix.simulatorId(),simulator==null?null:simulator.id)) fix=null;
            try {
                return ConnectionFlag.gps(GoTo.now(),lastReceiverMs,fix==null?-1:fix.at(),
                    fix==null?-1:fix.packet(),number(saved,3),simulator!=null);
            } catch(IllegalArgumentException invalid) { return ConnectionFlag.failed("Invalid GPS age setting"); }
        }
        if(channel==ConnectionFlag.Channel.GTI) {
            if(gtiConnection==null) return ConnectionFlag.failed("Disconnected");
            return gtiExchangeFailed?ConnectionFlag.failed("Last exchange failed"):ConnectionFlag.good("Last reply OK");
        }
        if(manualCamera()) return ConnectionFlag.pending("Manual camera");
        CameraZoomReader reader=camera;
        if(reader==null) return ConnectionFlag.pending("Starting");
        CameraFlags flags=reader.flags;
        return switch(channel) {
            case CANON -> flags.link;
            case RECORDING -> flags.recording;
            case EVENTS -> flags.events;
            case ZOOM -> flags.zoom;
            default -> throw new AssertionError(channel);
        };
    }

    String gtiStatus() {
        MountConnection mounted=gtiConnection;
        return mounted==null ? "GTi disconnected" :
            "GTi connected · "+mounted.endpoint().getHostString()+":"+mounted.endpoint().getPort();
    }
    String resultText() {
        if(stopWhenIdle && !destroyed) return "Waiting for full stop";
        if(liveGps!=null && liveStopRequested>=0) return liveStopConfirmed?"Both motors stopped":"Waiting for full stop";
        return resultText;
    }
    List<ReplayRowReport> replayReports() { return Collections.unmodifiableList(replayReports); }
    String replayMotionText() { return replayMotionText; }
    Long replayDurationMs() { return replayDurationMs; }
    Long replayFirstStartMs() { return replayFirstStartMs; }
    HsReport hsReport() { return hsReport; }
    boolean canCancelHs() { return hsCanCancel&&!hsCancelled; }
    void cancelHs() {
        if(!canCancelHs()) return;
        hsCancelled=true;
        publish();
    }
    boolean canStopLiveTracking() { return liveGps!=null&&liveStopRequested<0; }
    double liveOffsetM() { return liveGps==null?0:liveGps.offsetM(); }

    boolean gpsSimulatorActive() { return gpsPad!=null; }

    SurferModes.Mode continuousSurferMode() {
        GpsPadSimulator selected=gpsPad;
        return selected==null?null:selected.mode();
    }

    void selectContinuousSurferMode(SurferModes.Mode mode) {
        GpsPadSimulator selected=gpsPad;
        if(selected==null||(!ready()&&!canStopLiveTracking())) return;
        long selectedAt=GoTo.now();
        String[] saved=SettingsFile.cached(prefs);
        double bearing=mode==null?90:surferBearing(saved);
        long limitMs=mode==null||mode==SurferModes.Mode.SITTING?0:
            SurferModes.continuousDurationMs(prefs.getString(mode.durationPreference(),SurferModes.DEFAULT_CONTINUOUS_SECONDS));
        if(selected.selectMode(mode,bearing,selectedAt,limitMs)&&mode==null)
            queueGpsPadEvent("gps_simulator_mode_cleared",selected);
        publish();
    }

    private static double surferBearing(String[] saved) {
        try {
            return Geo.wrap(Geo.bearing(number(saved,MountCoordinates.LATITUDE),
                number(saved,MountCoordinates.LONGITUDE),number(saved,LandmarkCalibration.LANDMARK_LAT),
                number(saved,LandmarkCalibration.LANDMARK_LON))+90);
        } catch(IllegalArgumentException unavailable) {
            return 90;
        }
    }

    boolean preparingSurferFixes() { return preparingSurferFixes; }
    boolean canApplySurferModes() { return gpsPad!=null && preparedSurferFixes!=null && (ready()||canStopLiveTracking()); }
    String surferFileStatus() { return surferFileStatus; }
    String surferPlaybackStatus() {
        GpsPadSimulator source=gpsPad;
        return source==null?"":source.playbackStatus();
    }

    void invalidateSurferPreparation() {
        if(preparedSurferFixes==null) return;
        preparedSurferFixes=null;
        surferFileStatus="Modes changed. Save or load the file before Apply.";
        publish();
    }

    enum SimulatorFile { SAVE_CSV, LOAD_CSV, LOAD_KML }

    int simulatorRateHz() { return prefs.getInt(GpsPadSimulator.RATE_PREF,GpsPadSimulator.DEFAULT_RATE_HZ); }

    void setSimulatorRate(int hz) {
        GpsPadSimulator selected=gpsPad;
        if(selected==null || hz==simulatorRateHz()) return;
        selected.setRateHz(hz);
        prefs.edit().putInt(GpsPadSimulator.RATE_PREF,hz).apply();
        queueGpsPadEvent("gps_simulator_rate_changed",selected,"rawFixesPerSecond",hz);
        publish();
    }

    void prepareSurferModes(List<SurferModes.Mode> modes,int rideSeconds,SimulatorFile action,Uri file,String name) {
        if(gpsPad==null||preparingSurferFixes||(!ready()&&!canStopLiveTracking())) return;
        preparingSurferFixes=true;
        preparedSurferFixes=null;
        boolean load=action!=SimulatorFile.SAVE_CSV;
        String source=action==SimulatorFile.LOAD_KML?name:SurferFixFile.LOCATION;
        int hz=simulatorRateHz();
        surferFileStatus=(load?"Loading ":"Saving ")+source;
        publish();
        String[] saved=SettingsFile.cached(prefs);
        surferFiles.execute(()->{
            try {
                List<GoTo.Point> fixes;
                if(action==SimulatorFile.LOAD_KML) {
                    try(InputStream input=getContentResolver().openInputStream(file)) {
                        if(input==null) throw new IOException("Could not open "+source);
                        fixes=LoRaTesterKml.read(input);
                    }
                } else if(load) fixes=SurferFixFile.load(this,prefs);
                else {
                    var landmark=new GoTo.Point(number(saved,LandmarkCalibration.LANDMARK_LAT),
                        number(saved,LandmarkCalibration.LANDMARK_LON),Double.NaN);
                    if(!Geo.valid(landmark.lat(),landmark.lon())) throw new IOException("Save valid landmark coordinates");
                    fixes=SurferModes.generate(landmark,surferBearing(saved),modes,rideSeconds,hz);
                    SurferFixFile.save(this,prefs,fixes);
                    if(modes.contains(SurferModes.Mode.RIDING))
                        prefs.edit().putInt(SurferModes.RIDE_SECONDS_PREF,rideSeconds).apply();
                }
                main.post(()->finishSurferPreparation(fixes,source,(load?"Loaded ":"Saved ")+source+" · "+fixes.size()+" fixes ready"));
            } catch(IOException|RuntimeException failure) {
                main.post(()->finishSurferPreparation(null,source,(load?"Could not load":"Could not save")+" · "+failure.getMessage()));
            }
        });
    }

    private void finishSurferPreparation(List<GoTo.Point> fixes,String source,String status) {
        if(destroyed) return;
        preparedSurferFixes=fixes;
        preparedFixFile=source;
        preparingSurferFixes=false;
        surferFileStatus=status;
        publish();
    }

    void applySurferModes() {
        long pressedAt=GoTo.now();
        if(!canApplySurferModes()) return;
        GpsPadSimulator selected=gpsPad;
        var playback=selected.apply(preparedSurferFixes,pressedAt,canStopLiveTracking());
        queueGpsPadEvent("gps_simulator_apply",selected,"playback",playback.number(),"pressMs",pressedAt,
            "fixCount",playback.fixes().size(),"file",preparedFixFile,"measurePan",playback.measurePan(),"rawFixesPerSecond",simulatorRateHz(),
            "scheduledDurationMs",(playback.fixes().size()-1L)*1000/simulatorRateHz());
        publish();
    }

    void toggleGpsSimulator() {
        if(!ready()&&!canStopLiveTracking()) return;
        if(gpsPad==null) {
            String[] saved=SettingsFile.cached(prefs);
            try {
                double lat=number(saved,LandmarkCalibration.LANDMARK_LAT);
                double lon=number(saved,LandmarkCalibration.LANDMARK_LON);
                GpsPadSimulator simulator=new GpsPadSimulator(lat,lon);
                simulator.setRateHz(simulatorRateHz());
                queueGpsPadEvent("gps_simulator_started",simulator,"latitude",lat,"longitude",lon,"rawFixesPerSecond",simulatorRateHz(),
                    "build",BuildConfig.VERSION_NAME,"wallTimeMs",System.currentTimeMillis());
                gpsPad=simulator;
            } catch(IllegalArgumentException e) {
                updateResult("Save the landmark coordinates before starting GP Simulator");
                return;
            }
        } else stopGpsSimulator();
        gpsText=gpsPad==null?"Waiting for LoRa GPS":"Waiting for the first simulated GPS fix";
        liveGpsRuleText="";
        publish();
    }

    void moveGpsSimulator(GpsPadSimulator.Move move,double metres) {
        GpsPadSimulator selected=gpsPad;
        if(selected==null) return;
        GpsPadSimulator.Tap tap=selected.move(move,metres);
        queueGpsPadEvent("gps_simulator_tap",selected,"tap",tap.number(),"direction",move.name(),
            "offsetM",metres,"latitude",tap.position().lat(),"longitude",tap.position().lon());
    }

    private void stopGpsSimulator() {
        GpsPadSimulator selected=gpsPad;
        if(selected==null) return;
        queueGpsPadEvent("gps_simulator_stopped",selected);
        gpsPad=null;
    }

    private void queueGpsPadEvent(String event,GpsPadSimulator simulator,Object... data) {
        Object[] fields=Arrays.copyOf(data,data.length+2);
        fields[data.length]="simulatorRun";
        fields[data.length+1]=simulator.id;
        gpsPadEvents.add(new GpsPadEvent(event,GoTo.now(),simulator.id,fields));
    }

    private void drainGpsPadEvents(Trace liveLog) {
        for(GpsPadEvent event;(event=gpsPadEvents.poll())!=null;) {
            try {
                if(event.name().equals("gps_simulator_started"))
                    gpsPadLog=new Trace(this,"FM-simulator-"+event.simulatorId()+".jsonl",event.simulatorId());
                event.write(gpsPadLog);
                if(event.name().equals("gps_simulator_stopped")&&gpsPadLog!=null) {
                    Trace completed=gpsPadLog;
                    gpsPadLog=null;
                    completed.close();
                    if(completed.downloadError!=null) updateResult("GP Simulator log · "+completed.downloadError);
                }
            } catch(IOException|RuntimeException failure) {
                updateResult("GP Simulator log · "+failure.getMessage());
            }
            event.write(liveLog);
        }
    }

    void offsetSurferPosition(double stepM) {
        if(!canStopLiveTracking()||gpsPad!=null) return;
        liveGps.advance(GoTo.now(),stepM);
        publish();
    }

    void stopLiveTracking() {
        if(!canStopLiveTracking()) return;
        liveStopRequested=GoTo.now();
        endCameraSession();
        resultText="Waiting for full stop";
        publish();
        refreshNotification();
    }

    boolean calibrating() { return calibrating; }
    String calibrationError() { return prefs.getString(LAST_CALIBRATION_ERROR,""); }
    boolean stopped() { return destroyed; }
    boolean ready() { return foreground&&motionAllowed()&&!busy&&!linkingSettings; }
    boolean settingsLoaded() { return settingsLoaded; }
    boolean linkingSettingsFolder() { return linkingSettings; }
    boolean settingsEditable() {
        return foreground && motionAllowed() && SettingsAutosave.editable(settingsLoaded(),liveGps!=null,liveStopConfirmed);
    }
    private void begin(String message) { busy=true; resultText=message; publish(); refreshNotification(); }
    private void finish(String message) { finish(message,null); }

    private void finish(String message,String calibrationFailure) {
        main.post(()->{
            resultText=message; busy=false;
            if(calibrationFailure!=null) {
                calibrating=false;
                prefs.edit().putString(LAST_CALIBRATION_ERROR,calibrationFailure).apply();
            }
            discoverGdlIfReady();
            publish(); refreshNotification();
        });
    }
    private void reportReplayRow(ReplayRowReport row) {
        main.post(()->{
            replayReports.add(row);
            resultText=row.details();
            publish();
            refreshNotification();
        });
    }

    private void updateResult(String message) { main.post(()->{resultText=message;publish();refreshNotification();}); }
    private void publishGps(String value,GpsPadSimulator source) {
        main.post(()->{
            if(gpsPad!=source) return;
            gpsText=value;
            publish();
        });
    }

    void linkSettingsFolder(Uri tree,int flags,Runnable refreshForm) {
        if(!settingsEditable() || linkingSettings) return;
        linkingSettings=true;
        setSettingsFileStatus("Reading "+SettingsFile.LOCATION);
        settingsWatcher.execute(()->{
            try {
                synchronized(settingsIo) {
                    SettingsFile.Loaded loaded=SettingsFile.linkFolder(this,prefs,tree,flags);
                    SettingsFile.Snapshot snapshot=loaded.snapshot();
                    String[] values=snapshot.values();
                    byte[] contents=loaded.contents();
                    if(calibrationSession.reconcile(values)) contents=SettingsFile.write(this,prefs,snapshot);
                    SettingsFile.cache(prefs,values);
                    configureCamera(values);
                    settingsRevision++;
                    applySavedSettingsWhenIdle();
                    recordSettingsLoad(snapshot,contents);
                    String status="Loaded "+SettingsFile.LOCATION;
                    if(!loaded.warning().isEmpty()) status+="\n"+loaded.warning();
                    String coordinates=MountCoordinates.format(values);
                    if(!coordinates.isEmpty()) status+="\nMount coordinates · "+coordinates;
                    if(!LandmarkCalibration.hasReference(values)) {
                        prefs.edit().remove(LAST_CALIBRATION_ERROR).apply();
                        status+="\nCentre the landmark and tap Calibrate here.";
                    }
                    setSettingsFileStatus(status);
                }
            } catch(Exception e) {
                setSettingsFileStatus("Could not link settings folder: "+e.getMessage());
            } finally {
                main.post(()->{
                    linkingSettings=false;
                    refreshForm.run();
                    discoverGdlIfReady();
                    publish();
                });
            }
        });
    }

    void releaseOldReplayFile(String previous) {
        // An active replay keeps its grant until the mount worker finishes reading it.
        worker.execute(()->main.post(()->{
            if(previous.equals(prefs.getString(REPLAY_URI,""))) return;
            try { getContentResolver().releasePersistableUriPermission(Uri.parse(previous),Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch(SecurityException ignored) { }
        }));
    }

    void saveEdit(SettingsAutosave.Edit edit, Consumer<String> completed) {
        if(!settingsEditable()) {
            completed.accept("Settings are unavailable");
            return;
        }
        settingsWatcher.execute(()->{
            String error=null;
            try {
                synchronized(settingsIo) {
                    SettingsFile.Snapshot disk=readCurrentSettings();
                    String[] values=disk.values().clone();
                    edit.apply(values);
                    LandmarkCalibration.clearStaleReference(disk.values(),values);
                    storeSettings(disk.withValues(values),false);
                    applySavedSettingsWhenIdle();
                }
            } catch(Exception e) {
                error=Objects.toString(e.getMessage(),e.getClass().getSimpleName());
            }
            String result=error;
            main.post(()->completed.accept(result));
        });
    }

    void connectGti() {
        if(!ready()) return;
        begin("Connecting to GTi");
        worker.execute(()->finish(connectMount(false)));
    }

    private String connectMount(boolean automatic) {
        gtiConnection=null;
        String id=UUID.randomUUID().toString().substring(0,12);
        String name="FM-"+id+".jsonl";
        String message;
        Trace trace=null;
        try(Trace log=new Trace(this,name,id)) {
            trace=log;
            prefs.edit().putString("lastLog",name).apply();
            log.event("gti_connect_attempt","wallTimeMs",System.currentTimeMillis(),"host",settings[4],"port",settings[5],
                "trigger",automatic?"startup":"button");
            try {
                DeviceDiscovery.Link link=deviceLink(settings[4]);
                DeviceDiscovery.Device discovered=automatic?
                    DeviceDiscovery.findStartupMount(link,settings[4],port(5),this::motionAllowed):null;
                if(!motionAllowed()) throw new IOException("FM stopped before connecting to GTi");
                SettingsFile.requireUnchanged(settings,SettingsFile.cached(prefs),4,5);
                if(discovered!=null)
                    log.event("gti_startup_discovery","address",discovered.address(),"identity",discovered.identity());
                String[] candidate=discovered!=null&&!settings[4].equals(discovered.address())?settings.clone():null;
                if(candidate!=null) candidate[4]=discovered.address();
                String[] selectedEndpoint=candidate==null?settings:candidate;
                InetSocketAddress target=discovered==null?endpoint(4,5):
                    new InetSocketAddress(InetAddress.getByName(discovered.address()),port(5));
                GoTo client=new GoTo(link::udp,target,log);
                GoTo.MountSetup setup=client.connect(()->{
                    rememberDiscoveredEndpoint(candidate,log);
                    invalidateReferenceForInitialization(settings,log);
                });
                rememberDiscoveredEndpoint(candidate,log);
                HsTiming pan=readHsTiming(client,1,log);
                HsTiming tilt=readHsTiming(client,2,log);
                if(destroyed) throw new IOException("FM stopped while connecting to GTi");
                SettingsFile.requireUnchanged(selectedEndpoint,SettingsFile.cached(prefs),4,5);
                gtiExchangeFailed=false;
                gtiConnection=new MountConnection(target,link,setup,pan,tilt);
                log.event("gti_connected","panCpr",setup.panCpr(),"tiltCpr",setup.tiltCpr(),
                    "panFirmware",setup.panFirmware(),"tiltFirmware",setup.tiltFirmware());
                message=LandmarkCalibration.hasReference(settings)?"GTi connected":
                    "GTi connected. Centre the landmark and tap Calibrate here.";
                if(pan.issue()!=null) message+="\nHS pan setup unavailable · "+pan.issue();
                if(tilt.issue()!=null) message+="\nHS tilt setup unavailable · "+tilt.issue();
            } catch(Exception failure) {
                if(failure instanceof InterruptedException) Thread.currentThread().interrupt();
                log.event("gti_connect_failed","reason",failure.toString());
                message="GTi connection failed: "+Objects.toString(failure.getMessage(),failure.getClass().getSimpleName());
            }
            message+="\nAttempt "+id+" · "+log.location();
            log.event("gti_connect_result","display",message);
        } catch(IOException failure) {
            message=gtiStatus()+" · Connection log unavailable: "+failure.getMessage();
        }
        return message+(trace==null?"":trace.downloadWarning());
    }

    private static HsTiming readHsTiming(GoTo client,int axis,Trace log) {
        String name=axis==1?"pan":"tilt";
        try {
            GoTo.PanTiming timing=client.readHighSpeedTiming(axis);
            log.event("hs_"+name+"_timing_cached","axis",axis,"timerHz",timing.frequency(),"highSpeedRatio",timing.ratio());
            return new HsTiming(timing,null);
        } catch(IOException failure) {
            log.event("hs_"+name+"_setup_issue","axis",axis,"reason",failure.getMessage());
            return new HsTiming(null,failure.getMessage());
        }
    }

    private void rememberDiscoveredEndpoint(String[] candidate,Trace log) throws IOException {
        if(candidate==null||candidate[4].equals(settings[4])) return;
        boolean cleared=LandmarkCalibration.clearStaleReference(settings,candidate);
        try { persistSettings(candidate,4,5); }
        catch(Exception failure) { throw new IOException("Could not save discovered GTi address "+candidate[4]+": "+
            failure.getMessage(),failure); }
        log.event("gti_discovered_endpoint_saved","address",candidate[4],"referenceCleared",cleared);
    }

    void discover(boolean mount,String[] values,java.util.function.Consumer<List<DeviceDiscovery.Device>> done) {
        if(!ready()) return;
        if(!mount) {
            if(camera==null || manualCamera()) return;
            begin("Discovering Canon SX");
            camera.execute(()->{
                try {
                    CameraSettings requested=new CameraSettings(values);
                    List<DeviceDiscovery.Device> found=DeviceDiscovery.find(network,gdl.route(),false,requested.cameraHost,requested.cameraPort,()->!manualCamera() && motionAllowed());
                    finish(found.isEmpty()?"No camera found":"Select a discovered camera");
                    main.post(()->done.accept(found));
                } catch(Exception failure) { finish("Camera discovery: "+failure.getMessage()); }
            });
            return;
        }
        begin("Discovering GTi");
        worker.execute(()->{
            String[] before=settings;
            try {
                settings=values;
                int host=mount?4:6;
                List<DeviceDiscovery.Device> found=DeviceDiscovery.find(network,gdl.route(),mount,values[host],port(host+1),this::motionAllowed);
                finish(found.isEmpty()?"No device found":"Select a discovered device");
                main.post(()->done.accept(found));
            } catch(Exception e) { finish("Discovery: "+e.getMessage()); }
            finally { settings=before; }
        });
    }

    void captureLandmarkGps(long press,long wallTime,Consumer<LandmarkGpsCapture.Result> done) {
        if(!ready()) return;
        LandmarkGpsCapture capture=new LandmarkGpsCapture(press);
        landmarkCapture=capture;
        captureProgress="Waiting for fresh LoRa GPS · 0/10";
        String moveReport=resultText;
        begin("Capturing landmark from LoRa");
        worker.execute(()->{
            String id=UUID.randomUUID().toString().substring(0,12);
            String name="FM-landmark-"+id+".jsonl";
            Trace trace=null;
            String warning="";
            try {
                trace=new Trace(this,name,id);
                prefs.edit().putString("lastLog",name).apply();
            } catch(IOException|RuntimeException error) {
                warning="\nCapture log unavailable · "+error.getMessage();
            }
            GoTo.Trace log=trace!=null?trace:(event,fields)->android.util.Log.w("FM",event+" "+Arrays.toString(fields));
            boolean simulator=gpsPad!=null;
            log.event("landmark_capture_pressed","pressMs",press,"wallTimeMs",wallTime,"build",BuildConfig.VERSION_NAME,
                "gpsSource",simulator?"Simulator":"LoRa","maxGpsAgeMs",settings[3],
                "previousCoordinates",MountCoordinates.format(settings,LandmarkCalibration.LANDMARK_LAT,LandmarkCalibration.LANDMARK_LON));
            LandmarkGpsCapture.Result result;
            if(simulator) {
                String why="Select Use LoRa GPS to capture the landmark";
                log.event("landmark_capture_result","outcome","blocked","reason",why);
                result=new LandmarkGpsCapture.Result(null,why);
            } else {
                selectGpsSource(null);
                result=capture.run(settings,collect->readReceiverGps(GoTo.now(),packet->{
                    log.event("landmark_receiver_packet","line",packet.line(),"receivedMs",packet.receivedAt(),
                        "accepted",packet.rejection()==null,"reason",packet.rejection());
                    if(packet.rejection()==null) collect.accept(new LandmarkGpsCapture.Reading(packet.fix(),packet.receivedAt()));
                }),GoTo::now,()->Thread.sleep(100),next->persistSettings(next,
                    LandmarkCalibration.LANDMARK_LAT,LandmarkCalibration.LANDMARK_LON),status->{
                    captureProgress=status;
                    main.post(this::publish);
                },log);
            }
            if(trace!=null) {
                try {
                    trace.close();
                    warning="\nLog · "+trace.location()+trace.downloadWarning();
                } catch(IOException|RuntimeException error) {
                    warning="\nCould not publish capture log · "+error.getMessage();
                }
            }
            LandmarkGpsCapture.Result display=new LandmarkGpsCapture.Result(result.coordinates(),result.message()+warning);
            main.post(()->{
                landmarkCapture=null;
                captureProgress=display.message();
                done.accept(display);
            });
            finish(moveReport);
        });
    }

    void calibrateHere(String[] values,String mountInput,String landmarkInput,boolean confirmed) {
        if(!ready()) return;
        calibrating=true;
        begin("Calibrating at landmark");
        worker.execute(()->{
            String id=UUID.randomUUID().toString().substring(0,12);
            String name="FM-"+id+".jsonl";
            String display;
            String failure="";
            boolean saved=false;
            Trace trace=null;
            try(Trace log=new Trace(this,name,id)) {
                trace=log;
                prefs.edit().putString("lastLog",name).apply();
                log.event("calibration_attempt","wallTimeMs",System.currentTimeMillis(),"build",BuildConfig.VERSION_NAME,
                    "settings",new JSONArray(Arrays.asList(values)));
                try {
                    if(!confirmed) throw new IllegalArgumentException("Confirm the mount setup and axis directions above Calibrate here");
                    MountCoordinates.apply(mountInput,values);
                    MountCoordinates.apply(landmarkInput,values,LandmarkCalibration.LANDMARK_LAT,
                        LandmarkCalibration.LANDMARK_LON,LandmarkCalibration.COORDINATES);
                    if(values[MountCoordinates.LATITUDE].isEmpty())
                        throw new IllegalArgumentException("Paste the Mount coordinates pair in Settings, then tap Calibrate here");
                    if(values[LandmarkCalibration.LANDMARK_LAT].isEmpty())
                        throw new IllegalArgumentException("Enter Landmark coordinates, then tap Calibrate here");
                    log.event("calibration_inputs","settings",new JSONArray(Arrays.asList(values)));
                    LandmarkCalibration calibration=captureLandmark(values,log);
                    synchronized(settingsIo) {
                        SettingsFile.Snapshot disk=readCurrentSettings();
                        if(!LandmarkCalibration.basis(values).equals(LandmarkCalibration.basis(disk.values())))
                            throw new IOException("Mount or landmark changed during calibration");
                        String[] merged=SettingsFile.mergeEdit(settings,values,disk.values());
                        if(!LandmarkCalibration.basis(values).equals(LandmarkCalibration.basis(merged))
                            || !LandmarkCalibration.hasReference(merged))
                            throw new IOException("Mount or landmark changed during calibration");
                        storeSettings(disk.withValues(merged),true);
                        apply(merged);
                    }
                    mapPan=new MapPan(new LiveTrackingKml.CameraHeading(calibration.bearing(),Integer.parseInt(values[14]),GoTo.now()),"Stopped");
                    saved=true;
                    log.event("calibration_saved","bearing",calibration.bearing(),"elevation",calibration.elevation(),
                        "panCount",values[LandmarkCalibration.PAN_COUNT],"tiltCount",values[LandmarkCalibration.TILT_COUNT]);
                    display="Landmark calibrated and saved · "+LandmarkCalibration.describe(values);
                } catch(Exception e) {
                    log.event("calibration_failed","reason",e.toString());
                    failure=Objects.toString(e.getMessage(),e.getClass().getSimpleName());
                    display="Calibration failed: "+failure;
                }
                display+="\nAttempt "+id+" · "+log.location();
                log.event("calibration_result","display",display);
            } catch(IOException e) {
                String logIssue="Calibration log unavailable: "+e.getMessage();
                if(!saved && failure.isEmpty()) failure=logIssue;
                display=saved ? "Landmark calibrated and saved\n"+logIssue :
                    "Calibration failed: "+failure;
            }
            finish(display+(trace==null?"":trace.downloadWarning()),failure);
        });
    }

    private LandmarkCalibration captureLandmark(String[] values,Trace log) throws IOException {
        String[] before=settings;
        settings=values;
        try {
            GoTo.Point pivot=point(MountCoordinates.LATITUDE,MountCoordinates.LONGITUDE,LandmarkCalibration.PIVOT_ELEVATION);
            GoTo.Point landmark=point(LandmarkCalibration.LANDMARK_LAT,LandmarkCalibration.LANDMARK_LON,
                LandmarkCalibration.LANDMARK_HEIGHT);
            LandmarkCalibration calibration=LandmarkCalibration.calculate(pivot,landmark);
            if(Math.abs(number(16))!=1||Math.abs(number(17))!=1)
                throw new IllegalArgumentException("Set the checked pan and tilt signs to 1 or -1");
            GoTo.Encoders encoders=connectedMount().client(log).captureReference();
            calibration.saveReference(values,encoders);
            return calibration;
        } finally { settings=before; }
    }

    private void invalidateReferenceForInitialization(String[] before,Trace log) throws IOException {
        calibrationSession.require("GTi axis needs initialization");
        if(!LandmarkCalibration.hasReference(before)) return;
        String[] cleared=before.clone();
        LandmarkCalibration.clearReference(cleared);
        try {
            persistSettings(cleared);
        } catch(Exception e) {
            throw new IOException("Could not clear the previous calibration before initializing the GTi",e);
        }
        System.arraycopy(cleared,0,before,0,cleared.length);
        log.event("reference_cleared","reason","GTi axis requires initialization");
    }

    private GoTo.Point point(int lat,int lon,int height) {
        return new GoTo.Point(number(lat),number(lon),number(height));
    }

    private void persistSettings(String[] values,int... protectedFields) throws Exception {
        synchronized(settingsIo) {
            SettingsFile.Snapshot disk=readCurrentSettings();
            SettingsFile.requireUnchanged(settings,disk.values(),protectedFields);
            String[] merged=SettingsFile.mergeEdit(settings,values,disk.values());
            storeSettings(disk.withValues(merged),false);
            apply(merged);
        }
    }

    private SettingsFile.Snapshot readCurrentSettings() throws Exception {
        byte[] bytes=SettingsFile.contents(this,prefs);
        String[] cached=SettingsFile.cached(prefs);
        return bytes==null?new SettingsFile.Snapshot(cached,""):
            SettingsFile.readSnapshot(SettingsFile.parseDocument(bytes).getJSONObject("settings"),cached);
    }

    private void storeSettings(SettingsFile.Snapshot snapshot,boolean captured) throws Exception {
        String[] values=snapshot.values();
        hsGapWarningM(values);
        validateLiveSettings(values);
        new CameraSettings(values);
        String why=captured?null:calibrationSession.reasonFor(values);
        if(why!=null) LandmarkCalibration.clearReference(values);
        byte[] contents=SettingsFile.write(this,prefs,snapshot);
        if(captured) calibrationSession.captured(values);
        else if(why!=null) calibrationSession.require(why);
        SettingsFile.cache(prefs,values);
        configureCamera(values);
        settingsRevision++;
        if(settingsFileStatus.startsWith("Settings file error")) setSettingsFileStatus("Saved "+SettingsFile.LOCATION);
        recordSettingsLoad(snapshot,contents);
    }

    private void applySavedSettingsWhenIdle() {
        // Only the mount worker may replace the settings used by its current operation.
        if(!motionAllowed()) return;
        try {
            worker.execute(()->{
                if(!motionAllowed()) return;
                apply(SettingsFile.cached(prefs));
                main.post(this::publish);
            });
        } catch(RejectedExecutionException stopped) {
            if(!destroyed) throw stopped;
        }
    }

    private void apply(String[] values) {
        if(settings==null || !Arrays.equals(Arrays.copyOf(settings,3),Arrays.copyOf(values,3))) resetGps();
        if(settings==null||!settings[4].equals(values[4])||!settings[5].equals(values[5])) gtiConnection=null;
        if(settings!=null && !LandmarkCalibration.basis(settings).equals(LandmarkCalibration.basis(values)))
            mapPan=new MapPan(null,"Awaiting encoder");
        if(settings!=null) {
            for(int key:new int[]{MountCoordinates.LATITUDE,MountCoordinates.LONGITUDE,3,
                LIVE_GPS_HOLD,LIVE_GPS_STATIONARY_MS,LIVE_PAN_LEAD}) {
                if(!settings[key].equals(values[key])) { idleSimulatorFilter=null; break; }
            }
        }
        settings=values;
        workerReferenceIssue=calibrationSession.reasonFor(values);
        landmarkRangeBlock=MountCoordinates.liveStartBlock(values);
    }

    private void checkSettingsFile() {
        if(destroyed || stopWhenIdle) return;
        try {
            synchronized(settingsIo) {
                byte[] bytes=SettingsFile.contents(this,prefs);
                if(bytes==null) throw new FileNotFoundException(SettingsFile.LOCATION);
                SettingsFile.LastLoad last=lastSettingsLoad;
                if(last!=null && last.matches(bytes) && !settingsFileStatus.startsWith("Settings file error")) return;
                String[] before=SettingsFile.cached(prefs);
                SettingsFile.Snapshot snapshot=SettingsFile.readSnapshot(SettingsFile.parseDocument(bytes).getJSONObject("settings"),before);
                String[] loaded=snapshot.values();
                if(calibrationSession.reconcile(loaded)) bytes=SettingsFile.write(this,prefs,snapshot);
                boolean changed=!Arrays.equals(before,loaded);
                if(changed) {
                    SettingsFile.cache(prefs,loaded);
                    configureCamera(loaded);
                    settingsRevision++;
                    applySavedSettingsWhenIdle();
                }
                recordSettingsLoad(snapshot,bytes);
                if(!changed && settingsFileStatus.isEmpty()) return;
            }
            setSettingsFileStatus("Reloaded "+SettingsFile.LOCATION);
        } catch(Exception error) {
            if(!destroyed) setSettingsFileStatus("Settings file error · "+error.getMessage());
        }
    }

    private void setSettingsFileStatus(String message) {
        if(settingsFileStatus.equals(message)) return;
        settingsFileStatus=message;
        main.post(()->{if(!destroyed) publish();});
    }

    private void configureCamera(String[] values) {
        try {
            CameraSettings next=new CameraSettings(values);
            CameraSettings current=cameraSettings;
            if(current==null||!current.cameraHost.equals(next.cameraHost)||current.cameraPort!=next.cameraPort||
                !current.values.equals(next.values)) cameraSettings=next;
        } catch(IllegalArgumentException failure) {
            android.util.Log.w("FM","Invalid Canon settings. Using defaults.",failure);
            cameraSettings=new CameraSettings(withCameraDisabled(DEFAULTS));
        }
        if(camera==null&&!destroyed)
            camera=new CameraZoomReader(()->cameraSettings,this::cameraNetwork,this::cameraInput,new CameraLog(this),
                ()->main.post(()->{if(!destroyed) publish();}));
    }

    private static String[] withCameraDisabled(String[] values) {
        String[] disabled=values.clone();
        disabled[6]="";
        return disabled;
    }

    private Network cameraNetwork() {
        CameraSettings current=cameraSettings;
        if(current==null||current.cameraHost.isEmpty()) return null;
        if(current.manual) return null;
        try { return deviceLink(current.cameraHost).network(); }
        catch(IOException|RuntimeException failure) { return null; }
    }

    private ZoomPlan.Input cameraInput() {
        long session=cameraSession;
        CameraPosition position=cameraPosition;
        if(position==null) return new ZoomPlan.Input(Double.NaN,0,false,session);
        long age=Math.max(0,GoTo.now()-position.freshAt());
        GoTo.Point mount=position.mount(), surfer=position.surfer();
        double distance=Geo.distance(mount.lat(),mount.lon(),surfer.lat(),surfer.lon());
        return new ZoomPlan.Input(distance,age,age<=position.maxAgeMs(),session);
    }

    private void endCameraSession() {
        long session=cameraSession;
        if(session<0) return;
        cameraSession=-1;
        cameraPosition=null;
        if(camera!=null) camera.stopRecording(session);
    }

    LiveMapFrame liveMapFrame() {
        String[] saved=settings;
        if(saved==null) return null;
        MapGps gps=mapGps;
        MapPan pan=mapPan;
        CameraSettings canon=cameraSettings;
        CameraZoomReader reader=camera;
        CameraZoomReader.MapZoom zoom=reader==null?null:reader.cachedMapZoom(canon);
        long now=GoTo.now();
        double ageLimit;
        try { ageLimit=number(saved,3); } catch(IllegalArgumentException ignored) { ageLimit=0; }
        return new LiveMapFrame(MountCoordinates.point(saved,MountCoordinates.LATITUDE),
            MountCoordinates.point(saved,LandmarkCalibration.LANDMARK_LAT),gps==null?null:gps.point(),
            gps!=null && now-gps.at()<=ageLimit,pan.heading()==null?null:pan.heading().bearingDeg(),
            pan.heading()!=null && now-pan.heading().sampledAt()<=ageLimit,liveGps!=null && liveStopRequested>=0?resultText():pan.state(),
            zoom==null?null:zoom.optical(),zoom!=null && zoom.fresh(),canon==null?Double.NaN:canon.wideFrameFactor);
    }

    boolean manualCamera() { return cameraSettings!=null && cameraSettings.manual; }
    String cameraStatus() { return manualCamera()?"Manual camera":camera==null?"Canon waiting for FM settings":camera.status(); }

    void cameraPreview(java.util.function.BiConsumer<android.graphics.Bitmap,String> result) {
        if(camera==null) result.accept(null,"Canon waiting for FM settings");
        else camera.requestPreview(this::cameraNetwork,(image,error)->main.post(()->result.accept(image,error)));
    }

    private void resetGps() {
        if(gps!=null) gps.close();
        lastReceiverMs=-1;
        mapGps=null;
        gps=null; latest=null; latestGpsTap=null; latestGpsMode=null;
        idleSimulatorFilter=null;
        tracker=new LoRaTelemetry.Tracker(); lastHello=-5000;
    }
    private boolean selectGpsSource(GpsPadSimulator source) {
        if(workerGpsPad==source) return false;
        resetGps();
        workerGpsPad=source;
        workerPlayback=null;
        lastGpsUi=GoTo.now()-300;
        return true;
    }

    private void readGps() {
        if(!motionAllowed()) return;
        drainGpsPadEvents(null);
        GpsPadSimulator source=gpsPad;
        selectGpsSource(source);
        readGps(source,source==null?null:this::processIdleSimulatorGps,null);
    }

    private void processIdleSimulatorGps(LiveGpsPacket raw) {
        if(raw.rejection()!=null) return;
        GoTo.Point mount;
        try {
            mount=new GoTo.Point(number(MountCoordinates.LATITUDE),number(MountCoordinates.LONGITUDE),0);
            if(!Geo.valid(mount.lat(),mount.lon())) return;
            if(idleSimulatorFilter==null)
                idleSimulatorFilter=new LiveGpsFilter(mount,liveGpsHold(settings),number(3),
                    stationaryConfirmationMs(settings),livePanLead(settings),(event,fields)->{});
        } catch(IllegalArgumentException unavailable) {
            return;
        }
        LiveGpsFilter filter=idleSimulatorFilter;
        if(raw.firstPlaybackFix()) filter.resetInput();
        LiveGpsFilter.Snapshot before=filter.latest();
        filter.accept(idleSimulatorGps.toFilterInput(raw.fix(),raw.receivedAt(),mount,0,true),GoTo.now());
        LiveGpsFilter.Snapshot after=filter.latest();
        if(after!=null&&(before==null||after.revision()!=before.revision())) writeSimulatorPublication(raw,after,null);
    }

    private void readGps(GpsPadSimulator source,Consumer<LiveGpsPacket> observe,Trace liveLog) {
        if(settings==null) return;
        long time=GoTo.now();
        try {
            if(source==null) readReceiverGps(time,observe);
            else {
                GpsPadSimulator.Sample sample=source.poll(time);
                if(sample!=null) {
                    workerPlayback=sample.playback();
                    if(sample.modeStart()!=null) logSimulatorModeStart(source,sample,time,liveLog);
                    acceptGpsLine(sample.line(),time,source.id,sample.lastTap(),sample.firstPlaybackFix(),sample.mode(),observe);
                    if(sample.firstPlaybackFix() || (sample.playbackFix()>0 && sample.playbackFix()==sample.playback().fixes().size()))
                        queueGpsPadEvent("gps_simulator_playback_fix",source,"playback",sample.playback().number(),
                            "fixIndex",sample.playbackFix(),"fixReceivedMs",time);
                }
            }
            if(time-lastGpsUi>=300) {
                lastGpsUi=time;
                String status=latest==null?(source==null?"GPS waiting for a valid packet":"Waiting for the first simulated GPS fix"):
                    (source==null?"Received GPS #":"Simulated GPS #")+latest.sequence+" · "+(GoTo.now()-received)
                        +" ms since receipt\n"+MountCoordinates.format(latest.lat,latest.lon);
                publishGps(status,source);
            }
        } catch(Exception e) {
            resetGps();
            if(GoTo.now()-lastGpsUi>=1000) {
                lastGpsUi=GoTo.now();
                publishGps("GPS: "+e.getMessage(),source);
            }
        }
    }

    private void logSimulatorModeStart(GpsPadSimulator source,GpsPadSimulator.Sample sample,long time,Trace liveLog) {
        SurferModes.Mode mode=sample.mode();
        GpsPadSimulator.ModeStart start=sample.modeStart();
        Long selectedAt=start.selectedAt();
        SurferModes.Mode timedOut=start.timedOutMode();
        Object[] fields={"simulatorRun",source.id,"surferMode",mode.name(),"modeNumber",mode.number,
            "modeLabel",mode.label,"startedAt",Instant.now().toString(),"firstFixMs",time,
            "startReason",timedOut==null?"selected":"time_limit",
            "selectedMs",selectedAt,"selectionToFirstFixMs",selectedAt==null?null:time-selectedAt,
            "timedOutMode",timedOut==null?null:timedOut.name(),
            "timedOutModeNumber",timedOut==null?null:timedOut.number,
            "modeLimitMs",start.limitMs()==0?null:start.limitMs()};
        var event=new GpsPadEvent("gps_simulator_mode_started",time,source.id,fields);
        if(gpsPadLog!=null&&gpsPadLog.id.equals(source.id)) event.write(gpsPadLog);
        event.write(liveLog);
    }

    private void readReceiverGps(long time,Consumer<LiveGpsPacket> observe) throws IOException {
        if(gps!=null && gdl.requested()) {
            Network selected=gdl.route();
            if(!Objects.equals(gpsLink.network(),selected)) resetGps();
        }
        if(gps==null) {
            receiver=endpoint(0,1);
            gpsLink=deviceLink(settings[0]);
            gps=gpsLink.udp(port(2));
            gps.setSoTimeout(3);
        }
        if(lastHello<0 || time-lastHello>=ConnectionFlag.HELLO_INTERVAL_MS) {
            byte[] hello="HELLO".getBytes(StandardCharsets.US_ASCII);
            gps.send(new DatagramPacket(hello,hello.length,receiver));
            lastHello=time;
        }
        for(int i=0;i<32;i++) {
            byte[] bytes=new byte[2048];
            DatagramPacket p=new DatagramPacket(bytes,bytes.length);
            try { gps.receive(p); } catch(SocketTimeoutException ignored) { break; }
            if(!receiver.equals(p.getSocketAddress()) || p.getLength()==0) continue;
            lastReceiverMs=GoTo.now();
            for(String line:new String(bytes,0,p.getLength(),StandardCharsets.US_ASCII).split("\n"))
                acceptGpsLine(line,GoTo.now(),null,null,false,null,observe);
        }
    }

    private void acceptGpsLine(String line,long at,String simulatorId,Long lastTap,boolean firstPlaybackFix,
                               SurferModes.Mode mode,Consumer<LiveGpsPacket> observe) {
        LoRaTelemetry fix;
        try { fix=LoRaTelemetry.parse(line); }
        catch(IllegalArgumentException rejected) {
            if(observe!=null && !line.trim().isEmpty())
                observe.accept(new LiveGpsPacket(line,null,at,rejected.getMessage(),simulatorId,lastTap,firstPlaybackFix,mode));
            return;
        }
        boolean duplicate=tracker.lastSequence()==fix.sequence;
        boolean accepted=tracker.accept(fix,at);
        if(accepted) {
            latest=fix;
            mapGps=new MapGps(new GoTo.Point(fix.lat,fix.lon,0),at,simulatorId,fix.sequence);
            latestGpsTap=lastTap;
            latestGpsMode=mode;
            received=at;
        }
        if(observe!=null) observe.accept(new LiveGpsPacket(line,fix,at,
            accepted?null:duplicate?"Duplicate GPS sequence":"Out-of-order GPS packet",simulatorId,lastTap,firstPlaybackFix,mode));
    }
    Saved loadSaved() {
        try {
            JSONObject j=new JSONObject(prefs.getString("saved",""));
            return new Saved(new GoTo.Point(j.getDouble("latitude"),j.getDouble("longitude"),j.getDouble("elevation")),
                j.getLong("sequence"),j.getLong("captured"),j.getString("attempt"));
        } catch(JSONException e) { return null; }
    }
    private void save(GoTo.Point point,LoRaTelemetry fix,String id,Trace trace) throws Exception {
        long at=System.currentTimeMillis();
        JSONObject j=new JSONObject();
        j.put("latitude",point.lat()); j.put("longitude",point.lon()); j.put("elevation",point.elevation());
        j.put("sequence",fix.sequence); j.put("captured",at); j.put("attempt",id);
        if(!prefs.edit().putString("saved",j.toString()).commit()) throw new IOException("Could not save start position");
        trace.event("saved_position","position",j);
        main.post(this::publish);
    }
    void launch(String action,Uri file,String[] values) { launch(action,file,values,false); }

    void launchHs(Uri file,String[] values) {
        launch("Replay viewpoints (HS)",file,values,true);
    }

    static boolean tiltDisabled(String[] values) {
        String flag=values[TILT_DISABLE];
        if("false".equalsIgnoreCase(flag)) return false;
        if("true".equalsIgnoreCase(flag)) return true;
        throw new IllegalArgumentException("tilt-disable must be true or false");
    }

    static void validateLiveSettings(String[] values) {
        tiltDisabled(values);
        LiveTracking.tiltSpeed(number(values,LIVE_TILT_SPEED));
        livePanSpeedMultiplier(values);
        liveGpsHold(values);
        stationaryConfirmationMs(values);
        livePanLead(values);
        simulatorShotWidth(values);
        if(number(values,LIVE_MIN_RANGE)<0) throw new IllegalArgumentException(KEYS[LIVE_MIN_RANGE]+" must be non-negative");
    }

    private static double livePanSpeedMultiplier(String[] values) {
        double multiplier=number(values,LIVE_PAN_SPEED_MULTIPLIER);
        if(multiplier<=0) throw new IllegalArgumentException(KEYS[LIVE_PAN_SPEED_MULTIPLIER]+" must be positive");
        return multiplier;
    }

    private static double livePanLead(String[] values) {
        double lead=number(values,LIVE_PAN_LEAD);
        if(lead<0) throw new IllegalArgumentException(KEYS[LIVE_PAN_LEAD]+" must be non-negative");
        return lead;
    }

    private static double simulatorShotWidth(String[] values) {
        double width=number(values,SIMULATOR_SHOT_WIDTH);
        if(width<=0) throw new IllegalArgumentException(KEYS[SIMULATOR_SHOT_WIDTH]+" must be positive");
        return width;
    }

    private static int liveGpsHold(String[] values) {
        double count=number(values,LIVE_GPS_HOLD);
        if(count<0||count>Integer.MAX_VALUE||count!=Math.rint(count))
            throw new IllegalArgumentException(KEYS[LIVE_GPS_HOLD]+" must be a non-negative whole number");
        return (int)count;
    }

    private static int stationaryConfirmationMs(String[] values) {
        double time=number(values,LIVE_GPS_STATIONARY_MS);
        if(time<1||time>Integer.MAX_VALUE||time!=Math.rint(time))
            throw new IllegalArgumentException(KEYS[LIVE_GPS_STATIONARY_MS]+" must be a positive whole number");
        return (int)time;
    }

    void launchLiveTracking(String[] values,long press,Instant pressedAt) {
        if(!ready()) return;
        String block=MountCoordinates.liveStartBlock(values);
        if(!block.isEmpty()) { resultText=block; publish(); return; }
        liveStopConfirmed=false;
        liveStopRequested=-1;
        mapPan=new MapPan(mapPan.heading(),"Waiting for GP target");
        cameraPosition=null;
        cameraSession=press;
        LiveGpsSource source=new LiveGpsSource(press);
        liveGps=source;
        liveGpsRuleText="";
        begin("Live Tracking · Preparing");
        worker.execute(()->runLiveTracking(values,press,pressedAt,source));
        if(camera!=null) camera.startRecording(press);
    }

    private boolean liveActive() { return liveStopRequested<0&&!destroyed&&!Thread.currentThread().isInterrupted(); }

    private void runLiveTracking(String[] values,long press,Instant pressedAt,LiveGpsSource source) {
        String id=UUID.randomUUID().toString().substring(0,12);
        String name="FM-"+id+".jsonl";
        String result=null;
        Trace trace=null;
        LiveTrackingKml report=new LiveTrackingKml();
        KmlPublication kml=null;
        try {
            Trace log=new Trace(this,name,id);
            trace=log;
            try(log) {
                prefs.edit().putString("lastLog",name).apply();
                GpsPadSimulator simulator=gpsPad;
                log.event("run_start","button","Live Tracking","build",BuildConfig.VERSION_NAME,
                    "pressMs",press,"workerStartedMs",GoTo.now(),"wallTimeMs",pressedAt.toEpochMilli(),
                    "settings",new JSONArray(Arrays.asList(values)),"simulationOffsetM",0,
                    "gpsSource",simulator==null?"LoRa":"Simulator",
                    "simulatorRun",simulator==null?null:simulator.id);
                try { result=trackLive(values,press,log,source,report); }
                finally { endCameraSession(); }
                drainGpsPadEvents(log);
                drainLiveOffsets(source,report,log);
                kml=publishLiveTrackingKml(values,id,pressedAt,press,result,report,log);
                log.event("live_report","path",kml.failure()==null?kml.path():null,"failure",kml.failure(),
                    "rawGpsFixes",report.rawFixes.size(),"publishedGpsFixes",report.publishedFixes.size(),
                    "offsetPresses",report.changes.size(),"publishMs",kml.elapsedMs(),
                    "lastCameraHeadingDeg",report.cameraHeading==null?null:report.cameraHeading.bearingDeg(),
                    "lastCameraPanCount",report.cameraHeading==null?null:report.cameraHeading.count(),
                    "lastCameraSampleMs",report.cameraHeading==null?null:report.cameraHeading.sampledAt());
            }
        } catch(IOException|RuntimeException failure) {
            result=(result==null?"Live Tracking":result)+"\nLog issue · "+failure.getMessage();
        }
        if(kml==null) {
            drainLiveOffsets(source,report,null);
            kml=publishLiveTrackingKml(values,id,pressedAt,press,result,report,trace);
        }
        endCameraSession();
        main.post(()->liveGps=null);
        finish(result+(trace==null?"":"\nAttempt "+id+" · "+trace.location()+trace.downloadWarning())+kml.status());
    }

    private static void drainLiveOffsets(LiveGpsSource source,LiveTrackingKml report,Trace log) {
        for(LiveGpsSource.Change change;(change=source.nextChange())!=null;) {
            report.changes.add(change);
            if(log!=null) log.event("live_offset_pressed","pressMs",change.pressedAt(),
                "pressNumber",change.pressNumber(),"stepM",change.stepM(),"offsetM",change.offsetM());
        }
    }

    private LiveGpsSource.Fix processLiveGps(LiveGpsPacket raw,LiveGpsSource source,LiveGpsFilter filter,
                                       GoTo.Point mount,double elevation,LiveTrackingKml report,Trace log) {
        LoRaTelemetry packet=raw.fix();
        LoRaTelemetry.RawFix position=packet==null?LoRaTelemetry.rawFix(raw.line()):
            new LoRaTelemetry.RawFix(packet.sequence,packet.senderMs,packet.lat,packet.lon);
        if(position!=null) report.rawFixes.add(new LiveTrackingKml.Raw(position,raw.receivedAt(),raw.rejection(),raw.simulated()));
        LiveGpsSource.Fix input=raw.rejection()==null?
            source.toFilterInput(packet,raw.receivedAt(),mount,elevation,raw.simulated()):null;
        if(input!=null) report.fixes.add(input);
        drainLiveOffsets(source,report,log);
        if(!raw.simulated()) {
            GoTo.Point candidate=input==null?null:input.target();
            log.event("live_gps_raw_fix","packet",position==null?null:position.sequence(),
                "senderMs",position==null?null:position.senderMs(),"receivedMs",raw.receivedAt(),
                "latitude",position==null?null:position.lat(),"longitude",position==null?null:position.lon(),
                "receiverAccepted",raw.rejection()==null,"rejection",raw.rejection(),"rawRecord",raw.line(),
                "gpsSource","LoRa","offsetM",input==null?null:input.offsetM(),
                "offsetPressNumber",input==null?null:input.offsetPressNumber(),
                "candidateLatitude",candidate==null?null:candidate.lat(),"candidateLongitude",candidate==null?null:candidate.lon());
        }
        if(input==null) {
            filter.reject(position==null?null:position.sequence(),raw.rejection());
            return null;
        }

        LiveGpsFilter.Snapshot before=filter.latest();
        filter.accept(input,GoTo.now());
        LiveGpsFilter.Snapshot after=filter.latest();
        if(after==null||after==before) return input;
        boolean newPosition=before==null||after.revision()!=before.revision();
        if(newPosition) {
            report.publishedFixes.add(after);
            if(raw.simulated()) writeSimulatorPublication(raw,after,log.id);
        } else if(raw.simulated()) return input;
        writeGpsPublication(log,newPosition?"live_gps_published_fix":"live_gps_published_refresh",raw,after,null);
        return input;
    }

    private void writeSimulatorPublication(LiveGpsPacket raw,LiveGpsFilter.Snapshot after,String liveRun) {
        if(gpsPadLog!=null&&gpsPadLog.id.equals(raw.simulatorId()))
            writeGpsPublication(gpsPadLog,"gps_simulator_published_fix",raw,after,liveRun);
    }

    private static void writeGpsPublication(Trace log,String event,LiveGpsPacket raw,
                                            LiveGpsFilter.Snapshot after,String liveRun) {
        GoTo.Point published=after.fix().target(), panTarget=after.panTarget();
        log.event(event,"sourcePacket",after.fix().packet().sequence,"inputPacket",raw.fix().sequence,
            "sourceReceivedMs",after.fix().receivedAt(),"latitude",published.lat(),"longitude",published.lon(),
            "panLeadM",after.panLeadM(),"panTargetLatitude",panTarget.lat(),"panTargetLongitude",panTarget.lon(),
            "direction",after.direction().name(),"state",after.state().name(),"positionRevision",after.revision(),
            "gpsSource",after.fix().simulated()?"Simulator":"LoRa","simulatorRun",raw.simulatorId(),
            "surferMode",raw.mode()==null?null:raw.mode().name(),"lastTap",raw.lastTap(),"liveRun",liveRun,
            "publishedMs",after.publishedAt(),"freshMs",after.freshAt(),"ageMs",after.ageMs(GoTo.now()),
            "ruleIds",new JSONArray(after.rules()),"offsetM",after.fix().offsetM(),
            "offsetPressNumber",after.fix().offsetPressNumber());
    }

    private String trackLive(String[] values,long press,Trace log,LiveGpsSource source,LiveTrackingKml report) {
        GoTo mount=null;
        LiveFraming framing=null;
        boolean readyToTrack=false;
        String reason="Stop requested", stop="GTi session unavailable";
        try {
            String block=MountCoordinates.liveStartBlock(values);
            if(!block.isEmpty()) throw new IllegalArgumentException(block);
            apply(values);
            MountConnection connected=connectedMount();
            mount=new GoTo(connected.link()::udp,connected.endpoint(),log,LiveTracking.RETAINED_REPLY_PORTS);
            GoTo.Reference pointing=reference();
            boolean disableTilt=tiltDisabled(values);
            double tiltSpeed=LiveTracking.tiltSpeed(number(values,LIVE_TILT_SPEED));
            double elevation=disableTilt?Double.NaN:number(11);
            double maximumGpsAge=number(3);
            GoTo.MountSetup setup=connected.setup();
            int panCpr=setup.panCpr(), tiltCpr=setup.tiltCpr();
            GoTo.PanTiming panTiming=connected.requireHsTiming();
            Integer tiltPeriod=null;
            if(!disableTilt) try {
                GoTo.PanTiming timing=connected.tilt().require("tilt");
                tiltPeriod=new GoTo.PanTiming(timing.frequency(),1).period(tiltSpeed*tiltCpr/GoTo.SIDEREAL_DAY_SECONDS);
            } catch(IOException failure) {
                log.event("live_tilt_setup_failed","reason",failure.getMessage());
            }
            int holdCount=liveGpsHold(values);
            int confirmationMs=stationaryConfirmationMs(values);
            double minimumRange=number(values,LIVE_MIN_RANGE);
            double panSpeedMultiplier=livePanSpeedMultiplier(values);
            double lead=livePanLead(values), shotWidth=simulatorShotWidth(values);
            LiveFraming framingReport=new LiveFraming(pointing.pivot(),shotWidth,maximumGpsAge,press);
            framing=framingReport;
            log.event("live_tracking_ready","initialPanSpeed",GoTo.MAX_HS_SIDEREAL,"restartSpeedSource","surfer-to-mount distance",
                "panSpeedMultiplier",panSpeedMultiplier,"tiltDisabled",disableTilt,"tiltSpeed",tiltSpeed,
                "tiltPeriod",tiltPeriod,"maxGpsAgeMs",maximumGpsAge,"gpsSmallStepHold",holdCount,
                "gpsStationaryConfirmationMs",confirmationMs,"panLeadM",lead,"simulatorShotWidthM",shotWidth,"minimumSurferRangeM",minimumRange);
            LiveTracking controller=new LiveTracking(mount,panCpr,panTiming,tiltPeriod,disableTilt,panSpeedMultiplier,this::liveActive,this::updateResult);
            readyToTrack=true;
            idleSimulatorFilter=null;
            LiveGpsFilter filter=new LiveGpsFilter(pointing.pivot(),holdCount,maximumGpsAge,confirmationMs,lead,
                (event,fields)->{if(workerGpsPad==null) log.event(event,fields);});
            LiveRangeGate rangeGate=new LiveRangeGate(pointing.pivot(),minimumRange);
            Consumer<LiveGpsPacket> observe=packet->{
                if(packet.firstPlaybackFix()) filter.resetInput();
                if(packet.rejection()==null) {
                    LoRaTelemetry raw=packet.fix();
                    framingReport.fix(packet.mode(),new GoTo.Point(raw.lat,raw.lon,elevation),packet.receivedAt());
                }
                LiveGpsSource.Fix accepted=processLiveGps(packet,source,filter,pointing.pivot(),elevation,report,log);
                if(accepted!=null) rangeGate.observe(accepted,controller.needsStartupTarget());
                if(cameraSession==press && !cameraSettings.cameraHost.isEmpty()) {
                    LiveGpsFilter.Snapshot published=filter.latest();
                    cameraPosition=published==null?null:
                        new CameraPosition(pointing.pivot(),published.fix().target(),published.freshAt(),maximumGpsAge);
                }
            };
            GpsPadSimulator initialGpsSource=gpsPad;
            selectGpsSource(initialGpsSource);
            if(latest!=null && GoTo.now()-received<=maximumGpsAge)
                observe.accept(new LiveGpsPacket(null,latest,received,null,initialGpsSource==null?null:initialGpsSource.id,
                    latestGpsTap,false,latestGpsMode));

            long selectedRevision=0;
            LiveTracking.Targets targets=null;
            String previousAvailability=null;
            LiveGpsFilter.RuleStatus previousRule=null;
            while(liveActive()) {
                drainGpsPadEvents(log);
                drainLiveOffsets(source,report,log);
                GpsPadSimulator selectedGpsSource=gpsPad;
                if(selectGpsSource(selectedGpsSource)) {
                    framingReport.endTakeoff(GoTo.now());
                    filter.resetInput();
                    targets=null;
                    rangeGate.reset();
                    controller.discardUnstartedAim();
                    previousRule=null;
                    previousAvailability=null;
                    log.event("live_gps_source_changed","gpsSource",selectedGpsSource==null?"LoRa":"Simulator",
                        "simulatorRun",selectedGpsSource==null?null:selectedGpsSource.id);
                }
                readGps(selectedGpsSource,observe,log);
                long now=GoTo.now();
                filter.expire(now);
                LiveGpsFilter.RuleStatus rule=filter.currentRule();
                if(!rule.equals(previousRule)) {
                    previousRule=rule;
                    main.post(()->{
                        if(liveGps==source&&gpsPad==selectedGpsSource&&canStopLiveTracking()) {
                            liveGpsRuleText=rule.displayText();
                            publish();
                        }
                    });
                }
                LiveGpsFilter.Snapshot fix=filter.latest();
                Long age=fix==null?null:fix.ageMs(now);
                boolean usable=age!=null&&age<=maximumGpsAge;
                boolean holdForRange=rangeGate.inside() || (!controller.startupPending() && usable && !rangeGate.allows(fix));
                LiveGpsSource.Fix first=rangeGate.takeFirst();
                String availability;
                if(holdForRange) availability="Surfer too close · waiting for an in-range fix";
                else if(controller.startupPending())
                    availability=first!=null || !controller.needsStartupTarget()?"Aiming at first GPS fix":"Waiting for first fresh in-range GPS fix";
                else availability=fix==null?"Waiting for GP target":usable?"GP target available":"GP target is stale";
                if(!availability.equals(previousAvailability)) {
                    log.event("live_gps_availability","usable",usable,"reason",availability,
                        "rawRangeM",latest==null?null:Geo.distance(pointing.pivot().lat(),pointing.pivot().lon(),latest.lat,latest.lon),
                        "minimumSurferRangeM",minimumRange,
                        "packet",fix==null?null:fix.fix().packet().sequence,"positionRevision",fix==null?null:fix.revision(),
                        "rawReceiptMs",latest==null?null:received,"ageMs",age);
                    updateResult("Live Tracking · "+availability);
                    previousAvailability=availability;
                }
                if(usable&&fix.revision()!=selectedRevision) {
                    selectedRevision=fix.revision();
                    targets=null;
                    LiveGpsSource.Fix published=fix.fix();
                    try {
                        AimAtSurfer.Target pan=AimAtSurfer.target(fix.panTarget(),pointing,panCpr);
                        double surferRange=Geo.distance(pointing.pivot().lat(),pointing.pivot().lon(),published.target().lat(),published.target().lon());
                        AimAtSurfer.Target tilt=disableTilt?null:AimAtSurfer.tiltTarget(elevation,pointing,pan.rangeM(),tiltCpr);
                        int direction=pointing.panSign()*(fix.direction()==LiveGpsSource.Direction.CLOCKWISE?1:-1);
                        targets=new LiveTracking.Targets(published.packet().sequence,fix.revision(),
                            pan.count(),tilt==null?null:tilt.count(),direction,surferRange);
                        log.event("live_gps_consumed","packet",published.packet().sequence,
                            "positionRevision",fix.revision(),"latitude",published.target().lat(),
                            "longitude",published.target().lon(),"direction",fix.direction().name(),"ageMs",age,
                            "panLeadM",fix.panLeadM(),"panTargetLatitude",fix.panTarget().lat(),
                            "panTargetLongitude",fix.panTarget().lon());
                        log.event("live_target","packet",published.packet().sequence,"positionRevision",fix.revision(),
                            "direction",fix.direction().name(),"panCount",pan.count(),"tiltCount",tilt==null?null:tilt.count(),
                            "bearingDeg",pan.angleDeg(),"elevationDeg",tilt==null?null:tilt.angleDeg(),"rangeM",pan.rangeM(),
                            "offsetM",published.offsetM(),"panLeadM",fix.panLeadM());
                    } catch(IllegalArgumentException failure) {
                        log.event("live_target_unusable","packet",published.packet().sequence,"reason",failure.getMessage());
                    }
                }
                long priorReply=controller.lastPanStartReply();
                boolean inRange=usable && rangeGate.allows(fix);
                if(first!=null && now-first.receivedAt()<=maximumGpsAge) {
                    try {
                        GoTo.Point position=first.target();
                        AimAtSurfer.Target aim=AimAtSurfer.target(position,pointing,panCpr);
                        controller.offerStartupTarget(first.packet().sequence,aim.count(),aim.rangeM(),first.receivedAt()+maximumGpsAge);
                        log.event("live_startup_fix","packet",first.packet().sequence,"latitude",position.lat(),
                            "longitude",position.lon(),"rangeM",aim.rangeM(),"ageMs",now-first.receivedAt());
                    } catch(IllegalArgumentException failure) {
                        log.event("live_startup_target_unusable","packet",first.packet().sequence,"reason",failure.getMessage());
                    }
                }
                controller.track(inRange?targets:null,holdForRange);
                long reply=controller.lastPanStartReply();
                if(reply!=priorReply && selectedGpsSource!=null && selectedGpsSource.recordFirstReply(workerPlayback,reply)) {
                    long applied=workerPlayback.pressedAt();
                    Object[] timing={"playback",workerPlayback.number(),"pressMs",applied,"replyMs",reply,"elapsedMsToReply",reply-applied};
                    queueGpsPadEvent("gps_simulator_first_pan_start_reply",selectedGpsSource,timing);
                }
                GoTo.PanSample sample=controller.lastPanSample();
                if(sample!=null&&(report.cameraHeading==null||sample.timeMs()!=report.cameraHeading.sampledAt())) {
                    double bearing=GoTo.panHeading(sample.count(),pointing,panCpr);
                    report.cameraHeading=new LiveTrackingKml.CameraHeading(bearing,sample.count(),sample.timeMs());
                    framingReport.sample(bearing,sample.timeMs());
                }
                MapPan previousPan=mapPan;
                LiveTrackingKml.CameraHeading heading=report.cameraHeading!=null?report.cameraHeading:previousPan.heading();
                if(previousPan.heading()!=heading || !previousPan.state().equals(controller.panState()))
                    mapPan=new MapPan(heading,controller.panState());
                if(liveActive()) Thread.sleep(LiveTracking.POLL_MS);
            }
        } catch(InterruptedException failure) {
            Thread.currentThread().interrupt();
            reason="Worker interrupted";
        } catch(IOException|RuntimeException failure) {
            reason=failure.getMessage();
            log.event("live_tracking_issue","reason",reason);
        } finally {
            if(framing!=null) framing.endTakeoff(liveStopRequested<0?GoTo.now():liveStopRequested);
            if(mount!=null&&(readyToTrack||liveStopRequested>=0)) {
                log.event("live_stop_requested","pressMs",liveStopRequested<0?null:liveStopRequested,"reason",reason);
                if(liveStopRequested<0) {
                    liveStopRequested=GoTo.now();
                    main.post(this::publish);
                }
                MountConnection active=lastMovingMount!=null?lastMovingMount:gtiConnection;
                GoTo stopper=active==null?mount:shutdownClient(active,log);
                while(!destroyed && !liveStopConfirmed) {
                    try {
                        liveStopConfirmed=LiveTracking.awaitFullStop(stopper,()->!destroyed);
                    } catch(InterruptedException interrupted) {
                        log.event("live_stop_unconfirmed","reason","Stop check interrupted");
                    }
                }
                if(liveStopConfirmed) lastMovingMount=null;
                stop=liveStopConfirmed?"Both motors stopped":"Full stop unconfirmed";
                main.post(this::publish);
                log.event("live_full_stop_result","confirmed",liveStopConfirmed,"elapsedMs",GoTo.now()-liveStopRequested);
                mapPan=new MapPan(mapPan.heading(),stop);
            }
        }
        Long firstStart=log.firstStartMs==null?null:log.firstStartMs-press;
        log.event("live_tracking_result","outcome",reason,"stop",stop,"durationMs",GoTo.now()-press,
            "buttonToFirstStartReplyMs",firstStart);
        String result=withFirstStart("Live Tracking · "+reason+"\n"+stop,firstStart);
        if(framing==null||!framing.sawTakeoff()) return result;
        log.event("live_takeoff_framing","shotWidthM",framing.shotWidthM,"takeoffMs",framing.takeoffMs(),
            "observedMs",framing.observedMs(),"estimatedOutsideMs",framing.observedMs()==0?null:framing.outsideMs());
        return result+"\n"+framing.summary();
    }

    void launchAim(String[] values) {
        if(!ready()) return;
        long press=GoTo.now();
        Instant pressedAt=Instant.now();
        begin("Aim at Surfer HS in progress");
        worker.execute(()->runAim(values,press,pressedAt));
    }

    private void runAim(String[] values,long press,Instant pressedAt) {
        AimAtSurfer.Attempt aim=new AimAtSurfer.Attempt(press,this::updateResult);
        String id=UUID.randomUUID().toString().substring(0,12);
        String name="FM-"+id+".jsonl";
        Trace log;
        try {
            log=aim.time(AimAtSurfer.Stage.LOG,()->new Trace(this,name,id));
        } catch(IOException|RuntimeException failure) {
            aim.fail("Aim blocked",failure);
            KmlPublication kml=publishAimKml(values,id,pressedAt,null,aim,null);
            finish(aim.summary()+aim.timing(GoTo.now()-press)+"\nAttempt "+id+kml.status());
            return;
        }

        aim.log=(event,fields)->log.axisEvent(1,event,GoTo.now(),fields);
        AimAtSurfer.Attempt tilt=new AimAtSurfer.Attempt(press,AimAtSurfer.Axis.TILT,this::updateResult);
        tilt.log=(event,fields)->log.axisEvent(2,event,GoTo.now(),fields);
        aim.tilt=tilt;
        prefs.edit().putString("lastLog",name).apply();
        log.event("run_start","button","Aim at Surfer HS","sequence","AIM-NEXT","pressMs",press,"workerStartedMs",aim.worker,
            "workerDelayMs",aim.worker-press,"wallTimeMs",System.currentTimeMillis(),"build",BuildConfig.VERSION_NAME,
            "logOpenMs",aim.elapsed.get(AimAtSurfer.Stage.LOG),"settings",new JSONArray(Arrays.asList(values)));
        LoRaTelemetry selectedFix=null;
        try {
            apply(values);
            GpsSnapshot snapshot=aim.time(AimAtSurfer.Stage.GPS,()->usableGps(press,log));
            selectedFix=snapshot.fix();
            aim.packet=selectedFix.sequence;
            aim.ageAtSelection=snapshot.ageMs();
            aim.fixReceived=snapshot.receivedAt();
            MountConnection connected=aim.time(AimAtSurfer.Stage.DRIVER,this::connectedMount);
            int cpr=connected.setup().panCpr();
            aim.panCpr=cpr;
            GoTo.Point fix=new GoTo.Point(selectedFix.lat,selectedFix.lon,Double.NaN);
            int tiltCpr=connected.setup().tiltCpr();
            aim.time(AimAtSurfer.Stage.TARGET,()->{
                aim.reference=reference();
                aim.target=AimAtSurfer.target(fix,aim.reference,cpr);
                return null;
            });
            if(camera!=null && motionAllowed()) camera.zoomOnce("Aim at Surfer HS",press,aim.target.rangeM());
            if(tiltDisabled(values)) tilt.skip("Tilt disabled");
            else prepareTiltTarget(tilt, values, aim, connected.setup());
            log.axisEvent(1,"aim_target",GoTo.now(),"panCount",aim.target.count(),"bearing",aim.target.angleDeg(),
                "rangeM",aim.target.rangeM(),"panCpr",cpr,"panFirmware",connected.setup().panFirmware());
            updateResult(String.format(Locale.US,"Aim at Surfer HS · GPS #%d · Range %.2f m",aim.packet,aim.target.rangeM()));
            GoTo.PanTiming timing=aim.time(AimAtSurfer.Stage.TIMING,connected::requireHsTiming);
            GoTo mount=new GoTo(connected.link()::udp,connected.endpoint(),aim::mountEvent,true);
            AimAtSurfer.run(aim,mount,timing,cpr,tiltCpr,this::motionAllowed);
        } catch(IOException|RuntimeException failure) {
            aim.fail("Aim blocked",failure);
        }

        if(tilt.phaseStarted==null&&tilt.issue==null)
            tilt.skip(aim.issue==null?"Pan did not reach tilt dispatch":aim.issue);
        tilt.event("aim_tilt_result","outcome",tilt.outcome,"issue",tilt.issue,
            "targetCount",tilt.target==null?null:tilt.target.count(),"baseline",tilt.baseline,
            "mode",tilt.gotoMode,"direction",tilt.direction,"dispatchMs",tilt.phaseStarted,
            "finishedMs",tilt.phaseFinished,"dispatchDurationMs",AimAtSurfer.Attempt.since(tilt.phaseFinished,tilt.phaseStarted),
            "startSentMs",tilt.startSent,"startAckMs",tilt.startAck,
            "buttonToTiltStartReplyMs",AimAtSurfer.Attempt.since(tilt.startAck,press),
            "panStopAckToTiltStartReplyMs",AimAtSurfer.Attempt.since(tilt.startAck,aim.stopAck),
            "finalPosition",tilt.startSent==null?null:"Unconfirmed");
        log.event("aim_result","outcome",aim.outcome,"issue",aim.issue,"startSentMs",aim.startSent,
            "startAckMs",aim.startAck,"buttonToFirstStartReplyMs",AimAtSurfer.Attempt.since(aim.firstStartReply(),press),
            "firstMotionMs",aim.firstMotion,"crossingMs",aim.crossing,"crossingCount",aim.crossing==null?null:aim.lastSampleCount,
            "stopDecisionMs",aim.stopDecision,"stopSentMs",aim.stopSent,"stopAckMs",aim.stopAck,"stoppedMs",aim.stopped,
            "encoderPolls",aim.encoderPolls,"stopStatusPolls",aim.statusPolls,"finalCount",aim.finalCount,
            "errorCounts",aim.errorCounts,"coastCounts",aim.coastCounts,"finalGapM",aim.finalGapM,
            "tiltOutcome",tilt.outcome,"tiltIssue",tilt.issue,"tiltStartAckMs",tilt.startAck,
            "display",aim.summary()+aim.timing(null));
        aim.log=null;
        long publishStart=GoTo.now();
        boolean logFinalized=false;
        try {
            log.close();
            logFinalized=true;
            if(log.download!=null&&log.downloadError==null)
                aim.elapsed.put(AimAtSurfer.Stage.PUBLISH,GoTo.now()-publishStart);
        } catch(IOException|RuntimeException failure) {
            aim.addIssue("Log finalization",failure);
        }
        KmlPublication kml=publishAimKml(values,id,pressedAt,selectedFix,aim,log);
        if(logFinalized) {
            try {
                log.appendAimPublication(aim.elapsed.get(AimAtSurfer.Stage.PUBLISH),GoTo.now()-press,kml);
            } catch(IOException|RuntimeException|JSONException failure) {
                aim.addIssue("Log publication details",failure);
            }
        }
        finish(aim.summary()+aim.timing(GoTo.now()-press)+"\nAttempt "+id+" · "+log.location()
            +log.downloadWarning()+kml.status());
    }

    private static void prepareTiltTarget(AimAtSurfer.Attempt tilt, String[] values, AimAtSurfer.Attempt aim,
                                          GoTo.MountSetup setup) {
        tilt.fixReceived=aim.fixReceived;
        tilt.packet=aim.packet;
        try {
            tilt.time(AimAtSurfer.Stage.TARGET,()->{
                tilt.targetElevationM=number(values,11);
                tilt.target=AimAtSurfer.tiltTarget(tilt.targetElevationM,aim.reference,aim.target.rangeM(),setup.tiltCpr());
                return null;
            });
            tilt.event("aim_tilt_target","tiltCount",tilt.target.count(),"elevationDeg",tilt.target.angleDeg(),
                "targetElevationM",tilt.targetElevationM,"rangeM",tilt.target.rangeM(),
                "tiltCpr",setup.tiltCpr(),"tiltFirmware",setup.tiltFirmware(),"packet",tilt.packet);
        } catch(IOException|RuntimeException failure) {
            tilt.fail("Blocked before Start",failure);
        }
    }

    private void launch(String action,Uri file,String[] values,boolean hs) {
        if(!ready()) return;
        long press=GoTo.now();
        hsCancelled=false;
        if(file!=null&&!hs) {
            replayReports.clear();
            replayMotionText=REPLAY_MOTION_PENDING;
            replayDurationMs=null;
            replayFirstStartMs=null;
        }
        begin(action+" in progress");
        worker.execute(()->{
            apply(values);
            String id=UUID.randomUUID().toString().substring(0,12);
            String name="FM-"+id+".jsonl";
            String status;
            Trace trace=null;
            List<HsRow> hsRows=hs?new ArrayList<>():null;
            Double gapWarningM=null;
            try(Trace log=new Trace(this,name,id)) {
                trace=log;
                prefs.edit().putString("lastLog",name).apply();
                log.event("run_start","button",action,"pressMs",press,"wallTimeMs",System.currentTimeMillis(),
                    "workerDelayMs",GoTo.now()-press,"build",BuildConfig.VERSION_NAME,"settings",new JSONArray(Arrays.asList(values)));
                List<ReplayKml.Viewpoint> reportPoints=file==null?null:new ArrayList<>();
                try {
                    if(hs) {
                        gapWarningM=hsGapWarningM(values);
                        status=replayHighSpeed(file,gapWarningM,log,reportPoints,hsRows);
                    } else if(file!=null) status=replay(file,log,reportPoints);
                    else if(action.equals("Go to landmark")) {
                        MountConnection mount=connectedMount();
                        GoTo.Reference ref=reference();
                        GoTo.Point landmark=point(LandmarkCalibration.LANDMARK_LAT,LandmarkCalibration.LANDMARK_LON,
                            LandmarkCalibration.LANDMARK_HEIGHT);
                        status=mount.client(log).goToPosition(mount.setup(),landmark,ref,this::motionAllowed,
                            press,tiltDisabled(settings)).report();
                    }
                    else if(action.equals("GoToMe")) status=goToMe(press,log);
                    else status=goHome(press,log);
                } catch(Exception e) {
                    status="Blocked or failed: "+e.getMessage();
                    log.event("failure","reason",e.toString());
                }
                Long firstStart=log.firstStartMs==null?null:log.firstStartMs-press;
                status=withFirstStart(status,firstStart);
                if(reportPoints!=null) status+=publishReplayKml(log,status,reportPoints,hs);
                status+="\nAttempt "+id+" · "+log.location();
                log.event("run_result","display",status,"totalMs",GoTo.now()-press,"buttonToFirstStartReplyMs",
                    firstStart==null?JSONObject.NULL:firstStart);
            } catch(Exception e) {
                Long received=trace==null||trace.firstStartMs==null?null:trace.firstStartMs-press;
                status=withFirstStart("Log unavailable: "+e.getMessage(),received);
            }
            Long firstStart=trace==null||trace.firstStartMs==null?null:trace.firstStartMs-press;
            String display=status+(trace==null?"":trace.downloadWarning());
            if(hs) {
                HsReport report=new HsReport(display,Collections.unmodifiableList(hsRows),gapWarningM);
                main.post(()->hsReport=report);
            } else if(file!=null) main.post(()->replayFirstStartMs=firstStart);
            finish(display);
        });
    }
    private static String withFirstStart(String status,Long elapsed) {
        int firstLine=status.indexOf('\n');
        String timing="\nButton to first successful Start reply · "+elapsedLabel(elapsed);
        if(firstLine<0) return status+timing;
        return status.substring(0,firstLine)+timing+status.substring(firstLine);
    }

    private GpsSnapshot usableGps(long press,Trace log) throws IOException {
        GpsPadSimulator source=gpsPad;
        if(selectGpsSource(source)) readGps(source,source==null?null:this::processIdleSimulatorGps,null);
        LoRaTelemetry fix=latest;
        if(fix==null) throw new IOException("No valid GPS fix");
        long fixReceived=received;
        long age=GoTo.now()-fixReceived;
        if(age>number(3)) {
            if(log!=null) log.event("gps_stale","sequence",fix.sequence,"receivedMs",fixReceived,"ageMs",age);
            throw new IOException("GPS fix is stale ("+age+" ms)");
        }
        if(log!=null) {
            log.fixReceived=fixReceived;
            log.event("gps_selected","packet",new JSONObject(fix.fields()),"gpsSource",source==null?"LoRa":"Simulator",
                "receivedMs",fixReceived,"ageMs",age,
                "ageAtPressMs",press>=fixReceived?press-fixReceived:JSONObject.NULL);
        }
        return new GpsSnapshot(fix,fixReceived,age);
    }

    private String goToMe(long press,Trace log) throws Exception {
        MountConnection connected=connectedMount();
        GoTo.Reference reference=reference();
        GpsSnapshot snapshot=usableGps(press,log);
        LoRaTelemetry fix=snapshot.fix();
        GoTo.Point surfer=new GoTo.Point(fix.lat,fix.lon,number(11));
        save(surfer,fix,log.id,log);
        if(camera!=null && motionAllowed()) camera.zoomOnce("GoToMe",press,
            Geo.distance(reference.pivot().lat(),reference.pivot().lon(),surfer.lat(),surfer.lon()));
        return connected.client(log).goToPosition(connected.setup(),surfer,reference,
            ()->motionAllowed() && GoTo.now()-snapshot.receivedAt()<=number(3),press,tiltDisabled(settings)).report()
            +"\nGPS #"+fix.sequence+" · "+snapshot.ageMs()+" ms at selection";
    }

    private String goHome(long press,Trace log) throws Exception {
        MountConnection connected=connectedMount();
        GoTo.Reference reference=reference();
        Saved saved=loadSaved();
        if(saved==null) throw new IOException("No saved start position");
        log.event("saved_target","sourceAttempt",saved.attempt,"sequence",saved.sequence,
            "captureAgeMs",System.currentTimeMillis()-saved.captured);
        return connected.client(log).goToPosition(connected.setup(),saved.point,reference,this::motionAllowed,press,tiltDisabled(settings)).report()
            +"\nSaved GPS #"+saved.sequence+" · source "+saved.attempt;
    }

    private String replay(Uri uri,Trace log,List<ReplayKml.Viewpoint> reportPoints) throws Exception {
        List<Viewpoint> points=readFile(uri);
        List<GoTo.Point> targets=new ArrayList<>(points.size());
        for(Viewpoint vp:points) targets.add(vp.point);
        log.event("replay_file","uri",uri.toString(),"viewpoints",points.size());
        updateResult("Viewpoints loaded: "+points.size());
        DateTimeFormatter callClock=DateTimeFormatter.ofPattern("HH:mm:ss.SSS",Locale.US)
            .withZone(ZoneId.systemDefault());
        ReplayStart start=lineUpFirstViewpoint(points.get(0),log);
        GoTo.Reference ref=start.reference();
        long origin=start.measuredStartMs();
        long wallOrigin=System.currentTimeMillis()-(GoTo.now()-origin);
        log.event("replay_measured_start","startMs",origin,"wallTimeMs",wallOrigin);
        long previousResult=origin;
        Long previousCall=null;
        int attempted=0,acknowledged=0,blocked=0,stopsConfirmed=0,stopFailures=0;
        StringJoiner outcomes=new StringJoiner("\n\n");
        for(Viewpoint vp:points) {
            if(!motionAllowed()) throw new IOException("Replay closed");
            long rowStarted=GoTo.now();
            int index=attempted+1;
            log.attempt=log.id+"-"+index;
            String dispatchReport=null;
            GoTo client=null;
            long waitFrom=previousResult;
            String stopMotorState=vp.stopMotor?"Not sent":"Off";
            Long stopMotorMs=null;
            if(vp.stopMotor) {
                Long stopStarted=null;
                try {
                    client=connectedMount().client(log);
                    stopStarted=GoTo.now();
                    waitFrom=client.stopBothMotors();
                    stopMotorState="Acknowledged";
                    stopMotorMs=waitFrom-stopStarted;
                } catch(IOException|RuntimeException e) {
                    if(stopStarted!=null) {
                        stopMotorState="Failed";
                        stopMotorMs=GoTo.now()-stopStarted;
                    }
                    dispatchReport="Blocked before Start · Stop both motors failed · "+
                        Objects.toString(e.getMessage(),e.getClass().getSimpleName());
                }
                log.event("replay_stop_motor","row",vp.row,"result",stopMotorState,"durationMs",stopMotorMs,
                    "reason",dispatchReport);
            }

            StopCheck stop=null;
            if(dispatchReport==null && vp.waitForStop) {
                updateResult("Replay "+index+" / "+points.size()+" · Waiting for both motors to stop before GoTo");
                try {
                    if(client==null) client=connectedMount().client(log);
                    stop=checkReplayStop(client,log,"beforeGoTo");
                    if(stop.stopped()) {
                        stopsConfirmed++;
                        waitFrom=stop.completedAtMs();
                    } else {
                        stopFailures++;
                        dispatchReport="Blocked before Start · "+stop.report();
                    }
                } catch(IOException|RuntimeException e) {
                    stopFailures++;
                    dispatchReport="Blocked before Start · Could not check motor stop · "+
                        Objects.toString(e.getMessage(),e.getClass().getSimpleName());
                }
            }

            Long due=null,begin=null,waited=null;
            if(dispatchReport==null) {
                due=waitFrom+vp.delayMs;
                waitUntil(due);
                if(!motionAllowed()) throw new IOException("Replay closed");
                begin=GoTo.now();
                waited=begin-waitFrom;
            }
            attempted++;
            log.event("viewpoint","row",vp.row,"delayMs",vp.delayMs,"waitFromMs",begin==null?null:waitFrom,
                "waitedMs",waited,"scheduledMs",due,"dispatchMs",begin,"lagMs",begin==null?null:begin-due,
                "waitForStop",vp.waitForStop,"stopMotor",vp.stopMotor,
                "latitude",vp.point.lat(),"longitude",vp.point.lon(),"elevation",vp.point.elevation());
            updateResult("Replay "+index+" / "+points.size());

            Long callMs=null,requiredStartsAcknowledgedAtMs=null;
            if(dispatchReport==null) {
                try {
                    MountConnection connected=connectedMount();
                    if(client==null) client=connected.client(log);
                    callMs=GoTo.now();
                    GoTo.PositionResult position=client.goToPosition(connected.setup(),vp.point,ref,this::motionAllowed,begin,tiltDisabled(settings));
                    dispatchReport=position.report();
                    requiredStartsAcknowledgedAtMs=position.requiredStartsAcknowledgedAtMs();
                } catch(Exception e) {
                    dispatchReport="Blocked before Start · "+Objects.toString(e.getMessage(),e.getClass().getSimpleName());
                }
            }
            boolean startAcknowledged=requiredStartsAcknowledgedAtMs!=null;
            if(startAcknowledged) acknowledged++;
            else if(dispatchReport.startsWith("Blocked before Start")) blocked++;
            previousResult=GoTo.now();
            Long callWallTimeMs=null,sinceReplayStartMs=null,sincePreviousCallMs=null;
            String callTiming="GoToPosition call was not made";
            String callTime="—";
            if(callMs!=null) {
                sinceReplayStartMs=callMs-origin;
                callWallTimeMs=wallOrigin+sinceReplayStartMs;
                sincePreviousCallMs=previousCall==null?null:callMs-previousCall;
                previousCall=callMs;
                callTime=callClock.format(Instant.ofEpochMilli(callWallTimeMs));
                callTiming="GoToPosition "+callTime+
                    " · +"+sinceReplayStartMs+" ms from replay start · Since previous call "+
                    (sincePreviousCallMs==null?"—":sincePreviousCallMs+" ms");
            }
            Long callToRequiredStartsMs=startAcknowledged?requiredStartsAcknowledgedAtMs-callMs:null;
            Long stopCheckWaitMs=stop==null?null:stop.elapsedMs();
            Long rowToBothStoppedMs=stop!=null&&stop.stopped()?stop.completedAtMs()-rowStarted:null;
            String stopMotorReport=stopMotorMs==null?stopMotorState:stopMotorState+" · "+stopMotorMs+" ms";
            String stopCheckReport=vp.waitForStop?"Not run":"Off";
            if(stop!=null) stopCheckReport=stop.stopped()?stop.report():"Failed after "+stop.elapsedMs()+" ms";
            String rowReport=String.format(Locale.US,
                "Viewpoint %d / %d · CSV row %d\n%s\nStop motor · %s\nPre-GoTo stop check · %s"+
                "\nCSV delay %d ms · Actual wait %s\n%s",
                index,points.size(),vp.row,callTiming,stopMotorReport,stopCheckReport,vp.delayMs,
                waited==null?"—":waited+" ms",dispatchReport);
            String outcome=replayOutcomeLine(index,dispatchReport);
            if(vp.stopMotor) outcome+="\nStop motor · "+stopMotorReport;
            if(vp.waitForStop) outcome+="\nPre-GoTo stop check · "+stopCheckReport;
            outcomes.add(outcome+"\nCSV row "+vp.row+" · "+callTiming);
            log.event("viewpoint_result","row",vp.row,"callMs",callMs,"callWallTimeMs",callWallTimeMs,
                "sinceReplayStartMs",sinceReplayStartMs,"sincePreviousCallMs",sincePreviousCallMs,
                "stopMotorResult",stopMotorState,"stopMotorMs",stopMotorMs,
                "callToBothStartsMs",tiltDisabled(settings)?null:callToRequiredStartsMs,
                "callToRequiredStartsMs",callToRequiredStartsMs,"stopCheckWaitMs",stopCheckWaitMs,"rowToBothStoppedMs",rowToBothStoppedMs,
                "outcome",outcome,"display",rowReport);
            reportPoints.add(new ReplayKml.Viewpoint(index,vp.row,vp.point,startAcknowledged,rowReport));
            reportReplayRow(new ReplayRowReport(index,callTime,sincePreviousCallMs,stopMotorReport,vp.delayMs,waited,callToRequiredStartsMs,
                stopCheckWaitMs,rowToBothStoppedMs,rowReport));
        }
        log.attempt=null;
        int startIssues=attempted-acknowledged-blocked;
        long duration=previousResult-origin;
        double pathLength=successfulReplayPathLength(reportPoints);
        Double averageSpeedKmh=averageReplaySpeedKmh(pathLength,duration);
        String motionSummary=String.format(Locale.US,"Total distance displaced · %.2f m across successful GoTo targets",pathLength)
            +"\nAverage speed · "+(averageSpeedKmh==null?"—":String.format(Locale.US,"%.2f km/h",averageSpeedKmh));
        main.post(()->{
            replayDurationMs=duration;
            replayMotionText=motionSummary;
        });
        Geo.FarthestPair farthest=Geo.farthestPair(targets);
        String spacing=farthest==null?"":String.format(Locale.US,
            "\nFarthest viewpoints · %.2f m between viewpoints %d and %d",
            farthest.metres(),farthest.firstIndex()+1,farthest.secondIndex()+1);
        log.event("replay_summary","attempted",attempted,"completed",acknowledged,
            "blockedBeforeStart",blocked,"startIssues",startIssues,"stopsConfirmed",stopsConfirmed,
            "stopCheckFailures",stopFailures,"remaining",points.size()-attempted,
            "durationMs",duration,"successfulTargetPathM",pathLength,"averageSpeedKmh",averageSpeedKmh,
            "averageSpeedMps",averageSpeedKmh==null?null:averageSpeedKmh/3.6,
            "farthestDistanceM",farthest==null?null:farthest.metres(),
            "farthestViewpointA",farthest==null?null:farthest.firstIndex()+1,
            "farthestViewpointB",farthest==null?null:farthest.secondIndex()+1);
        String stopSummary=stopsConfirmed+stopFailures==0?"":"\nPre-GoTo stops confirmed "+stopsConfirmed+" · Stop check failures "+stopFailures;
        return "Replay complete · "+attempted+" of "+points.size()+" viewpoints attempted · "+duration+" ms"+
            "\nStart acknowledged "+acknowledged+" · Blocked before Start "+blocked+" · Start issues "+startIssues+
            stopSummary+"\n"+motionSummary+spacing+"\n\n"+outcomes;
    }

    private String replayHighSpeed(Uri uri,double gapWarningM,Trace log,List<ReplayKml.Viewpoint> reportPoints,
                                   List<HsRow> rows) throws IOException {
        List<Viewpoint> views=readFile(uri);
        log.event("hs_replay_file","uri",uri.toString(),"viewpoints",views.size(),
            "timing","cumulative CSV delay_ms","csvStopFlagsApplied",false,"distanceGapLimitM",gapWarningM);
        MountConnection connected=connectedMount();
        GoTo.Reference ref=reference();
        List<HighSpeedReplay.TimedPoint> targets=new ArrayList<>(views.size());
        for(Viewpoint view:views) targets.add(new HighSpeedReplay.TimedPoint(view.point(),view.delayMs()));
        HighSpeedReplay.Plan plan=HighSpeedReplay.plan(targets,ref,connected.setup().panCpr());
        HighSpeedReplay.Result result=null;
        String failure=null;
        try {
            logHsPlan(plan,views,log);
            GoTo client=connected.client(log);
            GoTo.PanTiming timing=connected.requireHsTiming();
            int period=client.highSpeedPanPeriod(timing,plan.countsPerSecond());
            HighSpeedReplay.SpeedUp[] speedUps=new HighSpeedReplay.SpeedUp[views.size()];
            for(int i=0;i<views.size();i++) speedUps[i]=views.get(i).speedUp();
            lineUpFirstViewpoint(views.get(0),log);
            if(!motionAllowed()) throw new IOException("HS replay closed");
            updateResult("Starting HS pan");
            main.post(()->{ hsCanCancel=true; publish(); });
            result=HighSpeedReplay.goToPositionHS(client,plan,timing,period,speedUps,
                ()->motionAllowed()&&!hsCancelled,this::readGps,this::showHsProgress,log);
        } catch(IOException|RuntimeException e) {
            failure=Objects.toString(e.getMessage(),e.getClass().getSimpleName());
        } finally {
            main.post(()->{ hsCanCancel=false; publish(); });
        }
        if(result==null) {
            result=new HighSpeedReplay.Result(views.size());
            result.reason=failure;
        }
        String report=hsReportText(plan,views,result,reportPoints,rows,gapWarningM,log);
        if(result.measuredSpeedSegments>0)
            report+="\nHS speed measurements logged · "+result.measuredSpeedSegments;
        return report;
    }

    private void showHsProgress(HighSpeedReplay.Progress progress) {
        String lead=progress.lead()>0?"Pan ahead":progress.lead()<0?"Pan behind":"Pan on target";
        updateResult("HS replay · "+progress.observed()+" of "+progress.total()+" checkpoints observed"+
            "\nSimulated surfer · Viewpoint "+progress.simulatedRow()+" · "+lead+
            "\nElapsed "+elapsedLabel(progress.elapsedMs())+" · Next checkpoint due "+elapsedLabel(progress.nextDueMs()));
    }

    private static void logHsPlan(HighSpeedReplay.Plan plan,List<Viewpoint> views,Trace log) {
        log.event("hs_replay_plan","paceKmh",plan.targetPaceKmh(),"pathM",plan.last().distanceM(),
            "durationMs",plan.last().dueMs(),"panDirection",plan.direction(),
            "requestedCountsPerSecond",plan.checkpoints().get(0).requiredCountsPerSecond(),
            "fixedCountsPerSecond",plan.countsPerSecond(),"speedCeilingApplied",plan.ceilingApplied());
        for(int i=0;i<views.size();i++) {
            HighSpeedReplay.Checkpoint point=plan.checkpoints().get(i);
            log.event("hs_viewpoint_plan","viewpoint",i+1,"row",views.get(i).row(),"rangeM",point.rangeM(),
                "distanceM",point.distanceM(),"delayMs",views.get(i).delayMs(),"dueMs",point.dueMs(),
                "targetPaceKmh",point.targetPaceKmh(),"targetCount",point.count(),
                "requiredCountsPerSecond",point.requiredCountsPerSecond(),"matchedPaceKmh",point.matchedPaceKmh(plan.countsPerSecond()),
                "speedUp",views.get(i).speedUp().requested(),
                "speedUpMultiplier",views.get(i).speedUp().matchCheckpoint()?null:views.get(i).speedUp().multiplier(),
                "latitude",point.point().lat(),"longitude",point.point().lon(),"elevation",point.point().elevation());
        }
    }

    private static String hsReportText(HighSpeedReplay.Plan plan,List<Viewpoint> views,HighSpeedReplay.Result run,
                                       List<ReplayKml.Viewpoint> reportPoints,List<HsRow> rows,double gapWarningM,Trace log) {
        DateTimeFormatter clock=DateTimeFormatter.ofPattern("HH:mm:ss.SSS",Locale.US).withZone(ZoneId.systemDefault());
        int seen=0,shortfallCount=0;
        double nearest=Double.POSITIVE_INFINITY,distance=0;
        Long previous=null;
        String finalTiming="—";
        String gapWarning=hsGapWarningLabel(gapWarningM);
        for(int i=0;i<views.size();i++) {
            HighSpeedReplay.Checkpoint checkpoint=plan.checkpoints().get(i);
            nearest=Math.min(nearest,checkpoint.rangeM());
            HighSpeedReplay.Observation observation=run.observed[i];
            double rate=observation==null?plan.countsPerSecond():observation.rate();
            if(checkpoint.requiredCountsPerSecond()-rate>1e-6) shortfallCount++;
            Long elapsed=observation==null?null:observation.elapsedMs();
            Long gap=elapsed==null||previous==null?null:elapsed-previous;
            long due=checkpoint.dueMs();
            Long timingError=elapsed==null?null:elapsed-due;
            String timing=timingError==null?"—":timingError==0?"On time":
                elapsedLabel(Math.abs(timingError))+(timingError<0?" early":" late");
            if(i==views.size()-1&&run.reachedLast) finalTiming=timing;
            String observed=elapsed==null?"—":clock.format(Instant.ofEpochMilli(observation.wallTimeMs()))+
                "\n+"+elapsedLabel(elapsed);
            boolean seenHere=observation!=null;
            String outcome=seenHere?(i==0?"Lined up":"Observed"):"Unobserved";
            if(seenHere) {
                seen++;
                previous=elapsed;
                distance=checkpoint.distanceM();
            }
            String range=String.format(Locale.US,"%.2f m",checkpoint.rangeM());
            Double distanceGapM=run.distanceGapsM[i];
            boolean distanceGapOverLimit=distanceGapM!=null&&distanceGapM>gapWarningM;
            String distanceGap=distanceGapM==null?"—":String.format(Locale.US,"%.2f m",distanceGapM);
            Double matchedPace=checkpoint.matchedPaceKmh(rate);
            String matched=paceLabel(matchedPace);
            String targetPace=paceLabel(checkpoint.targetPaceKmh());
            String details=String.format(Locale.US,
                "Viewpoint %d · CSV row %d · %s\n%.8f, %.8f · Elevation %.2f m"+
                "\nGap to target at due time · %s%s\nMount range · %s\nTarget pace · %s\nMatched pace · %s\nCSV delay · %s"+
                 "\nExpected · %s\nObserved · %s\nGap · %s%s\nLead / lag · %s"+
                "\nRequired %.2f counts/s · Commanded %.2f counts/s\nTarget count %d · Observed %s",
                i+1,views.get(i).row(),outcome,checkpoint.point().lat(),checkpoint.point().lon(),checkpoint.point().elevation(),
                distanceGap,distanceGapOverLimit?" · Over "+gapWarning:"",range,targetPace,matched,
                elapsedLabel(views.get(i).delayMs()),elapsedLabel(due),observed,elapsedLabel(gap),
                gap!=null&&gap==0?" · Shared sample":"",timing,checkpoint.requiredCountsPerSecond(),rate,
                checkpoint.count(),observation==null?"—":Integer.toString(observation.count()));
            rows.add(new HsRow(i+1,seenHere,distanceGapOverLimit,
                new String[]{(i+1)+" / "+views.get(i).row(),distanceGap,range,targetPace,matched,
                elapsedLabel(due),observed,elapsedLabel(gap),timing,outcome},details));
            reportPoints.add(new ReplayKml.Viewpoint(i+1,views.get(i).row(),views.get(i).point(),seenHere,details));
            log.event("hs_viewpoint_result","viewpoint",i+1,"row",views.get(i).row(),"expectedMs",due,
                "observedMs",elapsed,"sincePreviousCheckpointMs",gap,"timingErrorMs",timingError,
                "distanceGapM",distanceGapM,"distanceGapLimitM",gapWarningM,
                "distanceGapOverLimit",distanceGapM==null?null:distanceGapOverLimit,
                "observedCount",observation==null?null:observation.count(),"commandedCountsPerSecond",rate,
                "delayMs",views.get(i).delayMs(),"targetPaceKmh",checkpoint.targetPaceKmh(),"matchedPaceKmh",matchedPace,"outcome",outcome);
        }
        Double average=distance>0&&previous!=null?averageReplaySpeedKmh(distance,previous):null;
        Long startReply=run.startAckMs==null?null:run.startAckMs-run.startMs;
        Long settle=run.stoppedMs==null?null:run.stoppedMs-run.stopSentMs;
        Long total=run.stoppedMs==null?null:run.stoppedMs-run.startMs;
        Integer finalError=run.finalCount==null?null:GoTo.delta(run.finalCount,plan.last().count());
        Integer coast=run.finalCount==null||run.lastSampleCount==null?null:
            plan.direction()*GoTo.delta(run.finalCount,run.lastSampleCount);
        log.event("hs_replay_summary","outcome",run.outcome(),"checkpointsObserved",seen,"checkpointsTotal",views.size(),
            "plannedDistanceM",plan.last().distanceM(),"observedDistanceM",distance,"averageSpeedKmh",average,
            "plannedDurationMs",plan.last().dueMs(),"targetPaceKmh",plan.targetPaceKmh(),"distanceGapLimitM",gapWarningM,
            "startToLastObservedMs",previous,"startReplyMs",startReply,"stopToStoppedMs",settle,
            "startToStoppedMs",total,"lastCheckpointTiming",finalTiming,"finalErrorCounts",finalError,"coastCounts",coast,
            "speedCeilingApplied",plan.ceilingApplied(),"viewpointsBelowRidePace",shortfallCount);
        String speedWarning="";
        if(shortfallCount>0) {
            String capped=plan.ceilingApplied()?String.format(Locale.US,
                "Capped at %d×. Viewpoint 1 matches %s. ",GoTo.MAX_HS_SIDEREAL,
                paceLabel(plan.checkpoints().get(0).matchedPaceKmh(plan.countsPerSecond()))):"";
            speedWarning=String.format(Locale.US,"\n%s%sBelow the CSV target pace at %d of %d viewpoints.",
                HS_SPEED_SHORTFALL,capped,shortfallCount,views.size());
        }
        String stopTime=settle==null?"Full stop unconfirmed":elapsedLabel(settle)+" to full stop";
        return String.format(Locale.US,
            "%s%s\n%s · %d of %d checkpoints observed\n%s · Nearest range %.2f m"+
            "\nRoute · %.2f m planned · %.2f m observed\nPlanned duration · %s\nTime to last observed checkpoint · %s"+
            "\nGap warning · Over %s"+
            "\nMeasured HS Start to reply · %s\nStop · %s · %s\nTotal replay time · %s"+
            "\nLast viewpoint · %s · Final error %s · Coast %s",
            run.outcome(),speedWarning,run.reachedLast?"Final checkpoint observed":"Incomplete pass",seen,views.size(),
            hsPaceLabel(plan.targetPaceKmh(),average),nearest,plan.last().distanceM(),distance,
            elapsedLabel(plan.last().dueMs()),elapsedLabel(previous),gapWarning,
            elapsedLabel(startReply),run.stopReply,run.stopSentMs==null?"—":stopTime,elapsedLabel(total),
            finalTiming,countsLabel(finalError),countsLabel(coast));
    }

    static double hsGapWarningM(String[] values) {
        try {
            double metres=Double.parseDouble(values[HS_GAP_WARNING]);
            if(Double.isFinite(metres)&&metres>=0) return metres;
        } catch(NumberFormatException ignored) {}
        throw new IllegalArgumentException(KEYS[HS_GAP_WARNING]+" must be a non-negative number");
    }

    static String hsGapWarningLabel(double metres) {
        return BigDecimal.valueOf(metres).stripTrailingZeros().toPlainString()+" m";
    }

    static String hsPaceLabel(double target,Double observed) {
        String measured=observed==null?"—":String.format(Locale.US,"%.2f",observed);
        return String.format(Locale.US,"Pace [target, observed] · [%.2f, %s] km/h",target,measured);
    }

    private static String paceLabel(Double value) {
        return value==null?"—":String.format(Locale.US,"%.2f km/h",value);
    }
    private static String countsLabel(Integer value) { return value==null?"—":value+" counts"; }
    static String elapsedLabel(Long value) { return value==null?"—":value+" ms"; }

    private ReplayStart lineUpFirstViewpoint(Viewpoint first,Trace log) throws IOException {
        updateResult("Lining up viewpoint 1");
        long started=GoTo.now();
        log.attempt=log.id+"-line-up";
        log.event("replay_line_up_start","row",first.row,"latitude",first.point.lat(),
            "longitude",first.point.lon(),"elevation",first.point.elevation());
        try {
            if(!motionAllowed()) throw new IOException("Replay closed");
            MountConnection connected=connectedMount();
            GoTo.Reference ref=reference();
            GoTo client=connected.client(log);
            long callMs=GoTo.now();
            GoTo.PositionResult position=client.goToPosition(connected.setup(),first.point,ref,this::motionAllowed,callMs,tiltDisabled(settings));
            log.event("replay_line_up_goto","callMs",callMs,
                "bothStartsAcknowledgedAtMs",tiltDisabled(settings)?null:position.requiredStartsAcknowledgedAtMs(),
                "requiredStartsAcknowledgedAtMs",position.requiredStartsAcknowledgedAtMs(),"display",position.report());
            if(position.requiredStartsAcknowledgedAtMs()==null)
                throw new IOException(position.report().split("\nTarget ",2)[0]);

            StopCheck stop=checkReplayStop(client,log,"lineUp");
            if(!stop.stopped()) throw new IOException(stop.report());
            log.event("replay_line_up_result","result","stopped","stoppedAtMs",stop.completedAtMs(),
                "durationMs",stop.completedAtMs()-started);
            return new ReplayStart(ref,stop.completedAtMs());
        } catch(IOException|RuntimeException e) {
            String reason=Objects.toString(e.getMessage(),e.getClass().getSimpleName());
            log.event("replay_line_up_result","result","failed","reason",reason,"durationMs",GoTo.now()-started);
            throw new IOException("Viewpoint 1 line-up failed · "+reason,e);
        } finally {
            log.attempt=null;
        }
    }

    static Double averageReplaySpeedKmh(double distanceMetres,long durationMs) {
        return durationMs>0?distanceMetres*3_600.0/durationMs:null;
    }

    static double successfulReplayPathLength(List<ReplayKml.Viewpoint> points) {
        List<GoTo.Point> successful=new ArrayList<>();
        for(ReplayKml.Viewpoint point:points)
            if(point.succeeded()) successful.add(point.point());
        return Geo.pathLength(successful);
    }

    private record KmlPublication(String path,String failure,long elapsedMs) {
        String status() {
            return failure==null?"\nKML report · "+path:"\nKML report could not be saved · "+failure;
        }
    }

    private KmlPublication publishLiveTrackingKml(String[] values,String id,Instant pressedAt,long press,
                                                  String result,LiveTrackingKml report,Trace log) {
        long start=GoTo.now();
        String filename="FM-"+id+".kml";
        String path=REPORT_DIRECTORY+"/"+filename;
        try {
            GoTo.Point mount=reportPosition(values,MountCoordinates.LATITUDE,MountCoordinates.LONGITUDE,
                LandmarkCalibration.PIVOT_ELEVATION);
            GoTo.Point landmark=reportPosition(values,LandmarkCalibration.LANDMARK_LAT,LandmarkCalibration.LANDMARK_LON,
                LandmarkCalibration.LANDMARK_HEIGHT);
            String summary="FM "+BuildConfig.VERSION_NAME+" · Attempt "+id+"\n"+result
                +"\nElapsed · "+(start-press)+" ms";
            if(log!=null) summary+="\nJSONL log · "+log.location();
            String details=summary;
            saveKml(filename,out->report.write(out,id,details,pressedAt,press,mount,landmark));
            return new KmlPublication(path,null,GoTo.now()-start);
        } catch(IOException|RuntimeException failure) {
            return new KmlPublication(path,Objects.toString(failure.getMessage(),failure.getClass().getSimpleName()),
                GoTo.now()-start);
        }
    }

    private KmlPublication publishAimKml(String[] values,String id,Instant pressedAt,LoRaTelemetry fix,
                                         AimAtSurfer.Attempt aim,Trace log) {
        long start=GoTo.now();
        String filename="FM-"+id+".kml";
        String path=REPORT_DIRECTORY+"/"+filename;
        try {
            GoTo.Point mount=reportPosition(values,MountCoordinates.LATITUDE,MountCoordinates.LONGITUDE,
                LandmarkCalibration.PIVOT_ELEVATION);
            GoTo.Point landmark=reportPosition(values,LandmarkCalibration.LANDMARK_LAT,LandmarkCalibration.LANDMARK_LON,
                LandmarkCalibration.LANDMARK_HEIGHT);
            String summary="FM "+BuildConfig.VERSION_NAME+" · Attempt "+id+"\nPress time (UTC) · "+pressedAt
                +"\n"+aim.summary()+aim.timing(start-aim.press).replace("Button to result · ","Button to report ready · ");
            if(log!=null) summary+="\nJSONL log · "+log.location()+log.downloadWarning();
            String details=summary;
            saveKml(filename,out->AimKml.write(out,id,details,mount,landmark,fix,aim));
            return new KmlPublication(path,null,GoTo.now()-start);
        } catch(IOException|RuntimeException failure) {
            return new KmlPublication(path,Objects.toString(failure.getMessage(),failure.getClass().getSimpleName()),
                GoTo.now()-start);
        }
    }

    private String publishReplayKml(Trace log,String status,List<ReplayKml.Viewpoint> points,boolean hs) {
        String filename="FM-"+log.id+".kml";
        String path=REPORT_DIRECTORY+"/"+filename;
        try {
            GoTo.Point mount=reportPosition(settings,MountCoordinates.LATITUDE,MountCoordinates.LONGITUDE,
                LandmarkCalibration.PIVOT_ELEVATION);
            GoTo.Point landmark=reportPosition(settings,LandmarkCalibration.LANDMARK_LAT,LandmarkCalibration.LANDMARK_LON,
                LandmarkCalibration.LANDMARK_HEIGHT);
            String summary="FM "+BuildConfig.VERSION_NAME+"\n"+status.split("\n\n",2)[0]+"\nLog "+log.location();
            saveKml(filename,out->ReplayKml.write(out,log.id,summary,mount,landmark,points,hs));
            log.event("replay_kml_saved","path",path,"placemarks",points.size()+(mount==null?0:1)+(landmark==null?0:1),
                "mountIncluded",mount!=null,"landmarkIncluded",landmark!=null);
            return "\nKML report · "+path;
        } catch(IOException|RuntimeException e) {
            log.event("replay_kml_failed","path",path,"reason",e.toString());
            return "\nKML report could not be saved · "+e.getMessage();
        }
    }

    private interface KmlContent { void write(Writer out) throws IOException; }

    private void saveKml(String filename,KmlContent content) throws IOException {
        File local=File.createTempFile("FM-report-",".kml",getCacheDir());
        AttemptDownload download=null;
        try {
            try(Writer out=new BufferedWriter(new OutputStreamWriter(new FileOutputStream(local),StandardCharsets.UTF_8))) {
                content.write(out);
            }
            download=AttemptDownload.create(this,filename,REPORT_DIRECTORY,Kml.MIME_TYPE);
            download.publish(local);
        } catch(IOException|RuntimeException failure) {
            if(download!=null) download.abandon();
            throw failure;
        } finally {
            local.delete();
        }
    }

    private static GoTo.Point reportPosition(String[] values,int latitude,int longitude,int elevation) {
        double lat,lon;
        try {
            lat=number(values,latitude);
            lon=number(values,longitude);
        } catch(IllegalArgumentException e) {
            return null;
        }
        if(!Geo.valid(lat,lon)) return null;

        double height;
        try { height=number(values,elevation); }
        catch(IllegalArgumentException e) { height=Double.NaN; }
        return new GoTo.Point(lat,lon,height);
    }

    private void waitUntil(long due) {
        while(motionAllowed() && GoTo.now()<due) {
            readGps();
            SystemClock.sleep(Math.min(75,Math.max(0,due-GoTo.now())));
        }
    }

    private StopCheck checkReplayStop(GoTo client,Trace log,String phase) {
        long started=GoTo.now(),deadline=started+REPLAY_STOP_TIMEOUT_MS;
        int polls=0;
        String failure="Timed out waiting for both motors to stop";
        log.event("replay_stop_check_start","phase",phase,"timeoutMs",REPLAY_STOP_TIMEOUT_MS,
            "pollIntervalMs",REPLAY_STOP_POLL_MS);
        try {
            while(motionAllowed() && GoTo.now()<deadline) {
                GoTo.MotionStatus status=client.readMotionStatus();
                log.event("replay_stop_poll","poll",++polls,"panMoving",status.panMoving(),"tiltMoving",status.tiltMoving());
                if(status.stopped()) {
                    failure=null;
                    break;
                }
                waitUntil(Math.min(deadline,GoTo.now()+REPLAY_STOP_POLL_MS));
            }
            if(!motionAllowed()) failure="Replay closed";
        } catch(IOException e) {
            failure=e.getMessage();
        }
        long finishedAt=GoTo.now();
        StopCheck result=new StopCheck(finishedAt-started,finishedAt,failure);
        log.event("replay_stop_result","stopped",result.stopped(),"elapsedWaitMs",result.elapsedMs,
            "polls",polls,"failure",result.failure);
        return result;
    }

    static String replayOutcomeLine(int index,String dispatchReport) {
        String prefix="Viewpoint "+index+" — ";
        if(dispatchReport.startsWith("GoTo sent · pan Start acknowledged"))
            return prefix+"Pan Start acknowledged. Tilt disabled. Arrival unverified.";
        if(dispatchReport.startsWith("GoTo sent"))
            return prefix+"Start acknowledged. Pan and tilt both replied successfully. Arrival unverified.";
        if(dispatchReport.equals("Blocked before Start · Axis 1 is already moving"))
            return prefix+"Blocked before Start. Pan was still moving. FM sent no GoTo for this viewpoint.";
        if(dispatchReport.equals("Blocked before Start · Axis 2 is already moving"))
            return prefix+"Blocked before Start. Tilt was still moving. FM sent no GoTo for this viewpoint.";
        if(dispatchReport.startsWith("Blocked before Start"))
            return prefix+dispatchReport;
        String[] lines=dispatchReport.split("\n",3);
        return prefix+lines[0]+(lines.length>1?" · "+lines[1]:"");
    }

    private List<Viewpoint> readFile(Uri uri) throws IOException {
        ArrayList<Viewpoint> points=new ArrayList<>();
        try(InputStream raw=openReplayCsv(uri)) {

            try(BufferedReader in=new BufferedReader(new InputStreamReader(raw,StandardCharsets.UTF_8))) {
                int row=0;
                String line;
                while((line=in.readLine())!=null) {
                    row++;
                    line=line.trim();
                    if(row==1) line=line.replaceFirst("^\ufeff","");
                    if(line.isEmpty()) continue;
                    if(row==1&&line.startsWith("latitude,longitude")) {
                        String[] header=line.split(",",-1);
                        if(header.length>=4&&header[3].trim().equalsIgnoreCase("elapsed_ms"))
                            throw new IOException("CSV uses elapsed_ms at file row 1. Use delay_ms for the wait before each viewpoint.");
                        if(header.length>=5&&!header[4].trim().equalsIgnoreCase(REPLAY_STOP_COLUMN))
                            throw new IOException("CSV column 5 must be "+REPLAY_STOP_COLUMN+" at file row 1.");
                        if(header.length>=6&&!header[5].trim().equalsIgnoreCase(REPLAY_STOP_MOTOR_COLUMN))
                            throw new IOException("CSV column 6 must be "+REPLAY_STOP_MOTOR_COLUMN+" at file row 1.");
                        if(header.length>7||header.length==7&&!header[6].trim().equalsIgnoreCase(HS_SPEED_UP_COLUMN))
                            throw new IOException("CSV column 7 must be "+HS_SPEED_UP_COLUMN+" at file row 1.");
                        continue;
                    }
                    try {
                        String[] f=line.split(",",-1);
                        if(f.length<2||f.length>7||points.size()>=10000) throw new IllegalArgumentException();
                        double lat=Double.parseDouble(f[0]), lon=Double.parseDouble(f[1]);
                        double elevation=f.length>=3&&!f[2].trim().isEmpty()?Double.parseDouble(f[2]):number(11);
                        long delayMs=f.length>=4&&!f[3].trim().isEmpty()?Long.parseLong(f[3].trim()):0;
                        if(!Geo.valid(lat,lon)||!Double.isFinite(elevation)||delayMs<0||delayMs>3600000)
                            throw new IllegalArgumentException();
                        boolean waitForStop=readReplayFlag(f,4,REPLAY_STOP_COLUMN,row);
                        boolean stopMotor=readReplayFlag(f,5,REPLAY_STOP_MOTOR_COLUMN,row);
                        HighSpeedReplay.SpeedUp speedUp=readSpeedUp(f,row);
                        points.add(new Viewpoint(new GoTo.Point(lat,lon,elevation),delayMs,waitForStop,stopMotor,speedUp,row));
                    } catch(RuntimeException e) { throw new IOException("Invalid viewpoint at file row "+row,e); }
                }
            }
        }
        if(points.isEmpty()) throw new IOException("No viewpoints in file");
        return points;
    }

    private static HighSpeedReplay.SpeedUp readSpeedUp(String[] fields,int row) throws IOException {
        try { return HighSpeedReplay.SpeedUp.parse(fields.length>6?fields[6]:""); }
        catch(IllegalArgumentException e) {
            throw new IOException("Invalid "+HS_SPEED_UP_COLUMN+" at file row "+row+". "+e.getMessage()+".",e);
        }
    }

    private static boolean readReplayFlag(String[] fields,int index,String name,int row) throws IOException {
        String value=index<fields.length?fields[index].trim():"";
        if(value.isEmpty()||value.equals("0")) return false;
        if(value.equals("1")) return true;
        throw new IOException("Invalid "+name+" at file row "+row+". Use 0 or 1.");
    }
    private InputStream openReplayCsv(Uri uri) throws IOException {
        try {
            InputStream input=getContentResolver().openInputStream(uri);
            if(input!=null) return input;
        } catch(FileNotFoundException|SecurityException e) {
            throw new IOException("Cannot open the saved replay CSV. Choose it again in Settings.",e);
        }
        throw new IOException("Cannot open the saved replay CSV. Choose it again in Settings.");
    }

    private MountConnection connectedMount() throws IOException {
        MountConnection connected=gtiConnection;
        if(connected==null) throw new IOException("Tap Connect GTi before calibrating or aiming");
        InetSocketAddress target=endpoint(4,5);
        if(!connected.endpoint().equals(target))
            throw new IOException("Save the GTi endpoint, then tap Connect GTi");
        try {
            DeviceDiscovery.Link link=deviceLink(settings[4]);
            if(connected.link().equals(link)) return connected;
        } catch(IOException|RuntimeException e) {
            gtiConnection=null;
            throw new IOException("GTi network unavailable. Rejoin its Wi-Fi and tap Connect GTi",e);
        }
        gtiConnection=null;
        throw new IOException("GTi network changed. Tap Connect GTi again");
    }

    private GoTo.Reference reference() {
        if(workerReferenceIssue!=null)
            throw new IllegalArgumentException(LandmarkCalibration.requiredMessage(calibrationError()));
        GoTo.Point pivot=point(MountCoordinates.LATITUDE,MountCoordinates.LONGITUDE,LandmarkCalibration.PIVOT_ELEVATION);
        double bearing=number(12), elevation=number(13);
        int pan=count(14), tilt=count(15);
        double ps=number(16),ts=number(17);
        if(!Geo.valid(pivot.lat(),pivot.lon())||bearing<0||bearing>=360||Math.abs(elevation)>90||
            Math.abs(ps)!=1||Math.abs(ts)!=1)
            throw new IllegalArgumentException("Invalid mount location, calibration or axis signs");
        return new GoTo.Reference(pivot,bearing,elevation,pan,tilt,(int)ps,(int)ts);
    }
    private int count(int index) {
        double v=number(index);
        if(v<0||v>0xffffff||v!=Math.rint(v)) throw new IllegalArgumentException("Invalid "+KEYS[index]);
        return (int)v;
    }
    private double number(int index) { return number(settings,index); }

    private static double number(String[] values,int index) {
        try {
            double value=Double.parseDouble(values[index]);
            if(Double.isFinite(value)) return value;
        } catch(NumberFormatException ignored) {}
        throw new IllegalArgumentException("Enter a valid "+KEYS[index]);
    }
    static void validateEditedField(String[] values,int index) {
        switch(index) {
            case 0,4 -> {
                if(!DeviceDiscovery.ipv4(values[index])) throw new IllegalArgumentException("Invalid "+KEYS[index]);
            }
            case 1,2,5 -> port(values,index);
            case 3,10,11,20 -> number(values,index);
            case 16,17 -> {
                if(Math.abs(number(values,index))!=1) throw new IllegalArgumentException(KEYS[index]+" must be 1 or -1");
            }
        }
    }

    private int port(int index) { return port(settings,index); }

    private static int port(String[] values,int index) {
        double n=number(values,index);
        if(n<1||n>65535||n!=Math.rint(n)) throw new IllegalArgumentException("Invalid "+KEYS[index]);
        return (int)n;
    }
    private InetSocketAddress endpoint(int host,int port) throws IOException {
        if(!DeviceDiscovery.ipv4(settings[host])) throw new IOException("Invalid "+KEYS[host]);
        return new InetSocketAddress(InetAddress.getByName(settings[host]),port(port));
    }
    private final class Trace implements GoTo.Trace,Closeable {
        final BufferedWriter out;
        final File local;
        final AttemptDownload download;
        final String id;
        String attempt;
        long fixReceived=-1;
        Long firstStartMs;
        IOException error;
        long dropped;
        String downloadError;
        Trace(Context context,String name,String id) throws IOException {
            this.id=id;
            local=new File(context.getFilesDir(),name);
            out=new BufferedWriter(new OutputStreamWriter(new FileOutputStream(local),StandardCharsets.UTF_8),65536);
            AttemptDownload destination=null;
            try { destination=AttemptDownload.create(context,name); }
            catch(IOException|RuntimeException e) {
                downloadError=e.toString();
                event("downloads_log_unavailable","reason",downloadError);
            }
            download=destination;
        }

        String location() {
            return (download==null?"":AttemptDownload.DIRECTORY+"/")+local.getName();
        }

        String downloadWarning() {
            return downloadError==null?"":"\nCould not save log to Downloads. Use Save a copy of the last log. "+downloadError;
        }

        @Override public void event(String name,Object... fields) {
            axisEvent(0,name,GoTo.now(),fields);
        }

        void axisEvent(int axis,String name,long at,Object... fields) {
            if ((name.equals("gti_rx") && gtiExchangeFailed) || (name.equals("gti_exchange_failed") && !gtiExchangeFailed)) {
                gtiExchangeFailed=name.equals("gti_exchange_failed");
                main.post(FmService.this::publish);
            }
            if(name.equals("gti_tx") && fields.length>=4 && (":J1\r".equals(fields[3]) || ":J2\r".equals(fields[3])))
                lastMovingMount=gtiConnection;
            if(name.equals("start_acknowledged")) {
                long reply=(Long)fields[3];
                if(firstStartMs==null||reply<firstStartMs) firstStartMs=reply;
            }
            try {
                out.write(eventRow(axis,name,at,fields).toString());
                out.newLine();
            } catch(JSONException|IOException failure) {
                dropped++;
                if(failure instanceof IOException io) error=io;
            }
        }

        private JSONObject eventRow(int axis,String name,long at,Object[] fields) throws JSONException {
            JSONObject row=new JSONObject();
            row.put("event",name); row.put("elapsedMs",at); row.put("run",id);
            row.put("attempt",attempt==null?id:attempt);
            for(int i=0;i<fields.length;i+=2) row.put((String)fields[i],fields[i+1]);
            if(axis!=0) row.put("axis",axis);
            if(name.equals("start_requested")&&fixReceived>=0) row.put("gpsAgeMs",at-fixReceived);
            return row;
        }

        void appendAimPublication(Long publishMs,long resultMs,KmlPublication kml) throws IOException,JSONException {
            JSONObject row=new JSONObject();
            row.put("event","aim_publication");
            row.put("run",id);
            row.put("attempt",id);
            row.put("elapsedMs",GoTo.now());
            row.put("logPublishMs",publishMs==null?JSONObject.NULL:publishMs);
            row.put("buttonToResultMs",resultMs);
            row.put("kmlPath",kml.failure()==null?kml.path():JSONObject.NULL);
            row.put("kmlError",kml.failure()==null?JSONObject.NULL:kml.failure());
            row.put("kmlPublishMs",kml.elapsedMs());
            appendPublished(row.toString()+"\n");
        }

        private void appendPublished(String lines) throws IOException {
            byte[] bytes=lines.getBytes(StandardCharsets.UTF_8);
            try(FileOutputStream output=new FileOutputStream(local,true)) { output.write(bytes); }
            if(download!=null&&downloadError==null) download.append(bytes);
        }

        @Override public void close() throws IOException {
            try {
                if(dropped>0) event("log_events_dropped","count",dropped);
                out.close();
                if(error!=null) throw error;
            } catch(IOException e) {
                if(download!=null) download.abandon();
                throw e;
            }
            if(download!=null) {
                try { download.publish(local); }
                catch(IOException|RuntimeException e) {
                    downloadError=e.toString();
                    download.abandon();
                }
            }
        }
    }
}





