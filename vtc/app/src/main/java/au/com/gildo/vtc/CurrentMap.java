package au.com.gildo.vtc;
import java.util.Locale;
final class CurrentMap {
    static boolean valid(Settings s){return Geo.valid(s.latitude,s.longitude)&&Geo.valid(s.landmarkLat,s.landmarkLon);}
    static String kml(Settings s,boolean calibrated){
        if(!valid(s))throw new IllegalArgumentException("Camera and landmark coordinates required");
        double distance=Geo.distance(s.latitude,s.longitude,s.landmarkLat,s.landmarkLon);
        String bearing=distance<.1?"Undefined: points coincide":String.format(Locale.US,"%.3f degrees true",Geo.bearing(s.latitude,s.longitude,s.landmarkLat,s.landmarkLon));
        String state=calibrated?"Calibration validated":"Not yet calibrated";
        String a=CalibrationMapData.coords(s.latitude,s.longitude),b=CalibrationMapData.coords(s.landmarkLat,s.landmarkLon);
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document><name>VTC current coordinates - "+state+"</name><description>Current saved camera and landmark coordinates. "+state+". Distance "+String.format(Locale.US,"%.2f",distance)+" m; geographic bearing "+bearing+". Line is camera-to-landmark geography, not measured camera direction.</description>"
            +"<Style id=\"bearing\"><LineStyle><color>ff00a5ff</color><width>4</width></LineStyle></Style>"
            +"<Placemark><name>Camera</name><Point><coordinates>"+a+"</coordinates></Point></Placemark>"
            +"<Placemark><name>Landmark</name><Point><coordinates>"+b+"</coordinates></Point></Placemark>"
            +"<Placemark><name>Camera to landmark - geographic bearing</name><styleUrl>#bearing</styleUrl><LineString><tessellate>1</tessellate><altitudeMode>clampToGround</altitudeMode><coordinates>"+a+" "+b+"</coordinates></LineString></Placemark></Document></kml>";
    }
}
