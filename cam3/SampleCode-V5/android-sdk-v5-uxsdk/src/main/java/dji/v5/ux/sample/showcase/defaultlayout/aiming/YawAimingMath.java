package dji.v5.ux.sample.showcase.defaultlayout.aiming;

/** CAM3 v2.0: Pure yaw calculations. Limits are initial engineering values, not flight-tested bounds. */
public final class YawAimingMath {
    // CAM3 v2.3: User-selected minimum shared by eligibility, UI and logs.
    public static final double MIN_AIMING_DISTANCE_METERS = 5.0;
    public static final double MAX_RATE = 8.0; // degrees/second
    public static final double MAX_ACCELERATION = 4.0; // degrees/second squared
    private YawAimingMath() { }

    public static boolean coordinateValid(double lat, double lon) {
        return Double.isFinite(lat) && Double.isFinite(lon) && Math.abs(lat) < 89.0
                && Math.abs(lon) <= 180.0;
    }

    public static double bearingToTarget(double lat, double lon, double targetLat, double targetLon) {
        double a = Math.toRadians(lat), b = Math.toRadians(targetLat);
        double delta = Math.toRadians(targetLon - lon);
        return (Math.toDegrees(Math.atan2(Math.sin(delta) * Math.cos(b),
                Math.cos(a) * Math.sin(b) - Math.sin(a) * Math.cos(b) * Math.cos(delta))) + 360) % 360;
    }

    public static double distance(double lat, double lon, double targetLat, double targetLon) {
        double a = Math.sin(Math.toRadians(targetLat - lat) / 2);
        double b = Math.sin(Math.toRadians(targetLon - lon) / 2);
        double h = a * a + Math.cos(Math.toRadians(lat)) * Math.cos(Math.toRadians(targetLat)) * b * b;
        return 6371000 * 2 * Math.asin(Math.sqrt(Math.max(0, Math.min(1, h))));
    }

    public static double shortestHeadingError(double target, double heading) {
        return ((target - heading) % 360 + 540) % 360 - 180;
    }

    public static boolean isBearingUsable(double separation, double phoneAccuracy) {
        // CAM3 v2.4: Accuracy is informational for both target sources.
        return Double.isFinite(separation) && separation >= MIN_AIMING_DISTANCE_METERS;
    }

    public static double calculateYawRate(double error, double previous, double seconds) {
        if (!Double.isFinite(error) || !Double.isFinite(previous) || !Double.isFinite(seconds)
                || seconds <= 0 || seconds > 0.5) throw new IllegalArgumentException("Invalid yaw input");
        // A stop or a sign change never prolongs rotation in the wrong direction for smoothness.
        if (Math.abs(error) <= 3 || error * previous < 0) return 0;
        double desired = Math.copySign(Math.min(MAX_RATE, (Math.abs(error) - 3) * 0.5), error);
        double step = MAX_ACCELERATION * seconds;
        return previous + Math.max(-step, Math.min(step, desired - previous));
    }
}
