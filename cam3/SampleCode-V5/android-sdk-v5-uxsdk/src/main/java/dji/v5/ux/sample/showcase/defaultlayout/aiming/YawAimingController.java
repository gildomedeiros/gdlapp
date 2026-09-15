package dji.v5.ux.sample.showcase.defaultlayout.aiming;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
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
    private final VirtualStickStateListener stateListener = new VirtualStickStateListener() {
        @Override public void onVirtualStickStateUpdate(VirtualStickState value) {
            AimingSession.Authority old = authority;
            FlightControlAuthority owner = value.getCurrentFlightControlAuthorityOwner();
            AimingSession.Owner mapped = owner == FlightControlAuthority.MSDK ? AimingSession.Owner.MSDK
                    : owner == FlightControlAuthority.RC ? AimingSession.Owner.RC
                    : owner == FlightControlAuthority.UNKNOWN ? AimingSession.Owner.UNKNOWN : AimingSession.Owner.OTHER;
            authority = new AimingSession.Authority(mapped, value.isVirtualStickEnable(), value.isVirtualStickAdvancedModeEnabled());
            if (old.owner == AimingSession.Owner.MSDK && (mapped != AimingSession.Owner.MSDK
                    || !value.isVirtualStickEnable() || (old.advanced && !value.isVirtualStickAdvancedModeEnabled()))) {
                interruptAiming();
            }
        }
        @Override public void onChangeReasonUpdate(FlightControlAuthorityChangeReason reason) {
            if (reason != FlightControlAuthorityChangeReason.MSDK_REQUEST) {
                // Do not compete with RTH/pilot while a matching state update is still in transit.
                yielding = true;
                interruptAiming();
            }
        }
    };
    private YawAimingController(Context context) {
        phone = new PhoneTargetLocationSource(context);
        aircraft = new AircraftAimingTelemetry(this::interruptAiming);
        session = new AimingSession(this);
        executor.scheduleWithFixedDelay(this::tick, 0, 100, TimeUnit.MILLISECONDS);
    }
    public void resume(Observer callback) {
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
    private CommonCallbacks.CompletionCallback callback(AimingSession.Completion completion) {
        return new CommonCallbacks.CompletionCallback() {
            @Override public void onSuccess() { executor.execute(() -> completion.complete(true)); }
            @Override public void onFailure(IDJIError error) { executor.execute(() -> completion.complete(false)); }
        };
    }
    @Override public void enable(AimingSession.Completion completion) { sdk.enableVirtualStick(callback(completion)); }
    @Override public void advanced() { sdk.setVirtualStickAdvancedModeEnabled(true); }
    @Override public void disable(AimingSession.Completion completion) { sdk.disableVirtualStick(callback(completion)); }
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
