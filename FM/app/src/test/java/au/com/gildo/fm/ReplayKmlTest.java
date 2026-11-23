package au.com.gildo.fm;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import static org.junit.Assert.*;

public class ReplayKmlTest {
    private static final String KML_NS="http://www.opengis.net/kml/2.2";

    @Test public void mapsEachAttemptInOrderWithStartColorAndReportDetails() throws Exception {
        GoTo.Point target=new GoTo.Point(-28.079324811,153.401402491,2);
        Document report=report("Replay complete · 3 of 3",List.of(
            new ReplayKml.Viewpoint(1,2,target,true,"Both Starts acknowledged\nSince previous call —"),
            new ReplayKml.Viewpoint(2,3,target,false,"Blocked before Start · Pan was still moving"),
            new ReplayKml.Viewpoint(3,4,target,false,"Start issue · :J2 timed out\nStart acknowledgments: 1 of 2")));

        NodeList styles=report.getElementsByTagNameNS(KML_NS,"IconStyle");
        assertEquals("ff00ff00",styles.item(0).getTextContent());
        assertEquals("ff0000ff",styles.item(1).getTextContent());
        NodeList placemarks=report.getElementsByTagNameNS(KML_NS,"Placemark");
        assertEquals(3,placemarks.getLength());
        Element first=(Element)placemarks.item(0);
        Element blocked=(Element)placemarks.item(1);
        Element startIssue=(Element)placemarks.item(2);
        assertEquals("Viewpoint 1 · CSV row 2 · Start acknowledged",child(first,"name"));
        assertEquals("#start-acknowledged",child(first,"styleUrl"));
        assertEquals("153.401402491,-28.079324811,2.000",child(first,"coordinates"));
        assertTrue(child(first,"description").contains("Since previous call —"));
        assertEquals("#start-unsuccessful",child(blocked,"styleUrl"));
        assertTrue(child(blocked,"description").contains("Pan was still moving"));
        assertEquals("#start-unsuccessful",child(startIssue,"styleUrl"));
        assertTrue(child(startIssue,"description").contains("Start acknowledgments: 1 of 2"));
    }

    @Test public void highSpeedReportShowsFirstStartTimingAndEncoderCheckpointOutcomes() throws Exception {
        StringWriter xml=new StringWriter();
        GoTo.Point point=new GoTo.Point(-28.08,153.4,0);
        ReplayKml.write(xml,"hs-attempt","Button to first successful Start reply · 350 ms",null,null,List.of(
            new ReplayKml.Viewpoint(1,2,point,true,"Lined up"),
            new ReplayKml.Viewpoint(2,3,point,false,"Unobserved")),true);
        String report=xml.toString();
        assertTrue(report.contains("<name>FM HS replay hs-attempt</name>"));
        assertTrue(report.contains("Button to first successful Start reply · 350 ms"));
        assertTrue(report.contains("Viewpoint 1 · CSV row 2 · Checkpoint observed"));
        assertTrue(report.contains("Viewpoint 2 · CSV row 3 · Checkpoint unobserved"));
    }

    @Test public void escapesReplyTextSoItCannotBecomeMarkup() throws Exception {
        String reply="Unexpected reply <bad>& ]]>\r\nNext line"+(char)1;
        Document report=report("Run summary",List.of(
            new ReplayKml.Viewpoint(1,2,new GoTo.Point(0,0,0),false,reply)));
        Element placemark=(Element)report.getElementsByTagNameNS(KML_NS,"Placemark").item(0);
        assertTrue(child(placemark,"description").contains("&lt;bad&gt;&amp; ]]&gt;<br/>Next line"));
    }

    @Test public void reportsAnEarlyReplayFailureWithoutPlacemarks() throws Exception {
        Document report=report("Invalid viewpoint at file row 6",List.of());
        assertEquals(0,report.getElementsByTagNameNS(KML_NS,"Placemark").getLength());
        assertEquals("Invalid viewpoint at file row 6",
            report.getElementsByTagNameNS(KML_NS,"description").item(0).getTextContent());
    }

    @Test public void includesConfiguredMountAndLandmarkEvenWhenNoViewpointWasAttempted() throws Exception {
        Document report=report("Blocked before replay",new GoTo.Point(-28.01,153.42,4.5),
            new GoTo.Point(-28.02,153.43,Double.NaN),List.of());
        NodeList placemarks=report.getElementsByTagNameNS(KML_NS,"Placemark");
        assertEquals(2,placemarks.getLength());
        Element mount=(Element)placemarks.item(0);
        Element landmark=(Element)placemarks.item(1);
        assertEquals("Mount",child(mount,"name"));
        assertEquals("#mount",child(mount,"styleUrl"));
        assertEquals("153.420000000,-28.010000000,4.500",child(mount,"coordinates"));
        assertTrue(child(mount,"description").contains("Elevation 4.500 m"));
        assertEquals("Calibration landmark",child(landmark,"name"));
        assertEquals("#landmark",child(landmark,"styleUrl"));
        assertEquals("153.430000000,-28.020000000",child(landmark,"coordinates"));
    }

    private static Document report(String summary,List<ReplayKml.Viewpoint> points) throws Exception {
        return report(summary,null,null,points);
    }

    private static Document report(String summary,GoTo.Point mount,GoTo.Point landmark,
                                   List<ReplayKml.Viewpoint> points) throws Exception {
        StringWriter xml=new StringWriter();
        ReplayKml.write(xml,"test-attempt",summary,mount,landmark,points);
        DocumentBuilderFactory factory=DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml.toString())));
    }

    private static String child(Element parent,String name) {
        return parent.getElementsByTagNameNS(KML_NS,name).item(0).getTextContent();
    }
}
