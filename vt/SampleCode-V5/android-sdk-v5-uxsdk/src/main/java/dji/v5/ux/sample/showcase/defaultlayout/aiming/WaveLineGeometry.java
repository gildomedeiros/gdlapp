package dji.v5.ux.sample.showcase.defaultlayout.aiming;

/** Local metre coordinates: A-to-B alongshore plus the explicitly chosen sea normal. */
public final class WaveLineGeometry {
    public static final double EARTH=6371000;
    public final double aLat,aLon,bLat,bLon,alongNorth,alongEast,seaNorth,seaEast;
    public final String seaSide;
    public WaveLineGeometry(double aLat,double aLon,double bLat,double bLon,String seaSide) {
        validPoint(aLat,aLon);validPoint(bLat,bLon);
        double length=YawAimingMath.distance(aLat,aLon,bLat,bLon);
        if(length<30 || length>10000)throw new IllegalArgumentException("Wave line A/B spacing must be 30–10000 m; use 50–100 m for capture");
        if(!"leftOfAToB".equals(seaSide)&&!"rightOfAToB".equals(seaSide))throw new IllegalArgumentException("seaSide must be leftOfAToB or rightOfAToB");
        this.aLat=aLat;this.aLon=aLon;this.bLat=bLat;this.bLon=bLon;this.seaSide=seaSide;
        double bearing=Math.toRadians(YawAimingMath.bearingToTarget(aLat,aLon,bLat,bLon));
        alongNorth=Math.cos(bearing);alongEast=Math.sin(bearing);
        double sign="leftOfAToB".equals(seaSide)?1:-1;
        seaNorth=sign*alongEast;seaEast=-sign*alongNorth;
    }
    public static void validPoint(double lat,double lon) {
        if(!Double.isFinite(lat)||!Double.isFinite(lon)||Math.abs(lat)>85||Math.abs(lon)>180)
            throw new IllegalArgumentException("Coordinates require latitude -85..85, longitude -180..180");
    }
    public double[] offset(double lat,double lon,double originLat,double originLon) {
        return new double[]{Math.toRadians(lat-originLat)*EARTH,
            Math.toRadians(YawAimingMath.shortestHeadingError(lon,originLon))*EARTH*Math.cos(Math.toRadians(originLat))};
    }
    public double[] point(double lat,double lon,double north,double east) {
        return new double[]{lat+Math.toDegrees(north/EARTH),
            ((lon+Math.toDegrees(east/(EARTH*Math.cos(Math.toRadians(lat))))+540)%360)-180};
    }
    public static double segmentDistance(double startNorth,double startEast,double endNorth,double endEast) {
        double n=endNorth-startNorth,e=endEast-startEast,den=n*n+e*e;
        double t=den==0?0:Math.max(0,Math.min(1,-(startNorth*n+startEast*e)/den));
        return Math.hypot(startNorth+t*n,startEast+t*e);
    }
}
