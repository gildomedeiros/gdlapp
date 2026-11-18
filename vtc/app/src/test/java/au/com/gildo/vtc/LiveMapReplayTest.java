package au.com.gildo.vtc;

import org.junit.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

public class LiveMapReplayTest {
    private static Map<String,Object> row(long ms,String type,Object... fields) {
        Map<String,Object> row=new LinkedHashMap<>();
        row.put("event",type);row.put("monoMs",ms);
        for(int i=0;i<fields.length;i+=2)row.put((String)fields[i],fields[i+1]);
        return row;
    }
    private static Map<String,Object> settings(){
        return ConfigJson.structured(new Settings(Map.of("latitude","0","longitude","0",
            "landmarkLat",".001","landmarkLon","0")).values);
    }
    private static Map<String,Object> gps(long time,double lat,double lon){
        return row(time,"gps_accepted","telemetry",Map.of("latitude",lat,"longitude",lon));
    }
    private static Map<String,Object> pan(long time,double heading,String status){
        return row(time,"mount_position","position",Map.of("headingDegrees",heading,"panStatus",status));
    }

    @Test public void lineUsesSettledAimVersusPanningAndFreshFieldOfView(){
        MapTimeline map=new MapTimeline();
        map.accept(row(100,"settings_snapshot","settings",settings()));
        map.accept(row(110,"aim_start"));
        map.accept(gps(120,.001,0));
        map.accept(pan(130,0,"101"));
        map.accept(row(140,"camera_zoom_response","zoom",116));
        map.accept(row(150,"aim_cycle","panErrorDegrees",0,"panToleranceDegrees",1,"targetHeadingDegrees",0));
        assertEquals("#22a66a",map.frame(150).get("lineColor"));
        assertEquals(10,((Number)map.frame(150).get("zoom")).doubleValue(),1e-9);
        assertEquals(111.195*1.648/10,((Number)map.frame(150).get("width")).doubleValue(),.01);
        map.accept(pan(200,0,"111"));
        assertEquals("#ed9324",map.frame(200).get("lineColor"));
        map.accept(pan(300,30,"111"));
        assertEquals("#e64650",map.frame(300).get("lineColor"));
        assertEquals("#ed9324",map.frame(6000).get("lineColor"));
        assertEquals(false,map.frame(6000).get("fovFresh"));
        map.accept(pan(6100,30,"101"));
        assertEquals("#9daab7",map.frame(6100).get("lineColor"));
    }

    @Test public void selectedLogChoosesLatestCompletedRunAndUsesEarlierZoom()throws Exception {
        List<Map<String,Object>> rows=List.of(
            row(100,"settings_snapshot","settings",settings()),
            row(120,"camera_zoom_response","zoom",116),
            row(200,"aim_start"),gps(225,.001,0),pan(250,0,"101"),row(800,"stop_requested"),
            row(1100,"aim_start"),gps(1350,.002,0),pan(1400,35,"111"),row(1700,"stop_requested"),
            row(2000,"aim_start"),gps(2050,.003,0));
        StringBuilder json=new StringBuilder();
        for(Map<String,Object> row:rows)json.append(LogJson.encode(row)).append('\n');
        json.append("{");
        MapReplay result=MapReplay.read(new ByteArrayInputStream(json.toString().getBytes(StandardCharsets.UTF_8)));
        assertNotNull(result);assertEquals(600,result.duration);
        assertEquals(List.of(.001,0.0),result.at(0).get("surfer"));
        assertEquals(List.of(.002,0.0),result.at(600).get("surfer"));
        assertEquals(10,((Number)result.at(600).get("zoom")).doubleValue(),1e-9);
        assertEquals("#e64650",result.at(600).get("lineColor"));
    }

    @Test public void liveFitKeepsLargestRangeUntilNextAimingRun(){
        MapTimeline map=new MapTimeline();
        map.accept(row(100,"settings_snapshot","settings",settings()));
        map.accept(row(110,"aim_start"));
        map.accept(gps(120,.002,0));
        map.accept(gps(130,.001,0));
        assertEquals(111.195,((Number)map.frame(130).get("range")).doubleValue(),.01);
        assertEquals(222.390,((Number)map.frame(130).get("fitRange")).doubleValue(),.01);
        map.accept(row(140,"aim_start"));
        assertEquals(111.195,((Number)map.frame(140).get("fitRange")).doubleValue(),.01);
    }

    @Test public void replayHasWaveIndicatorAndTenSecondsOfRecordedContext()throws Exception {
        List<Map<String,Object>> rows=new ArrayList<>();
        rows.add(row(100,"settings_snapshot","settings",settings()));
        rows.add(row(1000,"aim_start"));
        for(int at=1000;at<=31000;at+=1000){
            rows.add(gps(at,at==5000?.003:.001,0));
            if(at==13000||at==18000)
                rows.add(row(at,"framing_observation","framing",Map.of("rideActive",at==13000)));
        }
        rows.add(row(31000,"stop_requested"));
        StringBuilder json=new StringBuilder();
        for(Map<String,Object> row:rows)json.append(LogJson.encode(row)).append('\n');
        MapReplay result=MapReplay.read(new ByteArrayInputStream(json.toString().getBytes(StandardCharsets.UTF_8)));
        assertNotNull(result);assertEquals(1,result.rides.size());
        MapReplay.RideClip clip=result.rides.get(0);
        assertEquals(12000,clip.rideStart);
        assertEquals(2000,clip.start);assertEquals(27000,clip.end);
        assertEquals(true,result.at(12000).get("rideActive"));
        assertEquals(false,result.at(17000).get("rideActive"));
        assertEquals(333.585,result.largestRange,.01);
    }

    @Test public void legacyWavelineMigratesAndRemainsVisibleInOldReplay() {
        String wave=LogJson.encode(Map.of("aLat",0,"aLon",0,"bLat",0,"bLon",.001,"seaSide","leftOfAToB"));
        Settings migrated=new Settings(Map.of("waveLine",wave));
        assertEquals(0,migrated.offshoreHeading.degrees,0);
        assertFalse(migrated.values.containsKey("waveLine"));
        Settings reloaded=new Settings(migrated.values);
        assertEquals(0,reloaded.offshoreHeading.degrees,0);
        assertNull(reloaded.legacyWaveLine);
        Settings explicit=new Settings(Map.of("waveLine",wave,"offshoreHeadingDegrees","90"));
        assertEquals(90,explicit.offshoreHeading.degrees,0);

        MapTimeline replay=new MapTimeline();
        replay.accept(Map.of("event","settings_snapshot","monoMs",0,"settings",Map.of("waveLine",ConfigJson.parse(wave))));
        Map<String,Object> oldFrame=replay.frame(0);
        assertTrue(oldFrame.containsKey("waveA"));
        assertTrue(oldFrame.containsKey("waveB"));
        assertTrue(oldFrame.containsKey("seaEnd"));
    }

    @Test public void savedHeadingDrawsSeawardArrowInLiveMapAndEarth()throws Exception {
        Settings s=new Settings(Map.of("offshoreHeadingDegrees","90"));
        MapTimeline live=new MapTimeline();
        live.accept(Map.of("event","settings_snapshot","monoMs",0,"settings",ConfigJson.structured(s.values)));
        Map<String,Object> frame=live.frame(0);
        assertEquals(frame.get("camera"),frame.get("seaStart"));
        List<?> tip=(List<?>)frame.get("seaEnd");
        assertTrue(((Number)tip.get(1)).doubleValue()>s.longitude);
        assertFalse(frame.containsKey("waveA"));
        String kml=CurrentMap.kml(s,true);
        KmlDocumentTest.parse(kml);
        assertTrue(kml.contains("Seaward arrow"));
        assertEquals(-170,s.panMin,0);assertEquals(170,s.panMax,0);
    }
}
