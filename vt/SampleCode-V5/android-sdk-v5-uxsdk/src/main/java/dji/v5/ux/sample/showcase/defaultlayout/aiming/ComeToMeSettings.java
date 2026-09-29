package dji.v5.ux.sample.showcase.defaultlayout.aiming;

/** Immutable per-session configuration. UI edits apply only while automatic control is stopped. */
public final class ComeToMeSettings {
    // VT 3.3: Faster cruise in both directions; preserve acceleration and arrival slope.
    public static final double MAX_SPEED = 3.0, ACCELERATION = 0.25;
    public static final double ARRIVAL_SPEED_PER_METRE = 0.2;
    public static final double SLOWDOWN_DISTANCE = MAX_SPEED / ARRIVAL_SPEED_PER_METRE;
    public static final double FILM_TOLERANCE = 2, ARRIVAL_RADIUS = 3, MAX_EXCURSION = 250;
    // VT 3.2: Finish saved travel within 1 m; keep an inward margin at the excursion cap.
    public static final double COMPLETION_TOLERANCE = 1, EXCURSION_STOP = MAX_EXCURSION - COMPLETION_TOLERANCE;
    // VT 3.2: Keep band bookkeeping, but impose no qualification delay on a moving surfer.
    public static final long QUALIFY_MS = 0, ATTEMPT_MS = 300000;
    public final boolean enabled;
    public final double filmingDistance, lineupWidth, rideStartKmh, rideEndKmh, reapproachMargin;
    public final long rideEndMs, inactivityMs;
    public final long rideDurationMs;
    public final double closeRangePitchDeg;

    public ComeToMeSettings(boolean enabled, double filming, double width, double start,
                            double end, long endMs, long inactivityMs) {
        this(enabled,filming,width,start,end,endMs,inactivityMs,15);
    }
    /** VT 3.1: Margin is a start threshold, never added to the saved travel destination. */
    public ComeToMeSettings(boolean enabled, double filming, double width, double start,
                            double end, long endMs, long inactivityMs, double margin) {
        this(enabled,filming,width,start,end,endMs,inactivityMs,margin,90000,-35);
    }
    public ComeToMeSettings(boolean enabled,double filming,double width,double start,
            double end,long endMs,long inactivityMs,double margin,long durationMs,double closePitchDeg) {
        if(durationMs<1000 || durationMs>600000 || !inRange(closePitchDeg,-90,0))
            throw new IllegalArgumentException("Ride duration 1–600 s; close-range pitch -90 to 0 degrees");
        rideDurationMs=durationMs; closeRangePitchDeg=closePitchDeg;
        if (!inRange(margin,0,200)) throw new IllegalArgumentException("Re-approach margin 0–200 m");
        reapproachMargin=margin;
        if (!inRange(filming,10,200) || !inRange(width,20,200)
                || !inRange(start,1,100) || !inRange(end,0.1,99) || end>=start
                || endMs<1000 || endMs>300000 || inactivityMs<60000 || inactivityMs>7200000)
            throw new IllegalArgumentException("Filming distance 10–200 m; lineup width 20–200 m; ride start 1–100 km/h; end below start; end 1–300 s; no-ride 1–120 min");
        this.enabled=enabled; filmingDistance=filming; lineupWidth=width;
        rideStartKmh=start; rideEndKmh=end; rideEndMs=endMs; this.inactivityMs=inactivityMs;
    }
    public static ComeToMeSettings defaults() { return new ComeToMeSettings(true,70,50,18,8,30000,900000); }
    private static boolean inRange(double v,double lo,double hi) { return Double.isFinite(v)&&v>=lo&&v<=hi; }
}
