package au.com.gildo.vtc;

final class PanHold {
    static boolean applies(GpsFeed.Fix fix, Settings settings, long now, double cameraHeading, double riderBearing) {
        String rule=rule(fix,settings,now,cameraHeading,riderBearing);
        return "PR2".equals(rule)||"PR4".equals(rule);
    }

    static int blockedDirection(String rule) {
        if("PR2".equals(rule))return 1;
        if("PR4".equals(rule))return -1;
        return 0;
    }

    static boolean blocks(String rule,int direction) {
        return direction!=0&&direction==blockedDirection(rule);
    }

    static boolean continues(String rule,int activeDirection) {
        return activeDirection!=0&&activeDirection==-blockedDirection(rule);
    }

    static String rule(GpsFeed.Fix fix, Settings settings, long now, double cameraHeading, double riderBearing) {
        if(fix==null)return null;
        LoRaTelemetry previous = fix.previousPacket;
        double maxAgeMs = Math.min(settings.gpsAge, settings.gpsGapTimeout) * 1000;
        if (previous == null || now < fix.receivedAt || now - fix.previousReceivedAt > maxAgeMs) return null;
        double latitude = settings.latitude;
        double longitude = settings.longitude;
        if (Geo.distance(latitude, longitude, previous.lat, previous.lon) < .1) return null;
        double previousBearing = Geo.bearing(latitude, longitude, previous.lat, previous.lon);
        return rule(previousBearing,riderBearing,cameraHeading);
    }

    static String rule(double previousBearing,double riderBearing,double cameraHeading) {
        double movement=Geo.delta(riderBearing,previousBearing);
        if(movement==0||Math.abs(movement)>=180||Geo.delta(riderBearing,cameraHeading)==-180)return null;
        boolean toward=movingTowardCentreline(previousBearing,riderBearing,cameraHeading);
        return movement>0?(toward?"PR4":"PR3"):(toward?"PR2":"PR1");
    }

    static boolean movingTowardCentreline(double previousBearing, double riderBearing, double cameraHeading) {
        double previous = Geo.delta(previousBearing, cameraHeading);
        double current = Geo.delta(riderBearing, cameraHeading);
        double movement = Geo.delta(current, previous);
        if (Math.abs(movement) >= 180 || current == -180) return false;
        return (movement > 0 && current <= 0) || (movement < 0 && current >= 0);
    }
}
