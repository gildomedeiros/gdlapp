from pathlib import Path
import json
ROOT=Path('C:/Users/gildo/.codex/worktrees/vt-28/gdlapp/vt')
OUT=Path('C:/Users/gildo/gdlapp/cam3/exports/vt40/files')
PK='SampleCode-V5/android-sdk-v5-uxsdk/src/main/java/dji/v5/ux/sample/showcase/defaultlayout/'
P=PK+'aiming/'
TOUCHED=set()
def read(n): return (OUT/n).read_text(encoding='utf-8') if n in TOUCHED else (ROOT/n).read_text(encoding='utf-8-sig')
def write(n,s):
 p=OUT/n;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(s,encoding='utf-8');TOUCHED.add(n)
def edit(n,a,b):
 s=read(n);assert a in s,(n,a[:100]);write(n,s.replace(a,b))
def java(n,s): write(P+n+'.java','package dji.v5.ux.sample.showcase.defaultlayout.aiming;\n'+s)
java('ShorelineGeometry',r'''
/** Local metre coordinates: A-to-B alongshore plus the explicitly chosen sea normal. */
public final class ShorelineGeometry {
    public static final double EARTH=6371000;
    public final double aLat,aLon,bLat,bLon,alongNorth,alongEast,seaNorth,seaEast;
    public final String seaSide;
    public ShorelineGeometry(double aLat,double aLon,double bLat,double bLon,String seaSide) {
        validPoint(aLat,aLon);validPoint(bLat,bLon);
        double length=YawAimingMath.distance(aLat,aLon,bLat,bLon);
        if(length<30 || length>10000)throw new IllegalArgumentException("Shoreline A/B spacing must be 30–10000 m; use 50–100 m for capture");
        if(!"leftOfAToB".equals(seaSide)&&!"rightOfAToB".equals(seaSide))throw new IllegalArgumentException("seaSide must be leftOfAToB or rightOfAToB");
        this.aLat=aLat;this.aLon=aLon;this.bLat=bLat;this.bLon=bLon;this.seaSide=seaSide;
        double bearing=Math.toRadians(YawAimingMath.bearingToTarget(aLat,aLon,bLat,bLon));
        alongNorth=Math.cos(bearing);alongEast=Math.sin(bearing);
        double sign="leftOfAToB".equals(seaSide)?1:-1;
        seaNorth=sign*alongEast;seaEast=-sign*alongNorth;
    }
    public static void validPoint(double lat,double lon) {
        if(!Double.isFinite(lat)||!Double.isFinite(lon)||Math.abs(lat)>85||Math.abs(lon)>180)
            throw new IllegalArgumentException("Coordinates require latitude -85..85, longitude -180..180");
    }
    public double[] offset(double lat,double lon,double originLat,double originLon) {
        return new double[]{Math.toRadians(lat-originLat)*EARTH,
            Math.toRadians(YawAimingMath.shortestHeadingError(lon,originLon))*EARTH*Math.cos(Math.toRadians(originLat))};
    }
    public double[] point(double lat,double lon,double north,double east) {
        return new double[]{lat+Math.toDegrees(north/EARTH),
            ((lon+Math.toDegrees(east/(EARTH*Math.cos(Math.toRadians(lat))))+540)%360)-180};
    }
    public static double segmentDistance(double startNorth,double startEast,double endNorth,double endEast) {
        double n=endNorth-startNorth,e=endEast-startEast,den=n*n+e*e;
        double t=den==0?0:Math.max(0,Math.min(1,-(startNorth*n+startEast*e)/den));
        return Math.hypot(startNorth+t*n,startEast+t*e);
    }
}
''')
java('ShorelinePositioning',r'''
/** Immutable session preset. Positive separation is on the selected filming side. */
public final class ShorelinePositioning {
    public final ShorelineGeometry shore;
    public final String mode,side,shorelineId;
    public final double axisNorth,axisEast,otherNorth,otherEast,tolerance,retreatRadius;
    public ShorelinePositioning(ShorelineGeometry shore,String id,String mode,String side,double tolerance,double retreatRadius) {
        if(!"front".equals(mode)&&!"sideways".equals(mode))throw new IllegalArgumentException("mode must be front or sideways");
        if(!"leftOfAToB".equals(side)&&!"rightOfAToB".equals(side))throw new IllegalArgumentException("sidewaysSide must be leftOfAToB or rightOfAToB");
        if(!Double.isFinite(tolerance)||tolerance<1||tolerance>20)throw new IllegalArgumentException("alignmentToleranceMetres 1–20");
        this.shore=shore;this.shorelineId=id;this.mode=mode;this.side=side;this.tolerance=tolerance;this.retreatRadius=retreatRadius;
        // Sideways side names mean left/right while looking seaward, independent of A/B ordering.
        if(mode.equals("front")) {axisNorth=-shore.seaNorth;axisEast=-shore.seaEast;}
        else {double sign=side.equals("leftOfAToB")?1:-1;axisNorth=sign*shore.seaEast;axisEast=-sign*shore.seaNorth;}
        otherNorth=-axisEast;otherEast=axisNorth;
    }
    public double[] measures(AimingSession.Inputs in) {
        double[] d=shore.offset(in.lat,in.lon,in.target.lat,in.target.lon);
        return new double[]{d[0]*axisNorth+d[1]*axisEast,d[0]*otherNorth+d[1]*otherEast};
    }
    public double[] destination(AimingSession.Inputs in,double separation) {
        return shore.point(in.target.lat,in.target.lon,axisNorth*separation,axisEast*separation);
    }
    public boolean safePath(AimingSession.Inputs in,double lat,double lon,double centralLat,double centralLon) {
        if(YawAimingMath.distance(lat,lon,centralLat,centralLon)>ComeToMeSettings.EXCURSION_STOP ||
                YawAimingMath.distance(in.lat,in.lon,centralLat,centralLon)>=ComeToMeSettings.EXCURSION_STOP)return false;
        double[] start=shore.offset(in.lat,in.lon,in.target.lat,in.target.lon),end=shore.offset(lat,lon,in.target.lat,in.target.lon);
        return ShorelineGeometry.segmentDistance(start[0],start[1],end[0],end[1])>=retreatRadius;
    }
    public double[] velocity(AimingSession.Inputs in,double lat,double lon,double speed) {
        double b=Math.toRadians(YawAimingMath.bearingToTarget(in.lat,in.lon,lat,lon)-in.heading);
        return new double[]{speed*Math.cos(b),speed*Math.sin(b)};
    }
}
''')
java('VtJsonFields',r'''
import com.google.gson.*;
import java.util.*;
final class VtJsonFields {
    static void keys(JsonObject o,String... keys) {
        Set<String> allowed=new HashSet<>(Arrays.asList(keys));
        for(String k:o.keySet())if(!allowed.contains(k))throw new IllegalArgumentException("Unknown setting: "+k);
        for(String k:keys)if(!o.has(k))throw new IllegalArgumentException("Missing setting: "+k);
    }
    static double number(JsonObject o,String k,double min,double max) {
        JsonElement e=o.get(k);
        if(e==null||!e.isJsonPrimitive()||!e.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException(k+" must be a number");
        double v=e.getAsDouble();if(!Double.isFinite(v)||v<min||v>max)throw new IllegalArgumentException(k+" must be "+min+".."+max);return v;
    }
    static String string(JsonObject o,String k) {
        JsonElement e=o.get(k);if(e==null||!e.isJsonPrimitive()||!e.getAsJsonPrimitive().isString())throw new IllegalArgumentException(k+" must be a string");
        String s=e.getAsString();if(s.length()>200)throw new IllegalArgumentException(k+" is too long");return s;
    }
    static boolean bool(JsonObject o,String k) {
        JsonElement e=o.get(k);if(e==null||!e.isJsonPrimitive()||!e.getAsJsonPrimitive().isBoolean())throw new IllegalArgumentException(k+" must be a boolean");return e.getAsBoolean();
    }
    static JsonObject object(JsonObject o,String k) {
        JsonElement e=o.get(k);if(e==null||!e.isJsonObject())throw new IllegalArgumentException(k+" must be an object");return e.getAsJsonObject();
    }
}
''')
java('VtSettingsConfig',r'''
import com.google.gson.*;
/** VT 4.0: tuning is loaded from one JSON source, never preferences or a tuning form. */
public final class VtSettingsConfig {
    public final ComeToMeSettings movement;
    public final String mode,shorelineId,sidewaysSide;
    public final double alignmentTolerance;
    private VtSettingsConfig(ComeToMeSettings movement,String mode,String id,String side,double tolerance) {
        this.movement=movement;this.mode=mode;shorelineId=id;sidewaysSide=side;alignmentTolerance=tolerance;
    }
    public static VtSettingsConfig parse(String raw,RetreatSettings retreat) {
        JsonObject o=StrictConfigJson.object(raw);
        VtJsonFields.keys(o,"version","enabled","filmingSeparationMetres","reapproachMarginMetres","maxMovementSpeedMetresPerSecond",
            "lineupWidthMetres","rideStartKmh","rideDurationSeconds","noRideTimeoutSeconds","maxYawRateDegreesPerSecond",
            "yawAccelerationDegreesPerSecondSquared","mode","shorelineId","sidewaysSide","alignmentToleranceMetres");
        VtJsonFields.number(o,"version",1,1);
        String mode=VtJsonFields.string(o,"mode"),id=VtJsonFields.string(o,"shorelineId"),side=VtJsonFields.string(o,"sidewaysSide");
        if(!mode.equals("front")&&!mode.equals("sideways"))throw new IllegalArgumentException("mode must be front or sideways");
        if(!side.equals("left")&&!side.equals("right"))throw new IllegalArgumentException("sidewaysSide must be left or right, looking seaward");
        ComeToMeSettings c=new ComeToMeSettings(VtJsonFields.bool(o,"enabled"),VtJsonFields.number(o,"filmingSeparationMetres",10,200),
            VtJsonFields.number(o,"lineupWidthMetres",20,200),VtJsonFields.number(o,"rideStartKmh",1,100),.1,1000,
            Math.round(1000*VtJsonFields.number(o,"noRideTimeoutSeconds",60,7200)),VtJsonFields.number(o,"reapproachMarginMetres",0,200),
            Math.round(1000*VtJsonFields.number(o,"rideDurationSeconds",1,600)),-25,-10,
            VtJsonFields.number(o,"maxYawRateDegreesPerSecond",1,30),VtJsonFields.number(o,"yawAccelerationDegreesPerSecondSquared",1,30),
            VtJsonFields.number(o,"maxMovementSpeedMetresPerSecond",.1,5));
        if(c.enabled&&retreat.enabled&&c.filmingDistance<retreat.minimumDistance+5)
            throw new IllegalArgumentException("filmingSeparationMetres must be >= retreat minimumDistanceMetres + 5 m for both modes; required "+(retreat.minimumDistance+5));
        return new VtSettingsConfig(c,mode,id,side,VtJsonFields.number(o,"alignmentToleranceMetres",1,20));
    }
}
''')
java('ShorelineLibrary',r'''
import com.google.gson.*;
import java.util.*;
/** Named straight shoreline profiles. Selected ID lives only in vt_settings.json. */
public final class ShorelineLibrary {
    public static final class Profile {
        public final String id,name,method,capturedAt;
        public final ShorelineGeometry geometry;
        public final double aScatter,bScatter;
        public final int aFixes,bFixes;
        public Profile(String id,String name,ShorelineGeometry g,String method,String date,double aScatter,double bScatter,int aFixes,int bFixes) {
            if(id.trim().isEmpty()||name.trim().isEmpty()||id.length()>200||name.length()>200)throw new IllegalArgumentException("Shoreline ID/name required, <=200 characters");
            if(!method.equals("lora")&&!method.equals("manual"))throw new IllegalArgumentException("captureMethod must be lora or manual");
            if(date.trim().isEmpty())throw new IllegalArgumentException("capturedAt required");
            if(!Double.isFinite(aScatter)||!Double.isFinite(bScatter)||aScatter<0||bScatter<0||aScatter>100||bScatter>100||aFixes<0||bFixes<0)throw new IllegalArgumentException("Invalid capture quality");
            this.id=id;this.name=name;geometry=g;this.method=method;capturedAt=date;this.aScatter=aScatter;this.bScatter=bScatter;this.aFixes=aFixes;this.bFixes=bFixes;
        }
    }
    public final List<Profile> profiles;
    public ShorelineLibrary(List<Profile> profiles) {
        if(profiles.size()>100)throw new IllegalArgumentException("Maximum 100 shorelines");
        Set<String> ids=new HashSet<>();for(Profile p:profiles)if(!ids.add(p.id))throw new IllegalArgumentException("Duplicate shoreline ID: "+p.id);
        this.profiles=Collections.unmodifiableList(new ArrayList<>(profiles));
    }
    public Profile find(String id) {for(Profile p:profiles)if(p.id.equals(id))return p;throw new IllegalArgumentException("Selected shoreline '"+id+"' missing; capture/select a shoreline before Start");}
    private static double[] point(JsonObject o,String k) {
        JsonObject p=VtJsonFields.object(o,k);VtJsonFields.keys(p,"latitude","longitude");
        return new double[]{VtJsonFields.number(p,"latitude",-85,85),VtJsonFields.number(p,"longitude",-180,180)};
    }
    public static ShorelineLibrary parse(String raw) {
        JsonObject o=StrictConfigJson.object(raw);VtJsonFields.keys(o,"version","shorelines");VtJsonFields.number(o,"version",1,1);
        if(!o.get("shorelines").isJsonArray())throw new IllegalArgumentException("shorelines must be an array");
        List<Profile> ps=new ArrayList<>();
        for(JsonElement e:o.getAsJsonArray("shorelines")) {
            if(!e.isJsonObject())throw new IllegalArgumentException("Shoreline must be an object");JsonObject p=e.getAsJsonObject();
            VtJsonFields.keys(p,"id","name","pointA","pointB","seaSide","captureMethod","capturedAt","quality");
            double[] a=point(p,"pointA"),b=point(p,"pointB");JsonObject q=VtJsonFields.object(p,"quality");
            VtJsonFields.keys(q,"pointAFixes","pointBFixes","pointAScatterMetres","pointBScatterMetres");
            double af=VtJsonFields.number(q,"pointAFixes",0,10000),bf=VtJsonFields.number(q,"pointBFixes",0,10000);
            if(af!=Math.rint(af)||bf!=Math.rint(bf))throw new IllegalArgumentException("Fix counts must be whole numbers");
            ps.add(new Profile(VtJsonFields.string(p,"id"),VtJsonFields.string(p,"name"),new ShorelineGeometry(a[0],a[1],b[0],b[1],VtJsonFields.string(p,"seaSide")),
                VtJsonFields.string(p,"captureMethod"),VtJsonFields.string(p,"capturedAt"),VtJsonFields.number(q,"pointAScatterMetres",0,100),VtJsonFields.number(q,"pointBScatterMetres",0,100),(int)af,(int)bf));
        }
        return new ShorelineLibrary(ps);
    }
    public String json() {
        JsonObject root=new JsonObject();root.addProperty("version",1);JsonArray a=new JsonArray();root.add("shorelines",a);
        for(Profile p:profiles) {
            JsonObject o=new JsonObject();a.add(o);o.addProperty("id",p.id);o.addProperty("name",p.name);
            JsonObject x=new JsonObject(),y=new JsonObject();x.addProperty("latitude",p.geometry.aLat);x.addProperty("longitude",p.geometry.aLon);
            y.addProperty("latitude",p.geometry.bLat);y.addProperty("longitude",p.geometry.bLon);o.add("pointA",x);o.add("pointB",y);
            o.addProperty("seaSide",p.geometry.seaSide);o.addProperty("captureMethod",p.method);o.addProperty("capturedAt",p.capturedAt);
            JsonObject q=new JsonObject();o.add("quality",q);q.addProperty("pointAFixes",p.aFixes);q.addProperty("pointBFixes",p.bFixes);
            q.addProperty("pointAScatterMetres",p.aScatter);q.addProperty("pointBScatterMetres",p.bScatter);
        }
        return new GsonBuilder().setPrettyPrinting().create().toJson(root);
    }
}
''')
java('ShorelineCapture',r'''
import java.util.*;
/** Stationary LoRa batches: 10 distinct fresh fixes spanning >=4s, <=5m scatter, 120s timeout. */
public final class ShorelineCapture {
    public final long startedAt;
    private long lastTime=-1;
    private final List<AimingSession.Fix> samples=new ArrayList<>();
    public String status="Waiting for fresh LoRa GPS";
    public static final class Point {
        public final double lat,lon,scatter;public final int fixes;
        Point(double lat,double lon,double scatter,int fixes){this.lat=lat;this.lon=lon;this.scatter=scatter;this.fixes=fixes;}
    }
    public ShorelineCapture(long now){startedAt=now;}
    public Point observe(AimingSession.Fix fix,long now) {
        if(now-startedAt>=120000)throw new IllegalArgumentException("Capture timed out: fresh stationary LoRa fixes required. Retry at the shoreline.");
        if(fix==null||now<fix.time||now-fix.time>3000||fix.time<startedAt) {
            samples.clear();status="Waiting for fresh LoRa GPS; batch reset";return null;
        }
        ShorelineGeometry.validPoint(fix.lat,fix.lon);
        if(fix.time<=lastTime){status="Waiting for a distinct LoRa fix ("+samples.size()+"/10)";return null;}
        lastTime=fix.time;samples.add(fix);if(samples.size()>10)samples.remove(0);
        status="Stationary fixes "+samples.size()+"/10";
        if(samples.size()<10||fix.time-samples.get(0).time<4000)return null;
        double lat=0,lon=0,origin=samples.get(0).lon;
        for(AimingSession.Fix f:samples){lat+=f.lat;lon+=YawAimingMath.shortestHeadingError(f.lon,origin);}
        lat/=samples.size();lon=((origin+lon/samples.size()+540)%360)-180;
        double scatter=0;for(AimingSession.Fix f:samples)scatter=Math.max(scatter,YawAimingMath.distance(lat,lon,f.lat,f.lon));
        if(scatter>5){status="GPS scatter "+Math.round(scatter)+" m > 5 m; remain stationary";return null;}
        return new Point(lat,lon,scatter,samples.size());
    }
}
''')
# Use human left/right in JSON, mapped to the established seaward view semantics.
edit(P+'ShorelinePositioning.java','"leftOfAToB".equals(side)&&!"rightOfAToB".equals(side)','"left".equals(side)&&!"right".equals(side)')
edit(P+'ShorelinePositioning.java','side.equals("leftOfAToB")','side.equals("left")')
edit(P+'ShorelinePositioning.java','sidewaysSide must be leftOfAToB or rightOfAToB','sidewaysSide must be left or right looking seaward')
write('SampleCode-V5/android-sdk-v5-uxsdk/src/main/assets/vt_settings.json',json.dumps(dict(version=1,enabled=True,filmingSeparationMetres=28,reapproachMarginMetres=5,maxMovementSpeedMetresPerSecond=4,lineupWidthMetres=50,rideStartKmh=18,rideDurationSeconds=90,noRideTimeoutSeconds=900,maxYawRateDegreesPerSecond=15,yawAccelerationDegreesPerSecondSquared=8,mode='front',shorelineId='',sidewaysSide='left',alignmentToleranceMetres=3),indent=2)+'\n')
write('SampleCode-V5/android-sdk-v5-uxsdk/src/main/assets/vt_shorelines.json','{"version":1,"shorelines":[]}\n')
# All new files must validate before any session configuration is published.
n=P+'VtSessionConfig.java'
edit(n,'public final RetreatSettings retreat;','public ComeToMeSettings movement;\n    public ShorelinePositioning positioning;\n    public String settingsJson,shorelinesJson;\n    public final RetreatSettings retreat;')
edit(n,'    public static VtSessionConfig load(Reader reader) {',r'''
    public static VtSessionConfig load40(Reader reader) {
        VtSessionConfig result=load(reader);String file="vt_settings.json";
        try {
            String settings=read(reader,file);VtSettingsConfig c=VtSettingsConfig.parse(settings,result.retreat);
            file="vt_shorelines.json";String library=read(reader,file);ShorelineLibrary l=ShorelineLibrary.parse(library);
            ShorelinePositioning positioning=null;
            if(c.movement.enabled) {
                ShorelineLibrary.Profile p=l.find(c.shorelineId);
                positioning=new ShorelinePositioning(p.geometry,p.id,c.mode,c.sidewaysSide,c.alignmentTolerance,result.retreat.enabled?result.retreat.minimumDistance:0);
            }
            result.movement=c.movement;result.positioning=positioning;result.settingsJson=settings;result.shorelinesJson=library;return result;
        }catch(Exception e){throw new IllegalArgumentException("Cannot start: "+file+" — "+e.getMessage()+". Correct configuration or capture/select a shoreline, then press Start.",e);}
    }
    public static VtSessionConfig load(Reader reader) {''')
# New preset branch leaves legacy regression path and saved return semantics intact.
n=P+'ComeToMeController.java'
edit(n,'private ComeToMeSettings settings=','public ShorelinePositioning positioning;\n    public double right,projectedSeparation=Double.NaN,alignmentError=Double.NaN;\n    public String journeyKind="none";\n    public long destinationFixTime=-1;\n    public boolean presetApproach() {return positioning!=null;}\n    private ComeToMeSettings settings=')
edit(n,'cancel(); settings=config;','cancel(); positioning=null; settings=config;')
edit(n,'forward=0;', 'forward=right=0;')
edit(n,'        // VT 3.1: A holding tripod may re-approach', '        if(positioning!=null) { updatePreset(in,now,freshSurfer);return; }\n        // VT 3.1: A holding tripod may re-approach')
edit(n,'    /** Record arrival once.',r'''
    private void updatePreset(AimingSession.Inputs in,long now,boolean freshSurfer) {
        double[] measures=positioning.measures(in);projectedSeparation=measures[0];alignmentError=measures[1];
        if(!approaching()) {
            if(phase!=Phase.WAITING&&phase!=Phase.HOLDING)return;
            if(!freshSurfer) {reason="waiting_fresh_planning_fix";return;}
            if(projectedSeparation<=0) {reason="wrong_filming_side_hold";return;}
            boolean close=projectedSeparation>approachStartThreshold()+.000001;
            boolean align=Math.abs(alignmentError)>positioning.tolerance;
            if(!close&&!align) {if(!hasFilmed)enterFilmingHold(now);else {phase=Phase.HOLDING;reason="within_projected_margin";}return;}
            double separation=close?settings.filmingDistance:projectedSeparation;
            double[] target=positioning.destination(in,separation);
            if(!positioning.safePath(in,target[0],target[1],centralLat,centralLon)) {reason="alignment_path_or_destination_blocked";return;}
            approachStartLat=in.lat;approachStartLon=in.lon;approachTargetLat=target[0];approachTargetLon=target[1];
            approachHeading=YawAimingMath.bearingToTarget(in.lat,in.lon,target[0],target[1]);
            approachDistance=YawAimingMath.distance(in.lat,in.lon,target[0],target[1]);approachProgress=0;
            destinationFixTime=in.target.time;journeyKind=close?"projected_reapproach":"alignment_only_preserve_projection";
            approachAligned=true;phase=Phase.APPROACHING;attemptSince=now;event="fixed_destination_planned";
            reason=journeyKind;return; // Neutral transition; yaw continues aiming at the surfer.
        }
        double remaining=YawAimingMath.distance(in.lat,in.lon,approachTargetLat,approachTargetLon);
        approachProgress=approachDistance-remaining;
        if(remaining<=ComeToMeSettings.COMPLETION_TOLERANCE) {enterFilmingHold(now);reason="fixed_destination_arrived";return;}
        if(centralDistance>=ComeToMeSettings.EXCURSION_STOP) {phase=Phase.HOLDING;reason="excursion_limit";attemptSince=-1;return;}
        double[] v=positioning.velocity(in,approachTargetLat,approachTargetLon,arrivalSpeed(Math.min(remaining,ComeToMeSettings.MAX_EXCURSION-centralDistance)));
        forward=v[0];right=v[1];reason="moving_to_fixed_destination_aiming_surfer";
    }
    /** Record arrival once.''')
edit(n,'    public ComeToMeSettings settings() {',r'''
    public boolean permits(AimingSession.Inputs in,long now,double requestedForward,double requestedRight) {
        if(requestedForward==0&&requestedRight==0)return true;
        if(!presetApproach()||returning())return requestedRight==0&&permits(in,now,requestedForward);
        double speed=Math.hypot(requestedForward,requestedRight);
        if(!Double.isFinite(speed)||speed>settings.maxMovementSpeed+.000001||!runEnabled||captureRequired||!approaching()||riding||
                in.validate(now,true)!=null||expireAttempt(now)||in.target.time!=lastFix)return false;
        double central=YawAimingMath.distance(in.lat,in.lon,centralLat,centralLon);
        double remaining=YawAimingMath.distance(in.lat,in.lon,approachTargetLat,approachTargetLon);
        if(central>=ComeToMeSettings.EXCURSION_STOP||remaining<=ComeToMeSettings.COMPLETION_TOLERANCE)return false;
        double[] v=positioning.velocity(in,approachTargetLat,approachTargetLon,arrivalSpeed(Math.min(remaining,ComeToMeSettings.MAX_EXCURSION-central)));
        // A changed heading/position must not rotate an already calculated body command toward another point.
        return Math.abs(v[0]-requestedForward)<=.01&&Math.abs(v[1]-requestedRight)<=.01;
    }
    public ComeToMeSettings settings() {''')
edit(n,'approachAligned=false;\n    }','approachAligned=false;right=0;journeyKind="none";destinationFixTime=-1;\n    }')
n=P+'AimingSession.java'
edit(n,'        default String startProblem()', '        default ShorelinePositioning positioningSettings() {return null;}\n        default void sendMotion(double yaw,double forward,double right) {\n            if(right!=0)throw new IllegalStateException("Lateral translation port not implemented");sendMotion(yaw,forward);\n        }\n        default String startProblem()')
edit(n,'public double cycleRequestedForward;','public double cycleRequestedForward,cycleRequestedRight;')
edit(n,'movement.start(port.movementSettings(),port.now());','movement.start(port.movementSettings(),port.now());\n        movement.positioning=port.positioningSettings();')
edit(n,'cycleRequestedForward=0; cycleDecision','cycleRequestedForward=cycleRequestedRight=0; cycleDecision')
edit(n,'if(movement.returning() || movement.approaching()) {','if(movement.returning() || (movement.approaching() && !movement.presetApproach())) {')
edit(n,'cycleRequestedForward=retreat.active ? -retreat.settings.speed : movement.forward;','cycleRequestedForward=retreat.active ? -retreat.settings.speed : movement.forward;\n            cycleRequestedRight=retreat.active?0:movement.right;')
edit(n,'permitsMotion(finalInputs,port.now(),cycleRequestedForward)','permitsMotion(finalInputs,port.now(),cycleRequestedForward,cycleRequestedRight)')
edit(n,'cycleRequestedForward=0; movement.forward=0;','cycleRequestedForward=cycleRequestedRight=0; movement.forward=movement.right=0;')
edit(n,'port.sendMotion(rate,cycleRequestedForward);','port.sendMotion(rate,cycleRequestedForward,cycleRequestedRight);')
edit(n,'    // CAM3 v2.3: Callback threads',r'''
    public boolean permitsMotion(Inputs raw,long now,double forward,double right) {
        Inputs in=controlInputs(raw,now);
        if(retreat.active)return right==0&&(forward==0||retreat.permits(movement,in,now,forward));
        if((forward!=0||right!=0)&&movement.approaching()&&retreat.blocksApproach(movement,in,now))return false;
        return movement.permits(in,now,forward,right);
    }
    // CAM3 v2.3: Callback threads''')
n=P+'AimingMotionCommand.java'
edit(n,'        if(!Double.isFinite(forward)', '        return build(yaw,forward,0);\n    }\n    public static VirtualStickFlightControlParam build(double yaw,double forward,double right) {\n        if(!Double.isFinite(right)||Math.abs(right)>ComeToMeSettings.HARD_MAX_SPEED||\n                Math.hypot(forward,right)>Math.max(ComeToMeSettings.HARD_MAX_SPEED,RetreatSettings.MAX_SPEED))throw new IllegalArgumentException("Combined velocity limit");\n        if(!Double.isFinite(forward)')
edit(n,'command.setRoll(forward);','command.setRoll(forward);\n        command.setPitch(right);')
edit(n,'// YawOnlyCommand explicitly retains pitch (lateral) and vertical velocity at zero.','// BODY X forward / BODY Y right; vertical velocity remains explicitly zero.')
n=P+'SharedConfigStorage.java'
edit(n,'private static final String KEY=', 'public static final String SETTINGS="vt_settings.json",SHORELINES="vt_shorelines.json";\n    private static final String KEY=')
edit(n,'    public static void select(Context c,Uri tree)',r'''
    public static void ensure40(Context c)throws IOException {
        String selected=folder(c);if(selected==null)throw new IOException("Choose Configuration folder before shoreline setup or Start");
        if(selected.equals(c.getSharedPreferences("vt28",Context.MODE_PRIVATE).getString("vt40SeededTree",null)))return;
        for(String name:new String[]{SETTINGS,SHORELINES})ConfigFileMigration.ensure(files(c,Uri.parse(selected)),name,()->GimbalBandStorage.read(c.getAssets().open(name)));
        if(!c.getSharedPreferences("vt28",Context.MODE_PRIVATE).edit().putString("vt40SeededTree",selected).commit())throw new IOException("Cannot save VT 4.0 setup state");
    }
    private static void overwrite(Context c,String name,String content)throws IOException {
        Uri tree=Uri.parse(folder(c)),file=find(c,tree,name);if(file==null)throw new FileNotFoundException(name+" missing");
        try(OutputStream out=c.getContentResolver().openOutputStream(file,"wt")) {
            if(out==null)throw new IOException("Cannot write "+name);out.write(content.getBytes(StandardCharsets.UTF_8));
        }
        if(!content.equals(read(c,name)))throw new IOException("Read-back verification failed for "+name);
    }
    /** Provider-safe backup + verification + attempted rollback; no claim of provider atomicity. */
    public static void writeVerified(Context c,String name,String content)throws IOException {
        if(!SETTINGS.equals(name)&&!SHORELINES.equals(name))throw new IOException("Unsupported shoreline file");
        String old=read(c,name);if(old==null)throw new IOException("Choose Configuration folder first");
        ConfigFileMigration.Files f=files(c,Uri.parse(folder(c)));String backup=name+".backup";
        if(!f.exists(backup))f.create(backup,old);else overwrite(c,backup,old);
        try {overwrite(c,name,content);}catch(Exception e) {
            try {overwrite(c,name,old);}catch(Exception rollback){e.addSuppressed(rollback);}
            throw new IOException("Save failed: "+name+"; backup retained; rollback errors="+java.util.Arrays.toString(e.getSuppressed()),e);
        }
    }
    public static void select(Context c,Uri tree)''')
edit(n,'        if(!c.getSharedPreferences("vt28",Context.MODE_PRIVATE).edit().putString("retreatSeededTree",tree.toString())', '        for(String name:new String[]{SETTINGS,SHORELINES})ConfigFileMigration.ensure(files,name,()->GimbalBandStorage.read(c.getAssets().open(name)));\n        if(!c.getSharedPreferences("vt28",Context.MODE_PRIVATE).edit().putString("vt40SeededTree",tree.toString()).putString("retreatSeededTree",tree.toString())')
n=P+'YawAimingController.java'
s=read(n);start=s.index('    public void setMovementSettings(');end=s.index('    public boolean usesPhoneGps()',start);s=s[:start]+s[end:]
start=s.index('        android.content.SharedPreferences prefs=');end=s.index('        executor.scheduleWithFixedDelay',start);s=s[:start]+'        // VT 4.0: movement tuning is authoritative JSON, loaded before Start.\n'+s[end:];write(n,s)
edit(n,'private double lastSubmittedForward;','private double lastSubmittedForward,lastSubmittedRight;\n    private volatile ShorelinePositioning positioningConfig;\n    @Override public ShorelinePositioning positioningSettings(){return positioningConfig;}')
edit(n,'if(movementConfigError!=null)throw new IllegalArgumentException(movementConfigError);','SharedConfigStorage.ensure40(appContext);')
edit(n,'VtSessionConfig.load(name->','VtSessionConfig.load40(name->')
edit(n,'retreatConfig=loaded.retreat;session.rotationCurve=loaded.rotation;','retreatConfig=loaded.retreat;session.rotationCurve=loaded.rotation;\n                        movementConfig=loaded.movement;positioningConfig=loaded.positioning;\n                        fullLog.record("vt40_configuration","nextSession",session.sessionId()+1,"effectiveSettingsJson",loaded.settingsJson,"effectiveShorelinesJson",loaded.shorelinesJson);')
edit(n,'@Override public void sendMotion(double rate,double forward) {','@Override public void sendMotion(double rate,double forward) {sendMotion(rate,forward,0);}\n    @Override public void sendMotion(double rate,double forward,double right) {')
edit(n,'boolean neutral = rate == 0 && forward == 0','boolean neutral = rate == 0 && forward == 0 && right == 0')
edit(n,'if(forward!=0 && (!active || !session.permitsMotion(inputs(),now(),forward))) forward=0;','if((forward!=0 || right!=0) && (!active || !session.permitsMotion(inputs(),now(),forward,right))) {forward=0;right=0;}')
edit(n,'(rate!=0 || forward!=0)', '(rate!=0 || forward!=0 || right!=0)')
edit(n,'AimingMotionCommand.build(rate,forward)', 'AimingMotionCommand.build(rate,forward,right)')
edit(n,'lastSubmittedForward=forward;','lastSubmittedForward=forward;lastSubmittedRight=right;')
edit(n,'if(forward!=0) lastTranslationAt=now();','if(forward!=0||right!=0) lastTranslationAt=now();')
edit(n,'"submittedForwardMps", forward, "submittedYawRate"','"submittedForwardMps", forward, "submittedRightMps", right, "submittedYawRate"')
edit(n,'session.movement.approaching() ? "Aiming: holding approach heading"','session.movement.approaching() && !session.movement.presetApproach() ? "Aiming: holding approach heading"')
edit(n,'MovementCycleLog.record(fullLog,session,at,submittedCycle,lastSubmittedForward);','MovementCycleLog.record(fullLog,session,at,submittedCycle,lastSubmittedForward,lastSubmittedRight);')
# Async stopped-only operations share the control executor, capture never blocks it.
edit(n,'    public boolean usesPhoneGps()',r'''
    public void shorelineTask(java.util.concurrent.Callable<String> task,java.util.function.Consumer<String> success,java.util.function.Consumer<String> failure) {
        executor.execute(()->{
            try {if(!canSelectGpsSource())throw new IllegalStateException("Stop automatic control before shoreline setup");
                SharedConfigStorage.ensure40(appContext);String result=task.call();main.post(()->success.accept(result));
            }catch(Exception e){main.post(()->failure.accept(e.getMessage()==null?e.toString():e.getMessage()));}
        });
    }
    public void captureShoreline(java.util.function.Consumer<String> progress,java.util.function.Consumer<ShorelineCapture.Point> done,
            java.util.function.Consumer<String> error,java.util.function.BooleanSupplier cancelled) {
        executor.execute(()->{
            if(!canSelectGpsSource()||!foreground||usePhone){main.post(()->error.accept("Capture requires stopped control, foreground screen, and LoRa GPS selected"));return;}
            ShorelineCapture capture=new ShorelineCapture(now());
            Runnable poll=new Runnable(){public void run(){
                if(cancelled.getAsBoolean())return;
                if(!canSelectGpsSource()||!foreground||usePhone){main.post(()->error.accept("Capture cancelled: control/source/screen changed"));return;}
                try {ShorelineCapture.Point point=capture.observe(lora.getLatestFix(),now());
                    if(point!=null){main.post(()->done.accept(point));return;}
                    main.post(()->progress.accept(capture.status));executor.schedule(this,500,TimeUnit.MILLISECONDS);
                }catch(Exception e){main.post(()->error.accept(e.getMessage()));}
            }};poll.run();
        });
    }
    public boolean usesPhoneGps()''')
n=P+'MovementCycleLog.java'
edit(n,'        ComeToMeController m=session.movement;', '        record(log,session,now,submittedCycle,submittedForward,0);\n    }\n    public static void record(FullSessionLog log,AimingSession session,long now,long submittedCycle,double submittedForward,double submittedRight) {\n        ComeToMeController m=session.movement;')
edit(n,'"yawPurpose",m.returning() ? "return" : m.approaching() ? "approach" : "surfer",','"yawPurpose",m.returning() ? "return" : m.approaching() && !m.presetApproach() ? "approach" : "surfer",\n                "positioningMode",m.positioning==null?"legacy":m.positioning.mode,"shorelineId",m.positioning==null?null:m.positioning.shorelineId,\n                "projectedSeparationM",m.projectedSeparation,"comeToMeAlignmentErrorM",m.alignmentError,\n                "fixedDestinationFixTime",m.destinationFixTime,"journeyKind",m.journeyKind,\n                "requestedRightMps",session.cycleRequestedRight,"submittedRightMps",submittedCycle==session.cycleId?submittedRight:null,')
n=P+'FullSessionLog.java';edit(n,'"version", "3.8"','"version", "4.0"') if '"version", "3.8"' in read(n) else edit(n,'"3.8"','"4.0"')
n='SampleCode-V5/android-sdk-v5-sample/build.gradle';edit(n,'versionCode 22','versionCode 23');edit(n,'versionName "3.8"','versionName "4.0"')
n=PK+'DefaultLayoutActivity.java';s=read(n)
a=s.index('            menu.getMenu().add(0,4,3,"Come to me")');b=s.index('            menu.getMenu().add(0,6,5,',a)
s=s[:a]+'            menu.getMenu().add(0,5,4,"VT 4.0 configuration").setEnabled(yawAimingController.canSelectGpsSource());\n            menu.getMenu().add(0,8,7,"Shorelines").setEnabled(yawAimingController.canSelectGpsSource());\n'+s[b:]
a=s.index('                if(item.getItemId()==4)');b=s.index('                if(item.getItemId()==5)',a);s=s[:a]+s[b:]
s=s.replace('if(item.getItemId()==5) showMovementSettings();','if(item.getItemId()==5) showMovementSettings();\n                if(item.getItemId()==8) new dji.v5.ux.sample.showcase.defaultlayout.aiming.ShorelineSetupUi(this,yawAimingController).show();')
a=s.index('    private void showMovementSettings()');b=s.index('    private void renderAimingState',a)
s=s[:a]+'''    private void showMovementSettings() {
        new android.app.AlertDialog.Builder(this).setTitle("VT 4.0 · JSON configuration")
            .setMessage("Edit tuning in the selected folder:\\nvt_settings.json — mode, filming separation, movement, ride, yaw limits\\nvt_retreat_settings.json — direct-distance retreat\\nvt_rotation_speeds.json — rotation curve\\nvt_gimbal_bands.json — gimbal\\nvt_shorelines.json — saved shoreline profiles\\n\\nBoth modes require filming separation >= retreat threshold + 5 m when enabled. Files are validated and frozen on Start. Capture/select a shoreline using Shorelines.")
            .setPositiveButton(android.R.string.ok,null).show();
    }

'''+s[b:];write(n,s)
# Compile new pure components with existing tests.
n='tools/test-aiming.ps1';edit(n,'"$source/ComeToMeController.java"','"$source/ComeToMeController.java" "$source/ShorelineGeometry.java" "$source/ShorelinePositioning.java" "$source/ShorelineCapture.java"')
print('Staged',len(list(OUT.rglob('*.*'))),'files')
