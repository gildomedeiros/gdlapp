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
        // CAM3 v2.1: Optional diagnostics port has no authority over flight state or command delivery.
        default void diagnostic(String event, String detail) { }
        // CAM3 v2.2: A reason callback can announce takeover before the state callback arrives.
        default boolean controlLost() { return false; }
    }
    public static final class Authority {
        // CAM3 v2.2: Object identity marks a received observation; arrival metadata is diagnostic.
        public final long sequence, receivedAt;
        public final Owner owner;
        public final boolean enabled, advanced;
        public Authority(Owner owner, boolean enabled, boolean advanced) {
            this(owner, enabled, advanced, 0, 0);
        }
        public Authority(Owner owner, boolean enabled, boolean advanced, long sequence, long receivedAt) {
            this.sequence = sequence; this.receivedAt = receivedAt;
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
    // CAM3 v2.2: Callback thread may inspect phase solely to latch cancellation.
    private volatile State state = State.OFF;
    private String reason = "ready";
    private boolean enablePending, claimed, enableSucceeded, releasePending, advancedRequested, releaseAttempted;
    private long session, started, lastTick, releaseStarted, releaseSequence;
    private double rate;
    private Authority beforeEnable;
    // CAM3 v2.2: Confirm advanced state after its request, independently of positive owner evidence.
    private Authority beforeAdvanced, beforeRelease;
    private boolean observedMsdk;
    private boolean lateGrantDuringRelease;
    public boolean commandEligible() {
        Authority a = port.authority();
        return !port.controlLost() && enableSucceeded && advancedRequested && a != beforeAdvanced && a.enabled && a.advanced
                && (a.owner == Owner.MSDK || (a.owner == Owner.UNKNOWN && !observedMsdk));
    }
    public AimingSession(Port port) { this.port = port; }
    public State state() { return state; }
    public String reason() { return reason; }
    // CAM3 v2.1: Expose session identity for correlating callbacks and readiness snapshots.
    public long sessionId() { return session; }
    private void diagnostic(String event, String detail) {
        try { port.diagnostic(event, "session=" + session + " " + detail); }
        catch (RuntimeException ignored) { /* Diagnostics must never alter a flight-control decision. */ }
    }
    private void setState(State next) {
        State previous = state;
        state = next;
        if (previous != next) diagnostic("state", previous + " -> " + next + " reason=" + reason);
    }
    public void cancelImmediately() { cancelled.set(true); }
    public boolean maySendYaw() { return !cancelled.get() && state == State.AIMING; }
    public boolean canStart() {
        Authority a = port.authority();
        return !enablePending && !claimed && !releasePending && state != State.STARTING
                // CAM3 v2.2: An unavailable initial owner permits a request, never implicit acquisition.
                && state != State.AIMING && (a.owner == Owner.RC || a.owner == Owner.UNKNOWN) && !a.enabled
                && port.inputs().validate(port.now()) == null;
    }
    public void startAiming() {
        if (!canStart()) return;
        cancelled.set(false);
        // CAM3 v2.1: Allocate the diagnostic session before logging its first transition.
        long token = ++session;
        reason = "acquiring"; setState(State.STARTING);
        started = port.now(); lastTick = 0; rate = 0;
        advancedRequested = false; enableSucceeded = false; releaseAttempted = false;
        enablePending = true; claimed = true;
        beforeEnable = port.authority();
        // CAM3 v2.2: Reset observation evidence for this explicit attempt and log the assumption.
        observedMsdk = false; beforeAdvanced = null; beforeRelease = beforeEnable;
        diagnostic("initial_control", "owner=" + beforeEnable.owner + " sequence=" + beforeEnable.sequence);
        try {
            port.enable(success -> {
                // CAM3 v2.1: Distinguish callback completion, cancellation and stale-session arrival.
                diagnostic("enable_result", "token=" + token + " success=" + success + " cancelled=" + cancelled.get());
                if (token != session) return;
                enablePending = false;
                enableSucceeded = success;
                if (!success) stopAiming("enable_failed");
                else if (cancelled.get() || state != State.STARTING) {
                    // CAM3 v2.2: A late enable can follow an earlier disable; release this new grant once.
                    if (!releasePending) releaseAttempted = false;
                    else lateGrantDuringRelease = true;
                    beforeEnable = port.authority();
                    beforeRelease = port.authority();
                    // A late grant after timeout still needs a release attempt, never a new aiming loop.
                    // CAM3 v2.1: Log the existing late-grant cleanup transition.
                    setState(State.STOPPING); releaseStarted = port.now(); stopAiming(reason);
                }
            });
        } catch (RuntimeException ex) {
            // CAM3 v2.1: Record exception type without logging potentially sensitive exception payloads.
            diagnostic("enable_exception", ex.getClass().getSimpleName());
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
            // CAM3 v2.2: New contradictory state vetoes acquisition; old RC state is not a takeover.
            if (a.owner == Owner.MSDK) observedMsdk = true;
            if (a != beforeEnable && (a.owner == Owner.RC || a.owner == Owner.OTHER
                    || (observedMsdk && a.owner == Owner.UNKNOWN))) {
                stopAiming("takeover"); return;
            }
            if (state == State.STARTING) {
                // CAM3 v2.2: Name the missing confirmation and request advanced after enable success.
                if (port.now() - started > 5000) { stopAiming(enableSucceeded ? "advanced_timeout" : "start_timeout"); return; }
                if (!enableSucceeded) return;
                if (!advancedRequested) {
                    advancedRequested = true;
                    beforeAdvanced = a;
                    reason = "advanced_wait";
                    if (cancelled.get()) { stopAiming("cancelled"); return; }
                    port.advanced();
                    return;
                }
                if (!commandEligible()) return;
                // CAM3 v2.1: Observe the existing confirmed-start transition, without changing its gate.
                reason = "aiming"; setState(State.AIMING); lastTick = port.now();
            }
            // CAM3 v2.2: The same eligibility rule applies at activation and every send.
            if (!commandEligible()) { stopAiming("takeover"); return; }
            long now = port.now();
            long elapsed = now - lastTick;
            if (elapsed < 0 || elapsed > 500) { stopAiming("loop_stall"); return; }
            double error = YawAimingMath.shortestHeadingError(
                    YawAimingMath.bearingToTarget(in.lat, in.lon, in.target.lat, in.target.lon), in.heading);
            rate = YawAimingMath.calculateYawRate(error, rate, Math.max(0.001, elapsed / 1000.0));
            // Fresh read immediately before sending; a pause must not revive a previously calculated command.
            Authority latest = port.authority();
            if (cancelled.get() || !commandEligible()
                    || port.now() - now > 200 || port.inputs().validate(port.now()) != null) {
                stopAiming("inputs_changed"); return;
            }
            port.sendYaw(rate);
            lastTick = now;
        // CAM3 v2.1: Record loop exceptions before the same fail-safe stop path.
        } catch (RuntimeException ex) {
            diagnostic("loop_exception", ex.getClass().getSimpleName()); stopAiming("sdk_error");
        }
    }
    public void stopAiming(String why) {
        cancelled.set(true); rate = 0; reason = why;
        // CAM3 v2.1: Log stop reasons even if the state itself has not changed.
        diagnostic("stop", "reason=" + why);
        if (!claimed && !enablePending) { setState(State.STOPPED); return; }
        if (state != State.STOPPING && state != State.RELEASE_UNCONFIRMED) {
            // CAM3 v2.1: Log the existing release-wait transition.
            setState(State.STOPPING); releaseStarted = port.now();
        }
        settleRelease();
    }
    private void settleRelease() {
        Authority a = port.authority();
        // A pending enable blocks a new session, but observed MSDK ownership can already be released.
        // CAM3 v2.2: Require a new disabled observation; UNKNOWN alone cannot prove release.
        if (!enablePending && !releasePending && !a.enabled && a != beforeRelease) {
            // CAM3 v2.1: Log observed release independently of API callbacks.
            claimed = false; setState(State.STOPPED); return;
        }
        // Other authority owners are not ours to disable or send a neutral command to.
        if (!enablePending && !releasePending && a.owner == Owner.OTHER && !a.enabled) {
            // CAM3 v2.1: Log yielding to a different authority owner.
            claimed = false; setState(State.STOPPED); return;
        }
        // CAM3 v2.2: Clean up our possible grant even without owner notification; yield to new RC/OTHER.
        boolean handedOver = a != beforeEnable && (a.owner == Owner.RC || a.owner == Owner.OTHER);
        if (!releasePending && claimed && !handedOver && !port.controlLost() && !releaseAttempted
                && (enableSucceeded || !enablePending || a.owner == Owner.MSDK)) {
            // A grant may arrive after both the enable callback and the stop timeout.
            // Track attempts separately from the UI state so that late ownership is still released.
            releasePending = true; releaseAttempted = true;
            long token = ++releaseSequence;
            beforeRelease = a;
            try {
                Inputs in = port.inputs();
                if (a.owner == Owner.MSDK && a.enabled && a.advanced && in.safeToNeutral
                        && port.now() - in.aircraftTime <= 1500) {
                    try { port.sendYaw(0); } catch (RuntimeException ignored) { /* Still attempt release. */ }
                }
                // Recheck after neutral: RTH/takeover may have happened during the call.
                Authority current = port.authority();
                if (current != beforeEnable && (current.owner == Owner.RC || current.owner == Owner.OTHER)) {
                    releasePending = false; return;
                }
                port.disable(success -> {
                    // CAM3 v2.1: Correlate release callbacks and identify late results.
                    diagnostic("disable_result", "releaseToken=" + token + " success=" + success);
                    if (token != releaseSequence) return;
                    releasePending = false;
                    // CAM3 v2.2: Disable dispatched before a late grant cannot settle that grant.
                    if (lateGrantDuringRelease) {
                        lateGrantDuringRelease = false; releaseAttempted = false;
                        beforeRelease = port.authority(); settleRelease(); return;
                    }
                    // CAM3 v2.1: Log existing failure/confirmation transitions without relaxing them.
                    if (!success) setState(State.RELEASE_UNCONFIRMED);
                    // Successful API completion still requires an observed RC/disabled state.
                    else if (!enablePending && port.authority() != beforeRelease && !port.authority().enabled) {
                        claimed = false; setState(State.STOPPED);
                    } else setState(State.RELEASE_UNCONFIRMED);
                });
            // CAM3 v2.1: Record release exception type and retain the existing unresolved state.
            } catch (RuntimeException ex) {
                diagnostic("release_exception", ex.getClass().getSimpleName());
                releasePending = false; setState(State.RELEASE_UNCONFIRMED);
            }
        }
        // CAM3 v2.1: Record the existing timeout transition once, not on every tick.
        if (port.now() - releaseStarted > 5000) setState(State.RELEASE_UNCONFIRMED);
    }
}
