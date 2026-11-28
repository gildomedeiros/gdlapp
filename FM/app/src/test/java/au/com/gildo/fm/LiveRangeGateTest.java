package au.com.gildo.fm;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class LiveRangeGateTest {
    private final GoTo.Point mount=new GoTo.Point(-28,153,0);
    private LoRaTelemetry gps(double metres,int n) {
        var p=Geo.destination(mount,90,metres);
        return LoRaTelemetry.parse("RX,GPS,"+n+","+n+","+p.lat()+","+p.lon()+",25,0.6,-75,10,0");
    }
    private LiveGpsFilter.Snapshot target(LoRaTelemetry gps,int revision) {
        var p=new GoTo.Point(gps.lat,gps.lon,0);
        var fix=new LiveGpsSource.Fix(gps,0,p,p,0,0,false);
        return new LiveGpsFilter.Snapshot(fix,LiveGpsSource.Direction.CLOCKWISE,LiveGpsFilter.State.MOVING,
            revision,0,0,List.of(),Geo.destination(mount,90,40),35);
    }
    @Test public void heldPublicationDoesNotHideNearRawFixAndFirstUsableOutsideReleasesHold() {
        var gate=new LiveRangeGate(mount,5);
        var far=gps(5.01,1);
        var retained=target(far,1);
        gate.observe(retained.fix(),true);
        assertSame(retained.fix(),gate.takeFirst());
        gate.observe(target(gps(4.99,2),2).fix(),true);
        assertTrue(gate.inside());
        assertFalse(gate.allows(retained));
        gate.observe(target(gps(5.01,3),3).fix(),true);
        assertFalse(gate.inside());
        assertTrue(gate.allows(retained));
        assertFalse(gate.allows(target(gps(4,4),2)));
    }
    @Test public void startupSelectsFirstAcceptedPositionInReceiverBatch() {
        var gate=new LiveRangeGate(mount,5);
        var first=target(gps(10,1),1);
        var last=target(gps(12,2),2);
        gate.observe(first.fix(),true);
        gate.observe(last.fix(),true);
        assertSame(first.fix(),gate.takeFirst());
    }
}
