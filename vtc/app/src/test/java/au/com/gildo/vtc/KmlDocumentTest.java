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
    static Map<String,String> settings(){return GeoAndCalibrationTest.configured("latitude","-28","longitude","153","height","4","landmarkLat","","landmarkLon","").values;}
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
        assertEquals(1,xml.getElementsByTagName("LineString").getLength());assertEquals(0,xml.getElementsByTagName("Polygon").getLength());
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
    private static String data(Document document,String point,String key) {
        org.w3c.dom.NodeList placemarks=document.getElementsByTagName("Placemark");
        for(int i=0;i<placemarks.getLength();i++){
            org.w3c.dom.Element feature=(org.w3c.dom.Element)placemarks.item(i);
            if(!feature.getElementsByTagName("name").item(0).getTextContent().startsWith(point))continue;
            org.w3c.dom.NodeList entries=feature.getElementsByTagName("Data");
            for(int j=0;j<entries.getLength();j++){
                org.w3c.dom.Element entry=(org.w3c.dom.Element)entries.item(j);
                if(key.equals(entry.getAttribute("name")))return entry.getTextContent();
            }
        }
        throw new AssertionError(point+" missing "+key);
    }
    @Test public void timestampsAndRideTelemetryFollowTheMatchingFixAcrossGpsLoss()throws Exception {
        KmlDocument kml=new KmlDocument();
        Map<String,String> settings=new LinkedHashMap<>(settings());
        settings.put("offshoreHeadingDegrees","0");
        settings.put("landmarkLat","-28.001");settings.put("landmarkLon","153");
        String text=KmlDocument.header("test")+kml.accept(event("settings_snapshot",1000,"settings",settings));
        text+=kml.accept(event("aim_start",1100));
        text+=kml.accept(event("camera_zoom_response",1150,"zoom",89,"opticalZoomX",6));
        Map<String,Object> first=new LinkedHashMap<>(gps(1,-28.001,153));
        first.put("receivedAtMs",1250);first.put("continuity",1);first.put("senderMs",1250);
        Map<String,Object> second=new LinkedHashMap<>(gps(2,-28.001,153.00005));
        second.put("receivedAtMs",1750);second.put("continuity",1);second.put("senderMs",1750);
        text+=kml.accept(event("gps_accepted",1250,"telemetry",first,"receivedAtMs",1250,"continuity",1,
            "timestamp","1970-01-01T10:00:01.250+10:00"));
        text+=kml.accept(event("framing_observation",1350,"gps",first,"framing",
            Map.of("rideActive",true,"qualifyingSpeedKmh",12.1,"appliedOffsetMetres",2)));
        text+=kml.accept(event("framing_observation",1750,"gps",second,"framing",
            Map.of("rideActive",true,"qualifyingSpeedKmh",14.2,"appliedOffsetMetres",2)));
        text+=kml.accept(event("gps_accepted",1750,"telemetry",second,"receivedAtMs",1750,"continuity",1));
        text+=kml.accept(event("aim_cycle",1950,"gps",second,"rangeM",120,"bearingDegrees",185,
            "cameraHeadingDegrees",180,"targetHeadingDegrees",186,"panErrorDegrees",6,"tiltErrorDegrees",1,"holdPan",true));
        text+=kml.accept(event("gps_invalid",2100,"reason","GPS interrupted"));
        text+=kml.accept(event("gps_accepted",2400,"telemetry",gps(3,-28.001,153.005),"receivedAtMs",2400,"continuity",2));
        Document doc=parse(text+kml.finish()+KmlDocument.FOOTER);
        assertEquals("1970-01-01T10:00:01.250+10:00",data(doc,"Surfer #1","localTimestamp"));
        assertEquals("12.1",data(doc,"Surfer #1","qualifyingSpeedKmh"));
        assertEquals("14.2",data(doc,"Surfer #2","qualifyingSpeedKmh"));
        assertEquals("2",data(doc,"Surfer #2","appliedOffsetMetres"));
        assertEquals("Hold pan",data(doc,"Surfer #2","aimingMode"));
        assertEquals("89",data(doc,"Surfer #2","confirmedCcapiZoom"));
        assertEquals(4.9,Double.parseDouble(data(doc,"Surfer #2","nonSeawardStepMetres")),.1);
        assertEquals(data(doc,"Surfer #2","nonSeawardStepMetres"),data(doc,"Surfer #2","nonSeawardRideMetres"));
        assertEquals("Unavailable",data(doc,"Surfer #3","nonSeawardStepMetres"));
        assertEquals(data(doc,"Surfer #2","nonSeawardSessionMetres"),data(doc,"Surfer #3","nonSeawardSessionMetres"));
        assertEquals("0",data(doc,"Offshore heading","offshoreHeadingDegrees"));
    }
    @Test public void outwardComponentDoesNotCountTowardKmlDistance()throws Exception {
        KmlDocument kml=new KmlDocument();
        Map<String,String> settings=new LinkedHashMap<>(settings());
        settings.put("offshoreHeadingDegrees","0");
        String text=KmlDocument.header("test")+kml.accept(event("settings_snapshot",1000,"settings",settings));
        text+=kml.accept(event("gps_accepted",1200,"telemetry",gps(1,-28,153)));
        text+=kml.accept(event("gps_accepted",1700,"telemetry",gps(2,-27.99995,153)));
        Document doc=parse(text+KmlDocument.FOOTER);
        assertEquals(0,Double.parseDouble(data(doc,"Surfer #2","nonSeawardStepMetres")),0);
        assertTrue(Double.parseDouble(data(doc,"Surfer #2","gpsSpeedKmh"))>0);
    }
    @Test public void rayProjectionMatchesKnownCardinalDirections() {
        double[] north=KmlDocument.destination(0,0,0,1000),east=KmlDocument.destination(0,0,90,1000);
        assertEquals(.008993216,north[0],1e-9);assertEquals(0,north[1],1e-9);
        assertEquals(0,east[0],1e-9);assertEquals(.008993216,east[1],1e-9);
    }
}
