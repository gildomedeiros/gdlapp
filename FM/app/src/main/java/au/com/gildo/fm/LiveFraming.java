package au.com.gildo.fm;

import java.util.Locale;

/** Estimates horizontal framing during labelled simulator takeoffs from existing pan encoder samples. */
final class LiveFraming {
    static final int DEFAULT_SHOT_WIDTH_M = 40;

    final double shotWidthM;
    private final GoTo.Point mount;
    private final double maxAgeMs;
    private final long runStartedAt;
    private GoTo.Point surfer;
    private long takeoffStartedAt = -1, fixAt, lastSampleAt = -1;
    private long takeoffMs, observedMs, outsideMs;
    private boolean sawTakeoff, lastOutside;

    LiveFraming(GoTo.Point mount, double shotWidthM, double maxAgeMs, long runStartedAt) {
        this.mount = mount;
        this.shotWidthM = shotWidthM;
        this.maxAgeMs = maxAgeMs;
        this.runStartedAt = runStartedAt;
    }

    void fix(SurferModes.Mode mode, GoTo.Point point, long receivedAt) {
        if (mode != SurferModes.Mode.TAKEOFF) {
            endTakeoff(receivedAt);
            return;
        }
        if (takeoffStartedAt < 0) {
            takeoffStartedAt = Math.max(runStartedAt, receivedAt);
            sawTakeoff = true;
        }
        surfer = point;
        fixAt = receivedAt;
    }

    void sample(double cameraBearing, long at) {
        if (surfer == null || at < takeoffStartedAt) return;
        double rangeM = Geo.distance(mount.lat(), mount.lon(), surfer.lat(), surfer.lon());
        if (rangeM < .1 || at - fixAt > maxAgeMs) {
            lastSampleAt = -1;
            return;
        }
        double bearing = Geo.bearing(mount.lat(), mount.lon(), surfer.lat(), surfer.lon());
        double halfFrameDegrees = Math.toDegrees(Math.atan2(shotWidthM / 2, rangeM));
        long interval = at - lastSampleAt;
        if (lastSampleAt >= 0 && interval > 0 && interval <= maxAgeMs) {
            observedMs += interval;
            if (lastOutside) outsideMs += interval;
        }
        lastSampleAt = at;
        lastOutside = Math.abs(Geo.delta(bearing, cameraBearing)) > halfFrameDegrees;
    }

    void endTakeoff(long at) {
        if (takeoffStartedAt < 0) return;
        takeoffMs += Math.max(0, at - takeoffStartedAt);
        takeoffStartedAt = -1;
        lastSampleAt = -1;
        surfer = null;
    }

    boolean sawTakeoff() { return sawTakeoff; }
    long takeoffMs() { return takeoffMs; }
    long observedMs() { return observedMs; }
    long outsideMs() { return outsideMs; }

    String summary() {
        String time = observedMs == 0 ? "unavailable" : String.format(Locale.ROOT, "%.2f s", outsideMs / 1000.0);
        return String.format(Locale.ROOT, "Simulated takeoff · Estimated out of frame %s · %s m shot",
            time, LiveGpsSource.formatMetres(shotWidthM))
            + String.format(Locale.ROOT, "\nEncoder coverage %.2f / %.2f s", observedMs / 1000.0, takeoffMs / 1000.0);
    }
}
