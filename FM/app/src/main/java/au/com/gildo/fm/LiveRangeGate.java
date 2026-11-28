package au.com.gildo.fm;

/** Distance is measured before pan lead. GPS filtering continues while tracking holds motion. */
final class LiveRangeGate {
    private final GoTo.Point mount;
    private final double minimum;
    private boolean inside;
    private LiveGpsSource.Fix first;

    LiveRangeGate(GoTo.Point mount,double minimum) { this.mount=mount; this.minimum=minimum; }
    void reset() { inside=false; first=null; }

    void observe(LiveGpsSource.Fix position,boolean acquiring) {
        LoRaTelemetry gps=position.packet();
        inside=Geo.distance(mount.lat(),mount.lon(),gps.lat,gps.lon)<minimum;
        if(inside) {
            first=null;
        } else if(acquiring && first==null && position.target()!=null) {
            first=position;
        }
    }

    boolean inside() { return inside; }
    boolean allows(LiveGpsFilter.Snapshot fix) {
        if(inside || fix==null) return false;
        GoTo.Point p=fix.fix().target();
        return Geo.distance(mount.lat(),mount.lon(),p.lat(),p.lon())>=minimum;
    }
    LiveGpsSource.Fix takeFirst() {
        LiveGpsSource.Fix result=first;
        first=null;
        return result;
    }
}
