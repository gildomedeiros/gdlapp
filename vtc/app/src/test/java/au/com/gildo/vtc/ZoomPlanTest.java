package au.com.gildo.vtc;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class ZoomPlanTest {
    private Settings settings(String... pairs){Map<String,String> m=new HashMap<>();m.put("autoZoom","1");for(int i=0;i<pairs.length;i+=2)m.put(pairs[i],pairs[i+1]);return new Settings(m);}
    private ZoomPlan.Decision at(ZoomPlan p,Settings s,double distance,long time){return p.decide(s,new ZoomPlan.Input(distance,100,true),time,0,201,1);}
    private double number(ZoomPlan.Decision d,String name){return ((Number)d.fields.get(name)).doubleValue();}
    @Test public void defaultsLeaveAutomaticZoomOff(){assertFalse(new Settings(Collections.emptyMap()).autoZoom);}
    @Test public void switchingOffDoesNotCommandWideOrResetZoom(){
        ZoomPlan p=new ZoomPlan();
        assertTrue(at(p,settings("zoomMultipliers","2"),450,0).enabled);
        for(double distance:new double[]{10,150,500}){
            ZoomPlan.Decision off=at(p,settings("autoZoom","0","zoomMultipliers","1,2"),distance,20000);
            assertFalse(off.enabled);assertEquals(-1,off.target);
            assertEquals("Automatic zoom off",off.fields.get("reason"));
        }
    }
    @Test public void everyAgreedBandIncludingAbove500(){
        Settings s=settings();double[] distances={20,50,80,120,170,220,270,320,370,420,470,600};
        double[] base={1,1,1.5,2.5,4,5,6.5,8,9.5,10.5,12,12};
        for(int i=0;i<distances.length;i++)assertEquals(base[i],number(at(new ZoomPlan(),s,distances[i],0),"baseOpticalZoom"),0);
    }
    @Test public void appliesMultiplierBelow40AndClampsAtWideEnd(){
        assertEquals(2,number(at(new ZoomPlan(),settings("zoomMultipliers","2.0"),20,0),"requestedOpticalZoom"),0);
        ZoomPlan.Decision d=at(new ZoomPlan(),settings("zoomMultipliers","0.5"),20,0);
        assertEquals(0,d.target);assertEquals(true,d.fields.get("clamped"));
    }
    @Test public void cyclesEnteredValuesAndSingleValueHolds(){
        ZoomPlan p=new ZoomPlan();Settings s=settings("zoomMultipliers","1.0, 1.2, 2.0","zoomSwitchSeconds","10");
        assertEquals(1,number(at(p,s,200,1000),"multiplier"),0);
        assertEquals(1.2,number(at(p,s,200,11000),"multiplier"),0);
        assertEquals(2,number(at(p,s,200,21000),"multiplier"),0);
        assertEquals(1,number(at(p,s,200,31000),"multiplier"),0);
        assertEquals(2,number(at(p,settings("zoomMultipliers","2"),200,40000),"multiplier"),0);
        assertEquals(2,number(at(p,settings("zoomMultipliers","2"),200,90000),"multiplier"),0);
    }
    @Test public void hysteresisPreventsBoundaryChatterInBothDirections(){
        ZoomPlan p=new ZoomPlan();Settings s=settings();
        assertEquals(1.5,number(at(p,s,90,0),"baseOpticalZoom"),0);
        assertEquals(1.5,number(at(p,s,104,1000),"baseOpticalZoom"),0);
        assertEquals(2.5,number(at(p,s,105,2000),"baseOpticalZoom"),0);
        assertEquals(2.5,number(at(p,s,96,3000),"baseOpticalZoom"),0);
        assertEquals(1.5,number(at(p,s,94,4000),"baseOpticalZoom"),0);
    }
    @Test public void staleGpsAndOffNeverCommand(){
        ZoomPlan p=new ZoomPlan();Settings s=settings();
        assertFalse(p.decide(s,new ZoomPlan.Input(150,6000,false),0,0,201,1).enabled);
        assertFalse(at(p,settings("autoZoom","0"),150,100).enabled);
        assertFalse(at(p,s,Double.NaN,200).enabled);
    }
    @Test public void mappingMatchesAnchorsAndIsMonotonic(){
        assertEquals(0,ZoomPlan.raw(1),0);assertEquals(89,ZoomPlan.raw(6),1e-8);assertEquals(102,ZoomPlan.raw(10),1e-8);
        double last=-1;for(double x=1;x<=65;x+=.1){assertTrue(ZoomPlan.raw(x)>last);last=ZoomPlan.raw(x);assertEquals(x,ZoomPlan.optical(last),1e-8);}
    }
    @Test public void honorsCameraLimitsAndStep(){
        ZoomPlan.Decision d=new ZoomPlan().decide(settings(),new ZoomPlan.Input(500,1,true),0,10,90,4);
        assertTrue(d.target<=90&&d.target>=10);assertEquals(0,(d.target-10)%4);assertEquals(true,d.fields.get("clamped"));
    }
    @Test public void manualObservationDoesNotRestartCycleButSequenceChangeDoes(){
        ZoomPlan p=new ZoomPlan();Settings s=settings("zoomMultipliers","1,2");at(p,s,150,0);
        assertEquals(2,number(at(p,settings("zoomMultipliers","1,2","manualZoomX","6"),150,11000),"multiplier"),0);
        assertEquals(1.2,number(at(p,settings("zoomMultipliers","1.2,2"),150,12000),"multiplier"),0);
    }
    @Test public void validatesDecimalListsAndLiveSettingScope(){
        assertArrayEquals(new double[]{1,1.1,1.2},ZoomPlan.parse("1.0, 1.1, 1.2",true),0);
        for(String bad:new String[]{"","1,","NaN","0.4","3.1","1.15"})assertThrows(IllegalArgumentException.class,()->ZoomPlan.parse(bad,true));
        Settings s=settings();assertTrue(s.onlyZoomChanged(settings("manualZoomX","6","zoomMultipliers","1.2")));
        assertFalse(s.onlyZoomChanged(settings("panTolerance","1")));
        assertEquals(s.values,new Settings(s.values).values);
    }
}
