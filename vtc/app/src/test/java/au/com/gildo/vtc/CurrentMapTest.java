package au.com.gildo.vtc;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class CurrentMapTest {
    @Test public void currentPositionsNeedNoCalibrationOrHeight(){Settings s=new Settings(Map.of("latitude","-28","longitude","153","landmarkLat","-28.001","landmarkLon","153.002"));assertTrue(CurrentMap.valid(s));String xml=CurrentMap.kml(s,false);assertTrue(xml.contains("153.000000000,-28.000000000"));assertTrue(xml.contains("153.002000000,-28.001000000"));assertTrue(xml.contains("Not yet calibrated"));assertTrue(CalibrationLinks.maps(s).contains("origin=-28.000000000%2C153.000000000"));assertTrue(CalibrationMapData.html(s,"").contains("<svg"));}
    @Test public void newerCoordinatesReplaceOldExport(){Settings old=new Settings(Map.of("landmarkLat","-28","landmarkLon","153"));Map<String,String> values=new LinkedHashMap<>(old.values);values.put("landmarkLat","-27");Settings updated=new Settings(values);assertFalse(CurrentMap.kml(updated,false).contains("153.000000000,-28.000000000"));assertTrue(CurrentMap.kml(updated,false).contains("153.000000000,-27.000000000"));}
    @Test public void missingPositionsNeverFallBack(){Settings s=new Settings(Map.of("landmarkLat","","landmarkLon",""));assertFalse(CurrentMap.valid(s));assertThrows(IllegalArgumentException.class,()->CurrentMap.kml(s,false));}
    @Test public void coincidentCoordinatesStillViewable(){Settings s=new Settings(Map.of("latitude","-28","longitude","153","landmarkLat","-28","landmarkLon","153"));assertTrue(CurrentMap.kml(s,false).contains("points coincide"));}
}
