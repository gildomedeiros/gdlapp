package au.com.gildo.vtc;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class V113Test {
    @Test public void screenshotCombinationIsAcceptedAndRoundTrips(){
        Settings s=new Settings(Map.of("magnitude","3","panTolerance","1","hfov","6.48"));
        Settings restored=new Settings(s.values);
        assertEquals(3,restored.magnitudeDegrees(143),1e-10);assertEquals(1,restored.panToleranceDegrees(143),1e-10);assertFalse(restored.values.containsKey("hfov"));
    }
    @Test public void oldFieldOfViewDoesNotDrawFixedSector()throws Exception {
        Map<String,String> settings=new LinkedHashMap<>(KmlDocumentTest.settings());settings.put("hfov","6.48");
        KmlDocument k=new KmlDocument();
        String xml=k.accept(KmlDocumentTest.event("status_snapshot",2000,"settings",settings,
            "position",Map.of("headingDegrees",90,"tiltDegrees",-5)));
        org.w3c.dom.Document doc=KmlDocumentTest.parse(KmlDocument.header("test")+xml+KmlDocument.FOOTER);
        assertEquals(1,doc.getElementsByTagName("LineString").getLength());
        assertEquals(0,doc.getElementsByTagName("Polygon").getLength());
        assertTrue(xml.contains("Camera position"));assertTrue(xml.contains("headingDegreesTrue"));assertFalse(xml.contains("NaN"));
    }
}
