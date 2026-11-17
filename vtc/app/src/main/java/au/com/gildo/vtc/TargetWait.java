package au.com.gildo.vtc;

import java.util.Locale;

/** Only temporary target limits are eligible for automatic resumption. */
final class TargetWait {
    enum Change { READY, PAUSED, WAITING, RESUMED }
    private volatile String reason;
    boolean waiting(){return reason!=null;}
    void reset(){reason=null;}
    Change update(String blocker) {
        boolean wasWaiting=waiting();reason=blocker;
        return blocker==null?(wasWaiting?Change.RESUMED:Change.READY):(wasWaiting?Change.WAITING:Change.PAUSED);
    }
    static String blocker(Settings s,Calibration cal,double range,double targetPan,double targetTilt) {
        String rangeReason=Readiness.rangeBlocker(s,range);if(rangeReason!=null)return rangeReason;
        if(!Double.isFinite(targetPan)||!Double.isFinite(targetTilt))return "Target direction unavailable";
        if(targetPan<s.panMin||targetPan>s.panMax)return String.format(Locale.US,
            "Target pan %+.2f° is outside %+.1f° to %+.1f°",targetPan,s.panMin,s.panMax);
        if(!cal.allowed(targetPan,targetTilt))return String.format(Locale.US,
            "Target tilt %+.2f° is outside %+.1f° to %+.1f°",targetTilt,s.tiltMin,s.tiltMax);
        return null;
    }
}
