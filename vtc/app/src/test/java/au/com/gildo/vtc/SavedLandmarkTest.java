package au.com.gildo.vtc;

import org.junit.Test;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

public class SavedLandmarkTest {
    static final String FIRMWARE="axis 1=0336C5 / axis 2=0336C5";
    static final int[] CPR={2073600,2073600};
    static Settings home(String... changes) {
        Map<String,String> values=new HashMap<>(Map.of("mountHost","192.168.4.3","latitude","-28.07924011",
            "longitude","153.4010217","height","4","targetHeight","0","landmarkLat","-28.0791129",
            "landmarkLon","153.4012993","landmarkHeight","4","tiltSign","-1","rangeMin","15"));
        values.put("panMin","-90");values.put("panMax","90");
        for(int i=0;i<changes.length;i+=2)values.put(changes[i],changes[i+1]);
        return new Settings(values);
    }
    static SavedLandmark bookmark(Settings s) {
        return SavedLandmark.capture(new Calibration(s,new GtiClient.Position(7677975,8386537,false),CPR),FIRMWARE,1794831979696L);
    }
    static SavedLandmark roundTrip(SavedLandmark bookmark)throws IOException {
        Properties properties=new Properties();properties.putAll(bookmark.values());
        ByteArrayOutputStream out=new ByteArrayOutputStream();properties.store(out,"last landmark");
        Properties restored=new Properties();restored.load(new ByteArrayInputStream(out.toByteArray()));
        Map<String,String> values=new HashMap<>();for(String key:restored.stringPropertyNames())values.put(key,restored.getProperty(key));
        return SavedLandmark.restore(values);
    }
    @Test public void recordedLandmarkRoundTripsAndReturnsBothAxesWithoutLiveGps()throws Exception {
        SavedLandmark loaded=roundTrip(bookmark(home()));
        Calibration navigation=loaded.navigationReference(home(),CPR,FIRMWARE);
        FixedGoto target=FixedGoto.landmark(navigation,1000);
        GtiClientTest.Wire wire=new GtiClientTest.Wire();wire.cpr=CPR[0];wire.pos[0]=7595929;wire.pos[1]=8442355;
        GtiClient mount=new GtiClient(wire);mount.connect();GtiClient.Position current=mount.position();
        double[] step=target.nextMove(navigation.panOffset(current.pan),navigation.tiltAngle(current.tilt));
        mount.goTo(current,step[0]*navigation.settings.panSign,step[1]*navigation.settings.tiltSign,()->true);
        assertEquals(7677975,wire.target[0]);assertEquals(8386537,wire.target[1]);assertEquals(2,wire.starts());
        assertNull(target.source);assertEquals(FixedGoto.Kind.LANDMARK,target.kind);assertEquals(1794831979696L,loaded.savedAtEpochMs);
        assertEquals(bookmark(home()).values(),loaded.values());
    }
    @Test public void returnReferenceDoesNotRemoveTheTrackingCalibrationRequirement()throws Exception {
        SavedLandmark loaded=roundTrip(bookmark(home()));
        assertNotNull(loaded.navigationReference(home(),CPR,FIRMWARE));
        List<String> blockers=Readiness.blockers(home(),true,false,false,false,true,true,"GPS fresh");
        assertTrue(blockers.stream().anyMatch(s->s.startsWith("Calibrate:")));
        assertFalse(loaded.values().containsKey("tracking"));assertFalse(loaded.values().containsKey("calibrationValid"));
    }
    @Test public void mountOrReferenceChangesPreventUsingTheOldBookmark() {
        SavedLandmark saved=bookmark(home());
        for(Settings changed:new Settings[]{home("mountHost","192.168.4.8"),home("mountPort","11881"),
            home("latitude","-28.07924012"),home("longitude","153.4010218"),home("height","5"),
            home("landmarkLat","-28.0791128"),home("landmarkLon","153.4012994"),home("landmarkHeight","5"),
            home("panSign","-1"),home("tiltSign","1")})
            assertThrows(IllegalArgumentException.class,()->saved.navigationReference(changed,CPR,FIRMWARE));
        assertThrows(IllegalArgumentException.class,()->saved.navigationReference(home(),new int[]{360000,360000},FIRMWARE));
        assertThrows(IllegalArgumentException.class,()->saved.navigationReference(home(),CPR,"other firmware"));
    }
    @Test public void currentLimitsAndLegSizeApplyButUnrelatedSettingsDoNotInvalidateBookmark() {
        SavedLandmark saved=bookmark(home());
        Settings edited=home("panMin","-60","panMax","60","gotoMaxMove","10","rangeMin","200",
            "receiverHost","192.168.4.2","framing","FIXED","fixedOffset","1","tiltOffset","3");
        Calibration navigation=saved.navigationReference(edited,CPR,FIRMWARE);
        FixedGoto target=FixedGoto.landmark(navigation,1000);
        assertArrayEquals(new double[]{10,-5},target.nextMove(-50,5),1e-10);
        assertEquals(0,target.offset,0);assertEquals(0,target.tilt,0);
        assertThrows(IllegalArgumentException.class,()->target.nextMove(-70,0));
        assertEquals(45,saved.saved.settings.gotoMaxMove,0);
    }
    @Test public void detectedMotorResetStaysInvalidAcrossAnotherAppRestart()throws Exception {
        SavedLandmark saved=roundTrip(bookmark(home()).invalidate("Motor reference reset; calibrate again"));
        IllegalArgumentException error=assertThrows(IllegalArgumentException.class,()->saved.navigationReference(home(),CPR,FIRMWARE));
        assertEquals("Motor reference reset; calibrate again",error.getMessage());
        assertEquals(7677975,saved.saved.panRaw); // Kept as history, never silently trusted.
        SavedLandmark fresh=SavedLandmark.capture(new Calibration(home(),new GtiClient.Position(8000000,8100000,false),CPR),FIRMWARE,1794832000000L);
        assertEquals(8000000,roundTrip(fresh).navigationReference(home(),CPR,FIRMWARE).panRaw);
    }
    @Test public void malformedStoredDataIsRejectedInsteadOfBecomingAMotorTarget() {
        for(String[] change:new String[][]{{"format","2"},{"panRaw","-1"},{"tiltRaw","16777216"},
            {"panCpr","0"},{"tiltCpr","999999999"},{"firmware",""},{"savedAtEpochMs","0"},
            {"setting.height",""},{"setting.panSign","0"}}) {
            Map<String,String> data=new HashMap<>(bookmark(home()).values());data.put(change[0],change[1]);
            assertThrows(IllegalArgumentException.class,()->SavedLandmark.restore(data));
        }
    }
    @Test public void numericFormattingChangesDoNotLoseTheSavedPosition() {
        Calibration navigation=bookmark(home()).navigationReference(home("height","4.0","landmarkHeight","4.00","panSign","1.0"),CPR,FIRMWARE);
        assertEquals(7677975,navigation.panRaw);assertEquals(8386537,navigation.tiltRaw);
    }
}
