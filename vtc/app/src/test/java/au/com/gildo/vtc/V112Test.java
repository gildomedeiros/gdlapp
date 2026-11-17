package au.com.gildo.vtc;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class V112Test {
    @Test public void highSpeedAlwaysUsesMaximumRatherThanHalfTheError(){
        ContinuousPlanner p=new ContinuousPlanner();
        for(double error:new double[]{2,2.5,2.2,1.8,1.6})assertEquals(3,p.rate(1,error,.15,3),0);
        assertEquals(.5,p.rate(1,1.5,.15,3),0);
    }
    @Test public void hysteresisPreventsTogglingInReturnBand(){
        ContinuousPlanner p=new ContinuousPlanner();
        assertEquals(.5,p.rate(1,1.75,.15,3),0);
        assertEquals(3,p.rate(1,1.76,.15,3),0);
        assertEquals(3,p.rate(1,1.6,.15,3),0);
        assertEquals(.5,p.rate(1,1.5,.15,3),0);
        for(double error:new double[]{1.6,1.7,1.75})assertEquals(.5,p.rate(1,error,.15,3),0);
    }
    @Test public void lowSpeedCapAndToleranceBehaviourRemain(){
        ContinuousPlanner p=new ContinuousPlanner();
        assertEquals(.5,p.rate(1,20,.15,.5),0);
        assertEquals(.2,p.rate(1,.4,.15,3),1e-9);
        assertEquals(0,p.rate(1,.15,.15,3),0);
        assertEquals(0,p.rate(1,.2,.15,3),0);
        assertEquals(.12,p.rate(1,.24,.15,3),1e-9);
    }
    @Test public void axesAndResetHaveIndependentHighSpeedState(){
        ContinuousPlanner p=new ContinuousPlanner();
        assertEquals(3,p.rate(1,2,.15,3),0);
        assertEquals(-.5,p.rate(2,-1.6,.1,3),0);
        assertEquals(3,p.rate(1,1.6,.15,3),0);
        p.reset();assertEquals(.5,p.rate(1,1.6,.15,3),0);
    }
    @Test public void reversalPermissionAndAcquisitionStillHavePriority(){
        Settings s=new Settings(Map.of("maxPanningSpeed","3","acquisitionTriggerDegrees","4"));
        AimPlanner permissions=new AimPlanner(s);ContinuousPlanner p=new ContinuousPlanner();
        double speed=p.rate(1,2,.15,3);assertEquals(3,speed,0);permissions.sent(1,speed,1000);
        double opposite=p.rate(1,-2,.15,3);assertEquals(-3,opposite,0);
        assertTrue(permissions.reversalBlocked(1,opposite,1500));assertFalse(permissions.reversalBlocked(1,opposite,3000));
        assertFalse(permissions.shouldGoTo(2,0,3000));assertTrue(permissions.shouldGoTo(4,0,3000));
    }
    @Test public void physicalSectorLimitDoesNotCreateIntermediateHighSpeeds(){
        assertEquals(3,ContinuousPlanner.limitRate(3,0,-90,90),0);
        assertEquals(.5,ContinuousPlanner.limitRate(3,60,-90,90),0);
        assertEquals(.5,ContinuousPlanner.limitRate(3,70,-90,90),0);
        assertTrue(ContinuousPlanner.limitRate(3,89,-90,90)<.5);
        assertEquals(-.5,ContinuousPlanner.limitRate(-3,-60,-90,90),0);
    }
    @Test public void changingErrorDuringHighSpeedDoesNotSendStopOrRateUpdates()throws Exception {
        V111Test.Wire w=new V111Test.Wire();GtiClient g=new GtiClient(w);g.connect();ContinuousPlanner p=new ContinuousPlanner();
        g.rates(p.rate(1,2,.15,3),0,()->true);assertTrue(w.sent(":G130"));
        w.commands.clear();
        for(double error:new double[]{2.5,2.2,1.9,1.6})g.rates(p.rate(1,error,.15,3),0,()->true);
        for(String prefix:List.of(":L",":G",":I",":J",":S"))assertFalse(w.sent(prefix));
        assertTrue(w.moving[0]);
        g.rates(p.rate(1,1.5,.15,3),0,()->true);
        assertEquals(1,w.commands.stream().filter(c->c.equals(":L1\r")).count());assertTrue(w.sent(":G110"));
    }
    @Test public void lowerHighSpeedMaximumIsUsedDirectly(){
        ContinuousPlanner p=new ContinuousPlanner();
        assertEquals(1,p.rate(1,1.3,.15,1),0);
        assertEquals(1,p.rate(1,1.1,.15,1),0);
        assertEquals(.5,p.rate(1,1,.15,1),0);
        assertEquals(-.1,p.rate(2,-3,.1,.1),0);
    }
}
