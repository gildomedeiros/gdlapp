package dji.v5.ux.sample.showcase.defaultlayout.aiming;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;

/** CAM3 v2.0: Independent GPS subscription; map listeners and movement filters are untouched. */
public final class PhoneTargetLocationSource implements LocationListener {
    private final Context context;
    private final LocationManager manager;
    private volatile AimingSession.Fix latest;
    private boolean subscribed;
    // CAM3 v2.5: Record each delivered fix separately from repeated control-loop snapshots.
    private final FullSessionLog fullLog;
    private final java.util.function.BiConsumer<String, String> diagnostic;
    public PhoneTargetLocationSource(Context context, FullSessionLog fullLog, java.util.function.BiConsumer<String, String> diagnostic) {
        this.fullLog = fullLog; this.diagnostic = diagnostic;
        this.context = context.getApplicationContext();
        manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
    }
    public synchronized void start() {
        if (subscribed || !usable()) return;
        try {
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500, 0, this, Looper.getMainLooper());
            subscribed = true;
        } catch (RuntimeException ex) { latest = null; }
    }
    private boolean usable() {
        try {
            return manager != null && context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED && manager.isProviderEnabled(LocationManager.GPS_PROVIDER);
        } catch (RuntimeException ex) { return false; }
    }
    public AimingSession.Fix getLatestFix() { return usable() ? latest : null; }
    public synchronized void stop() {
        latest = null;
        if (subscribed) {
            subscribed = false;
            try { manager.removeUpdates(this); } catch (RuntimeException ignored) { }
        }
    }
    @Override public synchronized void onLocationChanged(Location location) {
        if (!subscribed) return;
        boolean mock = location.isFromMockProvider();
        long time = location.getElapsedRealtimeNanos() / 1000000;
        boolean newer = latest == null || time > latest.time;
        fullLog.record("phone_fix", "latitude", location.getLatitude(), "longitude", location.getLongitude(),
                "fixElapsedMs", time, "accuracyM", location.hasAccuracy() ? location.getAccuracy() : null,
                "altitudeM", location.hasAltitude() ? location.getAltitude() : null,
                "speedMps", location.hasSpeed() ? location.getSpeed() : null,
                "bearingDegrees", location.hasBearing() ? location.getBearing() : null,
                "acceptedBySource", !mock && newer, "mock", mock);
        if (mock || !YawAimingMath.coordinateValid(location.getLatitude(), location.getLongitude()))
            diagnostic.accept("phone_invalid", mock ? "Mock location rejected" : "Invalid coordinates; aiming gate rejects fix");
        if (mock) return;
        AimingSession.Fix fix = new AimingSession.Fix(location.getLatitude(), location.getLongitude(),
                (location.hasAccuracy() ? location.getAccuracy() : Double.NaN), location.getElapsedRealtimeNanos() / 1000000);
        if (latest == null || fix.time > latest.time) latest = fix;
    }
    @Override public void onProviderDisabled(String provider) { latest = null; diagnostic.accept("phone_connection", "GPS provider disabled"); }
    @Override public void onProviderEnabled(String provider) { diagnostic.accept("phone_connection", "GPS provider enabled"); }
    @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
}
