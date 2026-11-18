package au.com.gildo.vtc;
import java.util.Locale;
final class CalibrationLinks {
    static String maps(SavedLandmark mark){return maps(mark.saved.settings);}
    static String maps(Settings s){return String.format(Locale.US,"https://www.google.com/maps/dir/?api=1&origin=%.9f%%2C%.9f&destination=%.9f%%2C%.9f",s.latitude,s.longitude,s.landmarkLat,s.landmarkLon);}
    static boolean validFile(String name){return name!=null&&name.matches("calibration_[0-9]+_[a-f0-9-]{36}\\.kml");}
}
