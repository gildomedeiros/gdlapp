package dji.v5.ux.sample.showcase.defaultlayout.aiming;

/** CAM3 v2.7: One direction per nearby pass, refreshed every five minutes. No flight APIs. */
public final class NearbyDirectionLock {
    public static final double RANGE_METERS=20, FULL_BOOST_METERS=30, MAX_MULTIPLIER=1.5;
    public static final long MAX_AGE_MS=300000;
    private int direction;
    private long capturedAt=-1;
    public int direction() { return direction; }
    public long age(long now) { return direction==0 ? -1 : Math.max(0,now-capturedAt); }
    public String clear(String reason) {
        if(direction==0) return null;
        int old=direction; direction=0; capturedAt=-1;
        return "released reason="+reason+" previous="+DominantDirectionTracker.name(old);
    }
    public String update(boolean enabled, boolean mayCapture, double distance, int dominant, long now) {
        if (!enabled) return clear("disabled");
        if (!Double.isFinite(distance)) return null; // missing data retains commitment; session still pauses
        if (distance>=RANGE_METERS) return clear("outside_20m");
        if (distance<0 || (direction==0 && !mayCapture)) return null;
        if(direction==0 || now<capturedAt || now-capturedAt>=MAX_AGE_MS) {
            String action=direction==0 ? "captured" : "refreshed";
            direction=dominant<0 ? -1 : 1; capturedAt=now;
            return action+" reason="+(action.equals("captured") ? "inside_20m" : "five_minute_expiry")
                    +" direction="+DominantDirectionTracker.name(direction);
        }
        return null;
    }
    public static double multiplier(double distance) {
        if (!Double.isFinite(distance)) return 1;

        return 1 + (MAX_MULTIPLIER - 1) * Math.max(0, Math.min(1,
                (60.0 - distance) / (60.0 - FULL_BOOST_METERS)));
    }
}
