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
                    : owner == FlightControlAuthority.UNKNOWN ? AimingSession.Owner.UNKNOWN : AimingSession.Owner.OTHER;
            authority = new AimingSession.Authority(mapped, value.isVirtualStickEnable(), value.isVirtualStickAdvancedModeEnabled());
            // CAM3 v2.1: Observed ownership is logged separately from enable/disable callbacks.
            String observed = "owner=" + mapped + " enabled=" + value.isVirtualStickEnable()
                    + " advanced=" + value.isVirtualStickAdvancedModeEnabled();
            if (old.owner == AimingSession.Owner.MSDK && (mapped != AimingSession.Owner.MSDK
                    || !value.isVirtualStickEnable() || (old.advanced && !value.isVirtualStickAdvancedModeEnabled()))) {
                interruptAiming();
            }
            // CAM3 v2.1: Latch any cancellation before queuing diagnostic output.
            logChanged("authority_observed", observed, observed, 0);
        }
        @Override public void onChangeReasonUpdate(FlightControlAuthorityChangeReason reason) {
            // CAM3 v2.1: Record DJI's exact takeover reason instead of only the generic UI label.
            if (reason != FlightControlAuthorityChangeReason.MSDK_REQUEST) {
                // Do not compete with RTH/pilot while a matching state update is still in transit.
                yielding = true;
                interruptAiming();
            }
            // CAM3 v2.1: Logging follows the existing immediate takeover latch.
            logChanged("authority_reason", String.valueOf(reason), String.valueOf(reason), 0);
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
                if (!listening) { listening = true; sdk.setVirtualStickStateListener(stateListener); }
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
            if (foreground && intent.get() == request) {
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
    private void interruptAiming() {
        intent.incrementAndGet();
        session.cancelImmediately();
        executor.execute(() -> {
            // Expected ownership changes during our own release must preserve the original stop reason.
            if (session.state() == AimingSession.State.STARTING || session.state() == AimingSession.State.AIMING)
                session.stopAiming("takeover");
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
                sdk.removeVirtualStickStateListener(stateListener); listening = false;
                authority = new AimingSession.Authority(AimingSession.Owner.UNKNOWN, false, false);
            }
            if (foreground && now() - lastRender >= 250) {
                lastRender = now();
                Observer target = observer;
                AimingSession.State state = session.state();
                boolean ready = session.canStart();
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
        AimingSession.Authority a = authority;
        return yielding && a.owner == AimingSession.Owner.MSDK
                ? new AimingSession.Authority(AimingSession.Owner.UNKNOWN, a.enabled, a.advanced) : a;
    }
    // CAM3 v2.1: Include operation/session and SDK error code, while preserving callback dispatch order.
    private CommonCallbacks.CompletionCallback callback(String operation, AimingSession.Completion completion) {
        long token = session.sessionId();
        return new CommonCallbacks.CompletionCallback() {
            @Override public void onSuccess() {
                diagnostic("sdk_callback", "operation=" + operation + " session=" + token + " success=true");
                executor.execute(() -> completion.complete(true));
            }
            @Override public void onFailure(IDJIError error) {
                diagnostic("sdk_callback", "operation=" + operation + " session=" + token
                        + " success=false error=" + (error == null ? "unknown" : error.errorCode()));
                executor.execute(() -> completion.complete(false));
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
                    + " owner=" + authority().owner;
            double separation = fix == null ? Double.NaN : YawAimingMath.distance(in.lat, in.lon, fix.lat, fix.lon);
            String quality = " phoneAccuracyM=" + (fix == null ? "unavailable" : fix.accuracy)
                    + " separationM=" + (Double.isFinite(separation) ? String.valueOf(Math.round(separation)) : "unavailable");
            String signature = gate + telemetry.signature + quality;
            String detail = gate + " " + telemetry.detail + quality
                    + " phoneAgeMs=" + (fix == null ? -1 : at - fix.time);
            logChanged("readiness", signature, detail, 5000);
        } catch (RuntimeException ignored) { /* Logging cannot cancel or keep aiming alive. */ }
    }
    public void exportLog(Uri destination, AimingDiagnosticLogger.Result result) {
        // CAM3 v2.1: ContentResolver access and copying run on the logger worker, never on the UI/control thread.
        logger.export(() -> appContext.getContentResolver().openOutputStream(destination, "wt"), result);
    }
    @Override public void sendYaw(double rate) {
        AimingSession.Authority a = authority();
        if (a.owner != AimingSession.Owner.MSDK || !a.enabled || !a.advanced) return;
        if (rate != 0 && (!foreground || !session.maySendYaw())) return;
        sdk.sendVirtualStickAdvancedParam(buildYawOnlyCommand(rate));
    }
    public static VirtualStickFlightControlParam buildYawOnlyCommand(double rate) {
        return YawOnlyCommand.build(rate);
    }
}
