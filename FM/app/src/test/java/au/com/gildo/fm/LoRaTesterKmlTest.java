package au.com.gildo.fm;

import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.Test;

public class LoRaTesterKmlTest {
    private static String placemark(String kind, String point) {
        return "<Placemark><ExtendedData><Data name='classification'><value>" + kind
            + "</value></Data></ExtendedData><MultiGeometry><Point><coordinates>" + point
            + "</coordinates></Point><LineString><coordinates>0,0 1,1</coordinates>"
            + "</LineString></MultiGeometry></Placemark>";
    }

    private static List<GoTo.Point> read(String xml) throws IOException {
        return LoRaTesterKml.read(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test public void readsOnlyGpsPointsKeepingOrderAndRepeats() throws IOException {
        var rows = placemark("gps","153,-28,0") + placemark("event","0,0")
            + placemark("gps","153,-28,0") + placemark("phone","1,1") + placemark("gps","153.25,-28.5");
        var fixes = read("<kml xmlns='http://www.opengis.net/kml/2.2'><Document>" + rows + "</Document></kml>");
        assertEquals(3, fixes.size());
        assertEquals(-28, fixes.get(0).lat(), 0);
        assertEquals(153, fixes.get(0).lon(), 0);
        assertEquals(fixes.get(0), fixes.get(1));
        assertEquals(-28.5, fixes.get(2).lat(), 0);
        assertEquals(153.25, fixes.get(2).lon(), 0);
    }

    @Test public void rejectsBadPointEmptyExportAndDoctype() {
        assertThrows(IOException.class, () -> read("<kml>" + placemark("gps","20,91,0") + "</kml>"));
        assertThrows(IOException.class, () -> read("<kml>" + placemark("event","0,0") + "</kml>"));
        assertThrows(IOException.class, () -> read("<!DOCTYPE kml><kml>" + placemark("gps","0,0") + "</kml>"));
    }
}
