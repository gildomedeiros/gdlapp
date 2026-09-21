package dji.v5.ux.sample.showcase.defaultlayout.aiming;

/** Immutable per-session configuration. UI edits apply only while automatic control is stopped. */
public final class ComeToMeSettings {
    public static final double MAX_SPEED = 1.0, ACCELERATION = 0.25;
    public static final double FILM_TOLERANCE = 2, ARRIVAL_RADIUS = 3, MAX_EXCURSION = 200;
    public static final long QUALIFY_MS = 60000, ATTEMPT_MS = 300000;
    public final boolean enabled;
    public final double filmingDistance, lineupWidth, rideStartKmh, rideEndKmh;
    public final long rideEndMs, inactivityMs;

    public ComeToMeSettings(boolean enabled, double filming, double width, double start,
                            double end, long endMs, long inactivityMs) {
        if (!inRange(filming,10,200) || !inRange(width,20,200)
                || !inRange(start,1,100) || !inRange(end,0.1,99) || end>=start
                || endMs<1000 || endMs>300000 || inactivityMs<60000 || inactivityMs>7200000)
            throw new IllegalArgumentException("Filming distance 10–200 m; lineup width 20–200 m; ride start 1–100 km/h; end below start; end 1–300 s; no-ride 1–120 min");
        this.enabled=enabled; filmingDistance=filming; lineupWidth=width;
        rideStartKmh=start; rideEndKmh=end; rideEndMs=endMs; this.inactivityMs=inactivityMs;
    }
    public static ComeToMeSettings defaults() { return new ComeToMeSettings(true,70,20,18,8,30000,900000); }
    private static boolean inRange(double v,double lo,double hi) { return Double.isFinite(v)&&v>=lo&&v<=hi; }
}
