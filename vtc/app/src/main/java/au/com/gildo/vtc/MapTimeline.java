package au.com.gildo.vtc;

import java.util.*;

/** Shared telemetry reducer for the live map and log replay. Times are monotonic. */
final class MapTimeline {
    private static final long ZOOM_FRESH_MS=5000;
    private static final String GREEN="#22a66a",ORANGE="#ed9324",RED="#e64650",GREY="#9daab7";
    private Settings settings;
    private double surferLat=Double.NaN,surferLon=Double.NaN,heading=Double.NaN,optical=Double.NaN;
    private double aimError=Double.NaN,aimTolerance=Double.NaN,aimHeading=Double.NaN;
    private long gpsAt=-1,headingAt=-1,zoomAt=-1,aimAt=-1;
    private boolean panning,positionValid,gpsValid,zoomValid,aiming;
    private int zoomValue;
    private boolean rideActive;
    private long fitEpoch;
    private double largestRange;

    private static Map<?,?> map(Object value){return value instanceof Map?(Map<?,?>)value:Collections.emptyMap();}
    private static double number(Map<?,?> row,String key) {
        Object value=row.get(key);
        return value instanceof Number?((Number)value).doubleValue():Double.NaN;
    }
    static long time(Map<?,?> row) {
        double now=number(row,"monoMs");
        return Double.isFinite(now)?(long)now:-1;
    }

    private void settings(Object raw) {
        Map<?,?> values=map(raw);
        if(values.isEmpty())return;
        try{
            Settings next=new Settings(ConfigJson.settings(LogJson.encode(values)));
            if(settings!=null) {
                if(Double.compare(settings.latitude,next.latitude)!=0||Double.compare(settings.longitude,next.longitude)!=0){
                    clearMount();largestRange=0;fitEpoch++;
                }
                if(!settings.cameraHost.equals(next.cameraHost)||settings.cameraPort!=next.cameraPort){
                    zoomAt=-1;zoomValid=false;optical=Double.NaN;
                }else if(zoomAt>=0)optical=next.zoomMapping.optical(zoomValue);
            }
            settings=next;
            if(gpsValid)rememberRange();
        }catch(IllegalArgumentException ignored){/* A malformed historical settings row cannot replace a usable one. */}
    }

    private void rememberRange(){
        if(settings!=null&&Geo.valid(settings.latitude,settings.longitude)&&Geo.valid(surferLat,surferLon))
            largestRange=Math.max(largestRange,Geo.distance(settings.latitude,settings.longitude,surferLat,surferLon));
    }

    private void updateRide(Object raw){
        Object value=map(raw).get("rideActive");
        if(value instanceof Boolean)rideActive=(Boolean)value;
    }

    private void clearMount(){positionValid=false;panning=false;heading=Double.NaN;headingAt=-1;aimAt=-1;}

    private void position(Object raw,long at) {
        Map<?,?> pos=map(raw);
        double h=number(pos,"headingDegrees");
        if(!Double.isFinite(h)){
            if(!pos.isEmpty())clearMount();
            return;
        }
        heading=h;headingAt=at;positionValid=true;
        Object status=pos.get("panStatus");
        if(status instanceof String && ((String)status).length()>1)
            panning=(Character.digit(((String)status).charAt(1),16)&1)!=0;
        else panning=Boolean.TRUE.equals(pos.get("moving"));
    }

    boolean accept(Map<?,?> row) {
        long at=time(row);
        if(at<0)return false;
        String event=String.valueOf(row.get("event"));
        switch(event) {
            case "settings_snapshot": settings(row.get("settings"));return true;
            case "aim_start":
                aiming=true;aimAt=-1;rideActive=false;largestRange=0;fitEpoch++;
                if(gpsValid)rememberRange();
                return true;
            case "stop_requested":
            case "session_end": aiming=false;rideActive=false;return true;
            case "calibration_cleared": clearMount();return true;
            case "mount_poll_missing":
            case "mount_poll_error": positionValid=false;return true;
            case "gps_invalid":
            case "gps_no_fix":
            case "gps_fault": gpsValid=false;return true;
            case "gps_accepted":
                Map<?,?> fix=map(row.get("telemetry"));
                double lat=number(fix,"latitude"),lon=number(fix,"longitude");
                if(!Geo.valid(lat,lon))return false;
                surferLat=lat;surferLon=lon;gpsAt=at;gpsValid=true;rememberRange();return true;
            case "mount_position":
            case "calibration_success": position(row.get("position"),at);return true;
            case "status_snapshot":
                if(settings==null)settings(row.get("settings"));
                updateRide(row.get("framing"));
                double sample=number(row,"positionSampleMonoMs");
                if(Boolean.FALSE.equals(row.get("positionFresh")))positionValid=false;
                else if(Double.isFinite(sample)&&sample>0&&sample>=headingAt)position(row.get("position"),(long)sample);
                if(Boolean.FALSE.equals(row.get("gpsFresh")))gpsValid=false;
                return true;
            case "framing_observation":
                updateRide(row.get("framing"));return true;
            case "aim_cycle":
                updateRide(row.get("framing"));
                aimError=number(row,"panErrorDegrees");
                aimTolerance=number(row,"panToleranceDegrees");
                aimHeading=number(row,"targetHeadingDegrees");aimAt=at;return true;
            case "camera_zoom_response":
                double value=number(row,"zoom");
                if(settings==null||!Double.isFinite(value))return false;
                zoomValue=(int)value;optical=settings.zoomMapping.optical(zoomValue);zoomAt=at;zoomValid=true;return true;
            case "camera_zoom_error":
            case "camera_zoom_skipped":
                if(!"Settings or network changed before command".equals(row.get("reason"))
                    &&"camera_zoom_error".equals(event))zoomValid=false;
                return true;
            default: return false;
        }
    }

    private static boolean fresh(long now,long at,long max){return at>=0&&now>=at&&now-at<=max;}
    private static List<Double> point(double lat,double lon){return List.of(lat,lon);}

    Map<String,Object> frame(long now) {
        Map<String,Object> f=new LinkedHashMap<>();
        if(settings==null)return f;
        Settings s=settings;
        if(Geo.valid(s.latitude,s.longitude))f.put("camera",point(s.latitude,s.longitude));
        if(Geo.valid(s.landmarkLat,s.landmarkLon))f.put("landmark",point(s.landmarkLat,s.landmarkLon));
        if(s.waveLine!=null){
            WaveLine line=s.waveLine;
            double[] arrow=line.seawardArrow();
            f.put("waveA",point(line.aLat,line.aLon));f.put("waveB",point(line.bLat,line.bLon));
            f.put("seaStart",point(arrow[0],arrow[1]));f.put("seaEnd",point(arrow[2],arrow[3]));
        }
        long maxAge=(long)(s.gpsAge*1000);
        boolean surferFresh=gpsValid&&fresh(now,gpsAt,maxAge);
        boolean headingFresh=positionValid&&fresh(now,headingAt,maxAge);
        boolean zoomFresh=zoomValid&&fresh(now,zoomAt,ZOOM_FRESH_MS);
        f.put("gpsFresh",surferFresh);f.put("headingFresh",headingFresh);f.put("zoomFresh",zoomFresh);
        f.put("rideActive",aiming&&rideActive&&surferFresh);
        f.put("fitEpoch",fitEpoch);
        f.put("fitRange",largestRange);
        if(Double.isFinite(heading))f.put("heading",heading);
        if(Double.isFinite(optical)){f.put("zoom",optical);f.put("zoomAgeMs",now-zoomAt);}
        if(gpsAt<0||!f.containsKey("camera"))return f;
        f.put("surfer",point(surferLat,surferLon));
        double range=Geo.distance(s.latitude,s.longitude,surferLat,surferLon);
        f.put("range",range);
        boolean geometry=range>=.1&&Double.isFinite(heading)&&Double.isFinite(optical)&&optical>0;
        boolean coverageFresh=geometry&&surferFresh&&headingFresh&&zoomFresh;
        boolean outside=false;
        double width=range*s.wideFrameFactor/optical;
        if(geometry){
            double error=Geo.delta(Geo.bearing(s.latitude,s.longitude,surferLat,surferLon),heading);
            outside=Math.abs(error)>Math.toDegrees(Math.atan(width/(2*range)));
            f.put("width",width);
            f.put("fovFresh",coverageFresh);
        }
        boolean aligned=aiming&&fresh(now,aimAt,maxAge)&&Double.isFinite(aimTolerance)
            &&Double.isFinite(aimError)&&Double.isFinite(aimHeading)&&Double.isFinite(heading)
            &&Math.abs(Geo.delta(aimHeading,heading))<=aimTolerance;
        f.put("lineColor",panning?(coverageFresh&&outside?RED:ORANGE)
            :(coverageFresh&&!outside&&aligned?GREEN:GREY));
        f.put("panning",panning);
        f.put("coverage",coverageFresh?(outside?"Potentially outside frame":"Inside estimated frame"):"Frame coverage uncertain");
        return f;
    }
}
