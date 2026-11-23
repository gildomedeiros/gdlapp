package au.com.gildo.fm;

import java.util.List;

final class Geo {
    private static final double EARTH_RADIUS_M=6_371_000;
    record FarthestPair(int firstIndex,int secondIndex,double metres) {}

    static boolean valid(double lat,double lon) {
        return Double.isFinite(lat)&&Double.isFinite(lon)&&lat>=-90&&lat<=90&&lon>=-180&&lon<=180;
    }
    static double wrap(double value) { return (value%360+360)%360; }
    static double delta(double target,double current) { return wrap(target-current+180)-180; }
    static double distance(double lat1,double lon1,double lat2,double lon2) {
        double a=Math.toRadians(lat1),b=Math.toRadians(lat2),d=Math.toRadians(lon2-lon1);
        double h=Math.pow(Math.sin((b-a)/2),2)+Math.cos(a)*Math.cos(b)*Math.pow(Math.sin(d/2),2);
        return EARTH_RADIUS_M*2*Math.asin(Math.sqrt(Math.max(0,Math.min(1,h))));
    }

    static double pathLength(List<GoTo.Point> points) {
        double total=0;
        for(int i=1;i<points.size();i++) {
            GoTo.Point previous=points.get(i-1),current=points.get(i);
            total+=distance(previous.lat(),previous.lon(),current.lat(),current.lon());
        }
        return total;
    }

    static FarthestPair farthestPair(List<GoTo.Point> points) {
        int size=points.size();
        if(size<2) return null;

        double[] x=new double[size], y=new double[size], z=new double[size];
        for(int i=0;i<size;i++) {
            GoTo.Point point=points.get(i);
            double latitude=Math.toRadians(point.lat()), longitude=Math.toRadians(point.lon());
            double cosLatitude=Math.cos(latitude);
            x[i]=cosLatitude*Math.cos(longitude);
            y[i]=cosLatitude*Math.sin(longitude);
            z[i]=Math.sin(latitude);
        }

        // Chord length on the unit sphere increases with surface distance.
        double longestChordSquared=-1;
        int first=0,second=1;
        for(int i=0;i<size-1;i++) {
            for(int j=i+1;j<size;j++) {
                double dx=x[i]-x[j],dy=y[i]-y[j],dz=z[i]-z[j];
                double chordSquared=dx*dx+dy*dy+dz*dz;
                if(chordSquared>longestChordSquared) {
                    longestChordSquared=chordSquared;
                    first=i;
                    second=j;
                }
            }
        }
        GoTo.Point a=points.get(first),b=points.get(second);
        return new FarthestPair(first,second,distance(a.lat(),a.lon(),b.lat(),b.lon()));
    }

    static double bearing(double lat1,double lon1,double lat2,double lon2) {
        if(distance(lat1,lon1,lat2,lon2)<.1) throw new IllegalArgumentException("Target is too close to define a bearing");
        double a=Math.toRadians(lat1),b=Math.toRadians(lat2),d=Math.toRadians(lon2-lon1);
        return wrap(Math.toDegrees(Math.atan2(Math.sin(d)*Math.cos(b),Math.cos(a)*Math.sin(b)-Math.sin(a)*Math.cos(b)*Math.cos(d))));
    }
    static GoTo.Point destination(GoTo.Point origin,double bearing,double distanceM) {
        double latitude=Math.toRadians(origin.lat());
        double azimuth=Math.toRadians(bearing);
        double arc=distanceM/EARTH_RADIUS_M;
        double sinLatitude=Math.sin(latitude)*Math.cos(arc)+Math.cos(latitude)*Math.sin(arc)*Math.cos(azimuth);
        double targetLatitude=Math.asin(Math.max(-1,Math.min(1,sinLatitude)));
        double longitudeOffset=Math.atan2(Math.sin(azimuth)*Math.sin(arc)*Math.cos(latitude),
            Math.cos(arc)-Math.sin(latitude)*Math.sin(targetLatitude));
        double longitude=delta(origin.lon()+Math.toDegrees(longitudeOffset),0);
        return new GoTo.Point(Math.toDegrees(targetLatitude),longitude,Double.NaN);
    }

    static double panGapM(double rangeM,double errorCounts,int countsPerRevolution) {
        return 2*rangeM*Math.abs(Math.sin(Math.PI*errorCounts/countsPerRevolution));
    }

    static double elevation(double distance,double cameraHeight,double targetHeight) {
        if(distance<=0) throw new IllegalArgumentException("Distance must be positive");
        return Math.toDegrees(Math.atan2(targetHeight-cameraHeight,distance));
    }
}


