package dji.v5.ux.sample.showcase.defaultlayout.aiming;

/** Independent timed retreat configuration, fixed for an explicit VT session. */
public final class RetreatSettings {
    // Separate hard envelope from the approach's cruise/arrival profile.
    public static final double MAX_SPEED=3.0;
    public final boolean enabled;
    public final double minimumDistance, speed;
    public final long durationMs, cooldownMs;
    public RetreatSettings(boolean enabled,double minimumDistance,long durationMs,double speed,long cooldownMs) {
        if(!Double.isFinite(minimumDistance)||minimumDistance<1||minimumDistance>200
                ||durationMs<1000||durationMs>60000||!Double.isFinite(speed)||speed<0.1||speed>MAX_SPEED
                ||cooldownMs<0||cooldownMs>60000)
            throw new IllegalArgumentException("Retreat distance 1-200 m; duration 1-60 s; speed 0.1-3 m/s; cooldown 0-60 s");
        this.enabled=enabled;this.minimumDistance=minimumDistance;this.durationMs=durationMs;
        this.speed=speed;this.cooldownMs=cooldownMs;
    }
    public static RetreatSettings defaults() { return new RetreatSettings(true,25,10000,3,5000); }
    public static RetreatSettings disabled() { return new RetreatSettings(false,25,10000,3,5000); }
}
