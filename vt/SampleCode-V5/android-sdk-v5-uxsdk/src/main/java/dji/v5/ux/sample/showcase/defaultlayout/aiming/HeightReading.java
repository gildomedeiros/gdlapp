package dji.v5.ux.sample.showcase.defaultlayout.aiming;
import java.util.Locale;
/** Height above takeoff, informational only; never a flight gate. */
public final class HeightReading {
    public final Double metres;
    public final long ageMs, sampleAtMs;
    public final boolean fresh;
    public HeightReading(Double value,long sampleAt,long now) {
        metres=value!=null && Double.isFinite(value) ? value : null;
        sampleAtMs=sampleAt;
        ageMs=sampleAt>0 && now>=sampleAt ? now-sampleAt : -1;
        fresh=metres!=null && ageMs>=0 && ageMs<=1500;
    }
    public String display() {
        return (fresh ? String.format(Locale.US,"Height: %.1f m",metres) : "Height: —")+" · above takeoff";
    }
}
