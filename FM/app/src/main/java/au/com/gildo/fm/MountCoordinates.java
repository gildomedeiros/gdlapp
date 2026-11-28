package au.com.gildo.fm;

import java.math.BigDecimal;

final class MountCoordinates {
    static final int LATITUDE=8, LONGITUDE=9;
    static final String KEY="Mount coordinates";
    static final String EXAMPLE="-28.070070, 153.387300";

    static GoTo.Point point(String[] values,int index) {
        try {
            double lat=Double.parseDouble(values[index]),lon=Double.parseDouble(values[index+1]);
            return Geo.valid(lat,lon)?new GoTo.Point(lat,lon,0):null;
        } catch(NumberFormatException e) { return null; }
    }

    static String liveStartBlock(String[] values) {
        GoTo.Point mount=point(values,LATITUDE),landmark=point(values,LandmarkCalibration.LANDMARK_LAT);
        if(mount==null||landmark==null) return "";
        double metres=Geo.distance(mount.lat(),mount.lon(),landmark.lat(),landmark.lon());
        return metres>2000 ? String.format(java.util.Locale.US,
            "LIVE TRACKING BLOCKED — Mount and landmark are %.3f km apart (limit 2 km). Check both coordinates.",metres/1000) : "";
    }

    static String format(String[] values) {
        return format(values,LATITUDE,LONGITUDE);
    }

    static String format(String[] values,int latitude,int longitude) {
        String lat=values[latitude], lon=values[longitude];
        return lat.isEmpty()&&lon.isEmpty() ? "" : lat+", "+lon;
    }

    static String format(double latitude,double longitude) {
        return BigDecimal.valueOf(latitude).stripTrailingZeros().toPlainString()+", "+
            BigDecimal.valueOf(longitude).stripTrailingZeros().toPlainString();
    }

    static void apply(String value,String[] values) {
        apply(value,values,LATITUDE,LONGITUDE,KEY);
    }

    static void apply(String value,String[] values,int latitude,int longitude,String label) {
        if(value.trim().isEmpty()) {
            values[latitude]="";
            values[longitude]="";
            return;
        }
        String[] pair=value.split(",",-1);
        if(pair.length==2) {
            String lat=pair[0].trim(),lon=pair[1].trim();
            String decimal="[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)";
            if(lat.matches(decimal)&&lon.matches(decimal)&&Geo.valid(Double.parseDouble(lat),Double.parseDouble(lon))) {
                values[latitude]=lat;
                values[longitude]=lon;
                return;
            }
        }
        throw new IllegalArgumentException(label+": enter latitude, longitude as decimal degrees, e.g. "+EXAMPLE);
    }
}
