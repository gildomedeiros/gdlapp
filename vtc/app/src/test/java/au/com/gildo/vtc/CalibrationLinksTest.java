package au.com.gildo.vtc;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.Locale;
public class CalibrationLinksTest {
    @Test public void directionsUseSavedCoordinatesAndNoNavigation(){SavedLandmark m=SavedLandmark.capture(new Calibration(GeoAndCalibrationTest.configured(),new GtiClient.Position(1,2,false),new int[]{360000,360000}),"fw",1000);Locale old=Locale.getDefault();try{Locale.setDefault(Locale.GERMANY);String url=CalibrationLinks.maps(m);assertTrue(url.contains("api=1&origin=-28.165513983%2C153.550457227"));assertTrue(url.contains("destination=-28.168607321%2C153.554566704"));assertFalse(url.contains("dir_action"));}finally{Locale.setDefault(old);}}
    @Test public void onlyGeneratedNamesAllowed(){assertTrue(CalibrationLinks.validFile("calibration_1000_12345678-1234-1234-1234-123456789abc.kml"));for(String bad:new String[]{"../setup.json","calibration_1000_../file.kml","setup.json","/etc/passwd","calibration_1000_bad.kml"})assertFalse(CalibrationLinks.validFile(bad));}
}
