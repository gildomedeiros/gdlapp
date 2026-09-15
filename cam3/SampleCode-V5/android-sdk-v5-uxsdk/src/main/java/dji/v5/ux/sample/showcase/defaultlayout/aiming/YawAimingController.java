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
            boolean running = session.state() == AimingSession.State.STARTING || session.state() == AimingSession.State.AIMING;
            if ((running && (mapped == AimingSession.Owner.RC || mapped == AimingSession.Owner.OTHER))
                    || (session.state() == AimingSession.State.AIMING && (!value.isVirtualStickEnable() || !value.isVirtualStickAdvancedModeEnabled()))
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
        phone = new PhoneTargetLocationSource(context);
        // CAM3 v2.1: Capture individual read failures/recovery, in addition to periodic field snapshots.
        aircraft = new AircraftAimingTelemetry(this::interruptAiming,
                (field, error) -> logChanged("read_" + field, error, "field=" + field + " error=" + error, 0));
        session = new AimingSession(this);
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
                if (foreground) aircraft.start();
            } catch (RuntimeException ex) { session.stopAiming("sdk_error"); }
        });
        phone.start();
    }
    public void pause(Observer callback) {
        // An old Activity's onDestroy must not detach a newer foreground Activity.
        if (observer != callback) return;
        foreground = false; observer = null;
        stopAiming("screen_closed");
        phone.stop();
        executor.execute(aircraft::stop);
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
    private void interruptAiming(String reason) {
        intent.incrementAndGet();
        session.cancelImmediately();
        executor.execute(() -> {
            // Expected ownership changes during our own release must preserve the original stop reason.
            if (session.state() == AimingSession.State.STARTING || session.state() == AimingSession.State.AIMING)
                session.stopAiming(reason);
        });
    }
    private void tick() {
        try {
            if (foreground) aircraft.poll();
            session.tick();
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
                // CAM3 v2.2: Listener availability is also required for user-visible readiness.
                boolean ready = listening && session.canStart();
                String reason = session.reason();
                if (state == AimingSession.State.OFF || state == AimingSession.State.STOPPED) {
                    String invalid = inputs().validate(now());
                    if (invalid != null) reason = invalid;
                    else if (!ready) reason = "authority";
                }
                final String message = reason;
                AimingSession.Fix fix = phone.getLatestFix();
                main.post(() -> {
                    if (foreground && target != null && target == observer) target.onState(state, message, ready, fix, now());
                });
            }
        } catch (RuntimeException ex) { session.stopAiming("sdk_error"); }
    }
    @Override public long now() { return SystemClock.elapsedRealtime(); }
    @Override public AimingSession.Inputs inputs() { return aircraft.getSnapshot(phone.getLatestFix()); }
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
        try { logger.event(event, detail); } catch (RuntimeException ignored) { }
    }
    private void logChanged(String event, String signature, String detail, long repeatMs) {
        try { logger.changed(event, signature, detail, repeatMs); } catch (RuntimeException ignored) { }
    }
    private void recordDiagnostics() {
        try {
            AimingSession.State state = session.state();
            if (!foreground && state != AimingSession.State.STOPPING
                    && state != AimingSession.State.RELEASE_UNCONFIRMED) return;
            long at = now();
            AircraftAimingTelemetry.DiagnosticSnapshot telemetry = aircraft.diagnostics(at, phone.getLatestFix());
            AimingSession.Inputs in = telemetry.inputs;
            AimingSession.Fix fix = in.target;
            String reason = in.validate(at);
            String gate = "session=" + session.sessionId() + " state=" + state + " stopReason=" + session.reason()
                    + " inputProblem=" + reason + " canStart=" + session.canStart()
                    + " owner=" + authority().owner + " stateSequence=" + authority.sequence
                    + " listenerGeneration=" + listenerGeneration.get() + " submittedYawRate=" + lastCommandRate;
            double separation = fix == null ? Double.NaN : YawAimingMath.distance(in.lat, in.lon, fix.lat, fix.lon);
            String quality = " phoneAccuracyM=" + (fix == null ? "unavailable" : fix.accuracy)
                    + " separationM=" + (Double.isFinite(separation) ? String.valueOf(Math.round(separation)) : "unavailable");
            String signature = gate + telemetry.signature + quality;
            String detail = gate + " " + telemetry.detail + quality
                    + " phoneAgeMs=" + (fix == null ? -1 : at - fix.time);
            logChanged("readiness", signature, detail, 5000);
            // CAM3 v2.2: Independently assess phone quality even when aircraft mode is rejected.
            readinessDetails = aircraft.blockers(at) + "\n" + phoneDetails(in, at)
                    + "\nControl: " + authority.owner + " (state callbacks: " + stateSequence.get() + ")"
                    + "\n" + (listening ? "Monitoring control changes" : "Control listener unavailable")
                    + "\nSession: " + state + " — " + session.reason();
            logChanged("blockers", readinessDetails, readinessDetails, 5000);
        } catch (RuntimeException ignored) { /* Logging cannot cancel or keep aiming alive. */ }
    }
    // CAM3 v2.2: Report accuracy and separation separately, without exposing coordinates.
    private static String phoneDetails(AimingSession.Inputs in, long now) {
        AimingSession.Fix f = in.target;
        if (f == null) return "Phone GPS unavailable";
        String age = now < f.time || now - f.time > 3000 ? "STALE" : "fresh";
        double distance = YawAimingMath.distance(in.lat, in.lon, f.lat, f.lon);
        double required = Math.max(20, 4 * (f.accuracy + 5));
        return String.format(java.util.Locale.US, "Phone GPS: %s; accuracy %.1f m (maximum 10 m)%nDistance: %s; required %.1f m",
                age, f.accuracy, Double.isFinite(distance) ? String.format(java.util.Locale.US, "%.1f m", distance) : "unavailable", required);
    }
    public void exportLog(Uri destination, AimingDiagnosticLogger.Result result) {
        // CAM3 v2.1: ContentResolver access and copying run on the logger worker, never on the UI/control thread.
        logger.export(() -> appContext.getContentResolver().openOutputStream(destination, "wt"), result);
    }
    @Override public void sendYaw(double rate) {
        AimingSession.Authority a = authority();
        // CAM3 v2.2: Normal zero-yaw ticks use the same grant rule; cleanup zero needs observed MSDK.
        boolean active = foreground && !yielding && session.maySendYaw() && session.commandEligible();
        boolean neutral = rate == 0 && !yielding && a.owner == AimingSession.Owner.MSDK && a.enabled && a.advanced;
        if (!active && !neutral) return;
        sdk.sendVirtualStickAdvancedParam(buildYawOnlyCommand(rate));
        // CAM3 v2.2: First submission is an event; subsequent rates use the bounded summary cadence.
        lastCommandRate = rate;
        if (active && commandSession != session.sessionId()) {
            commandSession = session.sessionId();
            diagnostic("first_command", "session=" + commandSession + " submittedYawRate=" + rate);
        }
    }
    public static VirtualStickFlightControlParam buildYawOnlyCommand(double rate) {
        return YawOnlyCommand.build(rate);
    }
}
