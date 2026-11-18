package au.com.gildo.vtc;
import java.util.Locale;
final class CurrentMap {
    private static String line(String a,String b){
        return "<LineString><tessellate>1</tessellate><altitudeMode>clampToGround</altitudeMode><coordinates>"
            +a+" "+b+"</coordinates></LineString>";
    }
    private static String marker(String name,String coordinates){
        return "<Placemark><name>"+name+"</name><Point><coordinates>"+coordinates+"</coordinates></Point></Placemark>";
    }
    private static String waveGeometry(WaveLine wave){
        if(wave==null)return "";
        String a=CalibrationMapData.coords(wave.aLat,wave.aLon),b=CalibrationMapData.coords(wave.bLat,wave.bLon);
        double[] arrow=wave.seawardArrow();
        double bearing=Geo.bearing(arrow[0],arrow[1],arrow[2],arrow[3]);
        double[] left=KmlDocument.destination(arrow[2],arrow[3],bearing+150,4);
        double[] right=KmlDocument.destination(arrow[2],arrow[3],bearing-150,4);
        String start=CalibrationMapData.coords(arrow[0],arrow[1]),tip=CalibrationMapData.coords(arrow[2],arrow[3]);
        return "<Style id=\"wave\"><LineStyle><color>fff4cb71</color><width>4</width></LineStyle></Style>"
            +marker("Waveline A",a)+marker("Waveline B",b)
            +"<Placemark><name>Saved waveline</name><styleUrl>#wave</styleUrl>"+line(a,b)+"</Placemark>"
            +"<Placemark><name>Seaward arrow</name><styleUrl>#wave</styleUrl><MultiGeometry>"
            +line(start,tip)+line(CalibrationMapData.coords(left[0],left[1]),tip)
            +line(tip,CalibrationMapData.coords(right[0],right[1]))+"</MultiGeometry></Placemark>"
            +marker("Seaward",tip);
    }
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
            +"<Placemark><name>Camera to landmark - geographic bearing</name><styleUrl>#bearing</styleUrl>"+line(a,b)+"</Placemark>"
            +waveGeometry(s.waveLine)+"</Document></kml>";
    }
}
