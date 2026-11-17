package au.com.gildo.vtc;
import java.util.*;
final class AimUnits {
    static final double REFERENCE=143;
    static double degrees(double metres,double range){
        if(!Double.isFinite(range)||range<=0||!Double.isFinite(metres))throw new IllegalArgumentException("Positive finite surfer distance required for metre aiming");
        return Math.toDegrees(Math.atan2(metres,range));
    }
    static double metres(double degrees,double range){return range*Math.tan(Math.toRadians(degrees));}
    static Map<String,String> migrate(Map<String,String> original){
        Map<String,String> m=new LinkedHashMap<>(original);
        String[][] rename={{"panTolerance","panToleranceMetres"},{"tiltTolerance","tiltToleranceMetres"},{"magnitude","magnitudeMetres"},{"acquisitionTriggerDegrees","acquisitionTriggerMetres"}};
        if(m.containsKey("gotoThreshold")&&!m.containsKey("acquisitionTriggerDegrees"))m.put("acquisitionTriggerDegrees",m.get("gotoThreshold"));m.remove("gotoThreshold");
        boolean migrated=false;
        for(String[] pair:rename)if(m.containsKey(pair[0])){
            if(m.containsKey(pair[1]))throw new IllegalArgumentException("Mixed degree/metre keys: "+pair[0]+" and "+pair[1]);
            double old=Double.parseDouble(m.remove(pair[0]));if(!Double.isFinite(old)||old<0||old>(pair[0].contains("Tolerance")?3:30))throw new IllegalArgumentException("Invalid legacy angle: "+pair[0]);
            m.put(pair[1],Double.toString(metres(old,REFERENCE)));migrated=true;
        }
        if(migrated)m.put("migrationReferenceMetres","143");
        if(m.containsKey("zoomMultipliers")){
            if(!m.containsKey("zoom_scales"))m.put("zoom_scales",LogJson.encode(ZoomPlan.parse(m.get("zoomMultipliers"),true)));
            m.remove("zoomMultipliers");
        }
        if(m.containsKey("zoomBands")&&!m.get("zoomBands").trim().startsWith("{"))m.put("zoomBands",ZoomBands.legacy(m.get("zoomBands")));
        if(m.containsKey("autoZoom")){if(!m.containsKey("autoFit"))m.put("autoFit",m.get("autoZoom"));m.remove("autoZoom");}
        m.remove("zoomBands");
        return m;
    }
}
