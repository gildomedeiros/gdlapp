package au.com.gildo.vtc;

import java.util.Map;

final class WaveLine {
    private static final double EARTH_METRES = 6371000;
    final double aLat, aLon, bLat, bLon;
    final String seaSide;
    private final double eastUnit, northUnit, seaSign;

    WaveLine(double aLat, double aLon, double bLat, double bLon, String seaSide) {
        if (!Geo.valid(aLat,aLon) || !Geo.valid(bLat,bLon))
            throw new IllegalArgumentException("Invalid wave line coordinates");
        double length = Geo.distance(aLat,aLon,bLat,bLon);
        if (length < 30 || length > 10000)
            throw new IllegalArgumentException("Wave line points must be 30–10000 m apart");
        if (!"leftOfAToB".equals(seaSide) && !"rightOfAToB".equals(seaSide))
            throw new IllegalArgumentException("Choose which side of the wave line is seaward");
        this.aLat=aLat; this.aLon=aLon; this.bLat=bLat; this.bLon=bLon; this.seaSide=seaSide;
        double heading = Math.toRadians(Geo.bearing(aLat,aLon,bLat,bLon));
        eastUnit=Math.sin(heading); northUnit=Math.cos(heading);
        seaSign="leftOfAToB".equals(seaSide) ? 1 : -1;
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

    String json() {
        return LogJson.encode(Map.of("aLat",aLat,"aLon",aLon,"bLat",bLat,"bLon",bLon,"seaSide",seaSide));
    }

    double[] seawardArrow(){
        double length=Geo.distance(aLat,aLon,bLat,bLon);
        double heading=Geo.bearing(aLat,aLon,bLat,bLon);
        double[] start=KmlDocument.destination(aLat,aLon,heading,length/2);
        double[] end=KmlDocument.destination(start[0],start[1],heading-90*seaSign,Math.min(40,length/3));
        return new double[]{start[0],start[1],end[0],end[1]};
    }

    double qualifyingDistance(double fromLat,double fromLon,double toLat,double toLon) {
        double east=EARTH_METRES*Math.toRadians(Geo.delta(toLon,fromLon))
            *Math.cos(Math.toRadians((fromLat+toLat)/2));
        double north=EARTH_METRES*Math.toRadians(toLat-fromLat);
        double along=east*eastUnit+north*northUnit;
        double seaward=seaSign*(north*eastUnit-east*northUnit);
        return Math.hypot(along,Math.min(0,seaward));
    }
}
