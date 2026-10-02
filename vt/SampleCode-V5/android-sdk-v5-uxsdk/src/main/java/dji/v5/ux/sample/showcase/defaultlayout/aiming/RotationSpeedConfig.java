package dji.v5.ux.sample.showcase.defaultlayout.aiming;
import com.google.gson.*;
/** VT 3.7: Bounded strict JSON schema, separate from Android and the control-loop math. */
public final class RotationSpeedConfig {
    private static double number(JsonElement e) {
        if(e==null||!e.isJsonPrimitive()||!e.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException("Expected number");
        double v=e.getAsDouble();if(!Double.isFinite(v))throw new IllegalArgumentException("Nonfinite number");return v;
    }
    private static double[][] rows(JsonArray a) {
        if(a==null||a.size()<2||a.size()>32)throw new IllegalArgumentException("Use 2-32 rotation points");
        double[][] r=new double[a.size()][2];
        for(int i=0;i<a.size();i++){JsonObject o=a.get(i).getAsJsonObject();r[i][0]=number(o.get("headingErrorDeg"));r[i][1]=number(o.get("speedDegPerSec"));}
        return r;
    }
    public static RotationSpeedCurve parse(String raw) {
        if(raw==null||raw.length()>65536)throw new IllegalArgumentException("Configuration exceeds 64 KiB");
        try {
            com.google.gson.stream.JsonReader r=new com.google.gson.stream.JsonReader(new java.io.StringReader(raw));r.setLenient(false);
            JsonObject o=com.google.gson.internal.Streams.parse(r).getAsJsonObject();
            if(r.peek()!=com.google.gson.stream.JsonToken.END_DOCUMENT||number(o.get("version"))!=1)throw new IllegalArgumentException("Unsupported rotation JSON");
            return new RotationSpeedCurve(rows(o.getAsJsonArray("normal")),rows(o.getAsJsonArray("riding")));
        }catch(java.io.IOException e){throw new IllegalArgumentException("Invalid rotation JSON",e);}
    }
}
