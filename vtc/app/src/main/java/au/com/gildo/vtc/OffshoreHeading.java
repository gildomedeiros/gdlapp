package au.com.gildo.vtc;

/** True bearing perpendicular to the shoreline and pointing out to sea. */
final class OffshoreHeading {
    private static final double EARTH_METRES = 6371000;
    final double degrees;
    private final double offshoreEast, offshoreNorth;

    OffshoreHeading(double degrees) {
        this.degrees = Geo.wrap(degrees);
        double angle = Math.toRadians(this.degrees);
        offshoreEast = Math.sin(angle);
        offshoreNorth = Math.cos(angle);
    }

    double qualifyingDistance(double fromLat, double fromLon, double toLat, double toLon) {
        double east = EARTH_METRES * Math.toRadians(Geo.delta(toLon, fromLon))
                * Math.cos(Math.toRadians((fromLat + toLat) / 2));
        double north = EARTH_METRES * Math.toRadians(toLat - fromLat);
        double along = east * offshoreNorth - north * offshoreEast;
        double offshore = east * offshoreEast + north * offshoreNorth;
        return Math.hypot(along, Math.min(0, offshore));
    }

    double[] seawardArrow(double lat, double lon) {
        double[] tip = KmlDocument.destination(lat, lon, degrees, 40);
        return new double[]{lat, lon, tip[0], tip[1]};
    }
}
