package au.com.gildo.vtc;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class V117Test {
    @Test public void deliveredSettingsFileImportsAndRetainsUserConfiguration()throws Exception {
        try(java.io.InputStream in=getClass().getResourceAsStream("/VTC-1.0.17-setup-metres.json")){
            assertNotNull(in);Settings s=new Settings(ConfigJson.settings(new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)));
            assertEquals(2,s.panTolerance,0);assertEquals(.5,s.tiltTolerance,0);assertEquals(12.51,s.magnitude,0);
            assertEquals(7.49,s.acquisitionTriggerMetres,0);assertEquals(2,s.reverseSeconds,0);
            assertFalse(s.autoZoom);assertEquals("192.168.4.3",s.mountHost);assertEquals("192.168.4.1",s.receiverHost);
            assertEquals("192.168.4.4",s.cameraHost);assertEquals(s.values,new Settings(ConfigJson.settings(LogJson.encode(ConfigJson.structured(s.values)))).values);
        }
    }

    @Test public void metresPreserveWidthAsDistanceChanges(){
        Settings s=new Settings(Map.of("panToleranceMetres","2","tiltToleranceMetres","0.5","magnitudeMetres","12.51","acquisitionTriggerMetres","7.49"));
        for(double r:new double[]{40,143,250,500}){
            assertEquals(2,AimUnits.metres(s.panToleranceDegrees(r),r),1e-9);
            assertEquals(.5,AimUnits.metres(s.tiltToleranceDegrees(r),r),1e-9);
            assertEquals(12.51,AimUnits.metres(s.magnitudeDegrees(r),r),1e-9);
            assertEquals(7.49,AimUnits.metres(s.acquisitionDegrees(r),r),1e-9);
        }
        assertThrows(IllegalArgumentException.class,()->s.magnitudeDegrees(0));
    }
    @Test public void oldFileMigratesOnceWithExplicitUnitsAndRoundTrips(){
        Settings s=new Settings(Map.of("panTolerance","0.8","tiltTolerance","0.2","magnitude","5","acquisitionTriggerDegrees","3","zoomMultipliers","1,3","zoomBands",ZoomPlan.DEFAULT_BANDS));
        assertEquals(143*Math.tan(Math.toRadians(.8)),s.panTolerance,1e-9);
        assertEquals("metres",s.values.get("aimingUnits"));assertEquals("2",s.values.get("schemaVersion"));
        for(String old:List.of("panTolerance","tiltTolerance","magnitude","acquisitionTriggerDegrees","zoomMultipliers"))assertFalse(s.values.containsKey(old));
        Settings round=new Settings(ConfigJson.settings(LogJson.encode(ConfigJson.structured(s.values))));assertEquals(s.values,round.values);
        assertTrue(round.values.containsKey("zoomBands"));assertEquals(40,round.desiredFrameWidthMetres,0);
        assertArrayEquals(new double[]{1,3},round.zoomMultipliers,0);
        assertThrows(IllegalArgumentException.class,()->new Settings(Map.of("panTolerance","1","panToleranceMetres","2")));
    }
    @Test public void goToRespectsReversalHold(){
        Settings s=new Settings(Map.of("panToleranceMetres","1","acquisitionTriggerMetres","3","reverseSeconds","2"));
        AimPlanner p=new AimPlanner(s);p.distance(100);p.sent(1,1,1000);
        assertTrue(p.reversalBlocked(1,-1,1500));assertFalse(p.shouldGoTo(-10,0,1500));
        assertTrue(p.shouldGoTo(-10,0,3000));
        p.distance(500);assertFalse(p.shouldGoTo(.2,0,4000));assertTrue(p.shouldGoTo(1,0,5000));
    }
    @Test public void metreRestartMarginIsExactlyOnePointFive(){
        ContinuousPlanner p=new ContinuousPlanner();double tol=AimUnits.degrees(10,40),restart=AimUnits.degrees(15,40);
        assertEquals(0,p.rate(1,AimUnits.degrees(14.99,40),tol,.5,restart),0);
        assertTrue(p.rate(1,AimUnits.degrees(15.01,40),tol,.5,restart)>0);
        assertEquals(0,p.rate(1,AimUnits.degrees(9.99,40),tol,.5,restart),0);
    }
    @Test public void legacyBandsRetireAndScalesSurvive(){
        Settings s=new Settings(Map.of("autoZoom","0","zoomBands",ZoomPlan.DEFAULT_BANDS,"zoom_scales","[1,3]"));
        assertTrue(s.values.containsKey("zoomBands"));assertFalse(s.values.containsKey("autoZoom"));
        assertEquals("off",s.values.get("zoomMode"));assertArrayEquals(new double[]{1,3},s.zoomMultipliers,0);
    }
    @Test public void rejectsMalformedOrUnboundedBandConfigurations(){
        for(String bad:List.of("{}","{\"bufferMetres\":5,\"bands\":[]}","{\"bufferMetres\":5,\"bands\":[{\"maxDistanceMetres\":50,\"zoomX\":1}]}","{\"bufferMetres\":-1,\"bands\":[{\"maxDistanceMetres\":null,\"zoomX\":1}]}"))
            assertThrows(IllegalArgumentException.class,()->new ZoomBands(bad));
        for(String bad:List.of("{\"a\":1,\"a\":2}","[NaN]","[1,]","{","[01]"))assertThrows(IllegalArgumentException.class,()->ConfigJson.parse(bad));
        assertEquals("line\ntext",((Map<?,?>)ConfigJson.parse("{\"s\":\"line\\ntext\"}")).get("s"));
    }
}
