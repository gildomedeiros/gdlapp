package au.com.gildo.vtc;
import org.junit.Test;
import static org.junit.Assert.*;
import javax.xml.parsers.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
public class CalibrationMapDataTest {
    private SavedLandmark mark(){return SavedLandmark.capture(new Calibration(GeoAndCalibrationTest.configured(),new GtiClient.Position(123,456,false),new int[]{360000,360000}),"firmware",1234567890000L);}
    @Test public void validKmlWithBothPointsAndHeading()throws Exception{
        String kml=CalibrationMapData.kml(mark());
        DocumentBuilderFactory factory=DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);
        org.w3c.dom.Document doc=factory.newDocumentBuilder().parse(new ByteArrayInputStream(kml.getBytes(StandardCharsets.UTF_8)));
        assertEquals(3,doc.getElementsByTagNameNS("http://www.opengis.net/kml/2.2","Placemark").getLength());
        assertTrue(kml.contains("153.550457227,-28.165513983,0"));assertTrue(kml.contains("clampToGround"));assertTrue(kml.contains("heading at calibration"));
    }
    @Test public void keylessMapIsOfflineAndContainsDirection(){String html=CalibrationMapData.html(mark(),"");assertTrue(html.contains("<svg"));assertTrue(html.contains("marker-end"));assertFalse(html.contains("maps.googleapis.com"));}
    @Test public void googleMapHasBothPinsAndNoRouteDirections(){String html=CalibrationMapData.html(mark(),"fake_key_for_test_only_123");assertTrue(html.contains("google.maps.Polyline"));assertTrue(html.contains("label:'C'"));assertTrue(html.contains("label:'L'"));assertTrue(html.contains("gm_authFailure"));assertTrue(html.contains("map.fitBounds"));}
    @Test public void savedSnapshotRoundTrips(){SavedLandmark m=mark();assertEquals(CalibrationMapData.kml(m),CalibrationMapData.kml(SavedLandmark.restore(m.values())));}
}
