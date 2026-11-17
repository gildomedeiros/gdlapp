package au.com.gildo.vtc;

import java.util.LinkedHashMap;
import java.util.Map;

/** One captured GPS target, including framing; later fixes cannot change this journey. */
final class FixedGoto {
    final LoRaTelemetry source;
    final boolean landmark;
    final long sourceReceivedAt,capturedAt;
    final double range,bearing,heading,pan,tilt,offset;
    final Calibration calibration;
    private final Settings settings;

    FixedGoto(Calibration calibration,LoRaTelemetry source,long receivedAt,long capturedAt,double offset) {
        this.calibration=calibration;settings=calibration.settings;
        this.source=source;landmark=false;sourceReceivedAt=receivedAt;this.capturedAt=capturedAt;this.offset=offset;
        range=Geo.distance(settings.latitude,settings.longitude,source.lat,source.lon);
        String rangeBlocker=Readiness.rangeBlocker(settings,range);
        if(rangeBlocker!=null)throw new IllegalArgumentException(rangeBlocker);
        bearing=Geo.bearing(settings.latitude,settings.longitude,source.lat,source.lon);
        heading=Geo.wrap(bearing+offset);pan=calibration.targetPan(heading);
        tilt=Geo.elevation(range,settings.height,settings.targetHeight)+settings.tiltOffset;
        if(!calibration.allowed(pan,tilt))throw new IllegalArgumentException("Saved GoTo target is outside the pan/tilt sector");
    }
    private FixedGoto(Calibration calibration,long capturedAt) {
        this.calibration=calibration;settings=calibration.settings;landmark=true;source=null;sourceReceivedAt=-1;
        this.capturedAt=capturedAt;offset=0;
        range=Geo.distance(settings.latitude,settings.longitude,settings.landmarkLat,settings.landmarkLon);
        bearing=heading=calibration.bearing;pan=0;tilt=calibration.elevation;
        if(!calibration.allowed(pan,tilt))throw new IllegalArgumentException("Landmark is outside the pan/tilt sector");
    }
    static FixedGoto landmark(Calibration calibration,long now){return new FixedGoto(calibration,now);}
    double[] nextMove(double currentPan,double currentTilt) {
        if(!calibration.allowed(currentPan,currentTilt))throw new IllegalArgumentException("Mount is outside the pan/tilt sector");
        // Work in the landmark-relative sector, never wrap a long move through a limit.
        return new double[]{step(pan-currentPan,landmark?.03:settings.panTolerance),step(tilt-currentTilt,landmark?.03:settings.tiltTolerance)};
    }
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
        if(landmark){origin.put("latitude",settings.landmarkLat);origin.put("longitude",settings.landmarkLon);}
        else origin.putAll(source.fields());
        out.put("kind",landmark?"LANDMARK":"SURFER");out.put("source",origin);
        if(landmark){out.put("calibratedPanRaw",calibration.panRaw);out.put("calibratedTiltRaw",calibration.tiltRaw);}
        out.put("sourceReceivedAtMs",sourceReceivedAt);out.put("capturedAtMs",capturedAt);
        out.put("rangeM",range);out.put("bearingDegrees",bearing);out.put("headingDegrees",heading);
        out.put("panFromLandmarkDegrees",pan);out.put("tiltDegrees",tilt);out.put("framingOffsetDegrees",offset);
        out.put("maxMoveDegrees",settings.gotoMaxMove);return out;
    }
}
