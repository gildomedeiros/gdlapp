package au.com.gildo.vtc;
import org.junit.Test;
import static org.junit.Assert.*;
public class StableGpsCaptureTest {
    @Test public void averagesTenDistinctStableFixes(){StableGpsCapture c=new StableGpsCapture(1000);StableGpsCapture.Point p=null;for(int i=0;i<10;i++)p=c.observe(-28+i*.000001,153,1000+i*500,1000+i*500);assertNotNull(p);assertEquals(-27.9999955,p.lat,1e-9);assertTrue(p.scatter<5);}
    @Test public void duplicateDoesNotCount(){StableGpsCapture c=new StableGpsCapture(0);for(int i=0;i<20;i++)assertNull(c.observe(-28,153,0,100));assertEquals(1,c.count());}
    @Test public void staleAndPreCaptureReset(){StableGpsCapture c=new StableGpsCapture(1000);c.observe(-28,153,1000,1000);assertNull(c.observe(-28,153,1000,4001));assertEquals(0,c.count());assertNull(c.observe(-28,153,999,1000));}
    @Test public void rejectsScatterThenRecovers(){StableGpsCapture c=new StableGpsCapture(0);for(int i=0;i<10;i++)assertNull(c.observe(-28+(i%2)*.001,153,i*500,i*500));assertTrue(c.scatter>5);StableGpsCapture.Point p=null;for(int i=10;i<20;i++)p=c.observe(-28,153,i*500,i*500);assertNotNull(p);}
    @Test public void requiresFourSeconds(){StableGpsCapture c=new StableGpsCapture(0);for(int i=0;i<10;i++)assertNull(c.observe(-28,153,i*100,i*100));}
    @Test public void timeoutAndInvalidFix(){StableGpsCapture c=new StableGpsCapture(0);assertNull(c.observe(Double.NaN,153,0,0));assertThrows(IllegalArgumentException.class,()->c.observe(-28,153,120000,120000));}
    @Test public void handlesDateLine(){StableGpsCapture c=new StableGpsCapture(0);StableGpsCapture.Point p=null;for(int i=0;i<10;i++)p=c.observe(0,i%2==0?179.999999:-179.999999,i*500,i*500);assertNotNull(p);assertEquals(180,Math.abs(p.lon),1e-7);}
}
