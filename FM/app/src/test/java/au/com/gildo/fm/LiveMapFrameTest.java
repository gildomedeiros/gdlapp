package au.com.gildo.fm;

import org.junit.Test;
import static org.junit.Assert.*;

public class LiveMapFrameTest {
    private final GoTo.Point mount=new GoTo.Point(-28,153,0);
    @Test public void frameUsesEncoderZoomAndSurferRangeAndDistinguishesStaleData() {
        var surfer=Geo.destination(mount,90,100);
        var live=new LiveMapFrame(mount,null,surfer,true,90.0,true,"Panning",2.0,true,1.6);
        assertEquals(80,live.width(),.001);
        assertTrue(live.inFrame());
        var off=new LiveMapFrame(mount,null,surfer,true,10.0,true,"Panning",2.0,true,1.6);
        assertTrue(off.status().contains("Potentially outside"));
        var stale=new LiveMapFrame(mount,null,surfer,false,10.0,true,"Panning",2.0,true,1.6);
        assertTrue(stale.status().contains("uncertain"));
    }
    @Test public void savedLandmarkRangeGuardDoesNotUseSurferMinimum() {
        var settings=FmService.DEFAULTS.clone();
        MountCoordinates.apply("-28, 153",settings);
        var inside=Geo.destination(mount,50,1999.99);
        var outside=Geo.destination(mount,50,2000.01);
        settings[18]=String.valueOf(inside.lat());settings[19]=String.valueOf(inside.lon());
        assertEquals("",MountCoordinates.liveStartBlock(settings));
        settings[18]=String.valueOf(outside.lat());settings[19]=String.valueOf(outside.lon());
        assertTrue(MountCoordinates.liveStartBlock(settings).contains("LIVE TRACKING BLOCKED"));
        assertEquals("5",settings[FmService.LIVE_MIN_RANGE]);
    }

    @Test public void dashboardReportsZoomAndRangeWithIndependentFreshness() {
        var surfer=Geo.destination(mount,90,155.2);
        var fresh=new LiveMapFrame(mount,null,surfer,true,null,false,"Idle",1.2,true,1.6);
        var staleZoom=new LiveMapFrame(mount,null,surfer,true,null,false,"Idle",1.2,false,1.6);
        var staleGps=new LiveMapFrame(mount,null,surfer,false,null,false,"Idle",null,false,1.6);
        assertEquals("Applied zoom 1.2× · Surfer-to-mount 155.2 m",LiveMapFrame.dashboardStatus(fresh));
        assertEquals("Applied zoom 1.2× (stale) · Surfer-to-mount 155.2 m",LiveMapFrame.dashboardStatus(staleZoom));
        assertEquals("Applied zoom — · Surfer-to-mount 155.2 m (stale)",LiveMapFrame.dashboardStatus(staleGps));
        assertEquals("Applied zoom — · Surfer-to-mount —",LiveMapFrame.dashboardStatus(null));
    }
}
