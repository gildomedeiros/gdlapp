package au.com.gildo.vtc;

final class Geo {
    static boolean valid(double lat,double lon) {
        return Double.isFinite(lat)&&Double.isFinite(lon)&&lat>=-90&&lat<=90&&lon>=-180&&lon<=180;
    }
    static double wrap(double value) { return (value%360+360)%360; }
    static double delta(double target,double current) { return wrap(target-current+180)-180; }
    static double distance(double lat1,double lon1,double lat2,double lon2) {
        double a=Math.toRadians(lat1),b=Math.toRadians(lat2),d=Math.toRadians(lon2-lon1);
        double h=Math.pow(Math.sin((b-a)/2),2)+Math.cos(a)*Math.cos(b)*Math.pow(Math.sin(d/2),2);
        return 6371000*2*Math.asin(Math.sqrt(Math.max(0,Math.min(1,h))));
    }
    static double bearing(double lat1,double lon1,double lat2,double lon2) {
        if(distance(lat1,lon1,lat2,lon2)<.1) throw new IllegalArgumentException("Target is too close to define a bearing");
        double a=Math.toRadians(lat1),b=Math.toRadians(lat2),d=Math.toRadians(lon2-lon1);
        return wrap(Math.toDegrees(Math.atan2(Math.sin(d)*Math.cos(b),Math.cos(a)*Math.sin(b)-Math.sin(a)*Math.cos(b)*Math.cos(d))));
    }
    static double elevation(double distance,double cameraHeight,double targetHeight) {
        if(distance<=0) throw new IllegalArgumentException("Distance must be positive");
        return Math.toDegrees(Math.atan2(targetHeight-cameraHeight,distance));
    }
}

