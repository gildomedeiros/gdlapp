package au.com.gildo.vtc;
import org.junit.Test;
import org.w3c.dom.Document;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

public class KmlDocumentTest {
    @Test public void missingMountRepliesDoNotDrawStaleAimAsANewObservation() {
        KmlDocument kml=new KmlDocument();kml.accept(event("settings_snapshot",1000,"settings",settings()));
        assertEquals("",kml.accept(event("status_snapshot",2000,"settings",settings(),"positionFresh",false,
            "position",Map.of("headingDegrees",45,"tiltDegrees",-5))));
    }
    @Test public void landmarkGotoIsLabelledSeparatelyFromSurferGps()throws Exception {
        KmlDocument kml=new KmlDocument();kml.accept(event("settings_snapshot",1000,"settings",settings()));
        Map<String,Object> target=Map.of("kind","LANDMARK","source",Map.of("latitude",-28.002,"longitude",153.003),
            "headingDegrees",45,"rangeM",530,"tiltDegrees",-5);
        String started=kml.accept(event("goto_captured",2000,"target",target));
        String finished=kml.accept(event("goto_completed",3000,"target",target));
        assertTrue(started.contains("Return to landmark"));assertTrue(finished.contains("Landmark reached"));
        parse(KmlDocument.header("test")+started+finished+KmlDocument.FOOTER);
    }
    static Map<String,Object> event(String type,long at,Object... data) {
        Map<String,Object> out=new LinkedHashMap<>();out.put("event",type);out.put("epochMs",at);
        for(int n=0;n<data.length;n+=2)out.put((String)data[n],data[n+1]);return out;
    }
    static Map<String,String> settings(){return GeoAndCalibrationTest.configured("latitude","-28","longitude","153","height","4","hfov","6.48").values;}
    static Map<String,Object> gps(long seq,double lat,double lon){return Map.of("sequence",seq,"latitude",lat,"longitude",lon,"satellites",20,"hdop",.6);}
    static Document parse(String text)throws Exception {
        DocumentBuilderFactory factory=DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }
    @Test public void cameraAndSurferUseLongitudeLatitudeAndValidXml()throws Exception {
        KmlDocument kml=new KmlDocument();String text=KmlDocument.header("Session <one> & test");
        text+=kml.accept(event("settings_snapshot",1000,"settings",settings()));
        text+=kml.accept(event("gps_accepted",1500,"telemetry",gps(1,-28.001,153.002)))+KmlDocument.FOOTER;
        Document xml=parse(text);
        assertEquals("http://www.opengis.net/kml/2.2",xml.getDocumentElement().getNamespaceURI());
        assertEquals(2,xml.getElementsByTagName("Placemark").getLength());
        assertTrue(text.contains("153.000000000,-28.000000000,0"));
        assertTrue(text.contains("153.002000000,-28.001000000,0"));
        assertTrue(text.contains("<when>1970-01-01T00:00:01.500Z</when>"));
        assertTrue(text.contains("Session &lt;one&gt; &amp; test"));
    }
    @Test public void trackSegmentsDoNotBridgeGpsLossOrLongGaps() {
        KmlDocument kml=new KmlDocument();kml.accept(event("settings_snapshot",1000,"settings",settings()));
        assertFalse(kml.accept(event("gps_accepted",1500,"telemetry",gps(1,-28,153))).contains("<LineString>"));
        assertTrue(kml.accept(event("gps_accepted",2000,"telemetry",gps(2,-28.001,153))).contains("<LineString>"));
        kml.accept(event("gps_invalid",2200));
        assertFalse(kml.accept(event("gps_accepted",2500,"telemetry",gps(3,-28.002,153))).contains("<LineString>"));
        assertFalse(kml.accept(event("gps_accepted",6000,"telemetry",gps(4,-28.003,153))).contains("<LineString>"));
    }
    @Test public void aimShowsCalibratedHeadingTiltTimeAndFieldOfView()throws Exception {
        KmlDocument kml=new KmlDocument();kml.accept(event("settings_snapshot",1000,"settings",settings()));
        kml.accept(event("gps_accepted",1500,"telemetry",gps(1,-28.001,153)));
        String fragment=kml.accept(event("status_snapshot",2000,"settings",settings(),
            "position",Map.of("headingDegrees",90,"tiltDegrees",-9.21,"moving",true),
            "state","GOTO <saved> & tracking","monoMs",1000,"positionSampleMonoMs",900));
        Document xml=parse(KmlDocument.header("test")+fragment+KmlDocument.FOOTER);
        assertEquals(1,xml.getElementsByTagName("LineString").getLength());assertEquals(1,xml.getElementsByTagName("Polygon").getLength());
        assertTrue(fragment.contains("name=\"headingDegreesTrue\"><value>90.0</value>"));
        assertTrue(fragment.contains("name=\"tiltDegreesFromHorizontal\"><value>-9.21</value>"));
        assertTrue(fragment.contains("<begin>1970-01-01T00:00:01.900Z</begin>"));
        assertTrue(fragment.contains("GOTO &lt;saved&gt; &amp; tracking"));
        assertTrue(fragment.contains("Latest fresh surfer range"));
    }
    @Test public void noInventedHeadingBeforeCalibrationAndSavedGotoUsesCapturedRange() {
        KmlDocument kml=new KmlDocument();kml.accept(event("settings_snapshot",1000,"settings",settings()));
        assertEquals("",kml.accept(event("status_snapshot",2000,"settings",settings(),"position",Map.of("panRaw",123))));
        String fragment=kml.accept(event("status_snapshot",3000,"settings",settings(),
            "position",Map.of("headingDegrees",45,"tiltDegrees",-5),"savedGoto",Map.of("rangeM",530),"state","GOTO SAVED TARGET"));
        assertTrue(fragment.contains("Saved GoTo target range"));assertTrue(fragment.contains("<value>530.0</value>"));
        Map<String,Object> target=Map.of("source",gps(7,-28.002,153.003),"headingDegrees",45,"rangeM",530,"tiltDegrees",-5);
        assertTrue(kml.accept(event("goto_captured",3000,"target",target)).contains("Saved GoTo target"));
    }
    @Test public void rayProjectionMatchesKnownCardinalDirections() {
        double[] north=KmlDocument.destination(0,0,0,1000),east=KmlDocument.destination(0,0,90,1000);
        assertEquals(.008993216,north[0],1e-9);assertEquals(0,north[1],1e-9);
        assertEquals(0,east[0],1e-9);assertEquals(.008993216,east[1],1e-9);
    }
}
