package au.com.gildo.vtc;

import java.time.Instant;
import java.util.*;

/** Pure KML 2.2 projection of recorded events. Coordinates in KML are longitude, latitude. */
final class KmlDocument {
    static final String FOOTER="</Document></kml>\n";
    private Map<String,Object> settings=Collections.emptyMap(),previousGps;
    private long previousGpsAt;
    private String cameraKey="";
    private Map<String,Object> latestGps;
    private long latestGpsAt;
    static boolean accepts(String type) {
        return type.equals("settings_snapshot")||type.equals("gps_accepted")||type.equals("status_snapshot")
            ||type.equals("goto_captured")||type.equals("goto_completed")||type.equals("goto_cancelled")
            ||type.equals("gps_invalid")||type.equals("gps_socket_closed")||type.equals("disconnected");
    }
    static String header(String name) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document>"
            +"<name>"+xml(name)+"</name><description>VTC session: camera, surfer track, calculated camera aim and saved GoTo targets. "
            +"Use the time slider for aiming history. Map geometry is clamped to terrain; heights above water and tilt are recorded as data. "
            +"Aiming direction comes from landmark calibration and motor counts, not image verification.</description>"
            +style("camera","ff00a5ff",3)+style("surfer","ff00cc33",3)+style("aim","ffffaa00",3)+style("goto","ffff00ff",3)+"\n";
    }
    private static String style(String id,String color,int width) {
        return "<Style id=\""+id+"\"><IconStyle><color>"+color+"</color><scale>0.6</scale></IconStyle>"
            +"<LabelStyle><scale>0.7</scale></LabelStyle><LineStyle><color>"+color+"</color><width>"+width+"</width></LineStyle>"
            +"<PolyStyle><color>18ffaa00</color></PolyStyle></Style>";
    }
    String accept(Map<String,Object> event) {
        String type=String.valueOf(event.get("event"));long at=(long)number(event,"epochMs");
        if(type.equals("settings_snapshot")){settings=map(event.get("settings"));return camera(at);}
        if(type.equals("gps_invalid")||type.equals("gps_socket_closed")||type.equals("disconnected")){
            previousGps=null;latestGps=null;return "";
        }
        if(type.equals("gps_accepted")) {
            Map<String,Object> gps=map(event.get("telemetry"));if(!location(gps,"latitude","longitude"))return "";
            latestGps=gps;latestGpsAt=at;
            String geometry=point(number(gps,"latitude"),number(gps,"longitude"));
            double maxAge=number(settings,"gpsAge");
            if(previousGps!=null&&at>previousGpsAt&&at-previousGpsAt<=maxAge*1000)
                geometry="<MultiGeometry>"+geometry+line(number(previousGps,"latitude"),number(previousGps,"longitude"),
                    number(gps,"latitude"),number(gps,"longitude"))+"</MultiGeometry>";
            String result=placemark("Surfer #"+whole(gps,"sequence"),"surfer",stamp(at),gps,geometry);
            previousGps=gps;previousGpsAt=at;return result;
        }
        if(type.equals("status_snapshot")) {
            settings=map(event.get("settings"));String site=camera(at);
            Map<String,Object> position=map(event.get("position"));
            if(Boolean.FALSE.equals(event.get("positionFresh")))return site;
            double heading=number(position,"headingDegrees"),tilt=number(position,"tiltDegrees");
            if(!siteReady()||!Double.isFinite(heading)||!Double.isFinite(tilt))return site;
            double range=100;String rangeSource="100 m display length; current target range unavailable";
            Map<String,Object> saved=map(event.get("savedGoto"));
            if(Double.isFinite(number(saved,"rangeM"))){range=number(saved,"rangeM");rangeSource="Saved GoTo target range";}
            else if(latestGps!=null&&at>=latestGpsAt&&at-latestGpsAt<=number(settings,"gpsAge")*1000){
                range=Geo.distance(number(settings,"latitude"),number(settings,"longitude"),number(latestGps,"latitude"),number(latestGps,"longitude"));
                rangeSource="Latest fresh surfer range";
            }
            double lat=number(settings,"latitude"),lon=number(settings,"longitude");
            double[] endpoint=destination(lat,lon,heading,range);
            Map<String,Object> data=new LinkedHashMap<>();data.put("headingDegreesTrue",heading);data.put("tiltDegreesFromHorizontal",tilt);
            data.put("lensHeightAboveWaterM",number(settings,"height"));data.put("rayLengthM",range);data.put("rayLengthSource",rangeSource);
            data.put("state",event.get("state"));data.put("moving",position.get("moving"));
            double hfov=number(settings,"hfov");data.put("horizontalFieldOfViewDegrees",hfov);
            String geometry=line(lat,lon,endpoint[0],endpoint[1]);
            if(Double.isFinite(hfov)&&hfov>0&&hfov<180) {
                double[] left=destination(lat,lon,heading-hfov/2,range),right=destination(lat,lon,heading+hfov/2,range);
                geometry="<MultiGeometry>"+geometry+"<Polygon><altitudeMode>clampToGround</altitudeMode><outerBoundaryIs><LinearRing><coordinates>"
                    +coordinate(lat,lon)+" "+coordinate(right[0],right[1])+" "+coordinate(left[0],left[1])+" "+coordinate(lat,lon)
                    +"</coordinates></LinearRing></outerBoundaryIs></Polygon></MultiGeometry>";
            }
            double sample=number(event,"positionSampleMonoMs"),mono=number(event,"monoMs");
            if(Double.isFinite(sample)&&sample>0&&Double.isFinite(mono)&&mono>=sample)at-=(long)(mono-sample);
            return site+placemark(String.format(Locale.US,"Camera aim %.2f° · tilt %+.2f°",heading,tilt),"aim",span(at,at+1000),data,geometry);
        }
        if(type.startsWith("goto_")) {
            Map<String,Object> target=map(event.get("target")),source=map(target.get("source"));
            if(!location(source,"latitude","longitude"))return "";
            Map<String,Object> data=new LinkedHashMap<>(target);data.remove("source");data.put("sourceSequence",source.get("sequence"));
            data.put("sourceLatitude",source.get("latitude"));data.put("sourceLongitude",source.get("longitude"));data.put("event",type);
            if(event.get("reason")!=null)data.put("reason",event.get("reason"));
            String geometry=point(number(source,"latitude"),number(source,"longitude"));
            if(siteReady()) {
                double lat=number(settings,"latitude"),lon=number(settings,"longitude");
                double[] end=destination(lat,lon,number(target,"headingDegrees"),number(target,"rangeM"));
                geometry="<MultiGeometry>"+geometry+line(lat,lon,end[0],end[1])+"</MultiGeometry>";
            }
            boolean landmark="LANDMARK".equals(target.get("kind"));
            return placemark(landmark?(type.equals("goto_captured")?"Return to landmark":type.equals("goto_completed")?"Landmark reached":"Landmark return cancelled"):
                type.equals("goto_captured")?"Saved GoTo target":type.equals("goto_completed")?"GoTo arrived":"GoTo cancelled",
                "goto",stamp(at),data,geometry);
        }
        return "";
    }
    String gap(long lost,long at) {
        previousGps=null;latestGps=null;
        return placemark("KML logging gap","surfer",stamp(at),Collections.singletonMap("droppedEvents",lost),"");
    }
    private boolean siteReady(){return location(settings,"latitude","longitude");}
    private String camera(long at) {
        if(!siteReady())return "";
        String key=settings.get("latitude")+","+settings.get("longitude")+","+settings.get("height");
        if(key.equals(cameraKey))return "";cameraKey=key;
        Map<String,Object> data=new LinkedHashMap<>();data.put("latitude",settings.get("latitude"));data.put("longitude",settings.get("longitude"));
        data.put("lensHeightAboveWaterM",settings.get("height"));data.put("configuredAt",Instant.ofEpochMilli(at).toString());
        return placemark("Camera position","camera","",data,point(number(settings,"latitude"),number(settings,"longitude")));
    }
    static double[] destination(double lat,double lon,double heading,double distance) {
        double angle=distance/6371000.0,b=Math.toRadians(heading),p=Math.toRadians(lat),l=Math.toRadians(lon);
        double next=Math.asin(Math.sin(p)*Math.cos(angle)+Math.cos(p)*Math.sin(angle)*Math.cos(b));
        double longitude=l+Math.atan2(Math.sin(b)*Math.sin(angle)*Math.cos(p),Math.cos(angle)-Math.sin(p)*Math.sin(next));
        return new double[]{Math.toDegrees(next),(Math.toDegrees(longitude)+540)%360-180};
    }
    private static String coordinate(double lat,double lon){return String.format(Locale.US,"%.9f,%.9f,0",lon,lat);}
    private static String point(double lat,double lon){return "<Point><altitudeMode>clampToGround</altitudeMode><coordinates>"+coordinate(lat,lon)+"</coordinates></Point>";}
    private static String line(double lat,double lon,double endLat,double endLon){return "<LineString><tessellate>1</tessellate><altitudeMode>clampToGround</altitudeMode><coordinates>"+coordinate(lat,lon)+" "+coordinate(endLat,endLon)+"</coordinates></LineString>";}
    private static String stamp(long at){return "<TimeStamp><when>"+Instant.ofEpochMilli(at)+"</when></TimeStamp>";}
    private static String span(long start,long end){return "<TimeSpan><begin>"+Instant.ofEpochMilli(start)+"</begin><end>"+Instant.ofEpochMilli(end)+"</end></TimeSpan>";}
    private static String placemark(String name,String style,String time,Map<String,?> data,String geometry) {
        StringBuilder text=new StringBuilder("<Placemark><name>").append(xml(name)).append("</name>").append(time)
            .append("<styleUrl>#").append(style).append("</styleUrl><ExtendedData>");
        for(Map.Entry<String,?> entry:data.entrySet())if(entry.getValue()!=null)
            text.append("<Data name=\"").append(xml(entry.getKey())).append("\"><value>").append(xml(String.valueOf(entry.getValue()))).append("</value></Data>");
        return text.append("</ExtendedData>").append(geometry).append("</Placemark>\n").toString();
    }
    static String xml(String value){return value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;");}
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value){return value instanceof Map?(Map<String,Object>)value:Collections.emptyMap();}
    private static boolean location(Map<String,Object> data,String lat,String lon){return Geo.valid(number(data,lat),number(data,lon));}
    private static long whole(Map<String,Object> data,String key){return (long)number(data,key);}
    private static double number(Map<String,Object> data,String key){
        try{return Double.parseDouble(String.valueOf(data.get(key)));}catch(NumberFormatException e){return Double.NaN;}
    }
}
