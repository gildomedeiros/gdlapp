package au.com.gildo.vtc;

import android.content.Context;
import android.net.*;
import android.os.SystemClock;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

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
    private volatile boolean stopping,stopUnconfirmed,replayLocked,exiting,mountReconnectPending;
    private volatile Runnable afterStop;
    private final StopCompletion stopCompletion=new StopCompletion();
    private volatile long lastAcceptedAt=-1;
    private volatile double panRate,tiltRate;
    private volatile long lastRatesAt;
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
    void connectLora(){if(closed||replayLocked)return;loraWanted=true;loraWorker.execute(this::maintainLora);log.event("lora_connect_requested","Independent LoRa connection");publish();}
    void disconnectLora(){loraWanted=false;loraWorker.execute(this::maintainLora);log.event("lora_disconnect_requested","Mount connection retained");}
    Network wifiNetwork()throws IOException{
        ConnectivityManager cm=context.getSystemService(ConnectivityManager.class);Network result=null;
        for(Network n:cm.getAllNetworks()){NetworkCapabilities cap=cm.getNetworkCapabilities(n);if(cap!=null&&cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)){if(result!=null)throw new IOException("Select a single Wi-Fi network");result=n;}}
        if(result==null)throw new IOException("Join Wi-Fi first");return result;
    }
    private synchronized void maintainLora(){
        try{
            if(closed||replayLocked||!loraWanted){closeLora();loraLinkStatus="Disconnected";return;}
            Network wifi=wifiNetwork();Settings settings=s;
            if(!Settings.ipv4(settings.receiverHost))throw new IOException("Enter the LoRa receiver IPv4 address");
            String key=wifi+"/"+settings.receiverHost+"/"+settings.receiverPort+"/"+settings.receivePort+"/"+settings.gpsAge+"/"+settings.maxGpsSpeed;
            if(gps==null||gps.ended()||!key.equals(loraKey)){
                closeLora();loraNetwork=wifi;loraKey=key;gps=new GpsFeed(wifi,settings,log,this::wakeTracking);gps.start();
                log.record("lora_link_started","network",wifi.toString(),"settings",settings.values);
            }
            loraLinkStatus=gps.connectionStatus();
            if(mount==null)gps.clearObservations();
        }catch(Exception e){closeLora();loraLinkStatus="Retrying: "+e.getMessage();log.error("lora_link_retry",e);}
    }
    private synchronized void closeLora(){GpsFeed old=gps;gps=null;loraNetwork=null;loraKey="";if(old!=null)old.close();}
    Network cameraNetwork(){return network!=null?network:loraNetwork;}
    boolean disconnectDropsCamera(boolean gti){
        Network mountLink=network,loraLink=loraNetwork;
        return gti?mountLink!=null&&loraLink==null:loraLink!=null&&mountLink==null;
    }
    Settings settings(){return s;}
    ZoomPlan.Input zoomInput(){
        Settings settings=s;GpsFeed feed=gps;GpsFeed.Fix fix=feed==null?null:feed.latest();
        long token=surferAimingGeneration;
        long zoomSession=active&&valid(token)?token:-1;
        if(fix==null)return new ZoomPlan.Input(Double.NaN,-1,false,zoomSession);
        long age=SystemClock.elapsedRealtime()-fix.receivedAt;
        double distance=Geo.valid(settings.latitude,settings.longitude)?Geo.distance(settings.latitude,settings.longitude,fix.packet.lat,fix.packet.lon):Double.NaN;
        return new ZoomPlan.Input(distance,age,fix.epoch==feed.continuity()&&age>=0&&age<=settings.gpsAge*1000&&Double.isFinite(distance),zoomSession);
    }
    String liveGps(){GpsFeed f=gps;return f==null?"LoRa disconnected":f.display();}
    void retryLandmarkStorage(){landmarkStore.retry();}
    private volatile Calibration calibration;
    private volatile GtiClient.Position position;
    private GtiClient.Position moveOrigin;
    private FramingEngine framing;
    private volatile AimPlanner planner;
    private FixedGoto fixedGoto;
    private String state="DISCONNECTED",message="Configure the site and connect both devices.";
    private String aimText="Calculating first aiming target";
    private long lastPoll,lastFramePacket=-1,lastFrameSender=-1,lastFrameAt=-1,continuity=-1,motionDeadline,lastPublish;
    private volatile boolean pendingMove;
    private boolean gpsWaiting;
    private long motionTimeoutMs=15000;
    private int expectedPan,expectedTilt;
    private long lastHeartbeat;
    private long aimingGeneration=-1;
    private volatile long surferAimingGeneration=-1;
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
    boolean canEditMountSettings() {
        GtiClient.Position last=position;
        return !closed&&!exiting&&!replayLocked&&!busy&&!stopping&&!stopUnconfirmed&&!poller.waiting()
            &&!active&&!pendingMove&&(last==null||!last.moving);
    }
    synchronized void configure(Settings settings) {
        Set<String> changed=settings.changesFrom(s);
        if(changed.isEmpty())return;
        if(closed||exiting||replayLocked)throw new IllegalStateException("VTC settings are unavailable");
        boolean live=changed.stream().allMatch(Settings::canEditDuringAiming);
        if(!live&&!canEditMountSettings())
            throw new IllegalStateException("Stop aiming and wait for the mount to stop before changing these settings");

        boolean mountAddressChanged=changed.contains("mountHost")||changed.contains("mountPort");
        boolean referenceChanged=!Collections.disjoint(changed,Arrays.asList("latitude","longitude","height",
            "landmarkLat","landmarkLon","landmarkHeight","panSign","tiltSign"));
        if(mountAddressChanged&&mount!=null&&!Settings.ipv4(settings.mountHost))
            throw new IllegalArgumentException("Enter a valid GTi IP address");
        GtiClient.Position last=position;
        Calibration current=calibration;
        s=settings;
        framing.configure(settings);
        if(!live)planner=new AimPlanner(settings);
        if(referenceChanged&&current!=null){
            log.record("calibration_cleared","reason","Calibration reference changed","previous",calibrationSnapshot(current));
            calibration=null;state="CONNECTED";message="Reference changed. Calibrate before starting aiming.";
        }else if(current!=null&&!Collections.disjoint(changed,Arrays.asList("panMin","panMax","tiltMin","tiltMax"))){
            calibration=current.withSettings(settings);
            if(last!=null&&!calibration.allowed(calibration.panOffset(last.pan),calibration.tiltAngle(last.tilt)))
                message="Mount is outside the new limits. Check the sector before starting aiming.";
        }
        log.settings("settings_applied",s);
        if(mountAddressChanged&&mount!=null) {
            mountReconnectPending=true;
            stopCompletion.request(ok->{
                if(ok&&!exiting){cleanup();state="DISCONNECTED";connectMount();}
                else if(!ok)message="GTi address saved. Stop unconfirmed at the old address. Confirm Stop and reconnect.";
            });
            emergencyStop("Changing GTi address",null);
        }else publish();
    }
    void connect() {
        if(exiting||replayLocked)return;
        connectLora();
        connectMount();
    }
    private synchronized void connectMount() {
        if(exiting||replayLocked)return;
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
            if(active||pendingMove)throw new IOException("Wait for the mount to stop before calibrating");
            GtiClient.Position here=mount.position(()->valid(token));
            calibrateAt(here,token,"manual");
        });
    }
    private void calibrateAt(GtiClient.Position here,long token,String method)throws IOException {
        if(here.moving)throw new IOException("Wait for the mount to stop before calibrating");
        if(!valid(token))return;
        calibration=new Calibration(s,here,mount.cpr);position=here;
        state="CALIBRATED";message="Landmark calibrated and saved. Check Aim / next steps before starting.";
        SavedLandmark bookmark=SavedLandmark.capture(calibration,mount.firmware,System.currentTimeMillis());
        try{landmarkStore.save(bookmark);lastLandmark=bookmark;landmarkStorageError="";log.record("last_landmark_saved","landmark",bookmark.snapshot());}
        catch(IOException e){landmarkStorageError=e.getMessage();message="Calibrated, but last landmark could not be saved: "+e.getMessage();log.error("last_landmark_save_failed",e);}
        calibrationFiles.save(bookmark);
        log.record("calibration_success","method",method,"calibration",calibrationSnapshot(calibration),"position",positionSnapshot(here));
        startOffshoreGoto(here,token);
    }
    private void startOffshoreGoto(GtiClient.Position here,long token) {
        if(!valid(token))return;
        OffshoreHeading saved=s.offshoreHeading;
        if(saved==null){
            log.record("offshore_goto_skipped","reason","No saved offshore heading");
            return;
        }
        double tilt=calibration.tiltAngle(here.tilt);
        if(!calibration.allowed(calibration.targetPan(saved.degrees),tilt)){
            message="Calibrated. Saved offshore heading is outside the pan limits.";
            log.record("offshore_goto_skipped","reason","Outside pan limits","headingDegrees",saved.degrees,
                "panMin",s.panMin,"panMax",s.panMax);
            return;
        }
        fixedGoto=FixedGoto.offshore(calibration,saved.degrees,tilt,SystemClock.elapsedRealtime());
        planner.reset();surferAimingGeneration=-1;aimingGeneration=token;active=true;
        state=fixedGoto.kind.state;message="Calibrated. Panning to the saved offshore heading.";
        log.record("goto_captured","target",fixedGoto.snapshot(),"reason","After calibration","position",positionSnapshot(here));
    }
    void nudge(int axis,double degrees) {
        if(active){log.record("manual_move_blocked","axis",axis,"degrees",degrees,"reason","Aiming active");return;}
        action("Positioning camera",token->{
            Calibration reference=calibration;
            int maxStep=axis==1&&reference!=null?30:1;
            if(!Double.isFinite(degrees)||Math.abs(degrees)>maxStep){
                message="Manual move must be at most "+maxStep+"°";
                log.record("manual_move_blocked","axis",axis,"degrees",degrees,"reason",message);
                return;
            }
            if(pendingMove){message="Wait for the current move to finish";return;}
            GtiClient.Position here=mount.position(()->valid(token));
            if(here.moving)throw new IOException("Wait for the current move to finish");
            double pan=axis==1?degrees:0,tilt=axis==2?degrees:0;
            if(reference!=null&&!reference.allowsManualMove(here,pan,tilt)){
                message="That step would exceed the movement limits. Choose a smaller step or turn the other way.";
                log.record("manual_move_blocked","axis",axis,"degrees",degrees,"reason",message,"position",positionSnapshot(here));
                return;
            }
            long deadline=SystemClock.elapsedRealtime()+1000;
            java.util.function.BooleanSupplier permitted=()->valid(token)&&SystemClock.elapsedRealtime()<=deadline;
            mount.begin();
            boolean coarsePan=Math.abs(pan)>1;
            if(coarsePan)mount.goTo(here,pan*s.panSign,0,permitted);
            else mount.move(here,pan*s.panSign,tilt*s.tiltSign,permitted);
            rememberMove(here,pan,tilt,FixedGoto.timeoutMs(pan,tilt));
            message="Finite native GoTo sent; waiting for arrival";
            log.record("manual_move","panDegrees",pan,"tiltDegrees",tilt,"destinationPan",expectedPan,"destinationTilt",expectedTilt,
                "coarsePan",coarsePan,"timeoutMs",motionTimeoutMs);
        });
    }
    void captureOffshoreHeading(Executor completionThread, Consumer<Settings> captured) {
        action("Reading offshore heading", token -> {
            Calibration reference=calibration;
            if(reference==null)throw new IOException("Calibrate the landmark first");
            if(active||pendingMove)throw new IOException("Wait for the mount to stop before saving");
            GtiClient.Position measured=mount.position();
            if(measured.moving)throw new IOException("Wait for the mount to stop before saving");
            if(!valid(token)||calibration!=reference)return;
            double heading=reference.heading(measured.pan);
            completionThread.execute(()->{
                if(!valid(token)||active||pendingMove||calibration!=reference)return;
                Map<String,String> values=new LinkedHashMap<>(s.values);
                values.put("offshoreHeadingDegrees",Double.toString(heading));
                Settings next=new Settings(values);
                framing=new FramingEngine(next);
                s=next;
                message=String.format(Locale.US,"Offshore heading set to %.2f° true",heading);
                log.record("offshore_heading_captured","headingDegrees",heading,"motorPanCount",measured.pan);
                log.settings("offshore_heading_captured",next);
                captured.accept(next);
                publish();
            });
        });
    }

    void start() {
        action("Starting aiming",token->{
            log.settings("aim_start_requested",s);
            if(calibration==null)throw new IOException("Calibrate the landmark first");
            s.siteReady();
            if(s.offshoreHeading==null)throw new IOException("Wave Ride: save an offshore heading before starting aiming");
            GpsFeed.Fix fix=freshFix();
            if(fix==null)throw new IOException("Wait for fresh, valid LoRa GPS");
            GtiClient.Position here=mount.position();
            if(here.moving)throw new IOException("Wait for the mount to stop");
            double distance=Geo.distance(s.latitude,s.longitude,fix.packet.lat,fix.packet.lon);
            double bearing=Geo.bearing(s.latitude,s.longitude,fix.packet.lat,fix.packet.lon);
            double tilt=Geo.elevation(distance,s.height,s.targetHeight)+s.tiltOffset;
            if(!valid(token))return;
            continuousPlanner.reset();lastAcceptedAt=fix.receivedAt;framing.reset();planner.reset();gps.clearObservations();lastFramePacket=lastFrameSender=lastFrameAt=-1;continuity=gps.continuity();
            clearFixedGoto("New aiming session");targetWait.reset();
            long now=SystemClock.elapsedRealtime();
            livePan.reset(here.pan,now);liveTilt.reset(here.tilt,now);nextGtiReconnectAt=-1;
            mount.begin();
            position=here;aimingGeneration=token;surferAimingGeneration=token;active=true;gpsWaiting=false;pendingMove=false;state="AIMING";message="Large error: saved-target GoTo. Near target: live GPS corrections.";
            log.record("aim_start","calibration",calibrationSnapshot(calibration),"gps",gpsSnapshot(fix),"rangeM",distance,"bearing",bearing,"tilt",tilt);
        });
    }
    void goToLandmark(){goToLandmark(false);}
    void goToLandmark(boolean calibrateOnArrival) {
        String blocker=lastLandmarkBlocker();
        if(closed||stopping||stopUnconfirmed||busy||mount==null||blocker!=null||poller.waiting()) {
            log.record("landmark_goto_blocked","connected",mount!=null,"calibrated",calibration!=null,
                "reason",blocker,"busy",busy,"screenVisible",screenVisible,"recovery",poller.snapshot());return;
        }
        // Cancel any pending surfer START before the serialized landmark action takes over.
        if(active&&surferAimingGeneration==aimingGeneration)log.event("aim_stopped","Returning to landmark");
        active=false;generation.incrementAndGet();mount.cancel();
        action("Returning to landmark",token->{
            Calibration navigation=lastLandmark.navigationReference(s,mount.cpr,mount.firmware);
            clearFixedGoto("Go to last landmark requested");targetWait.reset();
            fixedGoto=FixedGoto.landmark(navigation,SystemClock.elapsedRealtime(),calibrateOnArrival);
            log.record("goto_captured","target",fixedGoto.snapshot(),"reason",
                calibrateOnArrival?"User Go to landmark and calibrate here":"User Go to last landmark",
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
    synchronized void disconnectForReplay(java.util.function.Consumer<Boolean> completed){
        if(closed||stopping||replayLocked||stopUnconfirmed&&mount==null){completed.accept(false);return;}
        replayLocked=true;
        stopCompletion.request(ok->{
            if(ok){
                cleanup();
                synchronized(this){loraWanted=false;closeLora();loraLinkStatus="Disconnected";}
                state="DISCONNECTED";message="Replay · mount stopped and devices disconnected";
                stopUnconfirmed=false;
                log.event("replay_devices_disconnected","GTi stopped and GTi/LoRa disconnected");
            }else replayLocked=false;
            publish();completed.accept(ok);
        });
        emergencyStop("Replay",null);
    }
    void leaveReplay(){replayLocked=false;publish();}

    synchronized void exit(java.util.function.Consumer<Boolean> completed){
        exiting=true;
        stopCompletion.request(ok->{
            if(ok){cleanup();loraWanted=false;closeLora();loraLinkStatus="Disconnected";}
            else exiting=false;
            log.record("exit_complete","stopConfirmed",ok);completed.accept(ok);
        });
        emergencyStop("Exit VTC",null);
    }
    private synchronized void emergencyStop(String why,Runnable afterConfirmed) {
        if(closed)return;
        if(afterConfirmed!=null)afterStop=afterConfirmed;
        if(active&&surferAimingGeneration==aimingGeneration)log.event("aim_stopped",why);
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
                else{mount.begin();livePan.reset(livePan.raw,SystemClock.elapsedRealtime());liveTilt.reset(liveTilt.raw,SystemClock.elapsedRealtime());state=active?"WAITING GPS":"CALIBRATED";message=active?"GPS timeout; aiming resumes when usable GPS returns":"Both axes stopped";
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
        if(closed||exiting||stopping||replayLocked||stopUnconfirmed||mountReconnectPending||busy||mount==null||poller.waiting()){log.record("action_blocked","action",title,"closed",closed,"busy",busy,"connected",mount!=null,"screenVisible",screenVisible,"recovery",poller.snapshot());return;}
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
    private final TrackingAxis livePan=new TrackingAxis(),liveTilt=new TrackingAxis();
    private long nextGtiReconnectAt=-1;
    private final java.util.concurrent.atomic.AtomicBoolean trackingWakeQueued=new java.util.concurrent.atomic.AtomicBoolean();

    private void wakeTracking() {
        if(!active||surferAimingGeneration!=aimingGeneration||closed||!trackingWakeQueued.compareAndSet(false,true))return;
        try {
            worker.execute(()->{trackingWakeQueued.set(false);tick();});
        }catch(java.util.concurrent.RejectedExecutionException closedWorker){trackingWakeQueued.set(false);}
    }

    private static final class TrackingAxis {
        int raw,direction,lastDirection,unchanged;
        long sampledAt=-1,lastStart=-1,lastAttemptFix=-1,stopAt=-1,stopFix=-1;
        long sourceFix=-1,lastReadSent=-1;
        GpsFeed.Fix lastPollFix;
        int targetRaw;
        boolean firstMotion,finite;
        double rate;
        void reset(int position,long now) {
            lastReadSent=-1;lastPollFix=null;
            raw=targetRaw=position;sampledAt=now;direction=lastDirection=unchanged=0;
            lastStart=lastAttemptFix=stopAt=stopFix=sourceFix=-1;firstMotion=finite=false;rate=0;
        }
    }

    private void trackingSample(int axis,TrackingAxis a,int raw,long now) {
        int change=GtiClient.deltaCounts(raw,a.raw,mount.cpr[axis-1]);
        if(change!=0) {
            a.unchanged=0;
            if(a.lastStart>=0&&a.stopAt<0&&!a.firstMotion) {
                a.firstMotion=true;
                log.record("tracking_first_encoder_movement","axis",axis,"gpsReceivedAtMs",a.sourceFix,
                    "startSentAtMs",a.lastStart,"observedAtMs",now,"startToObservedMs",now-a.lastStart,
                    "gpsToObservedMs",now-a.sourceFix,"encoderChange",change,"intendedDirection",a.direction,
                    "observedDirection",Integer.signum(change)*(axis==1?s.panSign:s.tiltSign));
            }
        }else if(a.unchanged<2) {
            a.unchanged++;
            if(a.unchanged==2&&(a.stopAt>=0||a.finite)) {
                if(a.stopAt>=0)log.record("tracking_stop_observed","axis",axis,"gpsReceivedAtMs",a.stopFix,
                    "stopSentAtMs",a.stopAt,"observedAtMs",now,"stopToObservedMs",now-a.stopAt,
                    "gpsToObservedMs",a.stopFix<0?null:now-a.stopFix,"evidence","Two unchanged encoder intervals");
                else log.record("tracking_goto_observed_settled","axis",axis,"gpsReceivedAtMs",a.sourceFix,
                    "targetRaw",a.targetRaw,"positionRaw",raw,"targetErrorCounts",
                    GtiClient.deltaCounts(a.targetRaw,raw,mount.cpr[axis-1]),"movementObserved",a.firstMotion);
                a.direction=0;a.stopAt=-1;a.finite=false;
            }
        }
        a.raw=raw;a.sampledAt=now;
    }

    private void stopTrackingAxis(int axis,TrackingAxis a,GpsFeed.Fix fix,long token,String reason)throws IOException {
        long now=SystemClock.elapsedRealtime();
        if(a.direction==0||!valid(token))return;
        // A retry is based on observed motion. A newer start may proceed
        // independently once the software reversal deadline passes.
        if(a.stopAt>=0&&(a.unchanged!=0||now-a.stopAt<500))return;
        mount.stopTrackingAxis(axis,()->valid(token),()->{
            long sentAt=SystemClock.elapsedRealtime();
            a.stopAt=sentAt;a.stopFix=fix==null?-1:fix.receivedAt;a.rate=0;a.unchanged=0;
            continuousPlanner.reset(axis);
            log.record("tracking_stop_sent","axis",axis,"gps",gpsSnapshot(fix),"stopSentAtMs",sentAt,
                "gpsToStopMs",fix==null?null:sentAt-fix.receivedAt,"reason",reason,"command",":K"+axis,
                "panRule",axis==1?loggedPanRule(fix,sentAt):null);
        });
    }

    private String loggedPanRule(GpsFeed.Fix fix,long now) {
        if(fix==null||calibration==null)return null;
        double bearing=Geo.bearing(s.latitude,s.longitude,fix.packet.lat,fix.packet.lon);
        return PanHold.rule(fix,s,now,calibration.heading(livePan.raw),bearing);
    }

    private void tickTracking(long token)throws IOException {
        GpsFeed.Fix fix=freshFix();
        long now=SystemClock.elapsedRealtime();
        if(nextGtiReconnectAt>=0){
            if(now>=nextGtiReconnectAt)reconnectTracking(token);
            return;
        }
        if(fix==null&&!ContinuousPlanner.gapAllowed(now,lastAcceptedAt,s.gpsGapTimeout)) {
            stopTrackingAxis(1,livePan,null,token,"GPS gap expired");
            stopTrackingAxis(2,liveTilt,null,token,"GPS gap expired");
        }else if(fix!=null&&livePan.direction!=0&&now-livePan.sampledAt<=s.maxPositionAgeMs) {
            // A Stop can go out before the routine encoder exchange.
            double bearing=Geo.bearing(s.latitude,s.longitude,fix.packet.lat,fix.packet.lon);
            double range=Geo.distance(s.latitude,s.longitude,fix.packet.lat,fix.packet.lon);
            double error=calibration.targetPan(Geo.wrap(bearing+s.magnitudeDegrees(Math.max(.001,range))
                *framing.direction))-calibration.panOffset(livePan.raw);
            String rule=PanHold.rule(fix,s,now,calibration.heading(livePan.raw),bearing);
            if(PanHold.blocks(rule,livePan.direction))
                stopTrackingAxis(1,livePan,fix,token,rule+" holds opposing pan");
            else if(!PanHold.continues(rule,livePan.direction)
                &&Math.abs(error)>s.panToleranceDegrees(Math.max(.001,range))&&Math.signum(error)!=livePan.direction)
                stopTrackingAxis(1,livePan,fix,token,"Opposite pan correction");
        }
        if(!refreshTrackingAxis(1,livePan,fix,token,s.panPollMs))return;
        GpsFeed.Fix panFix=trackingFix(SystemClock.elapsedRealtime());
        if(panFix==null||SystemClock.elapsedRealtime()-livePan.sampledAt<=s.maxPositionAgeMs)
            trackAxis(1,livePan,panFix,token);
        if(!valid(token)||!refreshTrackingAxis(2,liveTilt,panFix,token,s.tiltPollMs))return;
        now=SystemClock.elapsedRealtime();
        if(panFix==null||now-liveTilt.sampledAt<=s.maxPositionAgeMs)
            trackAxis(2,liveTilt,trackingFix(now),token);
        if(!valid(token))return;
        lastPoll=now;
        position=new GtiClient.Position(livePan.raw,liveTilt.raw,livePan.direction!=0||liveTilt.direction!=0);
        panRate=livePan.rate;tiltRate=liveTilt.rate;
        log.record("mount_position","position",positionSnapshot(position),"panSampleMonoMs",livePan.sampledAt,
            "tiltSampleMonoMs",liveTilt.sampledAt,"positionSource","encoder only");
        publishThrottled();
    }

    private boolean refreshTrackingAxis(int axis,TrackingAxis tracking,GpsFeed.Fix fix,long token,int interval)throws IOException {
        long now=SystemClock.elapsedRealtime();
        if(fix==null||!valid(token))return valid(token);
        if(tracking.lastPollFix!=fix&&(tracking.lastReadSent<0||now-tracking.lastReadSent>=interval)){
            tracking.lastPollFix=fix;
            if(readTrackingEncoder(axis,tracking,token,false))return true;
        }
        if(SystemClock.elapsedRealtime()-tracking.sampledAt<=s.maxPositionAgeMs)return true;
        tracking.lastPollFix=fix;
        if(readTrackingEncoder(axis,tracking,token,true))return true;
        nextGtiReconnectAt=SystemClock.elapsedRealtime();
        state="RECONNECTING GTi";message="Reconnecting GTi. Aiming remains armed.";
        publish();
        return false;
    }

    private boolean readTrackingEncoder(int axis,TrackingAxis tracking,long token,boolean recovery)throws IOException {
        tracking.lastReadSent=SystemClock.elapsedRealtime();
        try {
            int raw=mount.encoder(axis,()->valid(token),s.encoderTimeoutMs);
            if(!valid(token))return false;
            trackingSample(axis,tracking,raw,SystemClock.elapsedRealtime());
            return true;
        }catch(IOException e){
            long age=SystemClock.elapsedRealtime()-tracking.sampledAt;
            log.error("tracking_encoder_read_failed",e,"axis",axis,"timeoutMs",s.encoderTimeoutMs,
                "positionAgeMs",age,"cachedPositionUsable",age<=s.maxPositionAgeMs,"recoveryRead",recovery);
            return false;
        }
    }

    private void reconnectTracking(long token)throws IOException {
        ConnectivityManager manager=context.getSystemService(ConnectivityManager.class);
        GtiClient previous=mount;
        GtiClient candidate=null;
        try{
            candidate=new GtiClient(new GtiUdpTransport(manager,network,s.mountHost,s.mountPort,log));
            candidate.reconnect();
            if(!Arrays.equals(candidate.cpr,previous.cpr)||!candidate.firmware.equals(previous.firmware)){
                previous.referenceLost=true;
                fault("GTi reference changed during reconnect. Calibrate again.",true);
                return;
            }
            int pan=candidate.encoder(1,()->valid(token),s.encoderTimeoutMs);
            long panAt=SystemClock.elapsedRealtime();
            int tilt=candidate.encoder(2,()->valid(token),s.encoderTimeoutMs);
            if(!valid(token))return;
            mount=candidate;candidate=null;previous.close();
            trackingSample(1,livePan,pan,panAt);
            trackingSample(2,liveTilt,tilt,SystemClock.elapsedRealtime());
            livePan.lastReadSent=panAt;liveTilt.lastReadSent=liveTilt.sampledAt;
            nextGtiReconnectAt=-1;
            state="AIMING";message="GTi reconnected. Aiming resumed.";
            log.event("tracking_gti_reconnected","Fresh pan and tilt positions");
            publish();
        }catch(IOException e){
            if(!valid(token))return;
            if(candidate!=null&&candidate.referenceLost){
                previous.referenceLost=true;
                fault("GTi lost its motor reference. Calibrate again.",true);
                return;
            }
            log.error("tracking_gti_reconnect_failed",e);
            nextGtiReconnectAt=SystemClock.elapsedRealtime()+500;
        }finally{if(candidate!=null)candidate.close();}
    }

    private GpsFeed.Fix trackingFix(long now) {
        GpsFeed.Fix f=freshFix();
        if(f==null) {
            if(!gpsWaiting){framing.unavailable(now);gps.clearObservations();log.event("gps_pause","Target stale or unavailable");}
            gpsWaiting=true;state="WAITING GPS";message="Waiting for fresh surfer GPS";
            return null;
        }
        if(gpsWaiting){gpsWaiting=false;log.event("gps_resumed","Fresh fix");}
        drainRideFixes(now);
        f=freshFix();
        if(f!=null)lastAcceptedAt=f.receivedAt;
        return f;
    }

    private void trackAxis(int axis,TrackingAxis a,GpsFeed.Fix f,long token)throws IOException {
        if(!valid(token))return;
        long now=SystemClock.elapsedRealtime();
        if(f==null) {
            if(!ContinuousPlanner.gapAllowed(now,lastAcceptedAt,s.gpsGapTimeout))
                stopTrackingAxis(axis,a,null,token,"GPS gap expired");
            return;
        }
        boolean pan=axis==1;
        double range=Geo.distance(s.latitude,s.longitude,f.packet.lat,f.packet.lon);
        double safeRange=Math.max(.001,range);
        double bearing=Geo.bearing(s.latitude,s.longitude,f.packet.lat,f.packet.lon);
        framing.distance(safeRange);
        double heading=Geo.wrap(bearing+framing.tick(now));
        double target=pan?calibration.targetPan(heading):Geo.elevation(range,s.height,s.targetHeight)+s.tiltOffset;
        double current=pan?calibration.panOffset(a.raw):calibration.tiltAngle(a.raw);
        double min=pan?s.panMin:s.tiltMin,max=pan?s.panMax:s.tiltMax;
        if(current<min||current>max){halt("Mount is outside the configured sector");return;}
        String blocker=Readiness.rangeBlocker(s,range);
        if(blocker!=null||target<min||target>max){
            stopTrackingAxis(axis,a,f,token,blocker==null?"Axis target is outside its sector":blocker);
            if(pan){state="WAITING TARGET";message=blocker==null?"Pan target is outside its sector":blocker;}
            return;
        }
        double error=target-current,tolerance=pan?s.panToleranceDegrees(safeRange):s.tiltToleranceDegrees(safeRange);
        String panRule=pan?PanHold.rule(f,s,now,calibration.heading(a.raw),bearing):null;
        int requestedDirection=Math.abs(error)<=tolerance?0:error>0?1:-1;
        boolean continuePan=a.stopAt<0&&PanHold.continues(panRule,a.direction);
        boolean stopOpposingPan=PanHold.blocks(panRule,a.direction);
        boolean hold=stopOpposingPan||(!continuePan&&PanHold.blocks(panRule,requestedDirection));
        int direction=hold||continuePan?0:requestedDirection;
        String panRuleAction=continuePan?"continue_existing_pan":stopOpposingPan?"stop_opposing_pan":
            hold?"suppress_opposing_start":null;
        boolean goTo=direction!=0&&Math.abs(error)+1e-10>=s.acquisitionDegrees(safeRange);
        if(pan) {
            state="AIMING";message=continuePan?"Continue existing pan":hold?"Hold opposing pan":goTo?"Pan GoTo":"Tracking";
            aimText=String.format(Locale.US,"Range %.1f m · bearing %.2f°\nCamera %.2f° · target %.2f° · error %+.2f°",
                range,bearing,calibration.heading(a.raw),heading,error);
            log.record("aim_cycle","gps",gpsSnapshot(f),"positionSampleMonoMs",a.sampledAt,"rangeM",range,
                "bearingDegrees",bearing,"targetHeadingDegrees",heading,"cameraHeadingDegrees",calibration.heading(a.raw),
                "panErrorDegrees",error,"panRule",panRule,"panRuleAction",panRuleAction,"holdPan",hold,
                "framing",framingSnapshot(now),"goTo",goTo);
        }
        log.record("tracking_axis_decision","axis",axis,"gps",gpsSnapshot(f),"errorDegrees",error,
            "direction",direction,"requestedDirection",requestedDirection,"activeDirection",a.direction,
            "panRule",panRule,"panRuleAction",panRuleAction,"holdPan",hold,"goTo",goTo);

        if(continuePan)return;
        boolean opposing=a.direction!=0&&direction!=0&&a.direction!=direction;
        if(hold||(opposing&&a.stopAt<0)||(direction==0&&a.rate!=0)) {
            stopTrackingAxis(axis,a,f,token,hold?panRule+" holds opposing pan":direction==0?"Within tolerance":"Opposite direction");
            return;
        }
        if(direction==0)return;
        if(a.lastDirection!=0&&direction!=a.lastDirection&&now-a.lastStart<s.reverseSeconds*1000) {
            if(opposing)stopTrackingAxis(axis,a,f,token,"Old movement before reversal deadline");
            log.record("tracking_reversal_block","axis",axis,"gps",gpsSnapshot(f),"remainingMs",s.reverseSeconds*1000-(now-a.lastStart));
            return;
        }
        if(goTo) {
            if(a.rate!=0){stopTrackingAxis(axis,a,f,token,"Switch to GoTo");return;}
            if(a.direction!=0&&a.stopAt<0&&!(pan&&s.gotoNoWait&&a.finite))return;
            // When a conditional restart is permitted, No-wait does not wait for
            // physical-idle confirmation. The GTi can reject the attempt.
            if(a.lastAttemptFix==f.receivedAt)return;
            double step=Math.copySign(Math.min(s.gotoMaxMove,Math.abs(error)),error);
            double motorStep=step*(pan?s.panSign:s.tiltSign);
            int dest=GtiClient.wrap24(a.raw+(int)Math.round(motorStep*mount.cpr[axis-1]/360));
            if(dest==a.raw)return;
            a.lastAttemptFix=f.receivedAt;
            GpsFeed.Fix[] verified={f};
            int[] programmed={dest};
            String[] skipReason={null};
            boolean[] preStartChecked={false};
            GtiClient.GoToResult result=mount.attemptGoToAxis(axis,dest,motorStep,s.gotoNoWait,()->valid(token),()->{
                preStartChecked[0]=true;
                GpsFeed.Fix latest=freshFix();
                if(latest==null){skipReason[0]="gps_stale_or_unavailable";return (Integer)null;}
                if(latest==verified[0])return programmed[0];
                verified[0]=latest;
                double latestDistance=Geo.distance(s.latitude,s.longitude,latest.packet.lat,latest.packet.lon);
                String rangeBlocker=Readiness.rangeBlocker(s,latestDistance);
                if(rangeBlocker!=null){skipReason[0]=rangeBlocker;return (Integer)null;}
                double latestBearing=Geo.bearing(s.latitude,s.longitude,latest.packet.lat,latest.packet.lon);
                if(pan){
                    String latestRule=PanHold.rule(latest,s,SystemClock.elapsedRealtime(),calibration.heading(a.raw),latestBearing);
                    if(PanHold.blocks(latestRule,direction)||PanHold.blocks(latestRule,a.direction)){
                        skipReason[0]="pan_rule_holds_opposing_direction";return (Integer)null;
                    }
                    if(a.stopAt<0&&PanHold.continues(latestRule,a.direction)){
                        skipReason[0]="pan_rule_continues_existing_pan";return (Integer)null;
                    }
                }
                double latestTarget=pan?calibration.targetPan(Geo.wrap(latestBearing+framing.direction*s.magnitudeDegrees(latestDistance))):
                    Geo.elevation(latestDistance,s.height,s.targetHeight)+s.tiltOffset;
                double latestTolerance=pan?s.panToleranceDegrees(latestDistance):s.tiltToleranceDegrees(latestDistance);
                double latestError=latestTarget-current;
                if(latestTarget<min||latestTarget>max)skipReason[0]="target_outside_axis_limits";
                else if(Math.abs(latestError)<=latestTolerance)skipReason[0]="within_aiming_tolerance";
                else if(Math.signum(latestError)!=direction)skipReason[0]="correction_direction_changed";
                else if(Math.abs(latestError)+1e-10<s.acquisitionDegrees(latestDistance))skipReason[0]="below_goto_trigger";
                if(skipReason[0]!=null)return (Integer)null;
                double latestStep=Math.copySign(Math.min(s.gotoMaxMove,Math.abs(latestError)),latestError)*(pan?s.panSign:s.tiltSign);
                programmed[0]=GtiClient.wrap24(a.raw+(int)Math.round(latestStep*mount.cpr[axis-1]/360));
                if(programmed[0]==a.raw){skipReason[0]="target_rounds_to_current_position";return (Integer)null;}
                return programmed[0];
            },()->{
                a.lastStart=SystemClock.elapsedRealtime();a.lastDirection=a.direction=direction;
                a.sourceFix=verified[0].receivedAt;a.targetRaw=programmed[0];a.firstMotion=false;a.finite=true;a.unchanged=0;a.stopAt=-1;
                a.lastAttemptFix=a.sourceFix;
                log.record("tracking_start_sent","axis",axis,"gps",gpsSnapshot(verified[0]),"initialGps",gpsSnapshot(f),"verifiedGps",gpsSnapshot(verified[0]),
                    "startSentAtMs",a.lastStart,"gpsToStartMs",a.lastStart-a.sourceFix,
                    "direction",direction,"targetRaw",a.targetRaw,"command",":J"+axis,
                    "panRule",pan?(verified[0]==f?panRule:loggedPanRule(verified[0],a.lastStart)):null,
                    "mode",s.gotoNoWait?"no-wait":"acknowledged");
            });
            if(!result.started()){
                log.record(result.startSent()?"tracking_goto_start_rejected":"tracking_goto_not_started",
                    "axis",axis,"gps",gpsSnapshot(f),"initialGps",gpsSnapshot(f),
                    "verifiedGps",preStartChecked[0]&&!"gps_stale_or_unavailable".equals(skipReason[0])?gpsSnapshot(verified[0]):null,
                    "preStartChecked",preStartChecked[0],"latestGpsAtOutcome",gpsSnapshot(freshFix()),
                    "panRule",pan?loggedPanRule(verified[0],SystemClock.elapsedRealtime()):null,
                    "direction",direction,"activeDirection",a.direction,"targetRaw",programmed[0],
                    "mode",s.gotoNoWait?"no-wait":"acknowledged","startSent",result.startSent(),
                    "reason",skipReason[0]==null?result.reason():skipReason[0],
                    "command",result.command(),"reply",result.reply());
                if(valid(token)&&pan&&verified[0]!=f&&a.direction!=0){
                    GpsFeed.Fix latest=verified[0];
                    double bearingNow=Geo.bearing(s.latitude,s.longitude,latest.packet.lat,latest.packet.lon);
                    double rangeNow=Math.max(.001,Geo.distance(s.latitude,s.longitude,latest.packet.lat,latest.packet.lon));
                    double errorNow=calibration.targetPan(Geo.wrap(bearingNow+framing.direction*s.magnitudeDegrees(rangeNow)))-current;
                    String latestRule=PanHold.rule(latest,s,SystemClock.elapsedRealtime(),calibration.heading(a.raw),bearingNow);
                    if(PanHold.blocks(latestRule,a.direction)||(!PanHold.continues(latestRule,a.direction)
                        &&Math.abs(errorNow)>s.panToleranceDegrees(rangeNow)&&Math.signum(errorNow)!=a.direction))
                        stopTrackingAxis(axis,a,latest,token,"Newer GPS before Start");
                }
            }
            return;
        }
        if(s.acquisitionTriggerMetres==0||a.finite||a.stopAt>=0)return;
        double maximum=Math.min(s.maxPanningSpeed,GtiClient.MAX_SLEW_SPEED);
        double rate=continuousPlanner.rate(axis,error,tolerance,maximum,
            AimUnits.degrees((pan?s.panTolerance:s.tiltTolerance)*1.5,safeRange));
        rate=ContinuousPlanner.limitRate(rate,current,min,max);
        if(rate==0&&a.rate!=0){stopTrackingAxis(axis,a,f,token,"Continuous pan tolerance");return;}
        if(rate==0||rate==a.rate||a.lastAttemptFix==f.receivedAt)return;
        a.lastAttemptFix=f.receivedAt;
        mount.rateAxis(axis,rate*(pan?s.panSign:s.tiltSign),()->valid(token)&&freshFix()!=null);
        a.direction=a.lastDirection=rate>0?1:-1;a.rate=rate;a.lastStart=SystemClock.elapsedRealtime();
        a.sourceFix=f.receivedAt;a.firstMotion=false;
        log.record("tracking_continuous_rate","axis",axis,"gps",gpsSnapshot(f),"rateDegreesPerSecond",rate,"panRule",panRule);
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
            if(active&&surferAimingGeneration==aimingGeneration){
                try {
                    tickTracking(pollToken);
                }catch(java.net.SocketTimeoutException timeout) {
                    log.error("tracking_gti_timeout",timeout);
                    state="RETRYING GTi";message="GTi command reply missed. Tracking remains armed.";
                    publishThrottled();
                }
                return;
            }
            GtiClient.Position here=poller.poll(mount,()->valid(pollToken),pendingMove?motionDeadline:0);
            if(!valid(pollToken))return;
            if(here==null) {
                // A mount outage must not fill the 64-observation framing queue and
                // appear as GPS loss. Keep latest GPS and raw telemetry logging intact.
                if(gps!=null)gps.discardUnusedObservations("Waiting for GTi; framing paused");
                boolean landmarkStop=fixedGoto!=null&&fixedGoto.kind==FixedGoto.Kind.LANDMARK&&poller.interruptedMove();
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
                if(poller.interruptedMove()){pendingMove=false;panRate=tiltRate=0;continuousPlanner.reset();}
                poller.reset();
                framing.unavailable(SystemClock.elapsedRealtime());
                if(gps!=null)gps.clearObservations();
                lastFramePacket=lastFrameSender=lastFrameAt=-1;
                state=active?fixedGoto==null?"AIMING":fixedGoto.kind.state:calibration==null?"CONNECTED":"CALIBRATED";
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
            // Ride detection can advance during a GoTo. The captured motor target never changes.
            if(fixedGoto!=null){
                if(fixedGoto.kind==FixedGoto.Kind.SURFER && freshFix()!=null)drainRideFixes(SystemClock.elapsedRealtime());
                else framing.unavailable(lastPoll);
                advanceFixedGoto(here,lastPoll);publishThrottled();return;
            }
        }catch(CancellationException e){log.event("mount_poll_cancelled","Stop or disconnect took priority");}
        catch(Exception e){
            log.error("control_error",e);
            if(!stopping&&active&&surferAimingGeneration==aimingGeneration&&freshFix()==null
                &&"Movement cancelled or inputs expired".equals(e.getMessage()))stopForGpsTimeout();
            else if(!stopping)fault(e.getMessage(),true);
        }
        catch(Throwable e){log.event("control_uncaught_error",e.toString());emergencyStop("Unexpected control failure",null);}
        finally{watchdog.healthy(SystemClock.elapsedRealtime());}
    }
    private void drainRideFixes(long now) {
        if(continuity!=gps.continuity()){
            framing.unavailable(now);continuity=gps.continuity();
            lastFramePacket=lastFrameSender=lastFrameAt=-1;
        }
        GpsFeed.Fix event;
        for(int n=0;n<64&&(event=gps.poll())!=null;n++) {
            if(event.epoch==continuity&&now-event.receivedAt<=s.gpsAge*1000)observe(event);
        }
        framing.tick(now);
    }

    private void observe(GpsFeed.Fix f) {
        if(f.receivedAt<lastFrameAt||(f.packet.sequence==lastFramePacket&&f.packet.senderMs==lastFrameSender))return;
        framing.distance(Math.max(.001,Geo.distance(s.latitude,s.longitude,f.packet.lat,f.packet.lon)));
        framing.observe(f.packet.senderMs,f.receivedAt,f.packet.lat,f.packet.lon,
            Geo.bearing(s.latitude,s.longitude,f.packet.lat,f.packet.lon));
        log.record("framing_observation","gps",gpsSnapshot(f),"bearingDegrees",Geo.bearing(s.latitude,s.longitude,f.packet.lat,f.packet.lon),"framing",framingSnapshot(f.receivedAt));
        lastFramePacket=f.packet.sequence;lastFrameSender=f.packet.senderMs;lastFrameAt=f.receivedAt;
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
        state=target.kind.state;
        switch(target.kind){
            case LANDMARK:
                if(gps!=null)gps.clearObservations();
                message=target.calibrateOnArrival?"Returning to the landmark for calibration.":"Returning pan and tilt to the last saved landmark; surfer tracking is off.";
                aimText=String.format(Locale.US,"Last saved landmark · remaining pan %+.2f° · tilt %+.2f°",
                    target.pan-pan,target.tilt-tilt);
                break;
            case OFFSHORE:
                message="Calibrated. Panning to the saved offshore heading.";
                aimText=String.format(Locale.US,"Offshore %.2f° true · remaining pan %+.2f°",target.heading,target.pan-pan);
                break;
            case SURFER:
                message="Moving to the saved position; new GPS does not change this GoTo.";
                aimText=String.format(Locale.US,"Saved GPS #%d · range %.1f m\nSaved aim %.2f° / tilt %.2f°\nCamera %.2f° / tilt %.2f°\nRemaining pan %+.2f° · tilt %+.2f°",
                    target.source.sequence,target.range,target.heading,target.tilt,navigation.heading(here.pan),tilt,
                    target.pan-pan,target.tilt-tilt);
                break;
        }
        log.record("goto_progress","target",target.snapshot(),"position",positionSnapshot(here),
            "remainingPanDegrees",target.pan-pan,"remainingTiltDegrees",target.tilt-tilt,"pendingMove",pendingMove);
        if(target.arrived(here,steps)) {
            long token=aimingGeneration;
            if(!valid(token)||!active)return;
            log.record("goto_completed","target",target.snapshot(),"position",positionSnapshot(here),"elapsedMs",now-target.capturedAt);
            fixedGoto=null;
            if(target.kind==FixedGoto.Kind.SURFER) {
                state="AIMING";message="Saved position reached. Resuming live GPS aiming.";
                aimText="Saved position reached; checking the latest surfer GPS";
                return;
            }
            framing.holdForGoto(now);lastFramePacket=lastFrameSender=lastFrameAt=-1;
            if(gps!=null)continuity=gps.continuity();
            active=false;state=calibration==null?"CONNECTED":"CALIBRATED";
            if(target.calibrateOnArrival){
                log.settings("calibration_attempt",s);
                calibrateAt(here,token,"saved_landmark");
            }else if(target.kind==FixedGoto.Kind.OFFSHORE){
                message="Calibrated. Camera is now pointing offshore.";
                aimText="At the saved offshore heading";
            }else{
                message=calibration==null?"Last landmark position reached. Check alignment and validate calibration before tracking.":
                    "Last landmark reached. Pan and tilt returned; surfer tracking remains off.";
                aimText="At the last saved landmark position";
            }
            return;
        }
        if(here.moving)return;
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
        if(currentMount==null||mountReconnectPending)return "Connect the GTi to return to the last landmark";
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

        GtiClient client=mount;mount=null;mountReconnectPending=false;if(client!=null)client.close();
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
        if(p!=null)mountText+="\nMotor counts "+p.pan+" / "+p.tilt+(p.panStatus.isEmpty()
            ?(p.moving?" · command may be active":" · encoder tracking")
            :(p.moving?" · last reported moving":" · last reported stopped"));
        if(poller.waiting())mountText+="\nStatus unavailable — checking automatically";
        String calText=cal==null?"Calibration required each connection":String.format(Locale.US,"Landmark %.2f° true · elevation %.2f°\nPan sector %+.1f° to %+.1f° from landmark\nTilt %.1f° to %.1f°",cal.bearing,cal.elevation,s.panMin,s.panMax,s.tiltMin,s.tiltMax);
        String frame=String.format(Locale.US,"%s · applied offset %+.2f m\nQualifying speed %s · sideways %+.2f m",
            framing.reason,framing.direction*s.magnitude,
            Double.isFinite(framing.speedKmh)?String.format(Locale.US,"%.1f km/h",framing.speedKmh):"unavailable",
            framing.sidewaysMetres);
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
        if(mountReconnectPending)out.add("Reconnect the GTi to use the new address");
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
        out.put("stoppedDuringPositionRead",p.panStatus.isEmpty()||p.tiltStatus.isEmpty()?null:!p.moving);
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
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("mode","RIDE");out.put("rideActive",framing.riding);out.put("direction",framing.direction);
        out.put("reason",framing.reason);out.put("appliedOffsetDegrees",framing.applied);
        out.put("appliedOffsetMetres",framing.direction*s.magnitude);
        out.put("qualifyingSpeedKmh",framing.speedKmh);out.put("sidewaysMetres",framing.sidewaysMetres);
        out.put("startConfirmations",framing.startCount);
        return out;
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
