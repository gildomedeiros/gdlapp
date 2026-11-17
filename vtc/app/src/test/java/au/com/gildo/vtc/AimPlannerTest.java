package au.com.gildo.vtc;
import org.junit.Test;
import java.util.Map;
import static org.junit.Assert.*;

public class AimPlannerTest {
    @Test public void capsMovesAndHoldsInsideTolerance() {
        AimPlanner p=new AimPlanner(new Settings(Map.of()));
        assertEquals(10,p.step(1,20,1000),0);
        assertEquals(-4,p.step(2,-4,1000),0);
        assertEquals(0,p.step(1,.15,1000),0);
        assertEquals(0,p.step(2,.1,1000),0);
        assertThrows(IllegalArgumentException.class,()->p.step(1,Double.NaN,1000));
    }
    @Test public void blockedAndZeroCommandsNeverRefreshReversalTimer() {
        AimPlanner p=new AimPlanner(new Settings(Map.of()));
        p.sent(1,.25,1000);
        assertEquals(0,p.step(1,-1,1500),0);
        p.sent(1,0,2000);
        assertEquals(0,p.step(1,-1,2999),0);
        assertEquals(-1,p.step(1,-1,3000),0);
        p.sent(1,-.25,3000);
        assertEquals(0,p.step(1,1,4000),0);
        assertEquals(1,p.step(1,1,5000),0);
    }
    @Test public void axesIndependentAndSessionResetClearsHistory() {
        AimPlanner p=new AimPlanner(new Settings(Map.of()));
        p.sent(1,.25,1000);
        assertEquals(-1,p.step(2,-1,1100),0);
        p.reset();assertEquals(-1,p.step(1,-1,1100),0);
    }
    @Test public void lastActualSameDirectionMoveRestartsHold() {
        AimPlanner p=new AimPlanner(new Settings(Map.of()));
        p.sent(1,.25,1000);p.sent(1,.25,2500);
        assertEquals(0,p.step(1,-1,4000),0);
        assertEquals(-1,p.step(1,-1,4500),0);
    }
    @Test public void largeErrorsTriggerGotoAndNearTargetKeepsFineCorrections() {
        AimPlanner p=new AimPlanner(new Settings(Map.of()));
        assertTrue(p.shouldGoTo(-37,-9,1000));assertTrue(p.shouldGoTo(3,0,1000));
        assertTrue(p.shouldGoTo(0,-3,1000));assertFalse(p.shouldGoTo(2.999,0,1000));
        assertEquals(2,p.step(1,2,1000),0);
    }
    @Test public void gotoRespectsReversalHoldAndOldSettingsStillLoad() {
        AimPlanner p=new AimPlanner(new Settings(Map.of()));
        p.sent(1,1,1000);
        assertFalse(p.shouldGoTo(-37,-9,1500));assertTrue(p.shouldGoTo(-37,-9,3000));
        Settings old=new Settings(Map.of("panTolerance","3","magnitude","0"));
        assertEquals(3,old.acquisitionDegrees(143),1e-10);assertEquals(45,old.gotoMaxMove,0);
        assertFalse(new AimPlanner(old).shouldGoTo(3,0,1000));
        assertThrows(IllegalArgumentException.class,()->new Settings(Map.of("gotoMaxMove","46")));
        assertThrows(IllegalArgumentException.class,()->new Settings(Map.of("gotoThreshold","NaN")));
    }
}

