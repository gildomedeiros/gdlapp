package au.com.gildo.vtc;
import org.junit.Test;import java.util.*;import static org.junit.Assert.*;
public class ZoomModesTest {
    private Settings mode(String mode){return new Settings(Map.of("zoomMode",mode,"zoom_scales","[1,3]","zoomBands",ZoomBands.legacy(ZoomPlan.DEFAULT_BANDS)));}
    private ZoomPlan.Decision at(ZoomPlan p,Settings s,double distance,long time){return p.decide(s,new ZoomPlan.Input(distance,0,true,1),time,0,201,1);}
    private double n(ZoomPlan.Decision d,String k){return ((Number)d.fields.get(k)).doubleValue();}
    @Test public void bandsScaleAndOffLeavesZoomAlone(){
        ZoomPlan p=new ZoomPlan();Settings b=mode("bands");
        assertEquals(5,n(at(p,b,220,0),"requestedOpticalZoom"),0);
        assertEquals(15,n(at(p,b,220,10000),"requestedOpticalZoom"),0);
        assertEquals(1,n(at(new ZoomPlan(),b,20,0),"requestedOpticalZoom"),0);
        assertEquals(3,n(at(new ZoomPlan(),new Settings(Map.of("zoomMode","bands","zoom_scales","[3]")),20,0),"requestedOpticalZoom"),0);
        ZoomPlan.Decision off=at(p,mode("off"),220,11000);assertFalse(off.enabled);assertEquals(-1,off.target);
        assertEquals(5,n(at(p,b,220,12000),"requestedOpticalZoom"),0);
    }
    @Test public void hysteresisAndLargeDistanceJumps(){
        ZoomPlan p=new ZoomPlan();Settings b=mode("bands");
        assertEquals(5,n(at(p,b,250,0),"requestedOpticalZoom"),0);
        assertEquals(5,n(at(p,b,254,1),"requestedOpticalZoom"),0);
        assertEquals(6.5,n(at(p,b,256,2),"requestedOpticalZoom"),0);
        assertEquals(6.5,n(at(p,b,246,3),"requestedOpticalZoom"),0);
        assertEquals(5,n(at(p,b,245,4),"requestedOpticalZoom"),0);
        assertEquals(12,n(at(p,b,900,5),"requestedOpticalZoom"),0);
        assertEquals(1,n(at(p,b,10,6),"requestedOpticalZoom"),0);
    }
    @Test public void modeChangesAndStaleGps(){
        ZoomPlan p=new ZoomPlan();Settings b=mode("bands"),a=mode("auto-fit");
        assertEquals(5,n(at(p,b,220,0),"requestedOpticalZoom"),0);
        assertEquals(220*1.648/40,n(at(p,a,220,1),"requestedOpticalZoom"),1e-8);
        assertFalse(p.decide(b,new ZoomPlan.Input(220,9999,false,1),2,0,201,1).enabled);
        ZoomPlan.Decision d=at(p,b,220,3);
        assertFalse(ZoomPlan.withinWidth(b,new ZoomPlan.Input(220,0,true,1),d,d.target-1));
        assertEquals(80,d.target);assertEquals(5,n(d,"bandZoomX"),0);
    }
    @Test public void existingModesAndCustomBandsSurviveMigration(){
        assertEquals("auto-fit",new Settings(Map.of("autoFit","1")).zoomMode);
        assertEquals("off",new Settings(Map.of("autoFit","0")).zoomMode);
        String bands="{\"bufferMetres\":2,\"bands\":[{\"maxDistanceMetres\":100,\"zoomX\":2},{\"maxDistanceMetres\":null,\"zoomX\":7}]}";
        Settings s=new Settings(Map.of("autoZoom","1","zoomBands",bands));
        assertEquals("bands",s.zoomMode);assertEquals(7,s.zoomBands.zoom[1],0);
        Settings round=new Settings(ConfigJson.settings(LogJson.encode(ConfigJson.structured(s.values))));
        assertEquals(s.values,round.values);
        assertEquals("off",new Settings(Map.of("zoomMode","off","autoFit","1")).zoomMode);
        assertTrue(s.onlyLiveCameraChanged(mode("auto-fit")));
    }
    @Test public void autoZoomRunsOnlyWhileAimingAndRestartsItsScaleOnNewSession(){
        for(String mode:List.of("bands","auto-fit")){
            Settings s=mode(mode);
            ZoomPlan p=new ZoomPlan();
            ZoomPlan.Input idle=new ZoomPlan.Input(120,0,true,-1);
            ZoomPlan.Input first=new ZoomPlan.Input(120,0,true,10);
            ZoomPlan.Input restarted=new ZoomPlan.Input(120,0,true,11);
            ZoomPlan.Decision before=p.decide(s,idle,0,0,201,1);
            assertFalse(before.enabled);assertEquals(-1,before.target);
            assertTrue(p.decide(s,first,1000,0,201,1).enabled);
            assertEquals(3,n(p.decide(s,first,11000,0,201,1),"multiplier"),0);
            ZoomPlan.Decision stopped=p.decide(s,idle,12000,0,201,1);
            assertFalse(stopped.enabled);assertEquals(-1,stopped.target);
            assertEquals(1,n(p.decide(s,restarted,13000,0,201,1),"multiplier"),0);
            assertFalse(idle.canSendFor(first.aimingSession));
            assertFalse(restarted.canSendFor(first.aimingSession));
        }
    }
    @Test public void allDefaultBandsAndLimits(){
        Settings b=new Settings(Map.of());
        double[] ranges={20,25,30,50,60,200,201};
        double[] zoom={1,1.5,2,2.5,3,8,10};
        for(int i=0;i<ranges.length;i++)assertEquals(zoom[i],n(at(new ZoomPlan(),b,ranges[i],0),"requestedOpticalZoom"),0);
        ZoomPlan.Decision d=new ZoomPlan().decide(b,new ZoomPlan.Input(600,0,true,1),0,0,100,1);
        assertEquals(100,d.target);assertEquals(true,d.fields.get("clamped"));
    }
}
