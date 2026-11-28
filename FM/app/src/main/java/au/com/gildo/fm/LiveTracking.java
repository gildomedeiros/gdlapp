package au.com.gildo.fm;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

final class LiveTracking {
    static final int DEFAULT_TILT_SPEED = 64;
    static final long POLL_MS = 100;
    // Keep recent reply ports distinct without exhausting the pool in long sessions.
    static final int RETAINED_REPLY_PORTS = 2048;

    record Targets(long packet, long revision, int pan, Integer tilt, Integer panDirection, double surferRangeM) {
        Targets {
            if (panDirection != null && panDirection != 1 && panDirection != -1)
                throw new IllegalArgumentException("A published GP target requires an encoder direction");
        }
    }

    private final GoTo mount;
    private final int panCpr;
    private final GoTo.PanTiming panTiming;
    private final boolean tiltDisabled;
    private double panRate = GoTo.MAX_HS_SIDEREAL;
    private String acknowledgedStop;
    private final Integer tiltPeriod;
    private final BooleanSupplier active;
    private final Consumer<String> progress;
    private Targets target, evaluatedAtRest;
    private GoTo.PanSample lastPanSample;
    private long panPosition, targetPosition;
    private int activeDirection;
    private boolean slewing, tiltMayBeMoving;
    private boolean startupPending = true, nearMount;
    private Targets startupTarget;
    private double startupValidUntil;
    private String panState = "Idle";
    private long lastPanStartReply = -1;

    LiveTracking(GoTo mount, int panCpr, GoTo.PanTiming panTiming, Integer tiltPeriod, boolean tiltDisabled,
                 BooleanSupplier active, Consumer<String> progress) {
        this.mount = mount;
        this.panCpr = panCpr;
        this.panTiming = panTiming;
        this.tiltDisabled = tiltDisabled;
        this.tiltPeriod = tiltPeriod;
        this.active = active;
        this.progress = progress;
    }

    static double tiltSpeed(double value) {
        if (!Double.isFinite(value) || value <= 0 || value > GoTo.MIN_HS_SIDEREAL)
            throw new IllegalArgumentException("Live slow tilt speed must be greater than 0× and at most 128×.");
        return value;
    }

    GoTo.PanSample lastPanSample() { return lastPanSample; }
    long lastPanStartReply() { return lastPanStartReply; }

    String panState() { return panState; }

    boolean needsStartupTarget() { return startupPending && startupTarget == null; }

    boolean startupPending() { return startupPending; }

    void discardUnstartedAim() {
        if (!slewing) startupTarget = null;
    }

    void offerStartupTarget(long packet, int pan, double rangeM, double validUntil) {
        Targets candidate = new Targets(packet, 0, pan, null, null, rangeM);
        if (!needsStartupTarget()) return;
        startupValidUntil = validUntil;
        startupTarget = candidate;
        mount.trace.event("live_startup_aim_target", "packet", candidate.packet(), "targetCount", candidate.pan());
    }

    void track(Targets fresh, boolean insideMinimum) {
        if (!active.getAsBoolean()) return;
        if (insideMinimum) {
            boolean entering = !nearMount;
            if (entering) {
                nearMount = true;
                discardUnstartedAim();
                evaluatedAtRest = null;
                mount.trace.event("live_range_hold", "slewing", slewing);
            }
            try {
                if (slewing) {
                    stopPan("Surfer inside minimum distance");
                    if (startupPending) finishStartup("Surfer inside minimum distance");
                }
            } catch (IOException e) { mount.trace.event("live_range_stop_failed", "axis", 1, "reason", e.toString()); }
            try {
                if (tiltMayBeMoving) {
                    exchange(() -> mount.stopAxis(2, sent -> {}));
                    tiltMayBeMoving = false;
                }
            } catch (IOException e) { mount.trace.event("live_range_stop_failed", "axis", 2, "reason", e.toString()); }
            if (entering) progress.accept("Live Tracking · Surfer too close · waiting for an in-range fix");
            return;
        }
        if (startupPending && !slewing && GoTo.now() > startupValidUntil) startupTarget = null;
        if (nearMount) {
            Targets resume = startupPending ? startupTarget : fresh;
            if (resume == null) return;
            nearMount = false;
            mount.trace.event("live_range_resume", "packet", resume.packet());
            progress.accept("Live Tracking · Surfer back in range");
        }
        if (startupPending) {
            if (startupTarget != null) advance(startupTarget, true);
        } else {
            tick(fresh);
        }
    }

    void tick(Targets fresh) { advance(fresh, false); }

    private void advance(Targets fresh, boolean startup) {
        if (fresh != null && fresh != target) {
            if (lastPanSample != null) {
                targetPosition += AimAtSurfer.shortestStep(fresh.pan(), target.pan(), panCpr);
            }
            target = fresh;
        }
        if (!slewing && (fresh == null || fresh == evaluatedAtRest)) return;

        try {
            if (!startup && slewing && fresh != null && fresh.panDirection() != activeDirection) {
                mount.trace.event("live_gp_reversal", "packet", fresh.packet(), "positionRevision", fresh.revision(),
                    "activeEncoderDirection", activeDirection, "gpEncoderDirection", fresh.panDirection());
                stopPan("GP reversal");
                return;
            }
            if (!slewing) {
                if (exchange(mount::panMoving)) { panState = "Moving"; return; }
                panState = "Stopped";
            }

            GoTo.PanSample sample;
            try {
                sample = exchange(mount::readPan);
            } catch (IOException failure) {
                if (active.getAsBoolean()) {
                    mount.trace.event("live_pan_feedback_failed", "packet", target.packet(), "positionRevision", target.revision(),
                        "slewing", slewing, "reason", failure.getMessage());
                }
                return;
            }
            int count = sample.count();
            if (lastPanSample == null) {
                panPosition = count;
                targetPosition = panPosition + AimAtSurfer.shortestStep(target.pan(), count, panCpr);
            } else {
                panPosition += GoTo.delta(count, lastPanSample.count());
            }
            lastPanSample = sample;
            long remaining = targetPosition - panPosition;
            int direction;
            if (slewing) direction = activeDirection;
            else if (startup) {
                remaining = AimAtSurfer.shortestStep(target.pan(), count, panCpr);
                targetPosition = panPosition + remaining;
                direction = Long.signum(remaining);
            } else direction = target.panDirection();
            long gap = remaining * direction;
            mount.trace.event("live_pan_sample", "packet", target.packet(), "count", count,
                "targetCount", target.pan(), "sampleMs", sample.timeMs(), "direction", direction,
                "gapCounts", gap, "slewing", slewing);

            if (!slewing && !startup) evaluatedAtRest = fresh;
            if (startup && !slewing && remaining == 0) {
                finishStartup("Already on target");
                evaluatedAtRest = fresh;
                return;
            }
            if (gap > 0) {
                if (!slewing) {
                    try {
                        startPan(direction);
                    } catch (IOException failure) {
                        if (startup && !slewing && active.getAsBoolean()) {
                            evaluatedAtRest = target;
                            finishStartup("Start failed: " + failure.getMessage());
                        }
                        throw failure;
                    }
                }
            } else if (slewing) {
                mount.trace.event("live_pan_crossing", "packet", target.packet(), "positionRevision", target.revision(),
                    "crossingMs", sample.timeMs(), "gapCounts", gap);
                stopPan(startup ? "Startup target crossed" : "Target crossed");
                evaluatedAtRest = target;
                if (startup) { finishStartup("Target crossed"); return; }
                if (!tiltDisabled && fresh != null) dispatchTilt(fresh);
            }
        } catch (IOException failure) {
            if (active.getAsBoolean()) {
                mount.trace.event("live_command_failed", "packet", target.packet(), "reason", failure.getMessage());
                progress.accept("Live Tracking · " + failure.getMessage());
            }
        }
    }

    private void finishStartup(String reason) {
        startupPending = false;
        startupTarget = null;
        mount.trace.event("live_startup_aim_complete", "packet", target.packet(), "reason", reason);
    }

    private void startPan(int direction) throws IOException {
        // tick has already confirmed a stopped motor and an eligible target ahead.
        double rate = acknowledgedStop == null ? panRate : AimAtSurfer.siderealForRange(target.surferRangeM());
        int panPeriod = mount.highSpeedPanPeriod(panTiming, rate * panCpr / GoTo.SIDEREAL_DAY_SECONDS);
        mount.trace.event("live_pan_speed_selected", "packet", target.packet(), "positionRevision", target.revision(),
            "source", acknowledgedStop != null ? "surfer-to-mount range" :
                (lastPanStartReply < 0 ? "initial maximum" : "retained speed"),
            "stopReason", acknowledgedStop, "rangeM", target.surferRangeM(),
            "previousRateSidereal", panRate, "rateSidereal", rate, "period", panPeriod);
        exchange(() -> mount.setHighSpeedMode(1, direction));
        exchange(() -> mount.setStepPeriod(1, panPeriod));
        try {
            lastPanStartReply = exchange(() -> mount.startAxis(1, sent -> {
                panRate = rate;
                acknowledgedStop = null;
                slewing = true;
                panState = "Panning";
                activeDirection = direction;
                mount.trace.event("live_pan_start_sent", "packet", target.packet(), "sentMs", sent,
                    "direction", direction, "period", panPeriod);
            }));
        } catch (IOException failure) {
            if (slewing && active.getAsBoolean()) {
                try { stopPan("Pan Start reply unusable"); }
                catch (IOException stopFailure) { failure.addSuppressed(stopFailure); }
            }
            throw failure;
        }
        progress.accept("Live Tracking · Pan slewing");
    }

    private void stopPan(String reason) throws IOException {
        panState = "Stop requested";
        long ack = exchange(() -> mount.stopAxis(1, sent ->
            mount.trace.event("live_pan_stop_sent", "packet", target.packet(), "positionRevision", target.revision(),
                "sentMs", sent, "reason", reason)));
        slewing = false;
        acknowledgedStop = reason;
        mount.trace.event("live_pan_stop_ack", "packet", target.packet(), "positionRevision", target.revision(),
            "replyMs", ack, "reason", reason);
        progress.accept("Live Tracking · Pan Stop accepted");
    }

    private void dispatchTilt(Targets fresh) throws IOException {
        if (tiltPeriod == null) {
            mount.trace.event("live_tilt_deferred", "packet", fresh.packet(), "reason", "Tilt timing unavailable");
            return;
        }
        if (exchange(() -> mount.moving(2))) {
            mount.trace.event("live_tilt_deferred", "packet", fresh.packet(), "reason", "Tilt still moving");
            return;
        }
        tiltMayBeMoving = false;
        int baseline = exchange(() -> mount.readEncoder(2)).count();
        int travel = GoTo.delta(fresh.tilt(), baseline);
        mount.trace.event("live_tilt_plan", "packet", fresh.packet(), "positionRevision", fresh.revision(), "baseline", baseline,
            "targetCount", fresh.tilt(), "stepPeriod", tiltPeriod, "travelCounts", travel);
        if (travel == 0) return;

        exchange(() -> mount.setPositionMode(2, "2", travel));
        exchange(() -> mount.setStepPeriod(2, tiltPeriod));
        exchange(() -> mount.setAbsoluteTarget(2, fresh.tilt()));
        long ack = exchange(() -> mount.startAxis(2, sent -> {
            tiltMayBeMoving = true;
            mount.trace.event("live_tilt_start_sent", "packet", fresh.packet(), "sentMs", sent);
        }));
        mount.trace.event("live_tilt_start_ack", "packet", fresh.packet(), "replyMs", ack);
        progress.accept("Live Tracking · Tilt Start accepted");
    }

    static boolean awaitFullStop(GoTo mount, BooleanSupplier running) throws InterruptedException {
        boolean[] stopped = new boolean[2];
        for (int axis = 1; axis <= 2; axis++) requestFinalStop(mount, axis);
        while (running.getAsBoolean()) {
            for (int axis = 1; axis <= 2; axis++) {
                if (stopped[axis - 1]) continue;
                try {
                    stopped[axis - 1] = !mount.moving(axis);
                    mount.trace.event("live_full_stop_status", "axis", axis, "stopped", stopped[axis - 1]);
                } catch (IOException failure) {
                    mount.trace.event("live_full_stop_status_unknown", "axis", axis, "reason", failure.toString());
                }
                if (!stopped[axis - 1]) requestFinalStop(mount, axis);
            }
            if (stopped[0] && stopped[1]) return true;
            Thread.sleep(500);
        }
        return false;
    }

    private static void requestFinalStop(GoTo mount, int axis) {
        try {
            long ack = mount.stopAxis(axis, sent -> mount.trace.event("live_final_stop_sent", "axis", axis, "sentMs", sent));
            mount.trace.event("live_final_stop_ack", "axis", axis, "replyMs", ack);
        } catch (IOException failure) {
            mount.trace.event("live_final_stop_unconfirmed", "axis", axis, "reason", failure.toString());
        }
    }

    private <T> T exchange(AimAtSurfer.Operation<T> operation) throws IOException {
        if (!active.getAsBoolean()) throw new InterruptedIOException("Live Tracking stop requested");
        return operation.run();
    }
}

