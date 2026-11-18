package au.com.gildo.vtc;

import android.content.Context;
import android.net.*;
import android.os.SystemClock;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** One serialized owner of motor commands. UI Stop cancels pending STARTs immediately. */
final class VtcController implements AutoCloseable {
    interface Listener { void update(View view); }
    static final class View {
        final String state,message,mount,gps,aim,framing,calibration,connections,lastLandmark;
        final boolean connected,tracking,calibrated,moving,busy,canStart,canGoToLandmark;
        View(String state,String message,String mount,String gps,String aim,String framing,String calibration,String connections,
             boolean connected,boolean tracking,boolean calibrated,boolean moving,boolean busy,boolean canStart,
             String lastLandmark,boolean canGoToLandmark) {
            this.state=state;this.message=message;this.mount=mount;this.gps=gps;this.aim=aim;this.framing=framing;this.calibration=calibration;
            this.connected=connected;this.tracking=tracking;this.calibrated=calibrated;this.moving=moving;this.busy=busy;
            this.connections=connections;this.canStart=canStart;
            this.lastLandmark=lastLandmark;this.canGoToLandmark=canGoToLandmark;
        }
    }
    private final Context context;
    private final SessionLog log;
    private final Listener listener;
    private final MountPoller poller;
    private final LandmarkStore landmarkStore;
    private final CalibrationFiles calibrationFiles;
    SavedLandmark mapLandmark(){return lastLandmark;}
    boolean mapValidated(){return calibration!=null;}
    private volatile SavedLandmark lastLandmark;
    private String landmarkStorageError="";
    private final TargetWait targetWait=new TargetWait();
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(r->new Thread(r,"vtc-mount"));
    private final ScheduledExecutorService watchdogThread=Executors.newSingleThreadScheduledExecutor(r->new Thread(r,"vtc-watchdog"));
    private final ExecutorService emergency=Executors.newSingleThreadExecutor(r->new Thread(r,"vtc-stop"));
    private final MovementWatchdog watchdog=new MovementWatchdog(SystemClock.elapsedRealtime());
    private final ContinuousPlanner continuousPlanner=new ContinuousPlanner();
    private volatile boolean stopping,stopUnconfirmed;
    private volatile Runnable afterStop;
    private final StopCompletion stopCompletion=new StopCompletion();
    private volatile long lastAcceptedAt=-1;
    private volatile double panRate,tiltRate;
    private volatile long lastRatesAt;
    private long lastRateFixAt=-1;
    private final AtomicLong generation=new AtomicLong();
    private volatile GtiClient mount;
    private volatile GpsFeed gps;
    private volatile boolean active,busy,screenVisible,closed;
    private volatile Settings s;
    private volatile Network network;
    private final ScheduledExecutorService loraWorker=Executors.newSingleThreadScheduledExecutor(r->new Thread(r,"vtc-lora-link"));
    private volatile boolean loraWanted;
    private volatile Network loraNetwork;
    private volatile String loraLinkStatus="Disconnected";
    private String loraKey="";
    boolean loraRequested(){return loraWanted;}
    GpsFeed.Fix captureFix(){GpsFeed f=gps;GpsFeed.Fix fix=f==null?null:f.latest();return fix!=null&&fix.epoch==f.continuity()?fix:null;}
    void connectLora(){if(closed)return;loraWanted=true;loraWorker.execute(this::maintainLora);log.event("lora_connect_requested","Independent LoRa connection");publish();}
    void disconnectLora(){loraWanted=false;loraWorker.execute(this::maintainLora);log.event("lora_disconnect_requested","Mount connection retained");}
    private Network wifiNetwork()throws IOException{
        ConnectivityManager cm=context.getSystemService(ConnectivityManager.class);Network result=null;
        for(Network n:cm.getAllNetworks()){NetworkCapabilities cap=cm.getNetworkCapabilities(n);if(cap!=null&&cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)){if(result!=null)throw new IOException("Select a single Wi-Fi network");result=n;}}
        if(result==null)throw new IOException("Join Wi-Fi first");return result;
    }
    private synchronized void maintainLora(){
        try{
            if(closed||!loraWanted){closeLora();loraLinkStatus="Disconnected";return;}
            Network wifi=wifiNetwork();Settings settings=s;
            if(!Settings.ipv4(settings.receiverHost))throw new IOException("Enter the LoRa receiver IPv4 address");
            String key=wifi+"/"+settings.receiverHost+"/"+settings.receiverPort+"/"+settings.receivePort+"/"+settings.gpsAge+"/"+settings.maxGpsSpeed;
            if(gps==null||gps.ended()||!key.equals(loraKey)){
                closeLora();loraNetwork=wifi;loraKey=key;gps=new GpsFeed(wifi,settings,log);gps.start();
                log.record("lora_link_started","network",wifi.toString(),"settings",settings.values);
            }
            loraLinkStatus=gps.connectionStatus();
            if(mount==null)gps.clearObservations();
        }catch(Exception e){closeLora();loraLinkStatus="Retrying: "+e.getMessage();log.error("lora_link_retry",e);}
    }
    private synchronized void closeLora(){GpsFeed old=gps;gps=null;loraNetwork=null;loraKey="";if(old!=null)old.close();}
    Network cameraNetwork(){return network!=null?network:loraNetwork;}
    Settings settings(){return s;}
    ZoomPlan.Input zoomInput(){
        Settings settings=s;GpsFeed feed=gps;GpsFeed.Fix fix=feed==null?null:feed.latest();
        if(fix==null)return new ZoomPlan.Input(Double.NaN,-1,false);
        long age=SystemClock.elapsedRealtime()-fix.receivedAt;
        double distance=Geo.valid(settings.latitude,settings.longitude)?Geo.distance(settings.latitude,settings.longitude,fix.packet.lat,fix.packet.lon):Double.NaN;
        return new ZoomPlan.Input(distance,age,fix.epoch==feed.continuity()&&age>=0&&age<=settings.gpsAge*1000&&Double.isFinite(distance));
    }
    String liveGps(){GpsFeed f=gps;return f==null?"LoRa disconnected":f.display();}
    void retryLandmarkStorage(){landmarkStore.retry();}
    private Calibration calibration;
    private GtiClient.Position position;
    private GtiClient.Position moveOrigin;
    private FramingEngine framing;
    private AimPlanner planner;
    private FixedGoto fixedGoto;
    private String state="DISCONNECTED",message="Configure the site and connect both devices.";
    private String aimText="Calculating first aiming target";
    private long lastPoll,lastFramePacket=-1,lastFrameSender=-1,lastFrameAt=-1,continuity=-1,motionDeadline,lastPublish;
    private boolean pendingMove,gpsWaiting;
    private long motionTimeoutMs=15000;
    private int expectedPan,expectedTilt;
    private long lastHeartbeat;
    private long aimingGeneration=-1;
    private String lastPublishedState="";

    VtcController(Context context,Settings settings,SessionLog log,Listener listener) {
        this.context=context.getApplicationContext();this.s=settings;this.log=log;this.listener=listener;
        poller=new MountPoller(SystemClock::elapsedRealtime,log::record);
        landmarkStore=new LandmarkStore(this.context,log);
        calibrationFiles=new CalibrationFiles(this.context,log);
        try{lastLandmark=landmarkStore.load();log.record("last_landmark_loaded","landmark",lastLandmark==null?null:lastLandmark.snapshot());}
        catch(Exception e){landmarkStorageError=e.getMessage();log.error("last_landmark_load_failed",e);}
        framing=new FramingEngine(s);planner=new AimPlanner(s);
        loraWorker.scheduleWithFixedDelay(this::maintainLora,0,2,TimeUnit.SECONDS);
        worker.scheduleWithFixedDelay(this::tick,100,100,TimeUnit.MILLISECONDS);
        watchdogThread.scheduleWithFixedDelay(()->{
            try {
                GtiClient m=mount;
                GpsFeed feed=gps;
                if(feed!=null&&feed.lastAcceptedAt()>=0)lastAcceptedAt=feed.lastAcceptedAt();
                if(m!=null&&!closed&&!stopping&&m.continuous()&&active&&
                    !ContinuousPlanner.gapAllowed(SystemClock.elapsedRealtime(),lastAcceptedAt,s.gpsGapTimeout)){
                    stopForGpsTimeout();return;
                }
                if(m!=null&&!closed&&!stopping&&watchdog.check(SystemClock.elapsedRealtime(),active||busy||m.continuous()))
                    emergencyStop("Control watchdog: no completed control cycle for 5 seconds",null);
            }catch(Throwable failure){log.event("watchdog_exception",failure.toString());emergencyStop("Watchdog exception",null);}
        },250,250,TimeUnit.MILLISECONDS);
        publish();
    }
    void configure(Settings settings) {
        if((mount!=null||busy||stopping)&&s.onlyZoomChanged(settings)){s=settings;log.settings("zoom_settings_applied_live",s);publish();return;}
        if(mount!=null||busy||stopping){log.event("settings_blocked","Disconnect before changing settings");throw new IllegalStateException("Disconnect before changing settings");}
        s=settings;framing=new FramingEngine(s);planner=new AimPlanner(s);
        log.settings("settings_applied",s);publish();
    }
    void connect() {
        connectLora();
        log.settings("connect_requested",s);
        if(mount!=null||busy||closed||stopping||stopUnconfirmed){log.record("connect_blocked","connected",mount!=null,"busy",busy,"closed",closed);return;}
        final long token=generation.incrementAndGet();busy=true;message="Connecting to GTi and LoRa receiver";publish();
        worker.execute(()->{
            GtiClient candidate=null;
            try {
                s.connectionReady();
                if(!valid(token))return;
                ConnectivityManager manager=context.getSystemService(ConnectivityManager.class);
                Network wifi=null;
                for(Network n:manager.getAllNetworks()) {
                    NetworkCapabilities cap=manager.getNetworkCapabilities(n);
                    log.record("network_candidate","network",n.toString(),"capabilities",String.valueOf(cap),"linkProperties",String.valueOf(manager.getLinkProperties(n)));
                    if(cap!=null&&cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        if(wifi!=null)throw new IOException("More than one Wi-Fi network is available; select a single network");
                        wifi=n;
                    }
                }
                if(wifi==null)throw new IOException("Join the Wi-Fi network used by the mount and receiver");
                log.record("network_selected","network",wifi.toString(),"linkProperties",String.valueOf(manager.getLinkProperties(wifi)));
                candidate=new GtiClient(new GtiUdpTransport(manager,wifi,s.mountHost,s.mountPort,log));
                candidate.stop(); // Verify both axes stopped before initializing a new session.
                candidate.connect();
                if(candidate.initializedDuringConnect)invalidateLastLandmark("Mount motor reference was reset; calibrate to save a new landmark");
                if(!valid(token)){candidate.close();return;}
                GtiClient.Position here=candidate.position();
                if(!valid(token)){candidate.close();return;}
                watchdog.reset(SystemClock.elapsedRealtime());network=wifi;mount=candidate;position=here;calibration=null;pendingMove=false;poller.reset();
                targetWait.reset();
                // LoRa lifecycle is independent of mount connection.
                state="CONNECTED";message=lastLandmarkBlocker()==null?"Go to last landmark or centre it manually, then validate calibration.":"Centre the landmark, then calibrate.";lastPoll=0;
                log.record("connected","mountHost",s.mountHost,"mountPort",s.mountPort,"firmware",candidate.firmware,"countsPerRevolution",candidate.cpr);
            }catch(Exception e){
                if(candidate!=null){
                    if(candidate.initializedDuringConnect)invalidateLastLandmark("Mount motor reference was reset; calibrate to save a new landmark");
                    candidate.close();
                }
                message=e.getMessage();state="DISCONNECTED";log.error("connect_failed",e);
            }
            finally{busy=false;publish();}
        });
    }
    void calibrate() {
        action("Calibrating",token->{
            log.settings("calibration_attempt",s);
            s.calibrationReady();
            GtiClient.Position here=mount.position();
            if(here.moving)throw new IOException("Wait for the mount to stop before calibrating");
            if(!valid(token))return;
            calibration=new Calibration(s,here,mount.cpr);position=here;
            state="CALIBRATED";message="Landmark calibrated and saved. Check Aim / next steps before starting.";
            SavedLandmark bookmark=SavedLandmark.capture(calibration,mount.firmware,System.currentTimeMillis());
            try{landmarkStore.save(bookmark);lastLandmark=bookmark;landmarkStorageError="";log.record("last_landmark_saved","landmark",bookmark.snapshot());}
            catch(IOException e){landmarkStorageError=e.getMessage();message="Calibrated, but last landmark could not be saved: "+e.getMessage();log.error("last_landmark_save_failed",e);}
            calibrationFiles.save(bookmark);
            log.record("calibration_success","calibration",calibrationSnapshot(calibration),"position",positionSnapshot(here));
        });
    }
    void nudge(int axis,double degrees) {
        if(active){log.record("manual_move_blocked","axis",axis,"degrees",degrees,"reason","Aiming active");return;}
        action("Positioning camera",token->{
            if(!Double.isFinite(degrees)||Math.abs(degrees)>1)throw new IllegalArgumentException("Manual move must be at most 1 degree");
            GtiClient.Position here=mount.position();
            if(here.moving)throw new IOException("Wait for the current move to finish");
            double pan=axis==1?degrees:0,tilt=axis==2?degrees:0;
            if(calibration!=null&&!calibration.allowed(calibration.panOffset(here.pan)+pan,calibration.tiltAngle(here.tilt)+tilt))
                throw new IOException("Manual move would exceed movement limits");
            long deadline=SystemClock.elapsedRealtime()+1000;
            mount.begin();
            mount.move(here,pan*s.panSign,tilt*s.tiltSign,()->valid(token)&&SystemClock.elapsedRealtime()<=deadline);
            rememberMove(here,pan,tilt);
            message="Finite native GoTo sent; waiting for arrival";
            log.record("manual_move","panDegrees",pan,"tiltDegrees",tilt,"destinationPan",expectedPan,"destinationTilt",expectedTilt);
        });
    }
    void start() {
        action("Starting aiming",token->{
            log.settings("aim_start_requested",s);
            if(calibration==null)throw new IOException("Calibrate the landmark first");
            s.siteReady();
            GpsFeed.Fix fix=freshFix();
            if(fix==null)throw new IOException("Wait for fresh, valid LoRa GPS");
            GtiClient.Position here=mount.position();
            if(here.moving)throw new IOException("Wait for the mount to stop");
            double distance=Geo.distance(s.latitude,s.longitude,fix.packet.lat,fix.packet.lon);
            double bearing=Geo.bearing(s.latitude,s.longitude,fix.packet.lat,fix.packet.lon);
            double tilt=Geo.elevation(distance,s.height,s.targetHeight)+s.tiltOffset;
            if(!valid(token))return;
            continuousPlanner.reset();lastRateFixAt=-1;lastAcceptedAt=fix.receivedAt;framing.reset();planner.reset();gps.clearObservations();lastFramePacket=lastFrameSender=lastFrameAt=-1;continuity=gps.continuity();
            clearFixedGoto("New aiming session");targetWait.reset();
            position=here;aimingGeneration=token;active=true;gpsWaiting=false;pendingMove=false;state="AIMING";message="Large error: saved-target GoTo. Near target: live GPS corrections.";
            log.record("aim_start","calibration",calibrationSnapshot(calibration),"gps",gpsSnapshot(fix),"rangeM",distance,"bearing",bearing,"tilt",tilt);
        });
    }
    void goToLandmark() {
        String blocker=lastLandmarkBlocker();
        if(closed||stopping||stopUnconfirmed||busy||mount==null||blocker!=null||poller.waiting()) {
            log.record("landmark_goto_blocked","connected",mount!=null,"calibrated",calibration!=null,
                "reason",blocker,"busy",busy,"screenVisible",screenVisible,"recovery",poller.snapshot());return;
        }
        // Cancel any pending surfer START before the serialized landmark action takes over.
        active=false;generation.incrementAndGet();mount.cancel();
        action("Returning to landmark",token->{
            Calibration navigation=lastLandmark.navigationReference(s,mount.cpr,mount.firmware);
            clearFixedGoto("Go to last landmark requested");targetWait.reset();
            fixedGoto=FixedGoto.landmark(navigation,SystemClock.elapsedRealtime());
            log.record("goto_captured","target",fixedGoto.snapshot(),"reason","User Go to last landmark",
                "savedAtEpochMs",lastLandmark.savedAtEpochMs,"calibrationValidated",calibration!=null,
                "position",positionSnapshot(position));
            // Stop acknowledgements do not mean deceleration is already complete.
            // Keep the old leg's bounds until a fresh stopped sample confirms the handover.
            poller.stopForTransition(mount,()->valid(token));
            if(!valid(token))return;
            framing.reset();planner.reset();gpsWaiting=false;aimingGeneration=token;active=true;
            state="STOPPING FOR LANDMARK";message="Waiting for both axes to stop, then returning to the last saved landmark.";
            // The next poll starts the first leg and uses the ordinary Stop/cancellation guards.
        });
    }
    void stop(String why) { emergencyStop(why,null); }
    synchronized void disconnect(){
        stopCompletion.request(ok->{cleanup();stopUnconfirmed=false;state="DISCONNECTED";
            message=ok?"Disconnected; both axes stopped":"Disconnected; mount stop NOT confirmed. Check mount power before reconnecting.";
            log.record("disconnect_complete","stopConfirmed",ok);publish();});
        emergencyStop("User disconnect",null);
    }
    synchronized void exit(java.util.function.Consumer<Boolean> completed){
        stopCompletion.request(ok->{cleanup();stopUnconfirmed=false;log.record("exit_complete","stopConfirmed",ok);completed.accept(ok);});
        emergencyStop("Exit VTC",null);
    }
    private synchronized void emergencyStop(String why,Runnable afterConfirmed) {
        if(closed)return;
        if(afterConfirmed!=null)afterStop=afterConfirmed;
        active=false;generation.incrementAndGet();
        GtiClient current=mount;if(current!=null)current.cancel();
        if(stopping)return;
        stopping=true;
        state="STOPPING";message=why+" — confirming both axes stopped";
        log.record("stop_requested","reason",why,"independentWorker",true);publish();
        emergency.execute(()->{
            boolean confirmed=false;
            try{if(current!=null)current.stop();confirmed=true;log.event("stop_confirmed",why);}
            catch(Throwable e){log.event("stop_unconfirmed",why+": "+e);}
            final boolean ok=confirmed;
            worker.execute(()->{
                try {
                    clearFixedGoto(why);pendingMove=false;panRate=tiltRate=0;
                    continuousPlanner.reset();framing.reset();planner.reset();poller.reset();targetWait.reset();gpsWaiting=false;
                    if(ok){stopUnconfirmed=false;position=null;watchdog.reset(SystemClock.elapsedRealtime());
                        state=current==null?"DISCONNECTED":calibration==null?"CONNECTED":"CALIBRATED";message=why+"; both axes stopped";
                        Runnable next=afterStop;afterStop=null;if(next!=null)next.run();
                    }else {stopUnconfirmed=true;state="STOP UNCONFIRMED";message="Stop unconfirmed. Reconnect Wi-Fi and retry Stop, or Disconnect / Exit. Mount stop is not confirmed.";}
                }catch(Exception e){stopUnconfirmed=true;state="STOP UNCONFIRMED";message="Stop verification failed: "+e.getMessage();log.error("stop_verification_error",e);}
                finally{stopping=false;if(stopCompletion.pending())afterStop=null;stopCompletion.complete(ok);publish();}
            });
        });
    }
    private synchronized void stopForGpsTimeout(){
        if(stopping||closed)return;
        stopping=true;GtiClient current=mount;current.cancel();
        log.record("gps_timeout_stop_requested","lastAcceptedAt",lastAcceptedAt,"timeoutSeconds",s.gpsGapTimeout);
        emergency.execute(()->{
            boolean ok=false;
            try{current.stop();ok=true;log.event("gps_timeout_stop_confirmed","Both axes stopped; aiming remains armed");}
            catch(Throwable e){log.event("gps_timeout_stop_unconfirmed",e.toString());}
            final boolean confirmed=ok;
            worker.execute(()->{
                panRate=tiltRate=0;continuousPlanner.reset();gpsWaiting=true;
                if(!confirmed){active=false;stopUnconfirmed=true;state="STOP UNCONFIRMED";message="GPS timeout: Stop unconfirmed; retry Stop.";}
                else{state=active?"WAITING GPS":"CALIBRATED";message=active?"GPS timeout; aiming resumes when usable GPS returns":"Both axes stopped";
                    Runnable next=afterStop;afterStop=null;if(next!=null)next.run();}
                stopping=false;if(stopCompletion.pending())afterStop=null;stopCompletion.complete(confirmed);publish();
            });
        });
    }
    void fatalFailure(Throwable failure){
        active=false;stopping=true;generation.incrementAndGet();GtiClient current=mount;
        if(current!=null)current.cancel();
        try{log.event("uncaught_failure",failure.toString());if(current!=null)current.stop();log.event("uncaught_stop_confirmed","Both axes stopped");}
        catch(Throwable stop){log.event("uncaught_stop_unconfirmed",stop.toString());}
    }
    void foreground(boolean value){screenVisible=value;log.record("screen_visibility","visible",value,"aimingContinues",active);}
    private interface Action {void run(long token)throws Exception;}
    private void action(String title,Action action) {
        if(closed||stopping||stopUnconfirmed||busy||mount==null||poller.waiting()){log.record("action_blocked","action",title,"closed",closed,"busy",busy,"connected",mount!=null,"screenVisible",screenVisible,"recovery",poller.snapshot());return;}
        log.record("action_begin","action",title,"generation",generation.get());
        busy=true;long token=generation.get();message=title;publish();
        worker.execute(()->{
            try{if(valid(token)&&mount!=null){action.run(token);log.record("action_complete","action",title);}else log.record("action_cancelled","action",title);}
            catch(CancellationException e){log.record("action_cancelled","action",title);}
            catch(Exception e) {
                message=e.getMessage();log.error("action_error",e,"action",title);
                // A motion exception may be ambiguous; verify the stop before allowing further use.
                if(!stopping&&(title.equals("Positioning camera")||title.equals("Returning to landmark")))fault(message,true);
            }catch(Throwable e){log.event("action_uncaught_failure",e.toString());emergencyStop("Unexpected action failure",null);}
            finally{busy=false;publish();}
        });
    }
    private boolean valid(long token){return !closed&&!stopping&&generation.get()==token;}
    private GpsFeed.Fix freshFix() {
        GpsFeed feed=gps;if(feed==null)return null;
        GpsFeed.Fix fix=feed.latest();long now=SystemClock.elapsedRealtime();
        return fix!=null&&fix.epoch==feed.continuity()&&now>=fix.receivedAt&&now-fix.receivedAt<=Math.min(s.gpsAge,s.gpsGapTimeout)*1000?fix:null;
    }
    private void tick() {
        if(closed||stopping||stopUnconfirmed)return;
        try {
            if(SystemClock.elapsedRealtime()-lastHeartbeat>=1000){lastHeartbeat=SystemClock.elapsedRealtime();heartbeat();publish();}
            if(mount==null||busy||(active&&aimingGeneration!=generation.get()))return;
            ConnectivityManager manager=context.getSystemService(ConnectivityManager.class);
            NetworkCapabilities cap=manager.getNetworkCapabilities(network);
            if(cap==null||!cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)){fault("Wi-Fi connection lost; calibration cleared",true,false);return;}
            long now=SystemClock.elapsedRealtime();
            if(!active&&now-lastPoll<500){if(gps!=null)gps.clearObservations();return;}
            long pollToken=generation.get();
            GtiClient.Position here=poller.poll(mount,()->valid(pollToken),pendingMove?motionDeadline:0);
            if(!valid(pollToken))return;
            if(here==null) {
                // A mount outage must not fill the 64-observation framing queue and
                // appear as GPS loss. Keep latest GPS and raw telemetry logging intact.
                if(gps!=null)gps.discardUnusedObservations("Waiting for GTi; framing paused");
                boolean landmarkStop=fixedGoto!=null&&fixedGoto.landmark&&poller.interruptedMove();
                state=landmarkStop?"STOPPING FOR LANDMARK":poller.interruptedMove()?"WAITING FOR GTi":"RETRYING GTi";
                message=landmarkStop?"Waiting for a confirmed stopped position before returning to the landmark.":
                    poller.interruptedMove()?"Stop requested; checking for a stopped position automatically. Calibration and saved target retained.":
                    "Status reply missed. Retrying automatically; watchdog and GPS timeout remain active.";
                publish();return;
            }
            if(poller.waiting()) {
                MountPoller.validateRecovery(pendingMove?moveOrigin:position,here,pendingMove,expectedPan,expectedTilt,mount.cpr);
                log.record("mount_poll_restored","recovery",poller.snapshot(),"position",positionSnapshot(here),
                    "savedGoto",fixedGoto==null?null:fixedGoto.snapshot(),"tracking",active);
                // If recovery sent Stop, an intermediate stopped position is expected.
                // Replan from that verified position toward the SAME saved destination.
                if(poller.interruptedMove()){lastRateFixAt=-1;pendingMove=false;panRate=tiltRate=0;continuousPlanner.reset();}
                poller.reset();
                framing.unavailable(SystemClock.elapsedRealtime());
                if(gps!=null)gps.clearObservations();
                lastFramePacket=lastFrameSender=lastFrameAt=-1;
                state=active?fixedGoto==null?"AIMING":fixedGoto.landmark?"GOTO LANDMARK":"GOTO SAVED TARGET":calibration==null?"CONNECTED":"CALIBRATED";
                message=active?"GTi replies restored. Continuing automatically.":"GTi replies restored.";
            }
            lastPoll=SystemClock.elapsedRealtime();position=here;
            log.record("mount_position","position",positionSnapshot(here),"pendingMove",pendingMove,"expectedPan",expectedPan,"expectedTilt",expectedTilt,"motionDeadlineMs",motionDeadline);
            if(pendingMove) {
                if(here.moving&&lastPoll>motionDeadline){fault("Native move exceeded its "+(motionTimeoutMs/1000)+" s timeout",true);return;}
                if(!here.moving) {
                    double panError=Math.abs(GtiClient.deltaCounts(here.pan,expectedPan,mount.cpr[0])*360.0/mount.cpr[0]);
                    double tiltError=Math.abs(GtiClient.deltaCounts(here.tilt,expectedTilt,mount.cpr[1])*360.0/mount.cpr[1]);
                    log.record("arrival_check","panErrorDegrees",panError,"tiltErrorDegrees",tiltError,"diagnosticThresholdDegrees",.03,
                        "position",positionSnapshot(here),"expectedPan",expectedPan,"expectedTilt",expectedTilt);
                    if(panError>.03||tiltError>.03)log.record("arrival_correction_needed","panErrorDegrees",panError,"tiltErrorDegrees",tiltError,"calibrationRetained",true);
                    pendingMove=false;
                }
            }else if(here.moving&&!mount.continuous()){fault("Unexpected mount movement; stop other controllers and recalibrate",true);return;}
            if(!active){publishThrottled();return;}
            // A captured GoTo owns the destination until arrival. Do not consume live
            // surfer positions, change its framing, or interrupt it for stale GPS.
            if(fixedGoto!=null){advanceFixedGoto(here,lastPoll);publishThrottled();return;}
            now=SystemClock.elapsedRealtime();
            GpsFeed.Fix fix=freshFix();
            if(fix==null) {
                framing.unavailable(now);gps.clearObservations();
                if(calibration!=null&&!calibration.allowed(calibration.panOffset(here.pan),calibration.tiltAngle(here.tilt))){halt("Mount outside movement limits during GPS gap");return;}
                if(mount.continuous()&&ContinuousPlanner.gapAllowed(now,lastAcceptedAt,s.gpsGapTimeout)) {
                    double pr=ContinuousPlanner.limitRate(panRate,calibration.panOffset(here.pan),s.panMin,s.panMax);
                    double tr=ContinuousPlanner.limitRate(tiltRate,calibration.tiltAngle(here.tilt),s.tiltMin,s.tiltMax);
                    sendRates(pr,tr,now,true);
                    state="GPS GAP";message="Holding last rate until GPS-gap timeout";
                    log.record("gps_gap_hold","ageMs",now-lastAcceptedAt,"timeoutSeconds",s.gpsGapTimeout,"panRate",panRate,"tiltRate",tiltRate);
                    publishThrottled();return;
                }
                if(!gpsWaiting) {
                    if(here.moving||mount.continuous())mount.stop();
                    panRate=tiltRate=0;continuousPlanner.reset();pendingMove=false;gpsWaiting=true;state="WAITING GPS";message="Movement paused until fresh GPS returns";
                    log.event("gps_pause","Target stale or unavailable");
                }
                publishThrottled();return;
            }
            lastAcceptedAt=fix.receivedAt;
            if(fix.receivedAt==lastRateFixAt&&!gpsWaiting) {
                double pan=calibration.panOffset(here.pan),tilt=calibration.tiltAngle(here.tilt);
                if(!calibration.allowed(pan,tilt)){halt("Mount outside movement limits between GPS packets");return;}
                double pr=ContinuousPlanner.limitRate(panRate,pan,s.panMin,s.panMax);
                double tr=ContinuousPlanner.limitRate(tiltRate,tilt,s.tiltMin,s.tiltMax);
                if(mount.continuous())sendRates(pr,tr,now,true);
                log.record("continuous_hold","reason","No new accepted GPS packet","lastAcceptedAt",lastAcceptedAt,
                    "panRateDegreesPerSecond",panRate,"tiltRateDegreesPerSecond",tiltRate,"position",positionSnapshot(here));
                publishThrottled();return;
            }
            if(gpsWaiting){gpsWaiting=false;state="AIMING";message="Fresh GPS restored; rebuilding framing evidence";log.event("gps_resumed","Fresh fix");}
            if(continuity!=gps.continuity()) {
                framing.unavailable(now);continuity=gps.continuity();lastFramePacket=lastFrameSender=lastFrameAt=-1;
            }
            if(!targetWait.waiting()) {
                GpsFeed.Fix event;
                for(int n=0;n<64&&(event=gps.poll())!=null;n++) {
                    if(event.epoch==continuity&&now-event.receivedAt<=s.gpsAge*1000)observe(event);
                }
            }else gps.clearObservations();
            // The receiver can publish a newer fix while the queue is being drained.
            fix=freshFix();now=SystemClock.elapsedRealtime();
            if(fix==null||fix.epoch!=continuity)return;
            if(!targetWait.waiting())observe(fix);
            double distance=Geo.distance(s.latitude,s.longitude,fix.packet.lat,fix.packet.lon);
            double calculationRange=Math.max(.001,distance);
            framing.distance(calculationRange);planner.distance(calculationRange);
            double offset=targetWait.waiting()?framing.applied:framing.tick(now);
            double panTolerance=s.panToleranceDegrees(calculationRange),tiltTolerance=s.tiltToleranceDegrees(calculationRange);
            double acquisitionDegrees=s.acquisitionDegrees(calculationRange);
            double bearing=Geo.bearing(s.latitude,s.longitude,fix.packet.lat,fix.packet.lon);
            double targetHeading=Geo.wrap(bearing+offset),targetPan=calibration.targetPan(targetHeading);
            double targetTilt=Geo.elevation(distance,s.height,s.targetHeight)+s.tiltOffset;
            double pan=calibration.panOffset(here.pan),tilt=calibration.tiltAngle(here.tilt);
            if(!calibration.allowed(pan,tilt)){halt("Mount is outside the configured sector; check calibration and limits");return;}
            String targetBlocker=TargetWait.blocker(s,calibration,distance,targetPan,targetTilt);
            TargetWait.Change targetChange=targetWait.update(targetBlocker);
            if(targetBlocker!=null) {
                if(mount.continuous()||here.moving){mount.stop();panRate=tiltRate=0;continuousPlanner.reset();}
                // Keep the session armed; return of a valid target resumes automatically.
                framing.unavailable(now);gps.clearObservations();lastFramePacket=lastFrameSender=lastFrameAt=-1;
                state="WAITING TARGET";message=targetBlocker+". Aiming resumes automatically when fresh GPS is inside the limits.";
                aimText=message;
                if(targetChange==TargetWait.Change.PAUSED)log.record("aim_target_wait","reason",targetBlocker,"gps",gpsSnapshot(fix),
                    "rangeM",distance,"targetPanDegrees",targetPan,"targetTiltDegrees",targetTilt,"pendingMove",pendingMove);
                publishThrottled();return;
            }
            if(targetChange==TargetWait.Change.RESUMED) {
                state="AIMING";message="Target is back inside the limits. Aiming resumed automatically.";
                log.record("aim_auto_resumed","reason","Target returned inside limits","gps",gpsSnapshot(fix),
                    "rangeM",distance,"targetPanDegrees",targetPan,"targetTiltDegrees",targetTilt);
            }
            double panError=targetPan-pan,tiltError=targetTilt-tilt;
            aimText=String.format(Locale.US,"Range %.1f m · bearing %.2f°\nCamera %.2f° / tilt %.2f°\nAim %.2f° / tilt %.2f°\nError pan %+.2f° · tilt %+.2f°",
                distance,bearing,calibration.heading(here.pan),tilt,targetHeading,targetTilt,panError,tiltError);
            if(Double.isFinite(s.hfov))aimText+=String.format(Locale.US,"\nEstimated frame width %.1f m",2*distance*Math.tan(Math.toRadians(s.hfov/2)));
            log.record("aim_cycle","gps",gpsSnapshot(fix),"rangeM",distance,"bearingDegrees",bearing,
                "targetHeadingDegrees",targetHeading,"targetPanDegrees",targetPan,"targetTiltDegrees",targetTilt,
                "cameraHeadingDegrees",calibration.heading(here.pan),"panFromLandmarkDegrees",pan,"tiltDegrees",tilt,
                "panErrorDegrees",panError,"tiltErrorDegrees",tiltError,
                "panToleranceMetres",s.panTolerance,"tiltToleranceMetres",s.tiltTolerance,"magnitudeMetres",s.magnitude,
                "panToleranceDegrees",panTolerance,"tiltToleranceDegrees",tiltTolerance,"magnitudeDegrees",s.magnitudeDegrees(calculationRange),
                "acquisitionTriggerMetres",s.acquisitionTriggerMetres,"acquisitionTriggerDegrees",acquisitionDegrees,"offsetRate",s.offsetRate,
                "framing",framingSnapshot(now),"moving",here.moving);
            if(pendingMove){publishThrottled();return;}
            if(planner.shouldGoTo(panError,tiltError,now)) {
                fixedGoto=new FixedGoto(calibration,fix.packet,fix.receivedAt,now,offset);
                framing.holdForGoto(now);gps.clearObservations();
                log.record("goto_captured","target",fixedGoto.snapshot(),"panErrorDegrees",panError,"tiltErrorDegrees",tiltError,
                    "acquisitionTriggerDegrees",acquisitionDegrees,"acquisitionTriggerMetres",s.acquisitionTriggerMetres,"gpsAgeAtCaptureMs",now-fix.receivedAt);
                if(here.moving||mount.continuous()) {
                    mount.stop();panRate=tiltRate=0;continuousPlanner.reset();
                    log.event("goto_transition_stop","Continuous movement stopped; captured destination retained");
                    publishThrottled();return;
                }
                advanceFixedGoto(here,now);publishThrottled();return;
            }
            double pr=continuousPlanner.rate(1,panError,panTolerance,Math.min(s.maxPanningSpeed,GtiClient.MAX_SLEW_SPEED),AimUnits.degrees(s.panTolerance*1.5,calculationRange));
            double tr=continuousPlanner.rate(2,tiltError,tiltTolerance,Math.min(s.maxPanningSpeed,GtiClient.MAX_SLEW_SPEED),AimUnits.degrees(s.tiltTolerance*1.5,calculationRange));
            boolean panReverse=planner.reversalBlocked(1,pr,now),tiltReverse=planner.reversalBlocked(2,tr,now);
            if(panReverse)pr=0;if(tiltReverse)tr=0;
            pr=ContinuousPlanner.limitRate(pr,pan,s.panMin,s.panMax);
            tr=ContinuousPlanner.limitRate(tr,tilt,s.tiltMin,s.tiltMax);
            log.record("continuous_decision","panErrorDegrees",panError,"tiltErrorDegrees",tiltError,
                "panRateDegreesPerSecond",pr,"tiltRateDegreesPerSecond",tr,"panReversalBlocked",panReverse,"tiltReversalBlocked",tiltReverse,
                "panToleranceDegrees",panTolerance,"tiltToleranceDegrees",tiltTolerance,"panToleranceMetres",s.panTolerance,"tiltToleranceMetres",s.tiltTolerance,"restartFactor",ContinuousPlanner.RESTART_FACTOR,"panRestartMetres",s.panTolerance*1.5,"tiltRestartMetres",s.tiltTolerance*1.5,
                "panRestartDegrees",AimUnits.degrees(s.panTolerance*1.5,calculationRange),"tiltRestartDegrees",AimUnits.degrees(s.tiltTolerance*1.5,calculationRange),
                "responseSeconds",ContinuousPlanner.RESPONSE_SECONDS,"maximumPanningSpeed",s.maxPanningSpeed,"effectiveMaximumPanningSpeed",Math.min(s.maxPanningSpeed,GtiClient.MAX_SLEW_SPEED),
                "highSpeedPolicy","FIXED_MAXIMUM",
                "highSpeedPan",Math.abs(pr)>GtiClient.LOW_SPEED_LIMIT,"highSpeedTilt",Math.abs(tr)>GtiClient.LOW_SPEED_LIMIT,
                "panHighEntryErrorDegrees",ContinuousPlanner.highEntryError(panTolerance,Math.min(s.maxPanningSpeed,GtiClient.MAX_SLEW_SPEED)),
                "panHighReturnErrorDegrees",ContinuousPlanner.highReturnError(panTolerance,Math.min(s.maxPanningSpeed,GtiClient.MAX_SLEW_SPEED)),
                "tiltHighEntryErrorDegrees",ContinuousPlanner.highEntryError(tiltTolerance,Math.min(s.maxPanningSpeed,GtiClient.MAX_SLEW_SPEED)),
                "tiltHighReturnErrorDegrees",ContinuousPlanner.highReturnError(tiltTolerance,Math.min(s.maxPanningSpeed,GtiClient.MAX_SLEW_SPEED)));
            if(sendRates(pr,tr,now,false))lastRateFixAt=fix.receivedAt;
            state="AIMING";message=panReverse||tiltReverse?"Direction reversal hold":pr==0&&tr==0?"Within aiming tolerance / restart boundary":Math.abs(pr)>.5||Math.abs(tr)>.5?"High-speed catch-up at fixed maximum":"Low-speed correction";
            publishThrottled();
        }catch(CancellationException e){log.event("mount_poll_cancelled","Stop or disconnect took priority");}
        catch(Exception e){
            log.error("control_error",e);
            if(!stopping&&active&&freshFix()==null&&"Movement cancelled or inputs expired".equals(e.getMessage()))stopForGpsTimeout();
            else if(!stopping)fault(e.getMessage(),true);
        }
        catch(Throwable e){log.event("control_uncaught_error",e.toString());emergencyStop("Unexpected control failure",null);}
        finally{watchdog.healthy(SystemClock.elapsedRealtime());}
    }
    private boolean sendRates(double pan,double tilt,long now,boolean gap)throws IOException {
        // At most four updates/s; zero/reversal requests are never delayed by rate throttling.
        if(now-lastRatesAt<250&&(pan!=0||panRate==0)&&(tilt!=0||tiltRate==0))return false;
        long token=aimingGeneration;GtiClient current=mount;
        if(!valid(token)||!active)return false;
        current.begin();
        current.rates(pan*s.panSign,tilt*s.tiltSign,()->valid(token)&&active&&
            (gap?ContinuousPlanner.gapAllowed(SystemClock.elapsedRealtime(),lastAcceptedAt,s.gpsGapTimeout):freshFix()!=null));
        panRate=pan;tiltRate=tilt;lastRatesAt=SystemClock.elapsedRealtime();
        planner.sent(1,pan,lastRatesAt);planner.sent(2,tilt,lastRatesAt);
        log.record("continuous_rates","panDegreesPerSecond",pan,"tiltDegreesPerSecond",tilt,"gpsGap",gap);
        return true;
    }
    private void observe(GpsFeed.Fix f) {
        if(f.receivedAt<lastFrameAt||(f.packet.sequence==lastFramePacket&&f.packet.senderMs==lastFrameSender))return;
        framing.distance(Math.max(.001,Geo.distance(s.latitude,s.longitude,f.packet.lat,f.packet.lon)));
        framing.observe(f.packet.sequence,f.packet.senderMs,f.receivedAt,Geo.bearing(s.latitude,s.longitude,f.packet.lat,f.packet.lon));
        log.record("framing_observation","gps",gpsSnapshot(f),"bearingDegrees",Geo.bearing(s.latitude,s.longitude,f.packet.lat,f.packet.lon),"framing",framingSnapshot(f.receivedAt));
        lastFramePacket=f.packet.sequence;lastFrameSender=f.packet.senderMs;lastFrameAt=f.receivedAt;
    }
    private void rememberMove(GtiClient.Position here,double pan,double tilt) {
        rememberMove(here,pan,tilt,15000);
    }
    private void rememberMove(GtiClient.Position here,double pan,double tilt,long timeoutMs) {
        moveOrigin=here;
        expectedPan=GtiClient.wrap24(here.pan+(int)Math.round(pan*s.panSign*mount.cpr[0]/360.0));
        expectedTilt=GtiClient.wrap24(here.tilt+(int)Math.round(tilt*s.tiltSign*mount.cpr[1]/360.0));
        pendingMove=true;motionTimeoutMs=timeoutMs;motionDeadline=SystemClock.elapsedRealtime()+timeoutMs;
    }
    private void advanceFixedGoto(GtiClient.Position here,long now)throws IOException {
        FixedGoto target=fixedGoto;
        Calibration navigation=target.calibration;
        double pan=navigation.panOffset(here.pan),tilt=navigation.tiltAngle(here.tilt);
        double[] steps=target.nextMove(pan,tilt);
        if(gps!=null)gps.clearObservations();
        state=target.landmark?"GOTO LANDMARK":"GOTO SAVED TARGET";
        message=target.landmark?"Returning pan and tilt to the last saved landmark; surfer tracking is off.":"Moving to the saved position; new GPS does not change this GoTo.";
        aimText=String.format(Locale.US,"%s · range %.1f m\nSaved aim %.2f° / tilt %.2f°\nCamera %.2f° / tilt %.2f°\nRemaining pan %+.2f° · tilt %+.2f°",
            target.landmark?"Last saved landmark":"Saved GPS #"+target.source.sequence,target.range,target.heading,target.tilt,navigation.heading(here.pan),tilt,target.pan-pan,target.tilt-tilt);
        log.record("goto_progress","target",target.snapshot(),"position",positionSnapshot(here),
            "remainingPanDegrees",target.pan-pan,"remainingTiltDegrees",target.tilt-tilt,"pendingMove",pendingMove);
        if(here.moving)return;
        if(steps[0]==0&&steps[1]==0) {
            log.record("goto_completed","target",target.snapshot(),"position",positionSnapshot(here),"elapsedMs",now-target.capturedAt);
            fixedGoto=null;lastRateFixAt=-1;framing.holdForGoto(now);lastFramePacket=lastFrameSender=lastFrameAt=-1;
            if(gps!=null){gps.clearObservations();continuity=gps.continuity();}
            if(target.landmark) {
                active=false;state=calibration==null?"CONNECTED":"CALIBRATED";
                message=calibration==null?"Last landmark position reached. Check alignment and validate calibration before tracking.":
                    "Last landmark reached. Pan and tilt returned; surfer tracking remains off.";
                aimText="At the last saved landmark position";
            }else {
                state="AIMING";message="Saved position reached. Resuming live GPS aiming.";
                aimText="Saved position reached; checking the latest surfer GPS";
            }
            return;
        }
        // Retain the fixed destination while respecting the same reversal hold as fine aiming.
        if(planner.reversalBlocked(1,steps[0],now)||planner.reversalBlocked(2,steps[1],now)){
            log.record("goto_reversal_hold","panStepDegrees",steps[0],"tiltStepDegrees",steps[1]);return;
        }
        long token=aimingGeneration,expires=now+1000;
        GtiClient current=mount;current.begin();
        current.goTo(here,steps[0]*s.panSign,steps[1]*s.tiltSign,
            ()->valid(token)&&active&&fixedGoto==target&&SystemClock.elapsedRealtime()<=expires);
        rememberMove(here,steps[0],steps[1],FixedGoto.timeoutMs(steps[0],steps[1]));
        planner.sent(1,steps[0],SystemClock.elapsedRealtime());planner.sent(2,steps[1],SystemClock.elapsedRealtime());
        log.record("goto_move","target",target.snapshot(),"panStepDegrees",steps[0],"tiltStepDegrees",steps[1],
            "destinationPan",expectedPan,"destinationTilt",expectedTilt,"timeoutMs",motionTimeoutMs,"motionDeadlineMs",motionDeadline);
    }
    private void clearFixedGoto(String reason) {
        if(fixedGoto!=null)log.record("goto_cancelled","reason",reason,"target",fixedGoto.snapshot());
        fixedGoto=null;
    }
    private String lastLandmarkBlocker() {
        SavedLandmark bookmark=lastLandmark;GtiClient currentMount=mount;
        if(bookmark==null)return "Calibrate once to save the last landmark";
        if(currentMount==null)return "Connect the GTi to return to the last landmark";
        try{bookmark.navigationReference(s,currentMount.cpr,currentMount.firmware);return null;}
        catch(IllegalArgumentException e){return e.getMessage();}
    }
    private void invalidateLastLandmark(String reason) {
        if(lastLandmark==null||lastLandmark.invalidReason.equals(reason))return;
        lastLandmark=lastLandmark.invalidate(reason);
        log.record("last_landmark_invalidated","reason",reason,"landmark",lastLandmark.snapshot());
        try{landmarkStore.save(lastLandmark);}
        catch(IOException e){landmarkStorageError=e.getMessage();log.error("last_landmark_invalidation_save_failed",e);}
    }
    private void halt(String why)throws IOException {
        log.event("aim_paused",why);emergencyStop(why,null);
    }
    private void fault(String why,boolean attemptStop) {
        fault(why,attemptStop,true);
    }
    private void fault(String why,boolean attemptStop,boolean allowRetainConnection) {
        String detail=why==null?"Control error":why;
        if(mount!=null&&mount.referenceLost)invalidateLastLandmark("Mount motor reference was lost; calibrate to save a new landmark");
        log.record("fault","cause",detail,"calibration",calibrationSnapshot(calibration));
        calibration=null;
        // Keep the transport available for Stop retries if confirmation fails.
        emergencyStop(detail,allowRetainConnection?null:()->{cleanup();state="CONNECTION CLOSED";message=detail+"; Stop confirmed. Reconnect and calibrate.";});
    }
    private void cleanup() {
        clearFixedGoto("Connection closed");
        log.record("calibration_cleared","previous",calibrationSnapshot(calibration));

        GtiClient client=mount;mount=null;if(client!=null)client.close();
        calibration=null;position=null;moveOrigin=null;pendingMove=false;framing.reset();planner.reset();network=null;poller.reset();targetWait.reset();
    }
    private void publishThrottled(){long now=SystemClock.elapsedRealtime();if(now-lastPublish>=200){lastPublish=now;publish();}}
    private void publish() {
        GtiClient m=mount;GpsFeed feed=gps;GtiClient.Position p=position;Calibration cal=calibration;
        String gpsText=feed==null?"LoRa disconnected":feed.status();
        GpsFeed.Fix f=feed==null?null:feed.latest();
        if(f!=null)gpsText+=String.format(Locale.US,"\nAge %.1f s · seq %d\n%.7f, %.7f\nSatellites %d · HDOP %.2f · RSSI %d · SNR %.1f",
            (SystemClock.elapsedRealtime()-f.receivedAt)/1000.0,f.packet.sequence,f.packet.lat,f.packet.lon,f.packet.satellites,f.packet.hdop,f.packet.rssi,f.packet.snr);
        String mountText=m==null?"GTi disconnected":s.mountHost+":"+s.mountPort+"\nFirmware "+m.firmware;
        if(m!=null)mountText+=String.format(Locale.US,"\nPan speed cap %.2f°/s · effective %.2f°/s",s.maxPanningSpeed,Math.min(s.maxPanningSpeed,GtiClient.MAX_SLEW_SPEED));
        if(p!=null)mountText+="\nMotor counts "+p.pan+" / "+p.tilt+(p.moving?" · last reported moving":" · last reported stopped");
        if(poller.waiting())mountText+="\nStatus unavailable — checking automatically";
        String calText=cal==null?"Calibration required each connection":String.format(Locale.US,"Landmark %.2f° true · elevation %.2f°\nPan sector %+.1f° to %+.1f° from landmark\nTilt %.1f° to %.1f°",cal.bearing,cal.elevation,s.panMin,s.panMax,s.tiltMin,s.tiltMax);
        String frame=String.format(Locale.US,"%s · magnitude %.2f m\nDesired %+.2f° · applied %+.2f°\n%s\nWindow change %+.2f° · consistency %.0f%% · supports %d",
            "AUTO",s.magnitude,framing.desired,framing.applied,framing.reason,framing.net,100*framing.consistency,framing.supporting);
        List<String> blockers=blockers();
        String readiness=blockers.isEmpty()?"Ready — tap Start aiming":String.join("\n",blockers);
        GpsFeed.Fix fresh=freshFix();
        if(fresh!=null&&Geo.valid(s.latitude,s.longitude))
            readiness=String.format(Locale.US,"Target range %.1f m · allowed %.1f–%.1f m\n",
                Geo.distance(s.latitude,s.longitude,fresh.packet.lat,fresh.packet.lon),s.rangeMin,s.rangeMax)+readiness;
        String displayedAim=poller.waiting()?message:active?(gpsWaiting?"Aiming paused: "+gpsText:aimText):readiness;
        String links=connections();
        String landmarkBlocker=lastLandmarkBlocker();
        String landmarkText=lastLandmark==null?"No last landmark saved":
            "Last landmark saved "+new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.US).format(new Date(lastLandmark.savedAtEpochMs));
        landmarkText+="\n"+(landmarkBlocker==null?cal==null?"Return is available. Tracking calibration still needs validation.":"Return is available; tracking will stay off after arrival.":landmarkBlocker);
        if(!landmarkStorageError.isEmpty())landmarkText+="\nStorage error: "+landmarkStorageError;
        String displayedState=state.equals("CONNECTED")?"GTi CONNECTED":state.equals("CALIBRATED")?
            (blockers.isEmpty()?"READY TO AIM":"CALIBRATED · AIMING BLOCKED"):state;
        String signature=displayedState+"|"+message+"|"+links+"|"+blockers;
        if(!signature.equals(lastPublishedState)){
            lastPublishedState=signature;log.record("state_change","state",displayedState,"message",message,"connections",links,"tracking",active,"blockers",blockers);
        }
        listener.update(new View(displayedState,message,mountText,gpsText,displayedAim,frame,calText,links,
            m!=null,active,cal!=null,pendingMove||(p!=null&&p.moving),busy||stopping||stopUnconfirmed||poller.waiting(),blockers.isEmpty()&&!active&&!stopping,
            landmarkText,landmarkBlocker==null&&m!=null&&!busy&&!stopping&&!stopUnconfirmed&&!poller.waiting()&&!closed));
    }
    private List<String> blockers() {
        List<String> out=Readiness.blockers(s,mount!=null,calibration!=null,pendingMove||(position!=null&&position.moving),busy,!closed,
            freshFix()!=null,gps==null?"LoRa disconnected":gps.status());
        if(stopping)out.add("Stopping both axes");
        if(stopUnconfirmed)out.add("Stop unconfirmed — retry Stop before any movement");
        if(poller.waiting())out.add("GTi status unavailable — automatic recovery in progress");
        return out;
    }
    private String connections() {
        GpsFeed feed=gps;
        return "GTi: "+(mount==null?"Disconnected":poller.waiting()?"Waiting for replies":"Connected")+" · "+s.mountHost+
            "\nLoRa: "+(feed==null?loraLinkStatus:feed.connectionStatus())+" · "+s.receiverHost+
            "\nCalibration: "+(calibration==null?"Required":"Set");
    }
    private void heartbeat() {
        log.record("status_snapshot","state",state,"message",message,"connected",mount!=null,"tracking",active,"busy",busy,
            "screenVisible",screenVisible,"generation",generation.get(),"settings",s.values,"blockers",blockers(),"connections",connections(),
            "calibration",calibrationSnapshot(calibration),"position",positionSnapshot(position),"positionSampleMonoMs",lastPoll,"gps",gpsSnapshot(gps==null?null:gps.latest()),
            "gpsStatus",gps==null?"disconnected":gps.status(),"gpsFresh",freshFix()!=null,"continuity",continuity,
            "panRateDegreesPerSecond",panRate,"tiltRateDegreesPerSecond",tiltRate,"gpsGapTimeout",s.gpsGapTimeout,
            "pendingMove",pendingMove,"expectedPan",expectedPan,"expectedTilt",expectedTilt,"motionDeadlineMs",motionDeadline,"motionTimeoutMs",motionTimeoutMs,
            "savedGoto",fixedGoto==null?null:fixedGoto.snapshot(),"mountRecovery",poller.snapshot(),"positionFresh",!poller.waiting(),
            "waitingForTargetLimits",targetWait.waiting(),"lastLandmark",lastLandmark==null?null:lastLandmark.snapshot(),
            "lastLandmarkBlocker",lastLandmarkBlocker(),"lastLandmarkStorageError",landmarkStorageError,
            "framing",framingSnapshot(SystemClock.elapsedRealtime()),"planner",planner.snapshot(SystemClock.elapsedRealtime()),"logStatus",log.status());
    }
    private Map<String,Object> positionSnapshot(GtiClient.Position p) {
        if(p==null)return null;
        Map<String,Object> out=new LinkedHashMap<>();out.put("panRaw",p.pan);out.put("tiltRaw",p.tilt);out.put("moving",p.moving);
        out.put("panStatus",p.panStatus);out.put("tiltStatus",p.tiltStatus);
        out.put("panStatusBefore",p.panStatusBefore);out.put("tiltStatusBefore",p.tiltStatusBefore);
        out.put("stoppedDuringPositionRead",!p.moving);
        Calibration c=calibration;
        if(c!=null){out.put("headingDegrees",c.heading(p.pan));out.put("panFromLandmarkDegrees",c.panOffset(p.pan));out.put("tiltDegrees",c.tiltAngle(p.tilt));}
        return out;
    }
    private Map<String,Object> calibrationSnapshot(Calibration c) {
        if(c==null)return null;
        Map<String,Object> out=new LinkedHashMap<>();out.put("panRaw",c.panRaw);out.put("tiltRaw",c.tiltRaw);
        out.put("panCountsPerRevolution",c.panCpr);out.put("tiltCountsPerRevolution",c.tiltCpr);
        out.put("landmarkBearingDegrees",c.bearing);out.put("landmarkElevationDegrees",c.elevation);out.put("settings",c.settings.values);return out;
    }
    private Map<String,Object> gpsSnapshot(GpsFeed.Fix f) {
        if(f==null)return null;
        Map<String,Object> out=f.packet.fields();out.put("receivedAtMs",f.receivedAt);out.put("ageMs",SystemClock.elapsedRealtime()-f.receivedAt);out.put("continuity",f.epoch);return out;
    }
    private Map<String,Object> framingSnapshot(long now) {
        Map<String,Object> out=new LinkedHashMap<>();out.put("mode","AUTO");out.put("direction",framing.direction);
        out.put("desiredOffsetDegrees",framing.desired);out.put("appliedOffsetDegrees",framing.applied);out.put("reason",framing.reason);
        out.put("netBearingChangeDegrees",framing.net);out.put("totalBearingTravelDegrees",framing.travel);out.put("consistency",framing.consistency);
        out.put("supportingChanges",framing.supporting);out.put("quietSeconds",framing.quietSeconds(now));return out;
    }
    public void close() {
        if(closed)return;
        active=false;closed=true;generation.incrementAndGet();GtiClient m=mount;if(m!=null)m.cancel();
        calibrationFiles.close();
        watchdogThread.shutdownNow();loraWanted=false;loraWorker.shutdownNow();closeLora();
        emergency.execute(()->{
            try{if(m!=null)m.stop();log.event("service_close_stopped","Both axes stopped");}
            catch(Throwable e){log.event("close_stop_unconfirmed",e.toString());}
            finally{worker.execute(()->{cleanup();log.close();});worker.shutdown();}
        });
        emergency.shutdown();
    }
}
