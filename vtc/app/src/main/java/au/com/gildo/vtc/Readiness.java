package au.com.gildo.vtc;

import java.util.*;

/** Actionable blockers, also persisted verbatim in full logs. */
final class Readiness {
    static List<String> blockers(Settings s,boolean connected,boolean calibrated,boolean moving,
                                 boolean busy,boolean serviceRunning,boolean freshGps,String gpsStatus) {
        List<String> out=new ArrayList<>();
        if(!Settings.ipv4(s.mountHost))out.add("Setup: enter the GTi IP address");
        if(!Settings.ipv4(s.receiverHost))out.add("Setup: enter the LoRa receiver IP address");
        if(!connected)out.add("Connect the GTi and LoRa receiver");
        if(!Geo.valid(s.latitude,s.longitude))out.add("Setup: enter camera coordinates (latitude, longitude)");
        if(!Double.isFinite(s.height))out.add("Setup: enter lens height above water");
        if(!Geo.valid(s.landmarkLat,s.landmarkLon))out.add("Setup: enter landmark coordinates (latitude, longitude)");
        if(!Double.isFinite(s.landmarkHeight))out.add("Setup: enter landmark height above water");
        if(Geo.valid(s.latitude,s.longitude)&&Geo.valid(s.landmarkLat,s.landmarkLon)
            &&Geo.distance(s.latitude,s.longitude,s.landmarkLat,s.landmarkLon)<20)out.add("Setup: choose a landmark at least 20 m away");
        if(!calibrated)out.add("Calibrate: centre the landmark, confirm level/directions, then tap Calibrate here");
        if(s.offshoreHeading==null)out.add("Wave Ride: save an offshore heading before starting aiming");
        if(moving)out.add("Wait for the mount to finish moving");
        if(busy)out.add("Wait for the current operation to finish");
        if(!serviceRunning)out.add("VTC control service is not running");
        if(!freshGps)out.add("Fresh surfer GPS needed: "+gpsStatus);
        return out;
    }
    record AimTarget(double distance,String rangeBlocker,Double bearing,Double tilt) {}

    static AimTarget aimTarget(Settings s,double latitude,double longitude){
        double distance=Geo.distance(s.latitude,s.longitude,latitude,longitude);
        String blocker=rangeBlocker(s,distance);
        if(blocker!=null)return new AimTarget(distance,blocker,null,null);
        return new AimTarget(distance,null,Geo.bearing(s.latitude,s.longitude,latitude,longitude),
            Geo.elevation(distance,s.height,s.targetHeight)+s.tiltOffset);
    }

    static String rangeBlocker(Settings s,double distance) {
        if(!Double.isFinite(distance))return "Target range unavailable";
        if(distance<s.rangeMin)return String.format(Locale.US,"Target %.1f m is below minimum range %.1f m",distance,s.rangeMin);
        if(distance>s.rangeMax)return String.format(Locale.US,"Target %.1f m exceeds maximum range %.1f m",distance,s.rangeMax);
        return null;
    }
    static List<String> targetBlockers(Settings s,Calibration cal,double distance,double bearing) {
        List<String> out=new ArrayList<>();
        String range=rangeBlocker(s,distance);if(range!=null)out.add(range);
        double tilt=Geo.elevation(distance,s.height,s.targetHeight)+s.tiltOffset;
        if(cal!=null&&!cal.allowed(cal.targetPan(bearing),tilt))
            out.add("Target is outside the configured pan/tilt sector");
        return out;
    }
}

