package au.com.gildo.vtc;

import java.util.Map;

/** Reads the two-point reference in older settings and replay logs. */
final class WaveLine {
    final double aLat, aLon, bLat, bLon, offshoreDegrees;

    WaveLine(double aLat, double aLon, double bLat, double bLon, String seaSide) {
        if (!Geo.valid(aLat,aLon) || !Geo.valid(bLat,bLon))
            throw new IllegalArgumentException("Invalid wave line coordinates");
        double length = Geo.distance(aLat,aLon,bLat,bLon);
        if (length < 30 || length > 10000)
            throw new IllegalArgumentException("Wave line points must be 30–10000 m apart");
        if (!"leftOfAToB".equals(seaSide) && !"rightOfAToB".equals(seaSide))
            throw new IllegalArgumentException("Choose which side of the wave line is seaward");
        this.aLat=aLat; this.aLon=aLon; this.bLat=bLat; this.bLon=bLon;
        double alongshore=Geo.bearing(aLat,aLon,bLat,bLon);
        offshoreDegrees=Geo.wrap(alongshore+("leftOfAToB".equals(seaSide)?-90:90));
    }

    static WaveLine parse(String json) {
        if (json.isEmpty() || json.equals("null")) return null;
        Object parsed=ConfigJson.parse(json);
        if (!(parsed instanceof Map)) throw new IllegalArgumentException("waveLine must be an object or null");
        Map<?,?> row=(Map<?,?>)parsed;
        Object side=row.get("seaSide");
        if (!(side instanceof String)) throw new IllegalArgumentException("waveLine needs a seaSide");
        return new WaveLine(coordinate(row,"aLat"),coordinate(row,"aLon"),
            coordinate(row,"bLat"),coordinate(row,"bLon"),(String)side);
    }

    private static double coordinate(Map<?,?> row,String key) {
        Object value=row.get(key);
        if (!(value instanceof Number)) throw new IllegalArgumentException("waveLine needs numeric "+key);
        return ((Number)value).doubleValue();
    }

    double[] seawardArrow(){
        double length=Geo.distance(aLat,aLon,bLat,bLon);
        double heading=Geo.bearing(aLat,aLon,bLat,bLon);
        double[] start=KmlDocument.destination(aLat,aLon,heading,length/2);
        double[] end=KmlDocument.destination(start[0],start[1],offshoreDegrees,Math.min(40,length/3));
        return new double[]{start[0],start[1],end[0],end[1]};
    }

}
