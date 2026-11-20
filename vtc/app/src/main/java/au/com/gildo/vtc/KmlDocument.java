package au.com.gildo.vtc;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

/** Pure KML 2.2 projection of recorded events. Coordinates in KML are longitude, latitude. */
final class KmlDocument {
    static final String FOOTER="</Document></kml>\n";
    private static final String UNAVAILABLE="Unavailable";
    private static final long POINT_DELAY_MS=3000, ZOOM_MAX_AGE_MS=5000;
    private static final DateTimeFormatter LABEL_TIME=DateTimeFormatter.ofPattern("HH:mm:ss.SSS",Locale.ROOT);
    private static final DateTimeFormatter LOCAL_TIME=DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSXXX",Locale.ROOT);
    private static final Set<String> EVENTS=new HashSet<>(Arrays.asList("settings_snapshot","gps_accepted","status_snapshot","mount_position",
        "calibration_success","calibration_cleared","aim_start","aim_stopped","aim_cycle","continuous_decision",
        "continuous_rates","framing_observation","aim_target_wait","aim_auto_resumed","aim_paused","gps_pause","gps_resumed",
        "goto_captured","goto_move","goto_completed","goto_cancelled","goto_reversal_hold","goto_transition_stop",
        "camera_zoom_response","camera_zoom_error","camera_zoom_skipped","camera_recording_event","camera_recording_command",
        "gps_invalid","gps_socket_closed","disconnected","session_end"));

    private Map<String,Object> settings=Collections.emptyMap(), previousGps, latestGps;
    private Map<String,Object> position=Collections.emptyMap(), gotoTarget=Collections.emptyMap();
    private final Map<List<Long>,SurferPoint> pending=new LinkedHashMap<>();
    // GPS publication can reach the controller just before the receiver logs its accepted fix.
    private final Map<List<Long>,Map<String,Object>> earlyEvidence=new LinkedHashMap<>();
    private long previousGpsAt, latestGpsAt, previousContinuity, positionAt=-1, zoomAt=-1;
    private long rideId, distanceRideId;
    private double sessionDistance, rideDistance, opticalZoom=Double.NaN, panRate, tiltRate;
    private Object zoomCcapi;
    private String cameraKey="", state="Disconnected", waitReason="", panCommand="Stopped";
    private boolean aiming, riding, zoomUnavailable;
    private OffshoreHeading offshore;
    private ZoneId localZone=ZoneId.systemDefault();

    private static final class SurferPoint {
        final long at, clock;
        final ZoneId zone;
        final String geometry;
        final Map<String,Object> data;
        SurferPoint(long at,long clock,ZoneId zone,String geometry,Map<String,Object> data) {
            this.at=at;this.clock=clock;this.zone=zone;this.geometry=geometry;this.data=data;
        }
    }

    static boolean accepts(String type) { return EVENTS.contains(type); }
    static String header(String name) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document>"
            +"<name>"+xml(name)+"</name><description>VTC session: camera, surfer track, calculated camera aim and saved GoTo targets. "
            +"Use the time slider for aiming history. Map geometry is clamped to terrain; heights above water and tilt are recorded as data. "
            +"Aiming direction comes from landmark calibration and motor counts, not image verification.</description>"
            +style("camera","ff00a5ff",3)+style("surfer","ff00cc33",3)+style("aim","ffffaa00",3)+style("goto","ffff00ff",3)+style("event","ff00e6ff",2)+"\n";
    }
    private static String style(String id,String color,int width) {
        return "<Style id=\""+id+"\"><IconStyle><color>"+color+"</color><scale>0.6</scale></IconStyle>"
            +"<LabelStyle><scale>0.7</scale></LabelStyle><LineStyle><color>"+color+"</color><width>"+width+"</width></LineStyle>"
            +"<PolyStyle><color>18ffaa00</color></PolyStyle></Style>";
    }
    String accept(Map<String,Object> event) {
        String type=String.valueOf(event.get("event"));
        long at=whole(event,"epochMs"), clock=clock(event);
        Object timestamp=event.get("timestamp");
        if(timestamp instanceof String)try{localZone=OffsetDateTime.parse((String)timestamp).getOffset();}
        catch(DateTimeParseException ignored){}
        // Include decision records emitted after a fix while retaining that fix's own time and position.
        String fragment=project(type,event,at,clock);
        return flush(clock,false)+fragment;
    }

    private String project(String type,Map<String,Object> event,long at,long clock) {
        switch(type) {
            case "settings_snapshot":
                updateSettings(map(event.get("settings")));
                return camera(at);
            case "gps_accepted":
                return surfer(event,at,clock);
            case "aim_start":
                aiming=true;waitReason="";panCommand="Waiting for first correction";state="Aiming";
                return marker("Start aiming",event,at,map(event.get("gps")));
            case "aim_stopped":
            case "session_end":
                aiming=false;panRate=tiltRate=0;state="Stopped";panCommand="Stopped";
                return finishRide(event,at)+marker(type.equals("aim_stopped")?"Stop aiming":"Session ended",event,at,latestGps);
            case "gps_invalid":
            case "gps_socket_closed":
            case "disconnected":
                previousGps=null;latestGps=null;
                return finishRide(event,at);
            case "calibration_cleared":
                position=Collections.emptyMap();positionAt=-1;
                return "";
            case "calibration_success":
                return marker("Landmark calibrated",event,at,map(event.get("position")));
            case "mount_position":
                position=map(event.get("position"));positionAt=clock;
                return "";
            case "framing_observation":
                return observeFraming(event,at,clock);
            case "aim_cycle":
                observeAim(event,clock);
                return "";
            case "continuous_decision":
                observeContinuous(event);
                return "";
            case "continuous_rates":
                panRate=number(event,"panDegreesPerSecond");tiltRate=number(event,"tiltDegreesPerSecond");
                panCommand=direction(panRate,"Stopped");
                return "";
            case "aim_target_wait":
            case "aim_paused":
            case "gps_pause":
            case "goto_reversal_hold":
            case "goto_transition_stop":
                waitReason=String.valueOf(event.getOrDefault("reason",event.getOrDefault("detail","Direction reversal hold")));
                Map<String,Object> waitGps=map(event.get("gps"));
                if(!waitGps.isEmpty())mergeEvidence(waitGps,Collections.singletonMap("waitReason",waitReason));
                if(type.equals("gps_pause")||type.equals("aim_target_wait")||type.equals("aim_paused"))
                    return finishRide(event,at)+marker("Aiming wait",event,at,latestGps);
                return type.equals("goto_reversal_hold")?"":marker("Aiming wait",event,at,latestGps);
            case "aim_auto_resumed":
            case "gps_resumed":
                waitReason="";
                return marker("Aiming resumed",event,at,map(event.get("gps")));
            case "camera_zoom_response":
                zoomAt=clock;zoomCcapi=event.get("zoom");opticalZoom=number(event,"opticalZoomX");zoomUnavailable=false;
                return "";
            case "camera_zoom_error":
            case "camera_zoom_skipped":
                if(type.equals("camera_zoom_error")||"Camera disabled".equals(event.get("reason"))||"No connected Wi-Fi".equals(event.get("reason")))
                    zoomUnavailable=true;
                return "";
            case "camera_recording_event":
                return marker("Recording "+event.get("action")+" observed",event,at,Collections.emptyMap());
            case "camera_recording_command":
                return marker("Recording command response",event,at,Collections.emptyMap());
            default:
                break;
        }
        if(type.equals("status_snapshot")) {
            Map<String,Object> currentSettings=map(event.get("settings"));
            if(!currentSettings.isEmpty())updateSettings(currentSettings);
            String site=camera(at);
            Map<String,Object> position=map(event.get("position"));
            state=String.valueOf(event.getOrDefault("state",state));
            if(state.equals("WAITING GPS")||state.equals("WAITING TARGET")||state.equals("STOP UNCONFIRMED"))
                waitReason=String.valueOf(event.getOrDefault("message",state));
            if(Boolean.FALSE.equals(event.get("positionFresh"))){this.position=Collections.emptyMap();positionAt=-1;return site;}
            long sampleClock=event.containsKey("positionSampleMonoMs")?whole(event,"positionSampleMonoMs"):clock;
            if(sampleClock>=positionAt){this.position=position;positionAt=sampleClock;}
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
            copy(event,data,"panRateDegreesPerSecond","tiltRateDegreesPerSecond","message","gpsFresh","waitingForTargetLimits","positionSampleMonoMs");
            String geometry=line(lat,lon,endpoint[0],endpoint[1]);
            double sample=number(event,"positionSampleMonoMs"),mono=number(event,"monoMs");
            if(Double.isFinite(sample)&&sample>0&&Double.isFinite(mono)&&mono>=sample)at-=(long)(mono-sample);
            return site+placemark(String.format(Locale.US,"Camera aim %.2f° · tilt %+.2f°",heading,tilt)+" · "+label(at,localZone),"aim",span(at,at+1000),timed(event,at,data),geometry);
        }
        if(type.startsWith("goto_")) {
            Map<String,Object> target=map(event.get("target")),source=map(target.get("source"));
            boolean captured=type.equals("goto_captured"), moved=type.equals("goto_move");
            if(captured||moved){
                gotoTarget=target;
                waitReason="";
                if(captured){
                    panCommand="Waiting to move";
                    Map<String,Object> gps=map(event.get("gps"));
                    if(!gps.isEmpty()){
                        Map<String,Object> capturedData=new LinkedHashMap<>();
                        capturedData.put("aimingMode","GoTo");
                        capturedData.put("goToSourceSequence",gps.get("sequence"));
                        mergeEvidence(gps,capturedData);
                    }
                }else panCommand=direction(number(event,"panStepDegrees"),"Pan stopped");
            }else{gotoTarget=Collections.emptyMap();panCommand="Stopped";panRate=tiltRate=0;waitReason="";}
            Map<String,Object> data=new LinkedHashMap<>(target);data.remove("source");
            data.put("sourceSequence",source.get("sequence"));data.put("sourceLatitude",source.get("latitude"));
            data.put("sourceLongitude",source.get("longitude"));
            copy(event,data,"reason","panStepDegrees","tiltStepDegrees","elapsedMs","acquisitionTriggerMetres");
            String geometry=location(source,"latitude","longitude")?point(number(source,"latitude"),number(source,"longitude")):"";
            if(siteReady()&&Double.isFinite(number(target,"rangeM"))&&Double.isFinite(number(target,"headingDegrees"))) {
                double lat=number(settings,"latitude"),lon=number(settings,"longitude");
                double[] end=destination(lat,lon,number(target,"headingDegrees"),number(target,"rangeM"));
                geometry="<MultiGeometry>"+geometry+line(lat,lon,end[0],end[1])+"</MultiGeometry>";
            }
            boolean landmark="LANDMARK".equals(target.get("kind")), seaward="OFFSHORE".equals(target.get("kind"));
            String name=landmark?(captured?"Return to landmark":moved?"Landmark GoTo started":type.equals("goto_completed")?"Landmark reached":"Landmark return cancelled"):
                seaward?(captured?"Saved offshore GoTo":moved?"Offshore GoTo started":type.equals("goto_completed")?"Offshore heading reached":"Offshore GoTo cancelled"):
                captured?"Saved GoTo target":moved?"GoTo started":type.equals("goto_completed")?"GoTo arrived":"GoTo cancelled";
            return placemark(name+" · "+label(at,localZone),"goto",stamp(at),timed(event,at,data),geometry);
        }
        return "";
    }

    private void updateSettings(Map<String,Object> next) {
        double oldHeading=number(settings,"offshoreHeadingDegrees"), heading=number(next,"offshoreHeadingDegrees");
        if(Double.compare(heading,oldHeading)!=0) {
            previousGps=null;
            offshore=Double.isFinite(heading)?new OffshoreHeading(heading):null;
        }
        if(!Objects.equals(next.get("cameraHost"),settings.get("cameraHost"))
            ||!Objects.equals(next.get("cameraPort"),settings.get("cameraPort"))){
            zoomAt=-1;zoomCcapi=null;opticalZoom=Double.NaN;
        }
        settings=next;
    }

    private String surfer(Map<String,Object> event,long at,long clock) {
        Map<String,Object> gps=map(event.get("telemetry"));
        if(!location(gps,"latitude","longitude"))return "";
        double lat=number(gps,"latitude"), lon=number(gps,"longitude");
        long received=event.containsKey("receivedAtMs")?whole(event,"receivedAtMs"):clock;
        long sampleAt=at-Math.max(0,clock-received), continuity=whole(event,"continuity");
        latestGps=gps;latestGpsAt=sampleAt;
        Map<String,Object> data=timed(event,sampleAt,new LinkedHashMap<>(gps));
        data.put("gpsAgeMs",Math.max(0,clock-received));
        data.put("gpsContinuity",continuity);
        data.put("gpsSpeedKmh",UNAVAILABLE);
        data.put("qualifyingSpeedKmh",UNAVAILABLE);
        data.put("nonSeawardStepMetres",UNAVAILABLE);
        String geometry=point(lat,lon);
        if(previousGps!=null&&continuity==previousContinuity&&received>previousGpsAt
            &&received-previousGpsAt<=number(settings,"gpsAge")*1000) {
            double priorLat=number(previousGps,"latitude"),priorLon=number(previousGps,"longitude");
            double dt=Double.isFinite(number(gps,"senderMs"))&&Double.isFinite(number(previousGps,"senderMs"))
                ? (whole(gps,"senderMs")-whole(previousGps,"senderMs"))&0xffffffffL : received-previousGpsAt;
            if(dt>0&&dt<=number(settings,"gpsAge")*1000)data.put("gpsSpeedKmh",Geo.distance(priorLat,priorLon,lat,lon)*3600/dt);
            if(offshore!=null) {
                double step=offshore.qualifyingDistance(priorLat,priorLon,lat,lon);
                sessionDistance+=step;
                data.put("nonSeawardStepMetres",step);
            }
            geometry="<MultiGeometry>"+geometry+line(priorLat,priorLon,lat,lon)+"</MultiGeometry>";
        }
        data.put("nonSeawardSessionMetres",offshore==null?UNAVAILABLE:sessionDistance);
        data.put("rideActive",aiming?UNAVAILABLE:false);
        data.put("appliedOffsetMetres",aiming?UNAVAILABLE:0);
        addCameraData(data,lat,lon,received);
        previousGps=gps;previousGpsAt=received;previousContinuity=continuity;
        SurferPoint point=new SurferPoint(sampleAt,clock,localZone,geometry,data);
        List<Long> key=fixKey(gps,received,continuity);
        Map<String,Object> early=earlyEvidence.remove(key);
        if(early!=null)data.putAll(early);
        if(!aiming)return surferPlacemark(point);
        pending.put(key,point);
        return "";
    }

    private void addCameraData(Map<String,Object> data,double lat,double lon,long clock) {
        double range=siteReady()?Geo.distance(number(settings,"latitude"),number(settings,"longitude"),lat,lon):Double.NaN;
        double bearing=range>=.1?Geo.bearing(number(settings,"latitude"),number(settings,"longitude"),lat,lon):Double.NaN;
        putNumber(data,"surferDistanceMetres",range);
        putNumber(data,"surferBearingDegrees",bearing);
        double heading=number(position,"headingDegrees"), tilt=number(position,"tiltDegrees");
        boolean stale=positionAt<0||clock<positionAt||clock-positionAt>2000;
        putNumber(data,"cameraHeadingDegrees",stale?Double.NaN:heading);
        putNumber(data,"cameraTiltDegrees",stale?Double.NaN:tilt);
        data.put("cameraPositionAgeMs",positionAt<0||clock<positionAt?UNAVAILABLE:clock-positionAt);
        data.put("cameraPositionStale",stale);
        if(!stale&&Double.isFinite(bearing)&&Double.isFinite(heading)){
            double error=Geo.delta(bearing,heading);
            putNumber(data,"riderPanGapMetres",metresError(error,range));
            data.put("riderSide",direction(error,"Centred"));
        }
        data.put("mountMoving",stale?UNAVAILABLE:position.getOrDefault("moving",UNAVAILABLE));
        data.put("panDirection",stale?UNAVAILABLE:Boolean.FALSE.equals(position.get("moving"))?"Stopped":panCommand);
        data.put("panRateDegreesPerSecond",panRate);
        data.put("tiltRateDegreesPerSecond",tiltRate);
        data.put("aimingMode",!gotoTarget.isEmpty()?"GoTo":aiming?"Tracking":"Idle");
        data.put("mountState",state);
        data.put("waitReason",waitReason);
        if(!gotoTarget.isEmpty()){
            double target=number(gotoTarget,"headingDegrees"), targetTilt=number(gotoTarget,"tiltDegrees");
            putNumber(data,"targetHeadingDegrees",target);
            putNumber(data,"targetTiltDegrees",targetTilt);
            putNumber(data,"panErrorMetres",stale?Double.NaN:metresError(Geo.delta(target,heading),range));
            putNumber(data,"tiltErrorMetres",stale?Double.NaN:metresError(targetTilt-tilt,range));
            data.put("goToSourceSequence",map(gotoTarget.get("source")).get("sequence"));
        }
        long zoomAge=clock-zoomAt;
        boolean zoomStale=zoomUnavailable||zoomAt<0||zoomAge<0||zoomAge>ZOOM_MAX_AGE_MS;
        data.put("confirmedCcapiZoom",zoomCcapi==null?UNAVAILABLE:zoomCcapi);
        putNumber(data,"confirmedOpticalZoomX",opticalZoom);
        data.put("zoomReadbackAgeMs",zoomAt<0||zoomAge<0?UNAVAILABLE:zoomAge);
        data.put("zoomReadbackStale",zoomStale);
        double factor=number(settings,"wideFrameFactor");
        putNumber(data,"estimatedFrameWidthMetres",zoomStale||opticalZoom<=0?Double.NaN:range*factor/opticalZoom);
    }

    private String observeFraming(Map<String,Object> event,long at,long clock) {
        Map<String,Object> gps=map(event.get("gps")), frame=map(event.get("framing"));
        boolean ride=Boolean.TRUE.equals(frame.get("rideActive"));
        boolean changed=ride!=riding;
        riding=ride;
        if(changed&&ride)rideId++;
        Map<String,Object> data=new LinkedHashMap<>();
        putNumber(data,"qualifyingSpeedKmh",number(frame,"qualifyingSpeedKmh"));
        copy(frame,data,"rideActive","appliedOffsetMetres","sidewaysMetres","startConfirmations");
        data.put("framingReason",frame.get("reason"));
        data.put("rideId",ride?rideId:0);
        if(aiming)mergeEvidence(gps,data);
        long sample=whole(gps,"receivedAtMs");
        long fixAt=sample>0?at-Math.max(0,clock-sample):at;
        return changed?marker((ride?"Wave ride started":"Wave ride ended")+" #"+rideId,event,fixAt,gps):"";
    }

    private void observeAim(Map<String,Object> event,long clock) {
        Map<String,Object> gps=map(event.get("gps")),data=new LinkedHashMap<>();
        double range=number(event,"rangeM"), heading=number(event,"cameraHeadingDegrees");
        boolean hold=Boolean.TRUE.equals(event.get("holdPan"));
        data.put("aimingMode",hold?"Hold pan":"Tracking");
        data.put("holdPan",hold);
        if(hold)data.put("waitReason",event.containsKey("panRuleAction")?"Pan rule holds the opposing direction":"Rider moving toward current camera aim");
        if("continue_existing_pan".equals(event.get("panRuleAction")))data.put("aimingMode","Continue existing pan");
        copy(event,data,"panRule","panRuleAction","targetHeadingDegrees","targetTiltDegrees","cameraHeadingDegrees","panToleranceMetres",
            "tiltToleranceMetres","acquisitionTriggerMetres","panErrorDegrees","tiltErrorDegrees");
        data.put("cameraTiltDegrees",event.get("tiltDegrees"));
        data.put("cameraPositionStale",false);
        long sample=whole(event,"positionSampleMonoMs");
        if(sample>0)data.put("cameraPositionAgeAtAimingDecisionMs",Math.max(0,clock-sample));
        putNumber(data,"riderPanGapMetres",metresError(Geo.delta(number(event,"bearingDegrees"),heading),range));
        data.put("riderSide",direction(Geo.delta(number(event,"bearingDegrees"),heading),"Centred"));
        putNumber(data,"panErrorMetres",metresError(number(event,"panErrorDegrees"),range));
        putNumber(data,"tiltErrorMetres",metresError(number(event,"tiltErrorDegrees"),range));
        long received=whole(gps,"receivedAtMs");
        if(received>0)data.put("gpsAgeAtAimingDecisionMs",Math.max(0,clock-received));
        mergeEvidence(gps,data);
    }

    private void observeContinuous(Map<String,Object> event) {
        Map<String,Object> gps=map(event.get("gps")),data=new LinkedHashMap<>();
        double pan=number(event,"panRateDegreesPerSecond"),tilt=number(event,"tiltRateDegreesPerSecond");
        boolean hold=Boolean.TRUE.equals(event.get("holdPan"));
        boolean reversal=Boolean.TRUE.equals(event.get("panReversalBlocked"))||Boolean.TRUE.equals(event.get("tiltReversalBlocked"));
        String reason=hold?"Rider moving toward current camera aim":reversal?"Direction reversal hold":
            pan==0&&tilt==0?"Within aiming tolerance / restart boundary":"";
        data.put("aimingMode",hold?"Hold pan":pan==0&&tilt==0?"Hold":"Continuous correction");
        data.put("waitReason",reason);
        data.put("requestedPanDirection",direction(pan,"Stopped"));
        copy(event,data,"panReversalBlocked","tiltReversalBlocked");
        putNumber(data,"requestedPanRateDegreesPerSecond",pan);
        putNumber(data,"requestedTiltRateDegreesPerSecond",tilt);
        mergeEvidence(gps,data);
    }

    private void mergeEvidence(Map<String,Object> gps,Map<String,Object> data) {
        if(!gps.containsKey("sequence"))return;
        List<Long> key=fixKey(gps,whole(gps,"receivedAtMs"),whole(gps,"continuity"));
        SurferPoint point=pending.get(key);
        if(point!=null)point.data.putAll(data);
        else {
            earlyEvidence.computeIfAbsent(key,unused->new LinkedHashMap<>()).putAll(data);
            if(earlyEvidence.size()>64)earlyEvidence.remove(earlyEvidence.keySet().iterator().next());
        }
    }
    private static List<Long> fixKey(Map<String,Object> gps,long received,long continuity) {
        return Arrays.asList(whole(gps,"sequence"),whole(gps,"senderMs"),received,continuity);
    }

    private String flush(long clock,boolean all) {
        StringBuilder out=new StringBuilder();
        Iterator<SurferPoint> points=pending.values().iterator();
        while(points.hasNext()) {
            SurferPoint point=points.next();
            if(!all&&clock-point.clock<POINT_DELAY_MS&&pending.size()<=128)break;
            out.append(surferPlacemark(point));points.remove();
        }
        return out.toString();
    }
    String finish(){return flush(0,true);}

    private String surferPlacemark(SurferPoint point) {
        Map<String,Object> data=point.data;
        long ride=whole(data,"rideId");
        if(ride>0){
            if(distanceRideId!=ride){distanceRideId=ride;rideDistance=0;}
            else if(Double.isFinite(number(data,"nonSeawardStepMetres")))rideDistance+=number(data,"nonSeawardStepMetres");
            data.put("nonSeawardRideMetres",rideDistance);
        }
        return placemark("Surfer #"+whole(data,"sequence")+" · "+label(point.at,point.zone),
            "surfer",stamp(point.at),data,point.geometry);
    }

    private String finishRide(Map<String,Object> event,long at) {
        if(!riding)return "";
        riding=false;
        return marker("Wave ride ended #"+rideId,event,at,latestGps);
    }

    String gap(long lost,long at) {
        previousGps=null;latestGps=null;earlyEvidence.clear();
        return finish()+placemark("KML logging gap · "+label(at,localZone),"event",stamp(at),
            Collections.singletonMap("droppedEvents",lost),"");
    }

    private String marker(String name,Map<String,Object> event,long at,Map<String,Object> location) {
        Map<String,Object> data=new LinkedHashMap<>();
        copy(event,data,"reason","detail","method","action","code","status","url","body","aimingSession");
        if(name.startsWith("Wave ride"))copy(map(event.get("framing")),data,"qualifyingSpeedKmh","appliedOffsetMetres","reason");
        if(location!=null)copy(location,data,"sequence","latitude","longitude","headingDegrees","tiltDegrees");
        if(location==null||!location(location,"latitude","longitude"))location=settings;
        String geometry=location(location,"latitude","longitude")?point(number(location,"latitude"),number(location,"longitude")):"";
        return placemark(name+" · "+label(at,localZone),"event",stamp(at),timed(event,at,data),geometry);
    }
    private boolean siteReady(){return location(settings,"latitude","longitude");}
    private String camera(long at) {
        if(!siteReady())return "";
        String key=settings.get("latitude")+","+settings.get("longitude")+","+settings.get("height")+","+
            settings.get("landmarkLat")+","+settings.get("landmarkLon")+","+settings.get("landmarkHeight")+","+settings.get("offshoreHeadingDegrees");
        if(key.equals(cameraKey))return "";
        cameraKey=key;
        Map<String,Object> data=new LinkedHashMap<>();
        copy(settings,data,"latitude","longitude","landmarkLat","landmarkLon","landmarkHeight","offshoreHeadingDegrees");
        data.put("lensHeightAboveWaterM",number(settings,"height"));data.put("configuredAt",Instant.ofEpochMilli(at).toString());
        String points=point(number(settings,"latitude"),number(settings,"longitude"));
        String result=placemark("Camera position","camera","",data,points);
        if(location(settings,"landmarkLat","landmarkLon"))
            result+=placemark("Landmark","camera","",data,point(number(settings,"landmarkLat"),number(settings,"landmarkLon")));
        if(offshore!=null){
            double[] arrow=offshore.seawardArrow(number(settings,"latitude"),number(settings,"longitude"));
            result+=placemark("Offshore heading","camera","",data,line(arrow[0],arrow[1],arrow[2],arrow[3]));
        }
        return result;
    }
    private static long clock(Map<String,Object> event){return event.containsKey("monoMs")?whole(event,"monoMs"):whole(event,"epochMs");}
    private Map<String,Object> timed(Map<String,Object> event,long at,Map<String,Object> data) {
        data.put("localTimestamp",LOCAL_TIME.format(Instant.ofEpochMilli(at).atZone(localZone)));
        data.put("utcTimestamp",Instant.ofEpochMilli(at).toString());
        copy(event,data,"record","event");
        return data;
    }
    private static String label(long at,ZoneId zone){return LABEL_TIME.format(Instant.ofEpochMilli(at).atZone(zone));}
    private static void copy(Map<String,Object> source,Map<String,Object> destination,String... keys) {
        for(String key:keys)if(source.get(key)!=null)destination.put(key,source.get(key));
    }
    private static void putNumber(Map<String,Object> data,String key,double value){
        data.put(key,Double.isFinite(value)?value:UNAVAILABLE);
    }
    private static double metresError(double angle,double range) {
        // Preserve the correction sign for targets at or beyond a right angle.
        return Math.abs(angle)<90?AimUnits.metres(angle,range):Math.toRadians(angle)*range;
    }
    private static String direction(double change,String zero) {
        return !Double.isFinite(change)?UNAVAILABLE:change>0?"Clockwise":change<0?"Anticlockwise":zero;
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
