package au.com.gildo.fm;

import java.util.Locale;

record LandmarkCalibration(double bearing, double elevation) {
    static final int PIVOT_ELEVATION=10;
    static final int BEARING=12, ELEVATION=13, PAN_COUNT=14, TILT_COUNT=15;
    static final int LANDMARK_LAT=18, LANDMARK_LON=19, LANDMARK_HEIGHT=20, BASIS=21;
    private static final int[] SETUP_KEYS={4,5,MountCoordinates.LATITUDE,MountCoordinates.LONGITUDE,PIVOT_ELEVATION,
        LANDMARK_LAT,LANDMARK_LON,LANDMARK_HEIGHT,16,17};
    static final String COORDINATES="Landmark coordinates";

    static LandmarkCalibration calculate(GoTo.Point mount, GoTo.Point landmark) {
        if (!Geo.valid(mount.lat(),mount.lon()) || !Geo.valid(landmark.lat(),landmark.lon()) ||
            !Double.isFinite(mount.elevation()) || !Double.isFinite(landmark.elevation()))
            throw new IllegalArgumentException("Enter valid mount and landmark coordinates and elevations");
        double bearing=Geo.bearing(mount.lat(),mount.lon(),landmark.lat(),landmark.lon());
        double range=Geo.distance(mount.lat(),mount.lon(),landmark.lat(),landmark.lon());
        return new LandmarkCalibration(bearing,Geo.elevation(range,mount.elevation(),landmark.elevation()));
    }

    void saveReference(String[] values,GoTo.Encoders encoders) {
        values[BEARING]=Double.toString(bearing);
        values[ELEVATION]=Double.toString(elevation);
        values[PAN_COUNT]=Integer.toString(encoders.pan());
        values[TILT_COUNT]=Integer.toString(encoders.tilt());
        values[BASIS]=basis(values);
    }

    static boolean hasReference(String[] values) {
        for(int i=BEARING;i<=TILT_COUNT;i++) if(values[i].isEmpty()) return false;
        return values[BASIS].isEmpty() || values[BASIS].equals(basis(values));
    }

    static boolean clearStaleReference(String[] before,String[] values) {
        boolean present=false;
        for(int i=BEARING;i<=TILT_COUNT;i++) present|=!values[i].isEmpty();
        if(!present) return false;
        String captured=values[BASIS];
        if(captured.isEmpty() && (before==null||!hasReference(before))) return false;
        if((captured.isEmpty()?basis(before):captured).equals(basis(values))) return false;
        clearReference(values);
        return true;
    }

    static void clearReference(String[] values) {
        for(int i=BEARING;i<=TILT_COUNT;i++) values[i]="";
        values[BASIS]="";
    }

    static boolean containsReference(String[] values) {
        for (int i=BEARING;i<=TILT_COUNT;i++) if (!values[i].isEmpty()) return true;
        return !values[BASIS].isEmpty();
    }

    /** Only a successful capture in this service instance can arm pointing. */
    static final class Session {
        private String[] captured;
        private String reason="New FM session";

        synchronized String reason() { return reason; }

        synchronized void captured(String[] values) {
            captured=values.clone();
            reason=null;
        }

        synchronized void require(String why) {
            captured=null;
            reason=why;
        }

        synchronized String reasonFor(String[] values) {
            if (captured==null) return reason;
            for (int key:SETUP_KEYS) if(!setupValue(captured,key).equals(setupValue(values,key))) {
                return switch(key) {
                    case 4,5 -> "GTi connection changed";
                    case MountCoordinates.LATITUDE,MountCoordinates.LONGITUDE -> "Mount coordinates changed";
                    case PIVOT_ELEVATION -> "Pivot elevation changed";
                    case LANDMARK_LAT,LANDMARK_LON -> "Landmark coordinates changed";
                    case LANDMARK_HEIGHT -> "Landmark height changed";
                    default -> "Axis direction changed";
                };
            }
            for(int i=BEARING;i<=TILT_COUNT;i++)
                if(!captured[i].equals(values[i])) return "Pointing reference changed";
            return hasReference(values)?null:"Pointing reference changed";
        }

        synchronized boolean reconcile(String[] values) {
            String why=reasonFor(values);
            if(why==null) return false;
            boolean dirty=containsReference(values);
            clearReference(values);
            require(why);
            return dirty;
        }
    }

    static String requiredMessage(String lastFailure) {
        String nextStep="Centre the landmark and tap Calibrate here before aiming.";
        return lastFailure.isEmpty()?nextStep : "Last calibration error: "+lastFailure+"\n"+nextStep;
    }

    static String describe(String[] values) {
        if(!hasReference(values)) return "Calibration required";
        try {
            return String.format(Locale.US,"Reference %.2f° true · Elevation %+.2f°",
                Double.parseDouble(values[BEARING]),Double.parseDouble(values[ELEVATION]));
        } catch(NumberFormatException e) {
            return "Calibration required";
        }
    }

    static String basis(String[] values) {
        java.util.StringJoiner text=new java.util.StringJoiner("|");
        for(int key:SETUP_KEYS) text.add(setupValue(values,key));
        return text.toString();
    }

    private static String setupValue(String[] values,int key) {
        if(key==4) return values[key];
        try { return Double.toString(Double.parseDouble(values[key])); }
        catch(NumberFormatException e) { return values[key]; }
    }
}
