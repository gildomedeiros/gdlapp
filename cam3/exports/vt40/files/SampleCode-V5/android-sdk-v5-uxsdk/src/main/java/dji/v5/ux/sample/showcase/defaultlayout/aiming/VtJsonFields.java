package dji.v5.ux.sample.showcase.defaultlayout.aiming;

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
