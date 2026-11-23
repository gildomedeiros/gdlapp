package au.com.gildo.fm;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.Locale;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

import static org.junit.Assert.*;

public class AimKmlTest {
    private static final String NS="http://www.opengis.net/kml/2.2";
    private static final GoTo.Point MOUNT=new GoTo.Point(-28.08,153.4,4.5);
    private static final GoTo.Point LANDMARK=new GoTo.Point(-28.08,153.41,0);
    private static final int CPR=360_000;

    @Test public void mapsMeasuredHeadingsAcrossEncoderWrapAndShowsTheActualGap() throws Exception {
        LoRaTelemetry fix=fixAt(90,200);
        AimAtSurfer.Attempt aim=attemptWithFix(fix);
        aim.reference=new GoTo.Reference(MOUNT,90,0,0xfffff8,0,-1,1);
        aim.panCpr=CPR;
        aim.target=AimAtSurfer.target(new GoTo.Point(fix.lat,fix.lon,Double.NaN),aim.reference,CPR);
        aim.baseline=(aim.reference.pan()+1000)&0xffffff;
        aim.goal=aim.target.count();
        aim.lastSampleCount=aim.goal-100;
        aim.crossing=1200L;
        aim.finalCount=aim.goal-300;
        aim.stopped=1310L;
        aim.startAck=1080L;
        aim.motorPeriod=42;
        aim.outcome="Target crossed";
        aim.calculateGap(CPR);

        AimAtSurfer.Attempt tilt=new AimAtSurfer.Attempt(1000,AimAtSurfer.Axis.TILT,message->{});
        tilt.target=new AimAtSurfer.Target(67,200,-2);
        tilt.targetElevationM=0.0;
        aim.stopAck=1190L;
        tilt.baseline=65;
        tilt.gotoMode="2";
        tilt.direction=0;
        tilt.startSent=1210L;
        tilt.startAck=1260L;
        tilt.outcome="Start accepted";
        aim.tilt=tilt;

        Document report=report(aim,fix);
        assertEquals("FM Aim at Surfer HS aim-123",child(report.getDocumentElement(),"name"));
        assertEquals("Target crossed",data(report,"outcome"));
        assertEquals("89.0",data(report,"start_heading_deg_true"));
        assertEquals(90.1,Double.parseDouble(data(report,"crossing_heading_deg_true")),1e-9);
        assertEquals(90.3,Double.parseDouble(data(report,"final_heading_deg_true")),1e-9);
        assertEquals(aim.finalGapM,Double.parseDouble(data(report,"final_gap_m")),1e-9);
        assertEquals("80",data(report,"button_to_start_reply_ms"));
        assertEquals("80",data(report,"button_to_pan_start_reply_ms"));
        assertEquals("67",data(report,"tilt_target_count"));
        assertEquals("Start accepted",data(report,"tilt_outcome"));
        assertEquals("65",data(report,"tilt_baseline_count"));
        assertEquals("2",data(report,"tilt_goto_mode"));
        assertEquals("260",data(report,"button_to_tilt_start_reply_ms"));
        assertEquals("70",data(report,"pan_stop_reply_to_tilt_start_reply_ms"));
        assertEquals("unconfirmed",data(report,"tilt_final_position"));
        assertEquals("310",data(report,"button_to_full_stop_ms"));
        assertTrue(child(report.getDocumentElement(),"description").contains("Button to pan fully stopped · 310 ms"));
        assertEquals("42",data(report,"step_period"));
        assertEquals(7,report.getElementsByTagNameNS(NS,"Placemark").getLength());

        Element surfer=placemark(report,"#surfer");
        assertTrue(child(surfer,"description").contains("GPS #1502"));
        assertTrue(child(surfer,"description").contains("Satellites 8"));
        assertEquals("153.400000000,-28.080000000,4.500",child(placemark(report,"#mount"),"coordinates"));
        assertEquals("Calibration landmark",child(placemark(report,"#landmark"),"name"));

        String finalRay=child(placemark(report,"#final-heading"),"coordinates");
        String[] finalPoint=finalRay.split(" ")[1].split(",");
        double lat=Double.parseDouble(finalPoint[1]), lon=Double.parseDouble(finalPoint[0]);
        assertEquals(200,Geo.distance(MOUNT.lat(),MOUNT.lon(),lat,lon),.001);
        assertEquals(90.3,Geo.bearing(MOUNT.lat(),MOUNT.lon(),lat,lon),.001);
        assertEquals(finalRay.split(" ")[1]+" "+child(surfer,"coordinates"),
            child(placemark(report,"#final-gap"),"coordinates"));
    }

    @Test public void blockedAttemptPublishesAvailablePointsAndEscapesTheError() throws Exception {
        AimAtSurfer.Attempt aim=new AimAtSurfer.Attempt(1000,message->{});
        aim.issue="GPS receiver <offline> & no fix";
        Document report=report(aim,null);
        assertEquals("Aim blocked",data(report,"outcome"));
        assertEquals(aim.issue,data(report,"issue"));
        assertTrue(child(report.getDocumentElement(),"description").contains("&lt;offline&gt; &amp;"));
        assertEquals(2,report.getElementsByTagNameNS(NS,"Placemark").getLength());
        assertEquals(0,report.getElementsByTagNameNS(NS,"LineString").getLength());
        assertTrue(child(report.getDocumentElement(),"description").contains("Button to pan fully stopped · —"));
    }

    @Test public void retainsGpsTargetWhenTheFinalHeadingIsUnavailable() throws Exception {
        LoRaTelemetry fix=fixAt(0,175);
        AimAtSurfer.Attempt aim=attemptWithFix(fix);
        aim.reference=new GoTo.Reference(MOUNT,0,0,1000,0,1,1);
        aim.panCpr=CPR;
        aim.baseline=1100;
        aim.target=AimAtSurfer.target(new GoTo.Point(fix.lat,fix.lon,Double.NaN),aim.reference,CPR);
        aim.issue="Full stop unconfirmed";
        Document report=report(aim,fix);
        assertNotNull(placemark(report,"#surfer"));
        assertNotNull(placemark(report,"#target-heading"));
        assertNull(placemark(report,"#final-heading"));
        assertNull(placemark(report,"#final-gap"));
    }

    private static AimAtSurfer.Attempt attemptWithFix(LoRaTelemetry fix) {
        AimAtSurfer.Attempt attempt=new AimAtSurfer.Attempt(1000,message->{});
        attempt.packet=fix.sequence;
        attempt.ageAtSelection=190L;
        return attempt;
    }

    private static LoRaTelemetry fixAt(double bearing,double range) {
        GoTo.Point point=Geo.destination(MOUNT,bearing,range);
        return LoRaTelemetry.parse(String.format(Locale.ROOT,"RX,GPS,1502,351,%.12f,%.12f,8,1.15,-85,7.0,0",
            point.lat(),point.lon()));
    }

    private static Document report(AimAtSurfer.Attempt aim,LoRaTelemetry fix) throws Exception {
        StringWriter xml=new StringWriter();
        AimKml.write(xml,"aim-123",aim.summary()+aim.timing(null),MOUNT,LANDMARK,fix,aim);
        DocumentBuilderFactory parser=DocumentBuilderFactory.newInstance();
        parser.setNamespaceAware(true);
        return parser.newDocumentBuilder().parse(new InputSource(new StringReader(xml.toString())));
    }

    private static Element placemark(Document report,String style) {
        var elements=report.getElementsByTagNameNS(NS,"Placemark");
        for(int i=0;i<elements.getLength();i++) {
            Element element=(Element)elements.item(i);
            if(style.equals(child(element,"styleUrl"))) return element;
        }
        return null;
    }

    private static String data(Document report,String key) {
        var elements=report.getElementsByTagNameNS(NS,"Data");
        for(int i=0;i<elements.getLength();i++) {
            Element element=(Element)elements.item(i);
            if(key.equals(element.getAttribute("name"))) return child(element,"value");
        }
        throw new AssertionError("Missing report field "+key);
    }

    private static String child(Element parent,String name) {
        return parent.getElementsByTagNameNS(NS,name).item(0).getTextContent();
    }
}
