package au.com.gildo.vtc;

import java.util.LinkedHashMap;
import java.util.Map;

/** Fixed motor destination for a surfer, landmark or offshore bearing. */
final class FixedGoto {
    enum Kind {
        SURFER("GOTO SAVED TARGET"),
        LANDMARK("GOTO LANDMARK"),
        OFFSHORE("GOTO OFFSHORE");

        final String state;
        Kind(String state){this.state=state;}
    }
    private static final double REFERENCE_TOLERANCE_DEGREES=.03;
    final LoRaTelemetry source;
    final Kind kind;
    final boolean calibrateOnArrival;
    final long sourceReceivedAt,capturedAt;
    final double range,bearing,heading,pan,tilt,offset;
    final Calibration calibration;
    private final Settings settings;

    FixedGoto(Calibration calibration,LoRaTelemetry source,long receivedAt,long capturedAt,double offset) {
        this.calibration=calibration;settings=calibration.settings;
        this.source=source;kind=Kind.SURFER;calibrateOnArrival=false;sourceReceivedAt=receivedAt;this.capturedAt=capturedAt;this.offset=offset;
        range=Geo.distance(settings.latitude,settings.longitude,source.lat,source.lon);
        String rangeBlocker=Readiness.rangeBlocker(settings,range);
        if(rangeBlocker!=null)throw new IllegalArgumentException(rangeBlocker);
        bearing=Geo.bearing(settings.latitude,settings.longitude,source.lat,source.lon);
        heading=Geo.wrap(bearing+offset);pan=calibration.targetPan(heading);
        tilt=Geo.elevation(range,settings.height,settings.targetHeight)+settings.tiltOffset;
        if(!calibration.allowed(pan,tilt))throw new IllegalArgumentException("Saved GoTo target is outside the pan/tilt sector");
    }
    private FixedGoto(Calibration calibration,Kind kind,double heading,double tilt,long capturedAt,
                      boolean calibrateOnArrival) {
        this.calibration=calibration;settings=calibration.settings;this.kind=kind;
        this.calibrateOnArrival=calibrateOnArrival;source=null;sourceReceivedAt=-1;
        this.capturedAt=capturedAt;offset=0;
        range=kind==Kind.LANDMARK?Geo.distance(settings.latitude,settings.longitude,settings.landmarkLat,settings.landmarkLon):Double.NaN;
        bearing=this.heading=heading;
        pan=calibration.targetPan(heading);
        this.tilt=tilt;
        if(!calibration.allowed(pan,tilt))
            throw new IllegalArgumentException(kind==Kind.OFFSHORE
                ?"Saved offshore heading is outside the pan limits":"Landmark is outside the pan/tilt sector");
    }
    static FixedGoto landmark(Calibration calibration,long now){return landmark(calibration,now,false);}
    static FixedGoto landmark(Calibration calibration,long now,boolean calibrateOnArrival){
        return new FixedGoto(calibration,Kind.LANDMARK,calibration.bearing,calibration.elevation,now,calibrateOnArrival);
    }
    static FixedGoto offshore(Calibration calibration,double heading,double currentTilt,long now){
        return new FixedGoto(calibration,Kind.OFFSHORE,heading,currentTilt,now,false);
    }
    double[] nextMove(double currentPan,double currentTilt) {
        if(!calibration.allowed(currentPan,currentTilt))throw new IllegalArgumentException("Mount is outside the pan/tilt sector");
        // Work in the landmark-relative sector and keep the tilt axis idle during offshore positioning.
        return new double[]{step(pan-currentPan,panTolerance()),kind==Kind.OFFSHORE?0:step(tilt-currentTilt,tiltTolerance())};
    }
    boolean arrived(GtiClient.Position position,double[] steps){
        return !position.moving&&steps[0]==0&&steps[1]==0;
    }
    private double panTolerance(){return kind==Kind.SURFER?settings.panToleranceDegrees(range):REFERENCE_TOLERANCE_DEGREES;}
    private double tiltTolerance(){return kind==Kind.SURFER?settings.tiltToleranceDegrees(range):REFERENCE_TOLERANCE_DEGREES;}
    private double step(double error,double tolerance) {
        return Math.abs(error)<=tolerance?0:Math.copySign(Math.min(settings.gotoMaxMove,Math.abs(error)),error);
    }
    static long timeoutMs(double panMove,double tiltMove) {
        // A bounded timeout allowance, not a prediction of the mount's physical speed.
        return Math.max(15000,10000+(long)Math.ceil(Math.max(Math.abs(panMove),Math.abs(tiltMove))*2000));
    }
    Map<String,Object> snapshot() {
        Map<String,Object> out=new LinkedHashMap<>();
        Map<String,Object> origin=new LinkedHashMap<>();
        if(kind==Kind.LANDMARK){origin.put("latitude",settings.landmarkLat);origin.put("longitude",settings.landmarkLon);}
        if(source!=null)origin.putAll(source.fields());
        out.put("kind",kind.name());out.put("source",origin);
        if(kind==Kind.LANDMARK){
            out.put("calibratedPanRaw",calibration.panRaw);out.put("calibratedTiltRaw",calibration.tiltRaw);
            out.put("calibrateOnArrival",calibrateOnArrival);
        }
        out.put("sourceReceivedAtMs",sourceReceivedAt);out.put("capturedAtMs",capturedAt);
        out.put("rangeM",range);out.put("bearingDegrees",bearing);out.put("headingDegrees",heading);
        out.put("panFromLandmarkDegrees",pan);out.put("tiltDegrees",tilt);out.put("framingOffsetDegrees",offset);
        out.put("panToleranceMetres",settings.panTolerance);out.put("tiltToleranceMetres",settings.tiltTolerance);
        out.put("panToleranceDegrees",panTolerance());out.put("tiltToleranceDegrees",kind==Kind.OFFSHORE?null:tiltTolerance());
        out.put("maxMoveDegrees",settings.gotoMaxMove);return out;
    }
}
