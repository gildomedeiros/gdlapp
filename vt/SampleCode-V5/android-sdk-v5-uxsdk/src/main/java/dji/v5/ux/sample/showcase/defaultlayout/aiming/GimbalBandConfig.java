package dji.v5.ux.sample.showcase.defaultlayout.aiming;
import com.google.gson.*;
/** Immutable validated configuration. Null final boundary means unlimited distance. */
public final class GimbalBandConfig {
    public final double bufferMetres;
    private final double[] upper,pitch;
    private GimbalBandConfig(double buffer,double[] upper,double[] pitch) { this.bufferMetres=buffer;this.upper=upper;this.pitch=pitch; }
    public int size(){return pitch.length;}
    public double upper(int i){return upper[i];}
    public double pitch(int i){return pitch[i];}
    private static double number(JsonElement e) {
        if(e==null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("Expected JSON number");
        double n=e.getAsDouble();if(!Double.isFinite(n))throw new IllegalArgumentException("Non-finite number");return n;
    }
    public static GimbalBandConfig parse(String json) {
        // VT 3.8: Invalid/duplicate JSON blocks Start; no silent value replacement.
        JsonObject o=StrictConfigJson.object(json);
        double b=number(o.get("bufferMetres"));if(b<0 || b>100)throw new IllegalArgumentException("bufferMetres must be 0..100");
        JsonArray a=o.getAsJsonArray("bands");if(a==null || a.size()<1 || a.size()>32)throw new IllegalArgumentException("Expected 1..32 bands");
        double[] u=new double[a.size()],p=new double[a.size()];double prev=0;
        for(int i=0;i<a.size();i++) {
            JsonObject row=a.get(i).getAsJsonObject();JsonElement limit=row.get("maxDistanceMetres");
            if(limit==null)throw new IllegalArgumentException("Missing boundary");
            if(i==a.size()-1) {if(!limit.isJsonNull())throw new IllegalArgumentException("Last boundary must be null");u[i]=Double.POSITIVE_INFINITY;}
            else {u[i]=number(limit);if(u[i]<=prev || u[i]>10000)throw new IllegalArgumentException("Boundaries must increase and be <=10000 m");prev=u[i];}
            p[i]=number(row.get("pitchDegrees"));if(p[i]<-90 || p[i]>0)throw new IllegalArgumentException("Pitch must be -90..0");
        }
        return new GimbalBandConfig(b,u,p);
    }
}
