package au.com.gildo.vtc;

/** Google Maps decimal-degree pair: latitude, longitude. */
final class Coordinates {
    static String[] parse(String text) {
        if(text==null||text.trim().isEmpty())return new String[]{"",""};
        String[] parts=text.trim().split(",",-1);
        if(parts.length!=2)throw new IllegalArgumentException("Paste coordinates as latitude, longitude — for example -28.165514, 153.550457");
        try {
            double lat=Double.parseDouble(parts[0].trim()),lon=Double.parseDouble(parts[1].trim());
            if(!Geo.valid(lat,lon))throw new NumberFormatException();
            return new String[]{parts[0].trim(),parts[1].trim()};
        }catch(NumberFormatException e){throw new IllegalArgumentException("Coordinates must be latitude (-90 to 90), longitude (-180 to 180)");}
    }
    static String display(String latitude,String longitude) {
        return latitude.isEmpty()&&longitude.isEmpty()?"":latitude+", "+longitude;
    }
}

