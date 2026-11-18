package au.com.gildo.vtc;

final class PanHold {
    static boolean applies(GpsFeed.Fix fix, Settings settings, long now, double cameraHeading, double riderBearing) {
        LoRaTelemetry previous = fix.previousPacket;
        double maxAgeMs = Math.min(settings.gpsAge, settings.gpsGapTimeout) * 1000;
        if (previous == null || now < fix.receivedAt || now - fix.previousReceivedAt > maxAgeMs) return false;
        double latitude = settings.latitude;
        double longitude = settings.longitude;
        if (Geo.distance(latitude, longitude, previous.lat, previous.lon) < .1) return false;
        double previousBearing = Geo.bearing(latitude, longitude, previous.lat, previous.lon);
        return movingTowardCentreline(previousBearing, riderBearing, cameraHeading);
    }

    static boolean movingTowardCentreline(double previousBearing, double riderBearing, double cameraHeading) {
        double previous = Geo.delta(previousBearing, cameraHeading);
        double current = Geo.delta(riderBearing, cameraHeading);
        double movement = Geo.delta(current, previous);
        if (Math.abs(movement) >= 180 || current == -180) return false;
        return (movement > 0 && current <= 0) || (movement < 0 && current >= 0);
    }
}
