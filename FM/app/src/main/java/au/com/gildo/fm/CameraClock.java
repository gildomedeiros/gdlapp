package au.com.gildo.fm;

import java.text.SimpleDateFormat;
import java.util.*;

/** Canon sample uses RFC-1123-style datetime plus a separate boolean dst. */
final class CameraClock {
    static String format(long epoch,TimeZone zone){
        SimpleDateFormat f=new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z",Locale.US);
        f.setTimeZone(zone);return f.format(new Date(epoch));
    }
    static long parse(String value)throws java.text.ParseException {
        SimpleDateFormat f=new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z",Locale.US);
        f.setLenient(false);return f.parse(value).getTime();
    }
}
