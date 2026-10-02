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
        {
            JsonObject o=StrictConfigJson.object(raw);
            if(number(o.get("version"))!=1)throw new IllegalArgumentException("version must be 1");
            return new RotationSpeedCurve(rows(o.getAsJsonArray("normal")),rows(o.getAsJsonArray("riding")));
        }
    }
}
