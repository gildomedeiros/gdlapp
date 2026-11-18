package au.com.gildo.vtc;

import java.util.*;

final class Settings {
    final Map<String,String> values;
    final String mountHost,receiverHost,cameraHost;
    final int cameraPort;
    final int mountPort,receivePort,receiverPort,panSign,tiltSign;
    final double latitude,longitude,height,landmarkLat,landmarkLon,landmarkHeight,targetHeight;
    final double panMin,panMax,tiltMin,tiltMax,rangeMin,rangeMax,panTolerance,tiltTolerance,reverseSeconds,gpsAge;
    final double acquisitionTriggerMetres,gotoMaxMove,maxPanningSpeed,gpsGapTimeout;
    final double magnitude,tiltOffset,maxGpsSpeed,rideStartKmh,rideEndKmh,rideEndSeconds;
    final int rideStartConfirmations;
    final WaveLine waveLine;
    final boolean autoZoom;
    final String zoomMode;
    final ZoomBands zoomBands;
    final double[] zoomMultipliers;
    final ZoomMapping zoomMapping;
    final double desiredFrameWidthMetres,wideFrameFactor;
    final double zoomSwitchSeconds,manualZoomX;
    static boolean liveZoomKey(String key){return Arrays.asList("zoomMode","zoomBands","desiredFrameWidthMetres","wideFrameFactor","zoomMapping","zoom_scales","zoomSwitchSeconds","manualZoomX").contains(key);}
    boolean onlyZoomChanged(Settings next){
        for(String key:values.keySet())if(!liveZoomKey(key)&&!values.get(key).equals(next.values.get(key)))return false;
        return true;
    }
    static LinkedHashMap<String,String> defaults() {
        LinkedHashMap<String,String> d=new LinkedHashMap<>();
        String[][] rows={
            {"schemaVersion", "2"},
            {"aimingUnits", "metres"},
            {"migrationReferenceMetres", "143"},
            {"zoomMode", "bands"},
            {"zoomBands", 
                "{\n" +
                "  \"bufferMetres\": 5,\n" +
                "  \"bands\": [\n" +
                "    {\"maxDistanceMetres\": 20, \"zoomX\": 1},\n" +
                "    {\"maxDistanceMetres\": 25, \"zoomX\": 1.5},\n" +
                "    {\"maxDistanceMetres\": 30, \"zoomX\": 2},\n" +
                "    {\"maxDistanceMetres\": 50, \"zoomX\": 2.5},\n" +
                "    {\"maxDistanceMetres\": 60, \"zoomX\": 3},\n" +
                "    {\"maxDistanceMetres\": 200, \"zoomX\": 8},\n" +
                "    {\"maxDistanceMetres\": null, \"zoomX\": 10}\n" +
                "  ]\n" +
                "}"},
            {"desiredFrameWidthMetres", "40"},
            {"wideFrameFactor", "1.648"},
            {"zoomMapping", ZoomMapping.DEFAULT},
            {"zoom_scales", "[1.0]"},
            {"zoomSwitchSeconds", "10"},
            {"manualZoomX", ""},
            {"mountHost", "192.168.4.4"},
            {"mountPort", "11880"},
            {"receiverHost", "192.168.4.1"},
            {"receivePort", "5005"},
            {"receiverPort", "5006"},
            {"latitude", "-28.07007000"},
            {"longitude", "153.38730000"},
            {"height", "3"},
            {"targetHeight", "1"},
            {"landmarkLat", "-28.07057000"},
            {"landmarkLon", "153.38709000"},
            {"landmarkHeight", "0"},
            {"panSign", "1"},
            {"tiltSign", "-1"},
            {"panMin", "-170"},
            {"panMax", "170"},
            {"tiltMin", "-45"},
            {"tiltMax", "20"},
            {"rangeMin", "10"},
            {"rangeMax", "2000"},
            {"panToleranceMetres", "2"},
            {"tiltToleranceMetres", "0.5"},
            {"reverseSeconds", "2"},
            {"gpsAge", "2"},
            {"acquisitionTriggerMetres", "3"},
            {"gotoMaxMove", "45"},
            {"cameraHost", "192.168.4.3"},
            {"cameraPort", "8080"},
            {"maxPanningSpeed", "5"},
            {"gpsGapTimeout", "3"},
            {"magnitudeMetres", "2"},
            {"tiltOffset", "0"},
            {"rideStartKmh", "12"},
            {"rideStartConfirmations", "2"},
            {"rideEndKmh", "10.8"},
            {"rideEndSeconds", "2"},
            {"waveLine", "null"},
            {"maxGpsSpeed", "20"},
        };
        for(String[] r:rows)d.put(r[0],r[1]);
        return d;
    }
    Settings(Map<String,String> input) {
        input=AimUnits.migrate(input);
        LinkedHashMap<String,String> d=defaults();
        for(Map.Entry<String,String> e:input.entrySet()) {
            if(java.util.Arrays.asList("framing","fixedOffset","waitForGoto","maxStep","window","observations","minNet","consistency","supports","supportChange","quietRate","quietSeconds","offsetRate","hfov").contains(e.getKey()))continue;
            
            if(!d.containsKey(e.getKey()))throw new IllegalArgumentException("Unknown setting: "+e.getKey());
            d.put(e.getKey(),e.getValue().trim());
        }
        if(!d.get("schemaVersion").equals("2")&&!d.get("schemaVersion").equals("2.0"))throw new IllegalArgumentException("Unsupported settings schemaVersion");
        if(!d.get("aimingUnits").equals("metres"))throw new IllegalArgumentException("aimingUnits must be metres");
        d.put("schemaVersion","2");
        values=Collections.unmodifiableMap(d);
        zoomMode=d.get("zoomMode");
        if(!Arrays.asList("off","bands","auto-fit").contains(zoomMode))throw new IllegalArgumentException("Zoom mode must be off, bands or auto-fit");
        autoZoom=!zoomMode.equals("off");
        zoomBands=new ZoomBands(d.get("zoomBands"));
        d.put("zoomBands",LogJson.encode(ConfigJson.parse(d.get("zoomBands"))));
        zoomMultipliers=ZoomPlan.parse(d.get("zoom_scales"),true);d.put("zoom_scales",LogJson.encode(zoomMultipliers));
        zoomMapping=new ZoomMapping(d.get("zoomMapping"));d.put("zoomMapping",LogJson.encode(ConfigJson.parse(d.get("zoomMapping"))));
        desiredFrameWidthMetres=number("desiredFrameWidthMetres",1,10000);wideFrameFactor=number("wideFrameFactor",0.01,10);
        zoomSwitchSeconds=number("zoomSwitchSeconds",2,3600);
        manualZoomX=optional("manualZoomX",1,65);
        mountHost=d.get("mountHost");receiverHost=d.get("receiverHost");
        cameraHost=d.get("cameraHost");cameraPort=integer("cameraPort",1,65535);
        if(!cameraHost.isEmpty()&&!ipv4(cameraHost))throw new IllegalArgumentException("Camera address must be an IPv4 address, or blank to disable");
        mountPort=integer("mountPort",1,65535);receivePort=integer("receivePort",1024,65535);receiverPort=integer("receiverPort",1,65535);
        latitude=optional("latitude",-90,90);longitude=optional("longitude",-180,180);height=optional("height",0,2000);
        landmarkLat=optional("landmarkLat",-90,90);landmarkLon=optional("landmarkLon",-180,180);landmarkHeight=optional("landmarkHeight",-100,3000);
        targetHeight=number("targetHeight",-10,20);
        panSign=integer("panSign",-1,1);tiltSign=integer("tiltSign",-1,1);
        if(panSign==0||tiltSign==0)throw new IllegalArgumentException("Axis directions must be +1 or -1");
        panMin=number("panMin",-170,0);panMax=number("panMax",0,170);
        tiltMin=number("tiltMin",-89,89);tiltMax=number("tiltMax",-89,89);
        if(panMin>=panMax||tiltMin>=tiltMax)throw new IllegalArgumentException("Movement limits must have a positive span");
        rangeMin=number("rangeMin",5,5000);rangeMax=number("rangeMax",10,10000);
        if(rangeMin>=rangeMax)throw new IllegalArgumentException("Minimum range must be below maximum range");
        panTolerance=number("panToleranceMetres",.001,100);tiltTolerance=number("tiltToleranceMetres",.001,100);
        acquisitionTriggerMetres=number("acquisitionTriggerMetres",0,1000);
        maxPanningSpeed=number("maxPanningSpeed",.01,5);gpsGapTimeout=number("gpsGapTimeout",.3,30);gotoMaxMove=number("gotoMaxMove",1,45);
        reverseSeconds=number("reverseSeconds",0,10);gpsAge=number("gpsAge",.3,5);
        magnitude=number("magnitudeMetres",0,1000);tiltOffset=number("tiltOffset",-10,10);
        rideStartKmh=number("rideStartKmh",.1,100);
        rideStartConfirmations=integer("rideStartConfirmations",1,20);
        rideEndKmh=number("rideEndKmh",.1,100);
        if(rideEndKmh>=rideStartKmh)throw new IllegalArgumentException("Ride end speed must be below ride start speed");
        rideEndSeconds=number("rideEndSeconds",.1,60);
        waveLine=WaveLine.parse(d.get("waveLine"));
        d.put("waveLine",waveLine==null?"null":waveLine.json());
        maxGpsSpeed=number("maxGpsSpeed",1,100);
    }
    double panToleranceDegrees(double range){return AimUnits.degrees(panTolerance,range);}
    double tiltToleranceDegrees(double range){return AimUnits.degrees(tiltTolerance,range);}
    double acquisitionDegrees(double range){return AimUnits.degrees(acquisitionTriggerMetres,range);}
    double magnitudeDegrees(double range){return AimUnits.degrees(magnitude,range);}
    private double number(String k,double lo,double hi) {
        try {
            double x=Double.parseDouble(values.get(k));
            if(!Double.isFinite(x)||x<lo||x>hi)throw new NumberFormatException();
            return x;
        }catch(NumberFormatException e){throw new IllegalArgumentException(k+" must be "+lo+" to "+hi);}
    }
    private double optional(String k,double lo,double hi) { return values.get(k).isEmpty()?Double.NaN:number(k,lo,hi); }
    private int integer(String k,int lo,int hi) {
        double n=number(k,lo,hi);
        if(n!=(int)n)throw new IllegalArgumentException(k+" must be a whole number");
        return (int)n;
    }
    void connectionReady() {
        if(!ipv4(mountHost)||!ipv4(receiverHost))throw new IllegalArgumentException("Enter the mount and LoRa receiver IPv4 addresses");
        if(mountHost.equals(receiverHost))throw new IllegalArgumentException("Mount and receiver must have different IP addresses");
    }
    void siteReady() {
        if(!Geo.valid(latitude,longitude)||!Double.isFinite(height))throw new IllegalArgumentException("Set camera coordinates and lens height above water");
    }
    void calibrationReady() {
        siteReady();
        if(!Geo.valid(landmarkLat,landmarkLon)||!Double.isFinite(landmarkHeight))throw new IllegalArgumentException("Set landmark coordinates and landmark height above the same water level");
        if(Geo.distance(latitude,longitude,landmarkLat,landmarkLon)<20)throw new IllegalArgumentException("Choose a landmark at least 20 metres away");
    }
    static boolean ipv4(String s) {
        String[] p=s.split("\\.",-1);
        if(p.length!=4)return false;
        for(String v:p)try { if(!v.matches("[0-9]{1,3}")||Integer.parseInt(v)>255)return false; }catch(NumberFormatException e){return false;}
        return !s.equals("0.0.0.0")&&!s.equals("255.255.255.255");
    }
}

