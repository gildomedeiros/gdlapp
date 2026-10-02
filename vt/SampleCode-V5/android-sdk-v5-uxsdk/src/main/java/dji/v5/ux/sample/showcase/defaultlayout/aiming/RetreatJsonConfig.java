package dji.v5.ux.sample.showcase.defaultlayout.aiming;
import com.google.gson.*;
/** VT 3.8: JSON-only retreat tuning, validated before any control is acquired. */
public final class RetreatJsonConfig {
    private static double number(JsonObject o,String key,double min,double max) {
        JsonElement e=o.get(key);
        if(e==null||!e.isJsonPrimitive()||!e.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException(key+" must be a number ("+min+" to "+max+")");
        double v=e.getAsDouble();
        if(!Double.isFinite(v)||v<min||v>max)throw new IllegalArgumentException(key+" is "+v+"; allowed range "+min+" to "+max);
        return v;
    }
    public static RetreatSettings parse(String raw) {
        if(raw==null||raw.length()>65536)throw new IllegalArgumentException("File missing or larger than 64 KiB");
        {
            JsonObject o=StrictConfigJson.object(raw);
            java.util.Set<String> keys=new java.util.HashSet<>(java.util.Arrays.asList("version","enabled","minimumDistanceMetres","durationSeconds","speedMetresPerSecond","comeToMeCooldownSeconds","maxStartsWithoutFreshGps"));
            for(String key:o.keySet())if(!keys.contains(key))throw new IllegalArgumentException("Unknown setting: "+key);
            number(o,"version",1,1);
            JsonElement enabled=o.get("enabled");
            if(enabled==null||!enabled.isJsonPrimitive()||!enabled.getAsJsonPrimitive().isBoolean())throw new IllegalArgumentException("enabled must be true or false");
            double count=number(o,"maxStartsWithoutFreshGps",0,100);
            if(count!=Math.rint(count))throw new IllegalArgumentException("maxStartsWithoutFreshGps must be a whole number");
            double duration=number(o,"durationSeconds",1,60),cooldown=number(o,"comeToMeCooldownSeconds",0,60);
            return new RetreatSettings(enabled.getAsBoolean(),number(o,"minimumDistanceMetres",1,200),Math.round(duration*1000),
                number(o,"speedMetresPerSecond",.1,5),Math.round(cooldown*1000),(int)count);
        }
    }
}
