package au.com.gildo.fm;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

final class LiveTracking {
    static final int DEFAULT_PAN_SPEED = GoTo.MAX_HS_SIDEREAL;
    static final int DEFAULT_TILT_SPEED = 64;
    static final long POLL_MS = 100;
    // Keep recent reply ports distinct without exhausting the pool in long sessions.
    static final int RETAINED_REPLY_PORTS = 2048;

    record Targets(long packet, long revision, int pan, int tilt, int panDirection) {
        Targets {
            if (panDirection != 1 && panDirection != -1)
                throw new IllegalArgumentException("A published GP target requires an encoder direction");
        }
    }

    private final GoTo mount;
    private final int panCpr, panPeriod;
    private final Integer tiltPeriod;
    private final BooleanSupplier active;
    private final Consumer<String> progress;
    private Targets target, evaluatedAtRest;
    private GoTo.PanSample lastPanSample;
    private long panPosition, targetPosition;
    private int activeDirection;
    private boolean slewing;
    private long lastPanStartReply = -1;

    LiveTracking(GoTo mount, int panCpr, int panPeriod, Integer tiltPeriod,
                 BooleanSupplier active, Consumer<String> progress) {
        this.mount = mount;
        this.panCpr = panCpr;
        this.panPeriod = panPeriod;
        this.tiltPeriod = tiltPeriod;
        this.active = active;
        this.progress = progress;
    }

    static double panSpeed(double value) {
        if (!Double.isFinite(value) || value < GoTo.MIN_HS_SIDEREAL || value > GoTo.MAX_HS_SIDEREAL)
            throw new IllegalArgumentException("Live HS pan speed must be between 128× and 800×.");
        return value;
    }

    static double tiltSpeed(double value) {
        if (!Double.isFinite(value) || value <= 0 || value > GoTo.MIN_HS_SIDEREAL)
            throw new IllegalArgumentException("Live slow tilt speed must be greater than 0× and at most 128×.");
        return value;
    }

    GoTo.PanSample lastPanSample() { return lastPanSample; }
    long lastPanStartReply() { return lastPanStartReply; }

    void tick(Targets fresh) {
        if (fresh != null && fresh != target) {
            if (lastPanSample != null) {
                targetPosition += AimAtSurfer.shortestStep(fresh.pan(), target.pan(), panCpr);
            }
            target = fresh;
        }
        if (!slewing && (fresh == null || fresh == evaluatedAtRest)) return;

        try {
            if (slewing && fresh != null && fresh.panDirection() != activeDirection) {
                mount.trace.event("live_gp_reversal", "packet", fresh.packet(), "positionRevision", fresh.revision(),
                    "activeEncoderDirection", activeDirection, "gpEncoderDirection", fresh.panDirection());
                stopPan("GP reversal");
                return;
            }
            if (!slewing && exchange(mount::panMoving)) return;

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
            int direction = slewing ? activeDirection : target.panDirection();
            long gap = remaining * direction;
            mount.trace.event("live_pan_sample", "packet", target.packet(), "count", count,
                "targetCount", target.pan(), "sampleMs", sample.timeMs(), "direction", direction,
                "gapCounts", gap, "slewing", slewing);

            if (!slewing) evaluatedAtRest = fresh;
            if (gap > 0) {
                if (!slewing) startPan(direction);
            } else if (slewing) {
                mount.trace.event("live_pan_crossing", "packet", target.packet(), "positionRevision", target.revision(),
                    "crossingMs", sample.timeMs(), "gapCounts", gap);
                evaluatedAtRest = target;
                stopPan("Target crossed");
                if (fresh != null) dispatchTilt(fresh);
            }
        } catch (IOException failure) {
            if (active.getAsBoolean()) {
                mount.trace.event("live_command_failed", "packet", target.packet(), "reason", failure.getMessage());
                progress.accept("Live Tracking · " + failure.getMessage());
            }
        }
    }

    private void startPan(int direction) throws IOException {
        exchange(() -> mount.setHighSpeedMode(1, direction));
        exchange(() -> mount.setStepPeriod(1, panPeriod));
        try {
            lastPanStartReply = exchange(() -> mount.startAxis(1, sent -> {
                slewing = true;
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
        slewing = false;
        long ack = exchange(() -> mount.stopAxis(1, sent ->
            mount.trace.event("live_pan_stop_sent", "packet", target.packet(), "positionRevision", target.revision(),
                "sentMs", sent, "reason", reason)));
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
        int baseline = exchange(() -> mount.readEncoder(2)).count();
        int travel = GoTo.delta(fresh.tilt(), baseline);
        mount.trace.event("live_tilt_plan", "packet", fresh.packet(), "positionRevision", fresh.revision(), "baseline", baseline,
            "targetCount", fresh.tilt(), "stepPeriod", tiltPeriod, "travelCounts", travel);
        if (travel == 0) return;

        exchange(() -> mount.setPositionMode(2, "2", travel));
        exchange(() -> mount.setStepPeriod(2, tiltPeriod));
        exchange(() -> mount.setAbsoluteTarget(2, fresh.tilt()));
        long ack = exchange(() -> mount.startAxis(2, sent ->
            mount.trace.event("live_tilt_start_sent", "packet", fresh.packet(), "sentMs", sent)));
        mount.trace.event("live_tilt_start_ack", "packet", fresh.packet(), "replyMs", ack);
        progress.accept("Live Tracking · Tilt Start accepted");
    }

    private <T> T exchange(AimAtSurfer.Operation<T> operation) throws IOException {
        if (!active.getAsBoolean()) throw new InterruptedIOException("Live Tracking stop requested");
        return operation.run();
    }
}

