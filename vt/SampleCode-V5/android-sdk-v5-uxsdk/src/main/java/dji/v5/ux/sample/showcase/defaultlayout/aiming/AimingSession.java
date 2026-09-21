package dji.v5.ux.sample.showcase.defaultlayout.aiming;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CAM3 v2.0: Testable state machine. All methods except cancelImmediately run on one executor.
 * Callbacks must be dispatched onto that executor. Never infer physical stopping from SDK success.
 */
public final class AimingSession {
    // CAM3 v2.3: Recoverable input failures pause the current explicit session.
    public enum State { OFF, STARTING, AIMING, PAUSED, STOPPING, STOPPED, RELEASE_UNCONFIRMED }
    public enum Owner { RC, MSDK, OTHER, UNKNOWN }
    public interface Completion { void complete(boolean success); }
    public interface Port {
        // CAM3 v2.7: Opt-in keeps the original steering path by default.
        default boolean nearbyTrackingEnabled() { return false; }
        default ComeToMeSettings movementSettings() { return new ComeToMeSettings(false,70,20,18,8,30000,900000); }
        default void sendMotion(double yaw, double forward) {
            if (forward != 0) throw new IllegalStateException("Translation port not implemented");
            sendYaw(yaw);
        }
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
        // CAM3 v2.7: Sequence travels atomically with its fix for per-cycle log correlation.
        public final long time, sequence, sampleTime;
        public Fix(double lat, double lon, double accuracy, long time) {
            this(lat, lon, accuracy, time, -1);
        }
        public Fix(double lat, double lon, double accuracy, long time, long sequence) {
            this(lat,lon,accuracy,time,sequence,time);
        }
        public Fix(double lat,double lon,double accuracy,long time,long sequence,long sampleTime) {
            this.lat=lat; this.lon=lon; this.accuracy=accuracy; this.time=time; this.sequence=sequence; this.sampleTime=sampleTime;
        }
    }
    public static final class Inputs {
        public final Fix target;
        public final double lat, lon, heading;
        public final long aircraftTime;
        public final String problem;
        public final boolean safeToNeutral;
        public final double horizontalSpeed, verticalSpeed;
        public boolean steadyHover() { return Double.isFinite(horizontalSpeed) && horizontalSpeed<=0.5
                && Double.isFinite(verticalSpeed) && Math.abs(verticalSpeed)<=0.3; }
        public Inputs(Fix target, double lat, double lon, double heading, long aircraftTime,
                      String problem, boolean safeToNeutral) {
            this(target,lat,lon,heading,aircraftTime,problem,safeToNeutral,0,0);
        }
        public Inputs(Fix target,double lat,double lon,double heading,long aircraftTime,
                      String problem,boolean safeToNeutral,double horizontalSpeed,double verticalSpeed) {
            this.horizontalSpeed=horizontalSpeed; this.verticalSpeed=verticalSpeed;
            this.target = target; this.lat = lat; this.lon = lon; this.heading = heading;
            this.aircraftTime = aircraftTime; this.problem = problem; this.safeToNeutral = safeToNeutral;
        }
        public String validate(long now) {
            // CAM3 v2.3: Stale telemetry requires control revalidation even when a recoverable field also fails.
            if (problem != null && !recoverable(problem)) return problem;
            if (aircraftTime <= 0 || now < aircraftTime || now - aircraftTime > 1500) return "telemetry";
            if (problem != null) return problem;
            if (!YawAimingMath.coordinateValid(lat, lon) || !Double.isFinite(heading)
                    || heading < -180 || heading > 360) return "heading";
            if (target == null) return "gps";
            if (target.time <= 0 || now < target.time || now - target.time > 3000) return "stale_gps";
            // CAM3 v2.7: No minimum-distance pause; coincident coordinates produce zero yaw below.
            if (!YawAimingMath.coordinateValid(target.lat, target.lon)
                    || !YawAimingMath.isBearingUsable(YawAimingMath.distance(lat, lon, target.lat, target.lon),
                    target.accuracy)) return "gps_quality";
            return null;
        }
    }
    // CAM3 v2.7: Executor-owned independent vote and lock helpers. Monitoring never acquires control.
    public final ComeToMeController movement=new ComeToMeController();
    public double cycleRequestedForward;
    public final DominantDirectionTracker directionTracker=new DominantDirectionTracker();
    public final NearbyDirectionLock nearbyLock=new NearbyDirectionLock();
    public long cycleId, cycleAt;
    public Inputs cycleInputs;
    public double cycleAngle=Double.NaN, cycleMultiplier=1, cycleRequestedYaw=Double.NaN;
    public String cycleDecision="idle";
    public void resetNearby(String reason, boolean clearVotes) {
        String event=nearbyLock.clear(reason);
        if(clearVotes) directionTracker.reset();
        if(event!=null) diagnostic("nearby_lock",event);
    }
    public void observeDirection(Inputs in,long now) {
        directionTracker.observe(in,now);
        // Release at range exit even while paused, but never use stale coordinates to release/capture.
        if(positionFresh(in,now)) {
            String event=nearbyLock.update(port.nearbyTrackingEnabled(),false,
                    YawAimingMath.distance(in.lat,in.lon,in.target.lat,in.target.lon),
                    directionTracker.calculateDominantDirection(),now);
            if(event!=null) diagnostic("nearby_lock",event);
        }
    }
    private static boolean positionFresh(Inputs in,long now) {
        return in.target!=null && in.target.time>0 && now>=in.target.time && now-in.target.time<=3000
                && in.aircraftTime>0 && now>=in.aircraftTime && now-in.aircraftTime<=1500
                && YawAimingMath.coordinateValid(in.lat,in.lon)
                && YawAimingMath.coordinateValid(in.target.lat,in.target.lon);
    }
    private final Port port;
    private final AtomicBoolean cancelled = new AtomicBoolean(true);
    // CAM3 v2.3: Urgent pause suppresses yaw without cancelling automatic recovery.
    private final AtomicBoolean pauseRequested = new AtomicBoolean();
    private volatile String pauseCause = "telemetry";
    private long recoverySince = -1;
    private boolean neutralSent, refreshControl;
    // CAM3 v2.3: Callback threads must latch loss during a previously activated pause, including UNKNOWN owner.
    private volatile boolean activated;
    public boolean hasActivated() { return activated; }
    private Authority recoveryAuthority;
    public void pauseImmediately(String cause) { pauseCause = cause; pauseRequested.set(true); }
    public long recoveryRemainingMs() { return recoverySince < 0 ? 2000 : Math.max(0, 2000 - (port.now() - recoverySince)); }
    public boolean maySendPauseNeutral() { return !cancelled.get() && state == State.PAUSED && commandEligible(); }
    private static boolean recoverable(String p) {
        return "telemetry".equals(p) || "connection".equals(p) || "gps".equals(p)
                || "stale_gps".equals(p) || "gps_quality".equals(p)
                || "heading".equals(p) || "aircraft_gps".equals(p) || "pilot_stick".equals(p) || "hover".equals(p);
    }
    private void pauseAiming(String cause) {
        movement.pause("pilot_stick".equals(cause),port.now());
        if (state != State.PAUSED) { neutralSent = false; recoverySince = -1; recoveryAuthority = null; lastTick = port.now(); }
        if (recoverySince >= 0) diagnostic("recovery_reset", "cause=" + cause);
        recoverySince = -1;
        if ("telemetry".equals(cause) || "connection".equals(cause)) { refreshControl = true; recoveryAuthority = null; }
        if (!cause.equals(reason) || state != State.PAUSED) diagnostic("pause", "cause=" + cause);
        reason = cause; rate = 0; setState(State.PAUSED);
        Inputs in = port.inputs();
        if (!neutralSent && commandEligible() && in.safeToNeutral && port.now() - in.aircraftTime <= 1500) {
            diagnostic("pause_neutral", "request yaw=0"); port.sendYaw(0); neutralSent = true;
        }
    }
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
    // CAM3 v2.3: A callback's pause request vetoes an already-calculated yaw command.
    public boolean maySendYaw() { return !cancelled.get() && !pauseRequested.get() && state == State.AIMING; }
    public boolean canStart() {
        Authority a = port.authority();
        return !enablePending && !claimed && !releasePending && state != State.STARTING
                // CAM3 v2.2: An unavailable initial owner permits a request, never implicit acquisition.
                && state != State.AIMING && (a.owner == Owner.RC || a.owner == Owner.UNKNOWN) && !a.enabled
                && port.inputs().validate(port.now()) == null;
    }
    public void startAiming() {
        if (!canStart()) return;
        // CAM3 v2.7: Start a new commitment while retaining recent foreground GPS votes.
        resetNearby("new_session", false);
        movement.start(port.movementSettings(),port.now());
        cancelled.set(false);
        // CAM3 v2.3: A new explicit session owns its recovery state.
        pauseRequested.set(false); refreshControl = false; recoveryAuthority = null; recoverySince = -1; activated = false;
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
                else if (cancelled.get() || (state != State.STARTING && state != State.PAUSED)) {
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
        // CAM3 v2.7: A fresh decision record each cycle, including cycles that issue no command.
        cycleId++; cycleAt=port.now(); cycleInputs=null; cycleAngle=Double.NaN;
        cycleMultiplier=1; cycleRequestedYaw=Double.NaN; cycleRequestedForward=0; cycleDecision="no_command";
        try {
            if (state == State.STOPPING || state == State.RELEASE_UNCONFIRMED) {
                settleRelease(); return;
            }
            // CAM3 v2.3: Paused sessions keep monitoring, but never run the yaw loop.
            if (state != State.STARTING && state != State.AIMING && state != State.PAUSED) return;
            if (cancelled.get()) { stopAiming("cancelled"); return; }
            if (port.controlLost()) { stopAiming("takeover"); return; }
            // CAM3 v2.3: A stalled recovery loop cannot count as continuous healthy observation.
            if (state == State.PAUSED) {
                if (port.now() < lastTick || port.now() - lastTick > 500) { stopAiming("loop_stall"); return; }
                lastTick = port.now();
            }
            Inputs in = port.inputs();
            cycleInputs=in; // CAM3 v2.7: Log the exact snapshot used by this decision.
            String problem = in.validate(port.now());
            if (problem != null && !recoverable(problem)) { stopAiming(problem); return; }
            Authority a = port.authority();
            // CAM3 v2.2: New contradictory state vetoes acquisition; old RC state is not a takeover.
            if (a.owner == Owner.MSDK) observedMsdk = true;
            if (a != beforeEnable && (a.owner == Owner.RC || a.owner == Owner.OTHER
                    || (observedMsdk && a.owner == Owner.UNKNOWN))) {
                stopAiming("takeover"); return;
            }
            // CAM3 v2.3: Control loss outranks bad inputs; pausing cannot extend acquisition forever.
            if (activated && (!a.enabled || !a.advanced)) { stopAiming("takeover"); return; }
            if (!activated && port.now() - started > 5000) {
                stopAiming(enableSucceeded ? "advanced_timeout" : "start_timeout"); return;
            }
            // CAM3 v2.3: An urgent transient event resets recovery even if polling already looks good.
            if (pauseRequested.getAndSet(false)) { pauseAiming(pauseCause); return; }
            if (problem != null) { pauseAiming(problem); return; }
            if (state == State.PAUSED) {
                if (enablePending) {
                    if (port.now() - started > 5000) stopAiming("start_timeout");
                    return;
                }
                if (!enableSucceeded) { stopAiming("enable_failed"); return; }
                if (!advancedRequested) {
                    advancedRequested = true; beforeAdvanced = a; port.advanced(); return;
                }
                // CAM3 v2.3: A pause during acquisition still waits for its first advanced confirmation.
                if (!commandEligible()) { reason = "control_wait"; return; }
                if (refreshControl) {
                    if (recoveryAuthority == null) {
                        recoveryAuthority = a;
                        diagnostic("recovery_control_request", "refresh advanced state; no enable/reacquire");
                        port.advanced(); reason = "control_wait"; return;
                    }
                    if (a == recoveryAuthority) { reason = "control_wait"; return; }
                    refreshControl = false;
                    diagnostic("recovery_control_confirmed", "sequence=" + a.sequence);
                }
                if (!commandEligible()) { reason = "control_wait"; return; }
                // CAM3 v2.3: If stale telemetry prevented neutral earlier, send it after confirmation.
                if (!neutralSent && in.safeToNeutral) {
                    diagnostic("pause_neutral", "recovered connection; request yaw=0");
                    port.sendYaw(0); neutralSent = true;
                }
                if (recoverySince < 0) { recoverySince = port.now(); diagnostic("recovery_timer", "valid for 2000ms required"); }
                reason = "recovering";
                if (port.now() - recoverySince < 2000) return;
                if (cancelled.get() || pauseRequested.get()) return;
                rate = 0; lastTick = port.now(); reason = "aiming";
                activated = true; setState(State.AIMING); diagnostic("resumed", "stable inputs; yaw restarts from zero");
                recoverySince = -1;
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
                // CAM3 v2.3: Remember activation so later loss of enabled/advanced cancels paused recovery.
                activated = true; reason = "aiming"; setState(State.AIMING); lastTick = port.now();
            }
            // CAM3 v2.2: The same eligibility rule applies at activation and every send.
            if (!commandEligible()) { stopAiming("takeover"); return; }
            long now = port.now();
            long elapsed = now - lastTick;
            if (elapsed < 0 || elapsed > 500) { stopAiming("loop_stall"); return; }
            // CAM3 v2.7: Raw coordinates, no v2.6 smoothing; lock chooses the directed route.
            double distance=YawAimingMath.distance(in.lat,in.lon,in.target.lat,in.target.lon);
            boolean nearby=port.nearbyTrackingEnabled();
            String lockEvent=nearbyLock.update(nearby,true,distance,
                    directionTracker.calculateDominantDirection(),now);
            if(lockEvent!=null) diagnostic("nearby_lock",lockEvent);
            cycleMultiplier=nearby && nearbyLock.direction()!=0 ? NearbyDirectionLock.multiplier(distance) : 1;
            // VT 2.9: Resolve movement state before selecting the sole yaw owner for this tick.
            String oldMovement=movement.phase.name()+":"+movement.reason;
            long oldGeneration=movement.centralGeneration;
            movement.update_state_machine(in,now,Math.max(0.001,elapsed/1000.0));
            if(movement.returning() || movement.approaching()) {
                double desiredHeading=movement.returning() ? movement.returnHeading : movement.approachHeading;
                cycleAngle=YawAimingMath.shortestHeadingError(desiredHeading,in.heading);
                rate=YawAimingMath.calculateYawRate(cycleAngle,rate,Math.max(0.001,elapsed/1000.0));
                cycleDecision=movement.returning() ? "return_alignment" : "approach_heading";
                cycleMultiplier=1;
            } else {
                if(distance==0) {
                    rate=0; cycleDecision="coincident_zero"; // undefined bearing, not a distance pause
                } else {
                    double bearing=YawAimingMath.bearingToTarget(in.lat,in.lon,in.target.lat,in.target.lon);
                    cycleAngle=YawAimingMath.directedError(bearing,in.heading,nearby ? nearbyLock.direction() : 0);
                    rate=nearby ? YawAimingMath.calculateNearbyYawRate(cycleAngle,rate,
                            Math.max(0.001,elapsed/1000.0),cycleMultiplier)
                            : YawAimingMath.calculateYawRate(cycleAngle,rate,Math.max(0.001,elapsed/1000.0));
                    cycleDecision=Math.abs(cycleAngle)<=3 ? "aligned_zero" : nearbyLock.direction()!=0 ? "locked_route" : "shortest_route";
                }
            }
            if(!oldMovement.equals(movement.phase.name()+":"+movement.reason)
                    || oldGeneration!=movement.centralGeneration || !movement.event.equals("none"))
                diagnostic("movement_transition",oldMovement+" -> "+movement.phase+":"+movement.reason
                        +" event="+movement.event+" centralGeneration="+movement.centralGeneration);
            cycleRequestedForward=movement.forward;
            cycleRequestedYaw=rate;
            // Fresh read immediately before sending; a pause must not revive a previously calculated command.
            Authority latest = port.authority();
            // CAM3 v2.3: Last-moment recoverable failures pause instead of cancelling the session.
            Inputs finalInputs=port.inputs();
            String finalProblem = finalInputs.validate(port.now());
            if (!cancelled.get() && (pauseRequested.get() || (finalProblem != null && recoverable(finalProblem)))) {
                pauseAiming(pauseRequested.get() ? pauseCause : finalProblem); return;
            }
            if (cancelled.get() || !commandEligible()
                    || port.now() - now > 200 || finalProblem != null) {
                stopAiming("inputs_changed"); return;
            }
            if(!movement.permits(finalInputs,port.now(),cycleRequestedForward)) {
                cycleRequestedForward=0; movement.forward=0;
            }
            port.sendMotion(rate,cycleRequestedForward);
            lastTick = now;
        // CAM3 v2.1: Record loop exceptions before the same fail-safe stop path.
        } catch (RuntimeException ex) {
            diagnostic("loop_exception", ex.getClass().getSimpleName()); stopAiming("sdk_error");
        }
    }
    public void stopAiming(String why) {
        // CAM3 v2.3: Explicit/aircraft stops permanently cancel any pending resume timer.
        if (state == State.PAUSED) diagnostic("recovery_cancelled", "reason=" + why);
        recoverySince = -1; pauseRequested.set(false);
        cancelled.set(true); rate = 0; reason = why;
        movement.cancel();
        resetNearby("stop_"+why, false); // CAM3 v2.7: Never carry a commitment into a new session.
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
