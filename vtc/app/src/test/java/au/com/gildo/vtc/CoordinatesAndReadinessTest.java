package au.com.gildo.vtc;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class CoordinatesAndReadinessTest {
    @Test public void acceptsGoogleMapsDecimalPairWithoutLosingPrecision() {
        String[] pair=Coordinates.parse(" -28.165513983264137, 153.55045722684628 ");
        assertEquals("-28.165513983264137",pair[0]);assertEquals("153.55045722684628",pair[1]);
        assertEquals("-28.165513983264137, 153.55045722684628",Coordinates.display(pair[0],pair[1]));
    }
    @Test public void rejectsSwappedMissingAndNonFiniteCoordinates() {
        for(String input:new String[]{"153.5504, -28.1655","-28.1655","NaN, 153","0, Infinity","0,0,1","abc, def"})
            assertThrows(IllegalArgumentException.class,()->Coordinates.parse(input));
    }
    @Test public void emptyPairKeepsRequiredSetupFieldsEmpty() {
        assertArrayEquals(new String[]{"",""},Coordinates.parse(" "));
        assertEquals("",Coordinates.display("",""));
    }
    @Test public void existingSeparateSettingsDisplayInGoogleFormatAndRoundTrip() {
        Settings old=new Settings(Map.of("landmarkLat","-28.168607320532757","landmarkLon","153.55456670415674"));
        String[] pair=Coordinates.parse(Coordinates.display(old.values.get("landmarkLat"),old.values.get("landmarkLon")));
        assertEquals(old.landmarkLat,Double.parseDouble(pair[0]),0);assertEquals(old.landmarkLon,Double.parseDouble(pair[1]),0);
    }
    @Test public void initialBlockersExplainEachMissingField() {
        List<String> blockers=Readiness.blockers(new Settings(Map.of()),false,false,false,false,true,false,"LoRa disconnected");
        assertTrue(blockers.stream().anyMatch(s->s.contains("GTi IP")));
        assertTrue(blockers.stream().anyMatch(s->s.contains("lens height")));
        assertTrue(blockers.stream().anyMatch(s->s.contains("landmark coordinates")));
        assertTrue(blockers.stream().anyMatch(s->s.contains("landmark height")));
        assertTrue(blockers.stream().anyMatch(s->s.contains("Calibrate here")));
        assertTrue(blockers.stream().anyMatch(s->s.contains("Fresh surfer GPS")));
    }
    @Test public void calibratedIdleStateDoesNotClaimItIsWaitingForCalibration() {
        Settings s=GeoAndCalibrationTest.configured("mountHost","192.168.4.2");
        assertTrue(Readiness.blockers(s,true,true,false,false,true,true,"Receiving GPS").isEmpty());
        List<String> blockers=Readiness.blockers(s,true,true,false,false,true,false,"stale");
        assertEquals(1,blockers.size());assertTrue(blockers.get(0).contains("GPS"));
    }
    @Test public void calibratedTargetBelowMinimumRangeIsBlockedWithActualDistance() {
        Settings s=GeoAndCalibrationTest.configured("rangeMin","30");
        Calibration cal=new Calibration(s,new GtiClient.Position(8000000,8000000,false),new int[]{2073600,2073600});
        List<String> blockers=Readiness.targetBlockers(s,cal,25.7,cal.bearing);
        assertTrue(blockers.contains("Target 25.7 m is below minimum range 30.0 m"));
    }
    @Test public void savedHomeMinimumIsHonouredAndSectorStillChecked() {
        Settings s=GeoAndCalibrationTest.configured("rangeMin","15","height","4","landmarkHeight","4");
        Calibration cal=new Calibration(s,new GtiClient.Position(8000000,8000000,false),new int[]{2073600,2073600});
        assertTrue(Readiness.targetBlockers(s,cal,25.3,cal.bearing).isEmpty());
        assertEquals(1,Readiness.targetBlockers(s,cal,25.3,Geo.wrap(cal.bearing+100)).size());
        assertEquals("Target is outside the configured pan/tilt sector",
            Readiness.targetBlockers(s,cal,25.3,Geo.wrap(cal.bearing+100)).get(0));
    }
    @Test public void rangeBoundariesAreInclusiveAndMaximumIsExplained() {
        Settings s=GeoAndCalibrationTest.configured("rangeMin","15","rangeMax","600");
        assertNull(Readiness.rangeBlocker(s,15));assertNull(Readiness.rangeBlocker(s,600));
        assertEquals("Target 601.0 m exceeds maximum range 600.0 m",Readiness.rangeBlocker(s,601));
        assertNotNull(Readiness.rangeBlocker(s,Double.NaN));
    }
    @Test public void structuredLogsPreserveSettingsRawCommandsAndUnicode() {
        Map<String,Object> fields=new LinkedHashMap<>();
        fields.put("settings",Map.of("coordinates","-28.1, 153.5"));
        fields.put("raw",":j1\r\n\"\\");fields.put("invalid",Double.NaN);fields.put("values",new int[]{1,2});
        String json=LogJson.encode(fields);
        assertEquals("{\"settings\":{\"coordinates\":\"-28.1, 153.5\"},\"raw\":\":j1\\u000d\\u000a\\\"\\\\\",\"invalid\":null,\"values\":[1,2]}",json);
        assertFalse(json.contains("\r"));assertFalse(json.contains("\n"));
    }
}

