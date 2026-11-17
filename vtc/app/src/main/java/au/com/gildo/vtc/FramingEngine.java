package au.com.gildo.vtc;

import java.util.ArrayDeque;

/** Trend decisions consume accepted packets once. Offset ramp runs on monotonic control time. */
final class FramingEngine {
    private static final class Observation {
        final long time;final double bearing;
        Observation(long t,double b){time=t;bearing=b;}
    }
    private final Settings settings;
    private final ArrayDeque<Observation> observations=new ArrayDeque<>();
    private long lastObservation=-1,quietSince=-1,lastTick=-1,sequence=-1,sender=-1;
    private boolean available;
    int direction;
    double desired,applied,net,travel,consistency;
    int supporting;
    String reason="Waiting for GPS";
    FramingEngine(Settings settings){this.settings=settings;}
    void reset() {
        observations.clear();lastObservation=quietSince=lastTick=sequence=sender=-1;
        available=false;direction=0;desired=applied=net=travel=consistency=0;supporting=0;reason="Waiting for GPS";
    }
    void unavailable(long now) {
        observations.clear();lastObservation=quietSince=-1;available=false;lastTick=now;reason="GPS unavailable; framing held";
    }
    void holdForGoto(long now) {
        unavailable(now);sequence=sender=-1;reason="Framing held for saved GoTo target";
    }
    void observe(long seq,long senderMs,long now,double bearing) {
        if(seq==sequence&&senderMs==sender)return;
        if(!Double.isFinite(bearing))throw new IllegalArgumentException("Invalid bearing");
        // Integrate the previous decision only up to this causal event.
        tick(now);
        if(lastObservation>=0&&(now<=lastObservation||now-lastObservation>settings.gpsAge*1000))unavailable(now);
        sequence=seq;sender=senderMs;lastObservation=now;available=true;lastTick=now;
        observations.addLast(new Observation(now,bearing));
        long boundary=now-(long)(settings.window*1000);
        while(observations.size()>2) {
            Observation first=observations.removeFirst();
            if(observations.peekFirst().time>boundary){observations.addFirst(first);break;}
        }
        net=travel=0;supporting=0;
        Observation previous=null;
        for(Observation o:observations){if(previous!=null){double d=Geo.delta(o.bearing,previous.bearing);net+=d;travel+=Math.abs(d);}previous=o;}
        double span=(now-observations.peekFirst().time)/1000.0;
        consistency=travel==0?0:Math.abs(net)/travel;
        int sign=net>0?1:net<0?-1:0;
        previous=null;
        for(Observation o:observations){if(previous!=null&&sign*Geo.delta(o.bearing,previous.bearing)>=settings.supportChange)supporting++;previous=o;}
        boolean ready=observations.size()>=settings.observations&&span>=settings.window;
        boolean trend=ready&&Math.abs(net)>=settings.minNet&&consistency>=settings.consistency&&supporting>=settings.supports;
        boolean quiet=ready&&travel/span<settings.quietRate;
        if(trend){direction=sign;desired=sign*settings.magnitude;quietSince=-1;reason=sign>0?"Right trend → surfer left":"Left trend → surfer right";}
        else if(quiet) {
            if(quietSince<0)quietSince=now;
            reason="Quiet; holding framing";
            if(now-quietSince>=settings.quietSeconds*1000){desired=0;direction=0;reason="Quiet; returning to centre";}
        }else{quietSince=-1;reason=ready?"Uncertain; holding framing":"Building direction evidence";}
    }
    double tick(long now) {
        if(lastTick<0){lastTick=now;return applied;}
        long elapsed=now-lastTick;lastTick=now;
        if(elapsed<0){unavailable(now);return applied;}
        if(!available||lastObservation<0||now-lastObservation>settings.gpsAge*1000) {
            if(available)unavailable(now);
            return applied;
        }
        // Do not catch up after a stalled control loop.
        double dt=Math.min(elapsed,500)/1000.0,limit=settings.offsetRate*dt;
        applied+=Math.max(-limit,Math.min(limit,desired-applied));
        return applied;
    }
    double quietSeconds(long now){return quietSince<0?0:(now-quietSince)/1000.0;}
}

