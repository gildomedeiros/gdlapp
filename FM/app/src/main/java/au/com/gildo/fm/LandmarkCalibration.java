package au.com.gildo.fm;

import java.util.Locale;

record LandmarkCalibration(double bearing, double elevation) {
    static final int PIVOT_ELEVATION=10;
    static final int BEARING=12, ELEVATION=13, PAN_COUNT=14, TILT_COUNT=15;
    static final int LANDMARK_LAT=18, LANDMARK_LON=19, LANDMARK_HEIGHT=20, BASIS=21;
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

    private static String basis(String[] values) {
        StringBuilder text=new StringBuilder(values[4]);
        for(int i:new int[]{5,MountCoordinates.LATITUDE,MountCoordinates.LONGITUDE,PIVOT_ELEVATION,
                LANDMARK_LAT,LANDMARK_LON,LANDMARK_HEIGHT,16,17}) {
            String value=values[i];
            try { value=Double.toString(Double.parseDouble(value)); }
            catch(NumberFormatException ignored) {}
            text.append('|').append(value);
        }
        return text.toString();
    }
}
