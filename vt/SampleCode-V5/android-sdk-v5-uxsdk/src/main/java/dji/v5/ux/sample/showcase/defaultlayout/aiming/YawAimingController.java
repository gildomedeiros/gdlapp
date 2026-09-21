package dji.v5.ux.sample.showcase.defaultlayout.aiming;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
// CAM3 v2.1: Local diagnostics and user-selected export destination; no flight API involved.
import android.net.Uri;
import android.util.Log;
import java.io.File;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import dji.sdk.keyvalue.value.flightcontroller.*;
import dji.v5.common.callback.CommonCallbacks;
import dji.v5.common.error.IDJIError;
import dji.v5.manager.aircraft.virtualstick.*;
import dji.v5.manager.interfaces.IVirtualStickManager;

/**
 * CAM3 v2.0: One process-scoped controller prevents Activity recreation creating competing owners.
 * The single fixed-delay worker monitors readiness and runs the state machine. It never replays
 * a saved flight command. Android/DJI callbacks only publish data or latch cancellation.
 */
public final class YawAimingController implements AimingSession.Port {
    public interface Observer { void onState(AimingSession.State state, String reason, boolean canStart,
                                             AimingSession.Fix fix, long now); }
    // CAM3 v2.2: Details are a snapshot, so opening the UI never queries flight APIs.
    private volatile String readinessDetails = "Waiting for telemetry";
    public String readinessDetails() { return readinessDetails; }
    private final AtomicLong stateSequence = new AtomicLong();
    // CAM3 v2.2: Ignore delivery from detached registrations; no SDK session token is implied.
    private final AtomicLong listenerGeneration = new AtomicLong();
    private VirtualStickStateListener registeredListener;
    private volatile double lastCommandRate;
    private long commandSession = -1;
    // CAM3 v2.3: Distinguish last command from active sending; recovery time is a UI snapshot.
    private long lastCommandAt;
    private volatile long recoveryRemaining;
    public long recoveryRemainingMs() { return recoveryRemaining; }
    private static YawAimingController instance;
    public static synchronized YawAimingController getInstance(Context context) {
        if (instance == null) instance = new YawAimingController(context.getApplicationContext());
        return instance;
    }
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "cam3-yaw-control"); thread.setDaemon(true); return thread;
    });
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicLong intent = new AtomicLong();
    private final PhoneTargetLocationSource phone;
    private final LoRaTargetLocationSource lora;
    private volatile boolean usePhone;
    // CAM3 v2.7: Toggle changes only with aiming OFF/STOPPED; no process persistence.
    private volatile boolean nearbyEnabled=true;
    private volatile String nearbyStatus="Nearby tracking OFF";
    private long submittedCycle=-1;
    private long lastTranslationAt=-1;
    private double lastSubmittedForward;
    private volatile ComeToMeSettings movementConfig=ComeToMeSettings.defaults();
    private volatile String movementScreen="Come to me: waiting for Start",movementDetails="",aimingScreen="";
    private volatile boolean fullLogWanted=true;
    public String movementScreen() { return movementScreen; }
    public String movementDetails() { return movementDetails; }
    public String aimingScreen() { return aimingScreen; }
    public ComeToMeSettings movementSettings() { return movementConfig; }
    public void setMovementSettings(ComeToMeSettings config) {
        executor.execute(() -> {
            if(!canSelectGpsSource()) return;
            movementConfig=config;
            appContext.getSharedPreferences("vt28",Context.MODE_PRIVATE).edit()
                    .putBoolean("comeToMe",config.enabled).putFloat("filming",(float)config.filmingDistance)
                    .putFloat("width",(float)config.lineupWidth).putFloat("rideStart",(float)config.rideStartKmh)
                    .putFloat("rideEnd",(float)config.rideEndKmh).putLong("endMs",config.rideEndMs)
                    .putLong("inactivityMs",config.inactivityMs).apply();
            diagnostic("movement_settings","enabled="+config.enabled+" filming="+config.filmingDistance
                    +" width="+config.lineupWidth+" rideStart="+config.rideStartKmh+" rideEnd="+config.rideEndKmh
                    +" endMs="+config.rideEndMs+" inactivityMs="+config.inactivityMs);
        });
    }
    @Override public boolean nearbyTrackingEnabled() { return nearbyEnabled; }
    public String nearbyTrackingStatus() { return nearbyStatus; }
    public void setNearbyTrackingEnabled(boolean enabled) {
        executor.execute(() -> {
            if(!canSelectGpsSource()) return;
            nearbyEnabled=enabled; session.resetNearby("mode_change",false);
            diagnostic("nearby_mode","enabled="+enabled);
        });
    }
    public boolean usesPhoneGps() { return usePhone; }
    public boolean canSelectGpsSource() {
        AimingSession.State s = session.state();
        return s == AimingSession.State.OFF || s == AimingSession.State.STOPPED;
    }
    public void selectPhoneGps(boolean selected) {
        executor.execute(() -> {
            if (!canSelectGpsSource() || selected == usePhone) return;
            phone.stop(); lora.stop(); usePhone = selected;
            session.resetNearby("source_change",true); // CAM3 v2.7: No cross-source votes.
            diagnostic("target_source", selected ? "phone" : "lora_wifi");
            if (foreground) startTargetSource();
        });
    }
    private void startTargetSource() { if (usePhone) phone.start(); else lora.start(); }
    private AimingSession.Fix getTargetFix() { return usePhone ? phone.getLatestFix() : lora.getLatestFix(); }
    public String targetSummary(AimingSession.Fix fix, long at) {
        String source = usePhone ? "Phone GPS" : "LoRa GPS";
        return fix == null ? source + " unavailable - see Details"
                : String.format(java.util.Locale.US, "%s - %.1f s old - Gimbal manual", source, Math.max(0, at - fix.time) / 1000.0)
                + (usePhone ? "" : "\n" + lora.signalSummary());
    }
    private final AircraftAimingTelemetry aircraft;
    private final AimingSession session;
    private final IVirtualStickManager sdk = VirtualStickManager.getInstance();
    private volatile AimingSession.Authority authority = new AimingSession.Authority(AimingSession.Owner.UNKNOWN, false, false);
    private volatile Observer observer;
    private volatile boolean foreground, yielding;
    private boolean listening;
    private long lastRender;
    // CAM3 v2.1: Logging owns a separate bounded writer, never a flight-command sender.
    private final AimingDiagnosticLogger logger;
    // CAM3 v2.5: Independent asynchronous full session logger; no control authority or SDK calls.
    private final FullSessionLog fullLog;
    public boolean fullLogEnabled() { return fullLog.enabled(); }
    public boolean fullLogBusy() { return fullLog.busy(); }
    public String loggingStatus() { return fullLog.status(); }
    public void setFullLogEnabled(boolean enabled) {
        fullLogWanted=enabled;
        if (enabled) fullLog.enable(); else fullLog.disable("user_off");
        if (enabled) fullLog.record("capture_source", "source", usePhone ? "phone" : "lora_wifi");
        diagnostic("logging_mode", enabled ? "Full requested" : "Minimal requested");
    }
    private final Context appContext;
    private long lastDiagnostic;
    private final VirtualStickStateListener stateListener = new VirtualStickStateListener() {
        @Override public void onVirtualStickStateUpdate(VirtualStickState value) {
            AimingSession.Authority old = authority;
            FlightControlAuthority owner = value.getCurrentFlightControlAuthorityOwner();
            AimingSession.Owner mapped = owner == FlightControlAuthority.MSDK ? AimingSession.Owner.MSDK
                    : owner == FlightControlAuthority.RC ? AimingSession.Owner.RC
                    // CAM3 v2.2: Missing owner is unavailable evidence, not a known competing owner.
                    : owner == null || owner == FlightControlAuthority.UNKNOWN ? AimingSession.Owner.UNKNOWN : AimingSession.Owner.OTHER;
            // CAM3 v2.2: Preserve each callback, including identical values, with arrival sequence/time.
            long sequence = stateSequence.incrementAndGet();
            authority = new AimingSession.Authority(mapped, value.isVirtualStickEnable(), value.isVirtualStickAdvancedModeEnabled(), sequence, now());
            // CAM3 v2.1: Observed ownership is logged separately from enable/disable callbacks.
            String observed = "owner=" + mapped + " enabled=" + value.isVirtualStickEnable()
                    + " advanced=" + value.isVirtualStickAdvancedModeEnabled();
            // CAM3 v2.2: Latch transient contradictory callbacks, including UNKNOWN-to-RC during Start.
            boolean running = session.state() == AimingSession.State.STARTING || session.state() == AimingSession.State.AIMING
                    || session.state() == AimingSession.State.PAUSED; // CAM3 v2.3: takeover cancels paused recovery too.
            if ((running && (mapped == AimingSession.Owner.RC || mapped == AimingSession.Owner.OTHER))
                    // CAM3 v2.3: A transient disabled/advanced-off callback permanently cancels an activated pause.
                    || (running && session.hasActivated() && (!value.isVirtualStickEnable() || !value.isVirtualStickAdvancedModeEnabled()))
                    || (old.owner == AimingSession.Owner.MSDK && (mapped != AimingSession.Owner.MSDK
                    || !value.isVirtualStickEnable() || (old.advanced && !value.isVirtualStickAdvancedModeEnabled())))) {
                // CAM3 v2.2: A subsequent positive callback cannot erase a reported handover.
                if (running && mapped != AimingSession.Owner.MSDK) yielding = true;
                interruptAiming();
            }
            // CAM3 v2.1: Latch any cancellation before queuing diagnostic output.
            diagnostic("authority_observed", "sequence=" + sequence + " " + observed);
        }
        @Override public void onChangeReasonUpdate(FlightControlAuthorityChangeReason reason) {
            // CAM3 v2.1: Record DJI's exact takeover reason instead of only the generic UI label.
            if (reason != FlightControlAuthorityChangeReason.MSDK_REQUEST) {
                // Do not compete with RTH/pilot while a matching state update is still in transit.
                yielding = true;
                // CAM3 v2.2: Explain specific aircraft operations when DJI supplies the reason.
                interruptAiming(reason.name().contains("GO_HOME") ? "returning_home"
                        : reason.name().contains("LANDING") ? "landing" : "takeover");
            }
            // CAM3 v2.1: Logging follows the existing immediate takeover latch.
            // CAM3 v2.2: Log every reason callback, even repeated reasons.
            diagnostic("authority_reason", String.valueOf(reason));
        }
    };
    private YawAimingController(Context context) {
        // CAM3 v2.1: Files are private to cam3 until the user selects an export destination.
        appContext = context.getApplicationContext();
        logger = new AimingDiagnosticLogger(new File(context.getFilesDir(), "aiming-logs"),
                line -> Log.i("CAM3_AIMING", line));
        fullLog = new FullSessionLog(name -> FullLogStorage.open(appContext, name), error -> {
            logger.event("full_log_error", error);
            main.post(() -> android.widget.Toast.makeText(appContext, error, android.widget.Toast.LENGTH_LONG).show());
        });
        phone = new PhoneTargetLocationSource(context, fullLog, this::diagnostic);
        lora = new LoRaTargetLocationSource(context, this::diagnostic, fullLog);
        // CAM3 v2.1: Capture individual read failures/recovery, in addition to periodic field snapshots.
        // CAM3 v2.3: Named telemetry callbacks either latch a pause or permanently cancel recovery.
        aircraft = new AircraftAimingTelemetry(this::telemetryEvent,
                (field, detail) -> diagnostic("telemetry_" + field, detail));
        session = new AimingSession(this);
        android.content.SharedPreferences prefs=context.getSharedPreferences("vt28",Context.MODE_PRIVATE);
        try {
            movementConfig=new ComeToMeSettings(prefs.getBoolean("comeToMe",true),Math.max(10,prefs.getFloat("filming",70)),
                    prefs.getFloat("width",20),prefs.getFloat("rideStart",18),prefs.getFloat("rideEnd",8),
                    prefs.getLong("endMs",30000),prefs.getLong("inactivityMs",900000));
        } catch(IllegalArgumentException invalid) { movementConfig=ComeToMeSettings.defaults(); }
        executor.scheduleWithFixedDelay(this::tick, 0, 100, TimeUnit.MILLISECONDS);
    }
    public void resume(Observer callback) {
        // CAM3 v2.1: Record screen lifecycle independently from aiming activation.
        diagnostic("screen", "resume");
        observer = callback; foreground = true;
        executor.execute(() -> {
            try {
                // CAM3 v2.2: A failed registration must not claim monitoring is active.
                if (!listening) {
                    long generation = listenerGeneration.incrementAndGet();
                    registeredListener = new VirtualStickStateListener() {
                        @Override public void onVirtualStickStateUpdate(VirtualStickState value) {
                            if (listenerGeneration.get() == generation) stateListener.onVirtualStickStateUpdate(value);
                        }
                        @Override public void onChangeReasonUpdate(FlightControlAuthorityChangeReason reason) {
                            if (listenerGeneration.get() == generation) stateListener.onChangeReasonUpdate(reason);
                        }
                    };
                    sdk.setVirtualStickStateListener(registeredListener); listening = true;
                    diagnostic("listener", "registered generation=" + generation);
                }
                if (foreground) {
                    boolean storageReady=android.os.Build.VERSION.SDK_INT>=29
                            || appContext.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            ==android.content.pm.PackageManager.PERMISSION_GRANTED;
                    if(fullLogWanted && storageReady && !fullLog.enabled() && !fullLog.busy()) setFullLogEnabled(true);
                    aircraft.start(); startTargetSource();
                }
            } catch (RuntimeException ex) { session.stopAiming("sdk_error"); }
        });
    }
    public void pause(Observer callback) {
        // An old Activity's onDestroy must not detach a newer foreground Activity.
        if (observer != callback) return;
        foreground = false; observer = null;
        stopAiming("screen_closed");
        // CAM3 v2.5: End full capture after source shutdown on screen exit; normal aiming cancellation stays intact.
        executor.execute(() -> { phone.stop(); lora.stop(); aircraft.stop(); session.resetNearby("screen_closed",true); fullLog.disable("screen_closed"); });
    }
    public void startAiming() {
        // CAM3 v2.1: Record explicit user intent, including attempts rejected by the prerequisites.
        diagnostic("user_start", "requested");
        long request = intent.incrementAndGet();
        executor.execute(() -> {
            if (foreground && listening && intent.get() == request) {
                yielding = false;
                session.startAiming();
            }
        });
    }
    public void stopAiming(String reason) {
        intent.incrementAndGet();
        session.cancelImmediately();
        // CAM3 v2.1: Cancellation is latched before any diagnostic work.
        diagnostic("stop_request", "reason=" + reason);
        executor.execute(() -> session.stopAiming(reason));
    }
    // CAM3 v2.2: Acknowledge every STOP on the UI after processing the cancellation.
    public void stopFromUser(java.util.function.Consumer<AimingSession.State> feedback) {
        stopAiming("user_stop");
        executor.execute(() -> {
            AimingSession.State state = session.state();
            diagnostic("stop_feedback", "state=" + state);
            main.post(() -> feedback.accept(state));
        });
    }
    private void interruptAiming() {
        // CAM3 v2.2: Telemetry/pilot stop retains its generic reason; SDK reasons can be specific.
        interruptAiming("takeover");
    }
    // CAM3 v2.3: Latch before logging; a short stick movement must reset the neutral timer.
    private void telemetryEvent(String reason, boolean permanent) {
        if (permanent) interruptAiming(reason);
        else if (session.state() == AimingSession.State.STARTING || session.state() == AimingSession.State.AIMING
                || session.state() == AimingSession.State.PAUSED) session.pauseImmediately(reason);
        diagnostic("input_event", "reason=" + reason + " action=" + (permanent ? "stop" : "pause"));
    }
    private void interruptAiming(String reason) {
        intent.incrementAndGet();
        session.cancelImmediately();
        executor.execute(() -> {
            // Expected ownership changes during our own release must preserve the original stop reason.
            if (session.state() == AimingSession.State.STARTING || session.state() == AimingSession.State.AIMING
                    || session.state() == AimingSession.State.PAUSED) // CAM3 v2.3: never revive a cancelled pause.
                session.stopAiming(reason);
        });
    }
    private void tick() {
        try {
            AimingSession.Inputs observed=null;
            if (foreground) {
                aircraft.poll(); observed=inputs();
                session.observeDirection(observed,now()); // CAM3 v2.7: Votes expire every tick, new fixes alone add votes.
            }
            session.tick();
            if(foreground && observed!=null) recordAimingCycle(observed); // CAM3 v2.7: Every foreground cycle, not only changed summaries.
            // CAM3 v2.1: Sample diagnostics at most twice per second; file I/O stays on the logger worker.
            if (now() - lastDiagnostic >= 500) {
                lastDiagnostic = now();
                recordDiagnostics();
            }
            if (!foreground && (session.state() == AimingSession.State.OFF || session.state() == AimingSession.State.STOPPED)
                    && listening) {
                // CAM3 v2.2: Invalidate delivery before removing this registration.
                listenerGeneration.incrementAndGet();
                sdk.removeVirtualStickStateListener(registeredListener); listening = false;
                authority = new AimingSession.Authority(AimingSession.Owner.UNKNOWN, false, false);
            }
            if (foreground && now() - lastRender >= 250) {
                lastRender = now();
                Observer target = observer;
                AimingSession.State state = session.state();
                // CAM3 v2.3: Main-thread rendering uses a worker-produced recovery snapshot.
                recoveryRemaining = session.recoveryRemainingMs();
                movementDetails=session.movement.details();
                movementScreen=session.movement.summary();
                if(state==AimingSession.State.OFF || state==AimingSession.State.STOPPED)
                    movementScreen=movementConfig.enabled ? "Come to me: ON — waiting for Start" : "Come to me: OFF";
                if(state==AimingSession.State.PAUSED) movementScreen="Come to me: PAUSED — "+session.reason();
                aimingScreen=state==AimingSession.State.AIMING
                        ? (session.movement.returning() ? "Aiming: holding return heading"
                        : session.movement.approaching() ? "Aiming: holding approach heading"
                        : Math.abs(session.cycleAngle)<=YawAimingMath.ALIGNMENT_DEGREES ? "Aiming: aligned with surfer"
                        : "Aiming: rotating "+(session.cycleAngle<0 ? "left" : "right")+" toward surfer") : "";
                // CAM3 v2.2: Listener availability is also required for user-visible readiness.
                boolean ready = listening && session.canStart();
                String reason = session.reason();
                if (state == AimingSession.State.OFF || state == AimingSession.State.STOPPED) {
                    String invalid = inputs().validate(now());
                    if (invalid != null) reason = invalid;
                    else if (!ready) reason = "authority";
                }
                final String message = reason;
                AimingSession.Fix fix = getTargetFix();
                main.post(() -> {
                    if (foreground && target != null && target == observer) target.onState(state, message, ready, fix, now());
                });
            }
        } catch (RuntimeException ex) { session.stopAiming("sdk_error"); }
    }
    @Override public long now() { return SystemClock.elapsedRealtime(); }
    @Override public AimingSession.Inputs inputs() { return aircraft.getSnapshot(getTargetFix(),session!=null && session.state()==AimingSession.State.AIMING
            && lastTranslationAt>=0 && now()-lastTranslationAt<2000); }
    @Override public AimingSession.Authority authority() {
        // CAM3 v2.2: Preserve raw observation identity; takeover is a separate cancellation latch.
        return authority;
    }
    // CAM3 v2.2: Keep reported owner separate from an urgent control-loss reason.
    @Override public boolean controlLost() { return yielding; }
    // CAM3 v2.1: Include operation/session and SDK error code, while preserving callback dispatch order.
    private CommonCallbacks.CompletionCallback callback(String operation, AimingSession.Completion completion) {
        long token = session.sessionId();
        return new CommonCallbacks.CompletionCallback() {
            @Override public void onSuccess() {
                diagnostic("sdk_callback", "operation=" + operation + " session=" + token + " success=true");
                // CAM3 v2.2: Distinguish SDK arrival time from executor processing time.
                executor.execute(() -> {
                    diagnostic("sdk_callback_processed", "operation=" + operation + " session=" + token + " success=true");
                    completion.complete(true);
                });
            }
            @Override public void onFailure(IDJIError error) {
                diagnostic("sdk_callback", "operation=" + operation + " session=" + token
                        + " success=false error=" + (error == null ? "unknown" : error.errorCode()));
                // CAM3 v2.2: Failed requests also retain processing order for diagnosis.
                executor.execute(() -> {
                    diagnostic("sdk_callback_processed", "operation=" + operation + " session=" + token + " success=false");
                    completion.complete(false);
                });
            }
        };
    }
    // CAM3 v2.1: Log authority requests; do not interpret their callbacks as physical aircraft response.
    @Override public void enable(AimingSession.Completion completion) {
        diagnostic("enable_request", "session=" + session.sessionId()); sdk.enableVirtualStick(callback("enable", completion));
    }
    @Override public void advanced() {
        diagnostic("advanced_request", "session=" + session.sessionId()); sdk.setVirtualStickAdvancedModeEnabled(true);
    }
    @Override public void disable(AimingSession.Completion completion) {
        diagnostic("disable_request", "session=" + session.sessionId()); sdk.disableVirtualStick(callback("disable", completion));
    }
    // CAM3 v2.1: Diagnostics failures are isolated from the existing control-loop exception/stop handler.
    @Override public void diagnostic(String event, String detail) {
        // CAM3 v2.5: Full sessions receive all diagnostics; minimal files omit routine traffic.
        try { fullLog.record(event, "detail", detail); if (MinimalLogPolicy.keep(event, detail)) logger.event(event, detail); } catch (RuntimeException ignored) { }
    }
    private void logChanged(String event, String signature, String detail, long repeatMs) {
        // CAM3 v2.5: Full summaries retain their sampling cadence; no periodic summaries in minimal mode.
        try { fullLog.record(event, "detail", detail); if (MinimalLogPolicy.keep(event, detail)) logger.changed(event, signature, detail, repeatMs); } catch (RuntimeException ignored) { }
    }
    private void recordDiagnostics() {
        try {
            AimingSession.State state = session.state();
            if (!foreground && state != AimingSession.State.STOPPING
                    && state != AimingSession.State.RELEASE_UNCONFIRMED) return;
            long at = now();
            AircraftAimingTelemetry.DiagnosticSnapshot telemetry = aircraft.diagnostics(at, getTargetFix());
            AimingSession.Inputs in = telemetry.inputs;
            AimingSession.Fix fix = in.target;
            String reason = in.validate(at);
            String gate = "session=" + session.sessionId() + " state=" + state + " stopReason=" + session.reason()
                    + " inputProblem=" + reason + " canStart=" + session.canStart()
                    + " owner=" + authority().owner + " stateSequence=" + authority.sequence
                    // CAM3 v2.3: This value is historical, not evidence of ongoing commands.
                    + " listenerGeneration=" + listenerGeneration.get() + " lastSubmittedYawRate=" + lastCommandRate
                    + " sending=" + session.maySendYaw();
            double separation = fix == null ? Double.NaN : YawAimingMath.distance(in.lat, in.lon, fix.lat, fix.lon);
            String quality = " source=" + (usePhone ? "phone" : "lora_wifi") + " accuracyGate=bypassed targetAccuracyM=" + (fix == null ? "unavailable" : fix.accuracy)
                    + " separationM=" + (Double.isFinite(separation) ? String.valueOf(Math.round(separation)) : "unavailable");
            String signature = gate + telemetry.signature + quality;
            String detail = gate + " " + telemetry.detail + quality
                    + " targetAgeMs=" + (fix == null ? -1 : at - fix.time)
                    + " lastCommandAgeMs=" + (lastCommandAt == 0 ? -1 : at - lastCommandAt)
                    + " recoveryRemainingMs=" + session.recoveryRemainingMs(); // CAM3 v2.3: trace recovery progress.
            logChanged("readiness", signature, detail, 5000);
            // CAM3 v2.2: Independently assess phone quality even when aircraft mode is rejected.
            readinessDetails = aircraft.blockers(at) + "\n" + phoneDetails(in, at)
                    + "\nControl: " + authority.owner + " (state callbacks: " + stateSequence.get() + ")"
                    + "\n" + (listening ? "Monitoring control changes" : "Control listener unavailable")
                    + "\nSession: " + state + " — " + session.reason()
                    + (state == AimingSession.State.PAUSED ? "\nRecovery remaining: " + session.recoveryRemainingMs() + " ms" : "");
            logChanged("blockers", readinessDetails, readinessDetails, 5000);
        } catch (RuntimeException ignored) { /* Logging cannot cancel or keep aiming alive. */ }
    }
    // CAM3 v2.2: Report accuracy and separation separately, without exposing coordinates.
    private String phoneDetails(AimingSession.Inputs in, long now) {
        AimingSession.Fix f = in.target;
        if (f == null) return (usePhone ? "Phone GPS unavailable; check precise location permission" : "LoRa GPS unavailable\n" + lora.details());
        String age = now < f.time || now - f.time > 3000 ? "STALE" : "fresh";
        double distance = YawAimingMath.distance(in.lat, in.lon, f.lat, f.lon);
        // CAM3 v2.7: Report distance without the removed minimum gate.
        return String.format(java.util.Locale.US, "%s: %s; accuracy gate bypassed%nReported accuracy: %s%nDistance: %s; no minimum-distance pause",
                usePhone ? "Phone GPS" : "LoRa GPS", age,
                Double.isFinite(f.accuracy) ? String.format(java.util.Locale.US, "%.1f m (informational)",f.accuracy) : "unavailable",
                Double.isFinite(distance) ? String.format(java.util.Locale.US,"%.1f m",distance) : "unavailable")
                + (usePhone ? "" : "\n" + lora.details());
    }
    // CAM3 v2.7: Immutable scalar snapshot for reconstruction; no disk work or extra SDK queries here.
    private void recordAimingCycle(AimingSession.Inputs observed) {
        try {
            long at=now();
            DominantDirectionTracker t=session.directionTracker;
            NearbyDirectionLock lock=session.nearbyLock;
            nearbyStatus=(nearbyEnabled ? "Nearby ON" : "Nearby OFF")+"; dominant="+DominantDirectionTracker.name(t.calculateDominantDirection())
                    +"; votes R/L="+t.rightVotes()+"/"+t.leftVotes()+"; lock="+DominantDirectionTracker.name(lock.direction())
                    +"; age="+(lock.age(at)<0 ? "-" : lock.age(at)/1000+"s");
            AimingCycleLog.record(fullLog,session,observed,at,nearbyEnabled,usePhone,submittedCycle,lastCommandRate);
            MovementCycleLog.record(fullLog,session,at,submittedCycle,lastSubmittedForward);
        } catch(RuntimeException ignored) { /* Logging cannot change a steering decision. */ }
    }
    public void exportLog(Uri destination, AimingDiagnosticLogger.Result result) {
        // CAM3 v2.1: ContentResolver access and copying run on the logger worker, never on the UI/control thread.
        logger.export(() -> appContext.getContentResolver().openOutputStream(destination, "wt"), result);
    }
    @Override public void sendYaw(double rate) { sendMotion(rate,0); }
    @Override public void sendMotion(double rate,double forward) {
        AimingSession.Authority a = authority();
        // CAM3 v2.2: Normal zero-yaw ticks use the same grant rule; cleanup zero needs observed MSDK.
        boolean active = foreground && !yielding && session.maySendYaw() && session.commandEligible();
        // CAM3 v2.3: A paused live session can submit one zero using its confirmed enable/advanced state.
        boolean neutral = rate == 0 && forward == 0 && !yielding && (session.maySendPauseNeutral()
                || (a.owner == AimingSession.Owner.MSDK && a.enabled && a.advanced));
        if (!active && !neutral) return;
        // CAM3 v2.7: Retain ownership gates; permit only the opt-in bounded speed increase.
        if(forward!=0 && (!active || !session.movement.permits(inputs(),now(),forward))) forward=0;
        // Callback cancellation can arrive during the final position read.
        if((rate!=0 || forward!=0) && (!foreground || yielding || !session.maySendYaw() || !session.commandEligible())) return;
        sdk.sendVirtualStickAdvancedParam(AimingMotionCommand.build(rate,forward,nearbyEnabled));
        lastSubmittedForward=forward;
        if(forward!=0) lastTranslationAt=now();
        submittedCycle=session.cycleId;
        // CAM3 v2.5: Log actual submissions after the unchanged flight-control call.
        fullLog.record("yaw_command", "submittedForwardMps", forward, "submittedYawRate", rate, "session", session.sessionId(), "cycleId", session.cycleId); // CAM3 v2.7: Join submissions to decisions.
        // CAM3 v2.2: First submission is an event; subsequent rates use the bounded summary cadence.
        lastCommandRate = rate;
        lastCommandAt = now(); // CAM3 v2.3: Timestamp actual API submission, never fabricate a zero.
        if (active && commandSession != session.sessionId()) {
            commandSession = session.sessionId();
            diagnostic("first_command", "session=" + commandSession + " submittedYawRate=" + rate);
        }
    }
    public static VirtualStickFlightControlParam buildYawOnlyCommand(double rate) {
        return YawOnlyCommand.build(rate);
    }
}
