package au.com.gildo.vtc;
import java.util.*;
/** VT wave-line stationary capture policy, without Android dependencies. */
final class StableGpsCapture {
    final long startedAt;
    private long lastTime=-1;
    private final List<double[]> samples=new ArrayList<>();
    String status="Waiting for fresh LoRa GPS";
    double scatter;
    static final class Point {final double lat,lon,scatter;Point(double lat,double lon,double scatter){this.lat=lat;this.lon=lon;this.scatter=scatter;}}
    StableGpsCapture(long now){startedAt=now;}
    int count(){return samples.size();}
    void reset(){samples.clear();lastTime=-1;scatter=0;}
    Point observe(double lat,double lon,long time,long now){
        if(now-startedAt>=120000)throw new IllegalArgumentException("Capture timed out after 120 seconds. Keep the LoRa unit still and retry.");
        if(!Geo.valid(lat,lon)||time<startedAt||now<time||now-time>3000){reset();status="Waiting for fresh LoRa GPS; batch reset";return null;}
        if(time<=lastTime){status="Waiting for a distinct fix ("+count()+"/10)";return null;}
        lastTime=time;samples.add(new double[]{lat,lon,time});if(samples.size()>10)samples.remove(0);
        status="Stationary fixes "+count()+"/10 · need at least 4 seconds";
        if(count()<10||time-samples.get(0)[2]<4000)return null;
        double meanLat=0,meanLon=0,origin=samples.get(0)[1];
        for(double[] sample:samples){meanLat+=sample[0];meanLon+=((sample[1]-origin+540)%360)-180;}
        meanLat/=count();meanLon=((origin+meanLon/count()+540)%360)-180;
        scatter=0;for(double[] sample:samples)scatter=Math.max(scatter,Geo.distance(meanLat,meanLon,sample[0],sample[1]));
        if(scatter>5){status=String.format(Locale.US,"GPS scatter %.1f m > 5 m; remain still",scatter);return null;}
        status=String.format(Locale.US,"Stable: 10 fixes · scatter %.1f m",scatter);return new Point(meanLat,meanLon,scatter);
    }
}
