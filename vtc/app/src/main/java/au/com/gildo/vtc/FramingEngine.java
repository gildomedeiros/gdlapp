package au.com.gildo.vtc;

import java.util.ArrayDeque;

/** Uses one GPS interval to measure qualifying ride speed and sideways travel. */
final class FramingEngine {
    private static final long SPEED_INTERVAL_MS=1000, MAX_INTERVAL_MS=2000;
    // Avoid choosing a framing side from a tiny net bearing change.
    private static final double MIN_SIDEWAYS_METRES=.25;
    private static final class Observation {
        final long sampleMs;
        final double lat,lon,bearing;
        Observation(long sampleMs,double lat,double lon,double bearing) {
            this.sampleMs=sampleMs;this.lat=lat;this.lon=lon;this.bearing=bearing;
        }
    }
    private final Settings settings;
    private final ArrayDeque<Observation> history=new ArrayDeque<>();
    private long lastReceived=-1,lastSample=-1,slowSince=-1;
    private double range=AimUnits.REFERENCE;
    int direction,startCount;
    boolean riding;
    double applied,speedKmh=Double.NaN,sidewaysMetres;
    String reason="Waiting for GPS";

    FramingEngine(Settings settings){this.settings=settings;}

    void reset(){clear("Waiting for GPS");}
    void unavailable(long now){clear("GPS unavailable");}
    void holdForGoto(long now){clear("Saved GoTo");}

    private void clear(String status) {
        history.clear();lastReceived=lastSample=slowSince=-1;
        startCount=direction=0;riding=false;applied=sidewaysMetres=0;
        speedKmh=Double.NaN;reason=status;
    }

    void distance(double metres) {
        range=metres;
        applied=direction*settings.magnitudeDegrees(range);
    }

    void observe(long sampleMs,long receivedMs,double lat,double lon,double bearing) {
        if(settings.offshoreHeading==null){clear("Save an offshore heading to enable ride detection");return;}
        if(sampleMs==lastSample||receivedMs<lastReceived)return;
        if(lastReceived>=0&&(receivedMs-lastReceived>settings.gpsAge*1000||sampleMs<lastSample))
            unavailable(receivedMs);
        lastReceived=receivedMs;lastSample=sampleMs;
        while(!history.isEmpty()&&sampleMs-history.peekFirst().sampleMs>MAX_INTERVAL_MS)history.removeFirst();
        Observation reference=null;
        for(Observation point:history) {
            if(sampleMs-point.sampleMs<SPEED_INTERVAL_MS)break;
            reference=point;
        }
        history.addLast(new Observation(sampleMs,lat,lon,bearing));
        if(reference==null) {
            startCount=0;slowSince=-1;direction=0;applied=sidewaysMetres=0;speedKmh=Double.NaN;
            reason="Waiting for a GPS speed estimate";
            return;
        }
        speedKmh=settings.offshoreHeading.qualifyingDistance(reference.lat,reference.lon,lat,lon)
            *3600/(sampleMs-reference.sampleMs);
        if(!riding) {
            startCount=speedKmh>=settings.rideStartKmh ? startCount+1 : 0;
            riding=startCount>=settings.rideStartConfirmations;
        }else if(speedKmh<settings.rideEndKmh) {
            if(slowSince<0)slowSince=sampleMs;
            if(sampleMs-slowSince>=settings.rideEndSeconds*1000) {
                riding=false;startCount=0;slowSince=-1;
            }
        }else slowSince=-1;
        sidewaysMetres=range*Math.sin(Math.toRadians(Geo.delta(bearing,reference.bearing)));
        direction=riding&&Math.abs(sidewaysMetres)>=MIN_SIDEWAYS_METRES ? (sidewaysMetres>0 ? 1 : -1) : 0;
        applied=direction*settings.magnitudeDegrees(range);
        reason=!riding ? "No active ride" : direction==0 ? "Ride · centred"
            : direction>0 ? "Ride · clockwise" : "Ride · counterclockwise";
    }

    double tick(long now) {
        if(lastReceived>=0&&now-lastReceived>settings.gpsAge*1000)unavailable(now);
        return applied;
    }
}
