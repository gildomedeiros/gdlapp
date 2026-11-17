package au.com.gildo.vtc;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class V113Test {
    @Test public void screenshotCombinationIsAcceptedAndRoundTrips(){
        Settings s=new Settings(Map.of("magnitude","3","panTolerance","1","hfov","6.48"));
        Settings restored=new Settings(s.values);
        assertEquals(3,restored.magnitude,0);assertEquals(1,restored.panTolerance,0);assertEquals(6.48,restored.hfov,0);
    }
    @Test public void fieldOfViewIsOptionalAndDoesNotRestrictOffsets(){
        assertTrue(Double.isNaN(new Settings(Map.of()).hfov));
        Settings s=new Settings(Map.of("hfov","","magnitude","30","panTolerance","3","tiltOffset","-2"));
        assertTrue(Double.isNaN(s.hfov));assertEquals(30,s.magnitude,0);assertEquals(-2,s.tiltOffset,0);
        assertEquals(30,new Settings(Map.of("hfov","0.2","magnitude","30")).magnitude,0);
        assertThrows(IllegalArgumentException.class,()->new Settings(Map.of("magnitude","31")));
    }
    @Test public void blankFieldOfViewKeepsAimLineWithoutInventingSector()throws Exception {
        Map<String,String> settings=new LinkedHashMap<>(KmlDocumentTest.settings());settings.put("hfov","");
        KmlDocument k=new KmlDocument();
        String xml=k.accept(KmlDocumentTest.event("status_snapshot",2000,"settings",settings,
            "position",Map.of("headingDegrees",90,"tiltDegrees",-5)));
        org.w3c.dom.Document doc=KmlDocumentTest.parse(KmlDocument.header("test")+xml+KmlDocument.FOOTER);
        assertEquals(1,doc.getElementsByTagName("LineString").getLength());
        assertEquals(0,doc.getElementsByTagName("Polygon").getLength());
        assertTrue(xml.contains("Camera position"));assertTrue(xml.contains("headingDegreesTrue"));assertFalse(xml.contains("NaN"));
    }
}
