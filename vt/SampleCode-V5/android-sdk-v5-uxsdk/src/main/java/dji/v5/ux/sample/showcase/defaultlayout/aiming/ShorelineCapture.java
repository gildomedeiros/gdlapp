package dji.v5.ux.sample.showcase.defaultlayout.aiming;

import java.util.*;
/** Stationary LoRa batches: 10 distinct fresh fixes spanning >=4s, <=5m scatter, 120s timeout. */
public final class ShorelineCapture {
    public final long startedAt;
    private long lastTime=-1;
    private final List<AimingSession.Fix> samples=new ArrayList<>();
    public String status="Waiting for fresh LoRa GPS";
    public static final class Point {
        public final double lat,lon,scatter;public final int fixes;
        Point(double lat,double lon,double scatter,int fixes){this.lat=lat;this.lon=lon;this.scatter=scatter;this.fixes=fixes;}
    }
    public ShorelineCapture(long now){startedAt=now;}
    public Point observe(AimingSession.Fix fix,long now) {
        if(now-startedAt>=120000)throw new IllegalArgumentException("Capture timed out: fresh stationary LoRa fixes required. Retry at the shoreline.");
        if(fix==null||now<fix.time||now-fix.time>3000||fix.time<startedAt) {
            samples.clear();status="Waiting for fresh LoRa GPS; batch reset";return null;
        }
        ShorelineGeometry.validPoint(fix.lat,fix.lon);
        if(fix.time<=lastTime){status="Waiting for a distinct LoRa fix ("+samples.size()+"/10)";return null;}
        lastTime=fix.time;samples.add(fix);if(samples.size()>10)samples.remove(0);
        status="Stationary fixes "+samples.size()+"/10";
        if(samples.size()<10||fix.time-samples.get(0).time<4000)return null;
        double lat=0,lon=0,origin=samples.get(0).lon;
        for(AimingSession.Fix f:samples){lat+=f.lat;lon+=YawAimingMath.shortestHeadingError(f.lon,origin);}
        lat/=samples.size();lon=((origin+lon/samples.size()+540)%360)-180;
        double scatter=0;for(AimingSession.Fix f:samples)scatter=Math.max(scatter,YawAimingMath.distance(lat,lon,f.lat,f.lon));
        if(scatter>5){status="GPS scatter "+Math.round(scatter)+" m > 5 m; remain stationary";return null;}
        return new Point(lat,lon,scatter,samples.size());
    }
}
