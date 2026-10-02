package dji.v5.ux.sample.showcase.defaultlayout.aiming;

/** Independent timed retreat configuration, fixed for an explicit VT session. */
public final class RetreatSettings {
    // Separate hard envelope from the approach's cruise/arrival profile.
    // VT 3.6: 18 km/h maximum/default; each retreat remains a fixed-speed command.
    public static final double MAX_SPEED=5.0;
    public static final double DEFAULT_DISTANCE=23;
    public static final long DEFAULT_DURATION_MS=3000, DEFAULT_COOLDOWN_MS=5000;
    public final boolean enabled;
    public final double minimumDistance, speed;
    public final long durationMs, cooldownMs;
    public RetreatSettings(boolean enabled,double minimumDistance,long durationMs,double speed,long cooldownMs) {
        if(!Double.isFinite(minimumDistance)||minimumDistance<1||minimumDistance>200
                ||durationMs<1000||durationMs>60000||!Double.isFinite(speed)||speed<0.1||speed>MAX_SPEED
                ||cooldownMs<0||cooldownMs>60000)
            throw new IllegalArgumentException("Retreat distance 1-200 m; duration 1-60 s; speed 0.1-5 m/s; cooldown 0-60 s");
        this.enabled=enabled;this.minimumDistance=minimumDistance;this.durationMs=durationMs;
        this.speed=speed;this.cooldownMs=cooldownMs;
    }
    public static RetreatSettings defaults() { return new RetreatSettings(true,DEFAULT_DISTANCE,DEFAULT_DURATION_MS,MAX_SPEED,DEFAULT_COOLDOWN_MS); }
    public static RetreatSettings disabled() { return new RetreatSettings(false,DEFAULT_DISTANCE,DEFAULT_DURATION_MS,MAX_SPEED,DEFAULT_COOLDOWN_MS); }
}
