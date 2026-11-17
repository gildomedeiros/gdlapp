package au.com.gildo.vtc;

import java.util.*;

final class Settings {
    final Map<String,String> values;
    final String mountHost,receiverHost,cameraHost;
    final int cameraPort;
    final int mountPort,receivePort,receiverPort,panSign,tiltSign;
    final double latitude,longitude,height,landmarkLat,landmarkLon,landmarkHeight,targetHeight;
    final double panMin,panMax,tiltMin,tiltMax,rangeMin,rangeMax,panTolerance,tiltTolerance,reverseSeconds,gpsAge;
    final double acquisitionTriggerDegrees,gotoMaxMove,maxPanningSpeed,gpsGapTimeout;
    final double magnitude,tiltOffset,window,minNet,consistency,supportChange,quietRate,quietSeconds,offsetRate,hfov,maxGpsSpeed;
    final int observations,supports;
    static LinkedHashMap<String,String> defaults() {
        LinkedHashMap<String,String> d=new LinkedHashMap<>();
        String[][] rows={
            {"mountHost",""},{"mountPort","11880"},{"receiverHost","192.168.4.1"},{"receivePort","5005"},{"receiverPort","5006"},
            {"latitude","-28.165513983264137"},{"longitude","153.55045722684628"},{"height",""},{"targetHeight","1"},
            {"landmarkLat",""},{"landmarkLon",""},{"landmarkHeight",""},{"panSign","1"},{"tiltSign","1"},
            {"panMin","-90"},{"panMax","90"},{"tiltMin","-45"},{"tiltMax","20"},{"rangeMin","30"},{"rangeMax","2000"},
            {"panTolerance","0.15"},{"tiltTolerance","0.10"},{"reverseSeconds","2"},{"gpsAge","2"},
            {"acquisitionTriggerDegrees","3"},{"gotoMaxMove","45"},{"cameraHost",""},{"cameraPort","8080"},
            {"maxPanningSpeed","0.5"},{"gpsGapTimeout","3"},{"magnitude","1"},{"tiltOffset","0"},{"hfov",""},
            {"window","1.5"},{"observations","3"},{"minNet","0.3"},{"consistency","0.8"},{"supports","2"},{"supportChange","0.03"},
            {"quietRate","0.08"},{"quietSeconds","3"},{"offsetRate","0.3"},{"maxGpsSpeed","30"}
        };
        for(String[] r:rows)d.put(r[0],r[1]);
        return d;
    }
    Settings(Map<String,String> input) {
        LinkedHashMap<String,String> d=defaults();
        for(Map.Entry<String,String> e:input.entrySet()) {
            if(java.util.Arrays.asList("framing","fixedOffset","waitForGoto","maxStep").contains(e.getKey()))continue;
            if(e.getKey().equals("gotoThreshold")){if(!input.containsKey("acquisitionTriggerDegrees"))d.put("acquisitionTriggerDegrees",e.getValue().trim());continue;}
            if(!d.containsKey(e.getKey()))throw new IllegalArgumentException("Unknown setting: "+e.getKey());
            d.put(e.getKey(),e.getValue().trim());
        }
        values=Collections.unmodifiableMap(d);
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
        panTolerance=number("panTolerance",.01,3);tiltTolerance=number("tiltTolerance",.01,3);
        acquisitionTriggerDegrees=number("acquisitionTriggerDegrees",0,30);
        maxPanningSpeed=number("maxPanningSpeed",.01,5);gpsGapTimeout=number("gpsGapTimeout",.3,30);gotoMaxMove=number("gotoMaxMove",1,45);
        reverseSeconds=number("reverseSeconds",0,10);gpsAge=number("gpsAge",.3,5);
        magnitude=number("magnitude",0,30);tiltOffset=number("tiltOffset",-10,10);
        hfov=optional("hfov",.2,120);
        window=number("window",.3,10);observations=integer("observations",3,40);minNet=number("minNet",.01,15);
        consistency=number("consistency",.5,1);supports=integer("supports",2,20);supportChange=number("supportChange",.001,5);
        quietRate=number("quietRate",.001,5);quietSeconds=number("quietSeconds",.5,30);offsetRate=number("offsetRate",.01,2);
        maxGpsSpeed=number("maxGpsSpeed",1,100);
    }
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

