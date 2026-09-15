package dji.v5.ux.sample.showcase.defaultlayout.aiming;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CAM3 v2.0: Testable state machine. All methods except cancelImmediately run on one executor.
 * Callbacks must be dispatched onto that executor. Never infer physical stopping from SDK success.
 */
public final class AimingSession {
    public enum State { OFF, STARTING, AIMING, STOPPING, STOPPED, RELEASE_UNCONFIRMED }
    public enum Owner { RC, MSDK, OTHER, UNKNOWN }
    public interface Completion { void complete(boolean success); }
    public interface Port {
        long now();
        Inputs inputs();
        Authority authority();
        void enable(Completion callback);
        void advanced();
        void disable(Completion callback);
        void sendYaw(double rate);
    }
    public static final class Authority {
        public final Owner owner;
        public final boolean enabled, advanced;
        public Authority(Owner owner, boolean enabled, boolean advanced) {
            this.owner = owner; this.enabled = enabled; this.advanced = advanced;
        }
    }
    public static final class Fix {
        public final double lat, lon, accuracy;
        public final long time;
        public Fix(double lat, double lon, double accuracy, long time) {
            this.lat = lat; this.lon = lon; this.accuracy = accuracy; this.time = time;
        }
    }
    public static final class Inputs {
        public final Fix target;
        public final double lat, lon, heading;
        public final long aircraftTime;
        public final String problem;
        public final boolean safeToNeutral;
        public Inputs(Fix target, double lat, double lon, double heading, long aircraftTime,
                      String problem, boolean safeToNeutral) {
            this.target = target; this.lat = lat; this.lon = lon; this.heading = heading;
            this.aircraftTime = aircraftTime; this.problem = problem; this.safeToNeutral = safeToNeutral;
        }
        public String validate(long now) {
            if (problem != null) return problem;
            if (aircraftTime <= 0 || now < aircraftTime || now - aircraftTime > 1500) return "telemetry";
            if (!YawAimingMath.coordinateValid(lat, lon) || !Double.isFinite(heading)
                    || heading < -180 || heading > 360) return "heading";
            if (target == null) return "gps";
            if (target.time <= 0 || now < target.time || now - target.time > 3000) return "stale_gps";
            if (!YawAimingMath.coordinateValid(target.lat, target.lon)
                    || !YawAimingMath.isBearingUsable(YawAimingMath.distance(lat, lon, target.lat, target.lon),
                    target.accuracy)) return "gps_quality";
            return null;
        }
    }
    private final Port port;
    private final AtomicBoolean cancelled = new AtomicBoolean(true);
    private State state = State.OFF;
    private String reason = "ready";
    private boolean enablePending, claimed, enableSucceeded, releasePending, advancedRequested, releaseAttempted;
    private long session, started, lastTick, releaseStarted, releaseSequence;
    private double rate;
    private Authority beforeEnable;
    public AimingSession(Port port) { this.port = port; }
    public State state() { return state; }
    public String reason() { return reason; }
    public void cancelImmediately() { cancelled.set(true); }
    public boolean maySendYaw() { return !cancelled.get() && state == State.AIMING; }
    public boolean canStart() {
        Authority a = port.authority();
        return !enablePending && !claimed && !releasePending && state != State.STARTING
                && state != State.AIMING && a.owner == Owner.RC && !a.enabled
                && port.inputs().validate(port.now()) == null;
    }
    public void startAiming() {
        if (!canStart()) return;
        cancelled.set(false);
        state = State.STARTING; reason = "acquiring";
        started = port.now(); lastTick = 0; rate = 0;
        advancedRequested = false; enableSucceeded = false; releaseAttempted = false;
        enablePending = true; claimed = true;
        beforeEnable = port.authority();
        long token = ++session;
        try {
            port.enable(success -> {
                if (token != session) return;
                enablePending = false;
                enableSucceeded = success;
                if (!success) stopAiming("enable_failed");
                else if (cancelled.get() || state != State.STARTING) {
                    // A late grant after timeout still needs a release attempt, never a new aiming loop.
                    state = State.STOPPING; releaseStarted = port.now(); stopAiming(reason);
                }
            });
        } catch (RuntimeException ex) {
            // A throwing enable call may still have reached DJI. Preserve unresolved ownership.
            stopAiming("sdk_error");
        }
    }
    public void tick() {
        try {
            if (state == State.STOPPING || state == State.RELEASE_UNCONFIRMED) {
                settleRelease(); return;
            }
            if (state != State.STARTING && state != State.AIMING) return;
            if (cancelled.get()) { stopAiming("cancelled"); return; }
            Inputs in = port.inputs();
            String problem = in.validate(port.now());
            if (problem != null) { stopAiming(problem); return; }
            Authority a = port.authority();
            if (state == State.STARTING) {
                if (port.now() - started > 5000) { stopAiming("start_timeout"); return; }
                if (!enableSucceeded || a.owner != Owner.MSDK || !a.enabled) return;
                if (!advancedRequested) {
                    advancedRequested = true;
                    if (cancelled.get()) { stopAiming("cancelled"); return; }
                    port.advanced();
                    return;
                }
                if (!a.advanced) return;
                state = State.AIMING; reason = "aiming"; lastTick = port.now();
            }
            if (a.owner != Owner.MSDK || !a.enabled || !a.advanced) { stopAiming("takeover"); return; }
            long now = port.now();
            long elapsed = now - lastTick;
            if (elapsed < 0 || elapsed > 500) { stopAiming("loop_stall"); return; }
            double error = YawAimingMath.shortestHeadingError(
                    YawAimingMath.bearingToTarget(in.lat, in.lon, in.target.lat, in.target.lon), in.heading);
            rate = YawAimingMath.calculateYawRate(error, rate, Math.max(0.001, elapsed / 1000.0));
            // Fresh read immediately before sending; a pause must not revive a previously calculated command.
            Authority latest = port.authority();
            if (cancelled.get() || latest.owner != Owner.MSDK || !latest.enabled || !latest.advanced
                    || port.now() - now > 200 || port.inputs().validate(port.now()) != null) {
                stopAiming("inputs_changed"); return;
            }
            port.sendYaw(rate);
            lastTick = now;
        } catch (RuntimeException ex) { stopAiming("sdk_error"); }
    }
    public void stopAiming(String why) {
        cancelled.set(true); rate = 0; reason = why;
        if (!claimed && !enablePending) { state = State.STOPPED; return; }
        if (state != State.STOPPING && state != State.RELEASE_UNCONFIRMED) {
            state = State.STOPPING; releaseStarted = port.now();
        }
        settleRelease();
    }
    private void settleRelease() {
        Authority a = port.authority();
        // A pending enable blocks a new session, but observed MSDK ownership can already be released.
        if (!enablePending && !releasePending && !a.enabled && a.owner == Owner.RC && a != beforeEnable) {
            claimed = false; state = State.STOPPED; return;
        }
        // Other authority owners are not ours to disable or send a neutral command to.
        if (!enablePending && !releasePending && a.owner == Owner.OTHER) {
            claimed = false; state = State.STOPPED; return;
        }
        if (!releasePending && claimed && a.owner == Owner.MSDK && !releaseAttempted) {
            // A grant may arrive after both the enable callback and the stop timeout.
            // Track attempts separately from the UI state so that late ownership is still released.
            releasePending = true; releaseAttempted = true;
            long token = ++releaseSequence;
            try {
                Inputs in = port.inputs();
                if (a.enabled && a.advanced && in.safeToNeutral
                        && port.now() - in.aircraftTime <= 1500) {
                    try { port.sendYaw(0); } catch (RuntimeException ignored) { /* Still attempt release. */ }
                }
                // Recheck after neutral: RTH/takeover may have happened during the call.
                if (port.authority().owner != Owner.MSDK) { releasePending = false; return; }
                port.disable(success -> {
                    if (token != releaseSequence) return;
                    releasePending = false;
                    if (!success) state = State.RELEASE_UNCONFIRMED;
                    // Successful API completion still requires an observed RC/disabled state.
                    else if (!enablePending && port.authority().owner == Owner.RC && !port.authority().enabled) {
                        claimed = false; state = State.STOPPED;
                    } else state = State.RELEASE_UNCONFIRMED;
                });
            } catch (RuntimeException ex) { releasePending = false; state = State.RELEASE_UNCONFIRMED; }
        }
        if (port.now() - releaseStarted > 5000) state = State.RELEASE_UNCONFIRMED;
    }
}
